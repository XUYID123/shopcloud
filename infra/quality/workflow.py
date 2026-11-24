"""Persistent, sequential acceptance for the construction workflow."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
HOME = "docs/agent-engineering"
PROGRESS = f"{HOME}/progress.json"
CATALOG = f"{HOME}/stages.json"
EVIDENCE = f"{HOME}/evidence/construction"
STATUSES = {"PENDING", "IN_PROGRESS", "VERIFYING", "PASS", "FAILED", "BLOCKED"}
RESULTS = {"PASS", "FAIL", "NOT_RUN", "NOT_VERIFIED", "NOT_APPLICABLE"}
RETRY_LIMIT = 3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def is_time(value):
    if not isinstance(value, str):
        return False
    try:
        return datetime.fromisoformat(value).utcoffset() is not None
    except ValueError:
        return False


def read(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def unique_object(pairs):
    result = dict(pairs)
    require(len(result) == len(pairs), "Duplicate JSON field")
    return result


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         dir=path.parent, delete=False) as stream:
            temp = Path(stream.name)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def file_at(root, name):
    require(isinstance(name, str) and "\\" not in name and ":" not in name, f"Invalid repo path: {name}")
    path = PurePosixPath(name)
    require(bool(path.parts) and not path.is_absolute() and ".." not in path.parts, f"Invalid repo path: {name}")
    result = (root / path).resolve()
    require(result.is_relative_to(root), f"Path leaves repository: {name}")
    return result


def digest(path):
    contents = path.read_bytes()
    try:
        contents = contents.decode("utf-8").replace("\r\n", "\n").encode("utf-8")
    except UnicodeDecodeError:
        pass
    return hashlib.sha256(contents).hexdigest()


def first_open(state):
    return next((stage["id"] for stage in state["stages"] if stage["status"] != "PASS"), None)


def new_stage(stage_id):
    return dict(id=stage_id, status="PENDING", started_at=None, completed_at=None,
                acceptance_result="NOT_RUN", evidence=None, evidence_sha256=None, failure_reason=None,
                retry_count=0, consecutive_failures=0, blocked_reason=None)


def result(check_id, status, summary, source="TOOL", required=True, **details):
    return dict(id=check_id, status=status, summary=summary, source=source, required=required, **details)


@contextmanager
def writer_lock(root):
    path = root / "target/harness-workflow/state.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as lock:
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError("Another construction command owns the state lock") from error
        yield


class Workflow:
    def __init__(self, root, checks):
        self.root = root.resolve()
        self.checks = checks
        self.path = file_at(self.root, PROGRESS)
        self.catalog = read(file_at(self.root, CATALOG))
        self.specs = self.catalog["stages"]
        require(self.catalog["workflow"] == "construction" and isinstance(self.catalog["version"], str),
                "Invalid construction catalog")
        require([item["id"] for item in self.specs] == list(range(1, 9)), "Expected eight ordered stages")
        for spec in self.specs:
            require(spec["depends_on"] == (spec["id"] - 1 or None), "Invalid stage prerequisite")
            for key in ("goal", "input", "execution", "acceptance", "failure", "exit"):
                require(isinstance(spec[key], str) and spec[key].strip(), f"Missing stage {spec['id']} {key}")
            require(bool(spec["artifacts"]) and bool(spec["checks"]), f"Stage {spec['id']} needs files and checks")
            for name in spec["artifacts"] + [spec["review"]]:
                file_at(self.root, name)
            require(len(set(spec["checks"])) == len(spec["checks"])
                    and set(spec["checks"]) <= self.checks.keys(), f"Unknown or duplicate check in stage {spec['id']}")

    def initialize(self):
        require(not self.path.exists(), "Progress exists; use run to resume")
        state = dict(workflow="construction", version=self.catalog["version"], current_stage=1,
                     updated_at=now(), stages=[new_stage(s["id"]) for s in self.specs])
        self.save(state)

    def save(self, state):
        state.update(current_stage=first_open(state), updated_at=now())
        write(self.path, state)

    def load(self):
        state = read(self.path)
        require(state["workflow"] == "construction" and state["version"] == self.catalog["version"],
                "Wrong workflow or version; review the catalog and migrate explicitly")
        require(is_time(state["updated_at"]), "Invalid progress timestamp")
        require([item["id"] for item in state["stages"]] == list(range(1, 9)), "Invalid progress stage list")
        require(state["current_stage"] == first_open(state), "Incorrect current_stage")
        current = state["current_stage"]
        for item in state["stages"]:
            status = item["status"]
            stage_id = item["id"]
            require(status in STATUSES, f"Invalid stage {stage_id} status")
            if current is not None and stage_id > current:
                require(status == "PENDING", "A later phase started before its prerequisite passed")
            for key in ("retry_count", "consecutive_failures"):
                require(type(item[key]) is int and item[key] >= 0, f"Invalid {key} in stage {stage_id}")
            started, completed = item["started_at"], item["completed_at"]
            require(started is None if status == "PENDING" else is_time(started), "Invalid start time")
            require(is_time(completed) if status == "PASS" else completed is None, "Invalid completion time")
            if completed is not None:
                require(datetime.fromisoformat(started) <= datetime.fromisoformat(completed)
                        <= datetime.fromisoformat(state["updated_at"]), "Invalid phase timestamp ordering")
            expected = {"PASS": "PASS", "FAILED": "FAIL", "BLOCKED": "FAIL"}.get(status, "NOT_RUN")
            require(item["acceptance_result"] == expected, f"Wrong acceptance result in stage {stage_id}")
            if expected != "NOT_RUN":
                self.evidence_path(item)
            else:
                require(item["evidence"] is None and item["evidence_sha256"] is None, "Unexpected evidence")
            require(bool(item["blocked_reason"]) == (status == "BLOCKED"), "Invalid blocked reason")
            require(bool(item["failure_reason"]) == (expected == "FAIL"), "Invalid failure reason")
        return state

    def evidence_path(self, item):
        name = item["evidence"]
        require(isinstance(name, str) and re.fullmatch(
                rf"{EVIDENCE}/stage-{item['id']:02d}-[0-9a-f]{{32}}\.json", name), "Invalid evidence path")
        require(isinstance(item["evidence_sha256"], str)
                and re.fullmatch("[0-9a-f]{64}", item["evidence_sha256"]), "Invalid evidence digest")
        return file_at(self.root, name)

    def read_attempt(self, item):
        path = self.evidence_path(item)
        require(path.is_file() and digest(path) == item["evidence_sha256"],
                f"Missing or modified acceptance evidence for stage {item['id']}")
        record = read(path)
        require(record["workflow_version"] == self.catalog["version"] and record["stage"] == item["id"]
                and record["result"] == item["acceptance_result"] and is_time(record["recorded_at"]),
                f"Invalid acceptance evidence for stage {item['id']}")
        checks = record["checks"]
        require(bool(checks), "Evidence has no checks")
        expected = {"artifacts", "agent-review", *self.specs[item["id"] - 1]["checks"]}
        required_checks = [c for c in checks if c["required"]]
        require(len(required_checks) == len(expected)
                and {c["id"] for c in required_checks} == expected, "Evidence omits required checks")
        for check in checks:
            require(type(check["required"]) is bool and check["source"] in {"TOOL", "AGENT"}
                    and check["status"] in RESULTS and isinstance(check["summary"], str), "Invalid check evidence")
            if check["required"]:
                expected_source = "AGENT" if check["id"] == "agent-review" else "TOOL"
                require(check["source"] == expected_source, "Invalid required-check provenance")
        require(record["result"] == self.verdict(checks), "Evidence result conflicts with individual checks")
        return record

    @staticmethod
    def verdict(checks):
        required = [check for check in checks if check["required"]]
        return "PASS" if (all(check["status"] == "PASS" for check in required)
                          and any(check["source"] == "TOOL" for check in required)) else "FAIL"

    def validate(self):
        state = self.load()
        for item in state["stages"]:
            if item["evidence"] is not None:
                self.read_attempt(item)
        return state

    def snapshots(self, spec, extra=()):
        snapshots = {"@stage-contract": hashlib.sha256(
            json.dumps(spec, sort_keys=True).encode("utf-8")).hexdigest()}
        for name in sorted(set(spec["artifacts"] + [spec["review"]] + list(extra))):
            path = file_at(self.root, name)
            require(path.is_file() and path.stat().st_size > 0, f"Missing or empty artifact: {name}")
            snapshots[name] = digest(path)
        return snapshots

    def reconcile(self, state):
        """Check actual passed artifacts and roll back from the first divergent stage."""
        for item in state["stages"]:
            if item["status"] != "PASS":
                break
            spec = self.specs[item["id"] - 1]
            stale_review = None
            try:
                record = self.read_attempt(item)
                review_file = file_at(self.root, spec["review"])
                if review_file.is_file() and digest(review_file) == record["snapshots"].get(spec["review"]):
                    for name in spec["artifacts"]:
                        artifact = file_at(self.root, name)
                        if not artifact.is_file() or digest(artifact) != record["snapshots"].get(name):
                            stale_review = digest(review_file)
                            break
                current = self.snapshots(spec, record["extra_inputs"])
                require(current == record["snapshots"], f"Stage {item['id']} inputs changed")
                for check_id in ("local-links", "runtime-evidence"):
                    if check_id in spec["checks"]:
                        require(self.checks[check_id](self.root, spec)["status"] == "PASS", f"{check_id} failed")
            except (OSError, ValueError, KeyError, TypeError) as error:
                print(f"Reconcile from stage {item['id']}: {error}", flush=True)
                self.reset_from(state, item["id"], str(error), stale_review=stale_review)
                break

    def reset_from(self, state, stage_id, reason, stale_review=None):
        state["stages"][stage_id - 1:] = [new_stage(i) for i in range(stage_id, 9)]
        if stale_review:
            state["stages"][stage_id - 1]["stale_review_sha256"] = stale_review
        state["last_recovery"] = dict(stage=stage_id, reason=reason, at=now())
        self.save(state)

    def evaluate(self, spec, stale_review=None):
        snapshots = {}
        checks = []
        extra = []
        try:
            snapshots = self.snapshots(spec)
            checks.append(result("artifacts", "PASS", "Nonempty repository artifacts hashed",
                                 files=spec["artifacts"]))
        except (OSError, ValueError) as error:
            checks.append(result("artifacts", "FAIL", str(error)))
        for check_id in spec["checks"]:
            try:
                entry = self.checks[check_id](self.root, spec)
                checks.append(entry)
                extra.extend(entry.get("inputs", []))
            except (OSError, ValueError, KeyError, TypeError) as error:
                checks.append(result(check_id, "FAIL", str(error)))
        review_path = file_at(self.root, spec["review"])
        marker = rf"^(?:- )?\[stage-{spec['id']:02d}\]: ACCEPTED(?:\s|$)"
        found = review_path.is_file() and re.search(marker, review_path.read_text(encoding="utf-8"), re.MULTILINE)
        reviewed = found and digest(review_path) != stale_review
        checks.append(result("agent-review", "PASS" if reviewed else "NOT_VERIFIED",
                             spec["acceptance"] if reviewed else "Current artifacts require an updated review",
                             source="AGENT", files=[spec["review"]]))
        if self.verdict(checks) == "PASS":
            try:
                snapshots = self.snapshots(spec, extra)
            except (OSError, ValueError) as error:
                checks[0] = result("artifacts", "FAIL", str(error))
        return checks, snapshots, sorted(set(extra))

    def start(self, state):
        current = first_open(state)
        require(current is not None, "All construction stages passed")
        item = state["stages"][current - 1]
        require(item["status"] != "BLOCKED", item["blocked_reason"])
        if item["status"] == "FAILED":
            item["retry_count"] += 1
        item.update(status="IN_PROGRESS", started_at=item["started_at"] or now(),
                    acceptance_result="NOT_RUN", evidence=None, evidence_sha256=None, failure_reason=None)
        self.save(state)
        return item

    def advance(self, state, through):
        self.reconcile(state)
        while (current := first_open(state)) is not None and current <= through:
            item = state["stages"][current - 1]
            if item["status"] == "BLOCKED":
                print(f"Stage {current} BLOCKED: {item['blocked_reason']}")
                return False
            if item["status"] in {"IN_PROGRESS", "VERIFYING"}:
                print(f"Resume stage {current} from {item['status']}", flush=True)
            previous = item.get("last_failure")
            item = self.start(state)
            item["status"] = "VERIFYING"
            self.save(state)
            checks, snapshots, extra = self.evaluate(self.specs[current - 1], item.get("stale_review_sha256"))
            verdict = self.verdict(checks)
            name = f"{EVIDENCE}/stage-{current:02d}-{uuid4().hex}.json"
            path = file_at(self.root, name)
            write(path, dict(workflow_version=self.catalog["version"], stage=current, result=verdict,
                             recorded_at=now(), checks=checks, snapshots=snapshots, extra_inputs=extra))
            item.update(acceptance_result=verdict, evidence=name, evidence_sha256=digest(path))
            if verdict == "PASS":
                item.update(status="PASS", completed_at=now(), consecutive_failures=0, last_failure=None)
                item.pop("stale_review_sha256", None)
            else:
                failure = "; ".join(f"{c['id']}: {c['summary']}" for c in checks
                                    if c["required"] and c["status"] != "PASS")
                streak = item["consecutive_failures"] + 1 if failure == previous else 1
                item.update(status="FAILED", failure_reason=failure,
                            last_failure=failure, consecutive_failures=streak)
                if streak >= RETRY_LIMIT:
                    item.update(status="BLOCKED", blocked_reason=f"{streak} repeated failures: {failure}")
            self.save(state)
            print(f"Stage {current} {item['status']} | {name}", flush=True)
            if verdict == "FAIL":
                print(item["failure_reason"], flush=True)
                return False
        print(f"Next construction stage: {first_open(state)}", flush=True)
        return True


def main():
    import workflow_checks
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest="action", required=True)
    for mode in ("init", "validate", "start"):
        commands.add_parser(mode)
    run = commands.add_parser("run")
    run.add_argument("--through", type=int, choices=range(1, 9), default=8)
    reopen = commands.add_parser("reopen")
    reopen.add_argument("stage", type=int, choices=range(1, 9))
    reopen.add_argument("--reason", required=True)
    args = cli.parse_args()
    workflow = Workflow(ROOT, workflow_checks.CHECKS)
    if args.action == "validate":
        state = workflow.validate()
        print(f"Construction state valid | next stage: {state['current_stage']}")
        return 0
    with writer_lock(ROOT):
        if args.action == "init":
            workflow.initialize()
            return 0
        state = workflow.load()
        if args.action == "run":
            return 0 if workflow.advance(state, args.through) else 1
        workflow.reconcile(state)
        if args.action == "start":
            print(f"Stage {workflow.start(state)['id']} IN_PROGRESS")
        else:
            require(args.stage <= (first_open(state) or 8), "Cannot reopen beyond the first incomplete stage")
            require(bool(args.reason.strip()), "Supply the recovered condition or reason")
            workflow.reset_from(state, args.stage, args.reason,
                                stale_review=state["stages"][args.stage - 1].get("stale_review_sha256"))
        return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Construction workflow: {error}", file=sys.stderr)
        sys.exit(1)
