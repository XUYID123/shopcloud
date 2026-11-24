"""Registered construction checks and replayable local evidence."""
import argparse
import hashlib
import json
from pathlib import Path
from queue import Empty, Queue
import re
import subprocess
import sys
from threading import Thread
from uuid import uuid4
import xml.etree.ElementTree as ET

from workflow import HOME, ROOT, digest, file_at, now, read, require, result, write

SKILLS = ("code-review", "concurrency-design", "database-design", "feature-development",
          "redis-design", "rocketmq-design", "security-review")
SKILL_PATHS = [f".agents/skills/{name}/SKILL.md" for name in SKILLS]
DISCOVERY = f"{HOME}/evidence/skill-discovery.json"
RUNTIME = f"{HOME}/evidence/runtime-baseline.json"
SCENARIOS = f"{HOME}/tasks/upgrade-rehearsal.md"


def project(root, _spec):
    from gate import MAVEN_NS
    modules = [node.text for node in ET.parse(root / "pom.xml").findall("m:modules/m:module", MAVEN_NS)]
    require(len(set(modules)) == len(modules) and {"shop-user", "shop-product", "shop-messaging"} <= set(modules),
            "Core modules missing from Maven reactor")
    paths = [f"{module}/pom.xml" for module in modules]
    for name in paths:
        ET.parse(file_at(root, name))
    return result("maven-reactor", "PASS", f"Parsed root POM and {len(paths)} module POMs",
                  files=["pom.xml", *paths], inputs=paths)


def links(root, spec):
    from gate import check_markdown_links
    sources = [file_at(root, name) for name in spec["artifacts"] if name.endswith(".md")]
    check_markdown_links(root, sources)
    return result("local-links", "PASS", f"Checked local references in {len(sources)} Markdown artifacts",
                  files=[path.relative_to(root).as_posix() for path in sources])


def skills(root, _spec):
    from gate import check_skill_metadata
    check_skill_metadata([file_at(root, name) for name in SKILL_PATHS])
    record = read(root / DISCOVERY)
    require(record["status"] == "PASS" and record["source"] == "TOOL" and record["exit_code"] == 0,
            "Repository discovery has no successful App Server evidence")
    require(record["observed_hashes"] == {name: digest(root / name) for name in SKILL_PATHS},
            "Skill files changed; rerun discovery with the current Codex executable")
    return result("skill-discovery", "PASS", "App Server evidence covers seven current enabled repository skills",
                  files=[DISCOVERY], inputs=[DISCOVERY, *SKILL_PATHS])


def source_fingerprint(root):
    paths = [root / "pom.xml"]
    for module in root.glob("shop-*"):
        if (module / "pom.xml").is_file():
            paths.append(module / "pom.xml")
            paths.extend(path for path in (module / "src").rglob("*") if path.is_file())
    paths.sort(key=lambda path: path.relative_to(root).as_posix())
    sha = hashlib.sha256()
    for path in paths:
        sha.update(path.relative_to(root).as_posix().encode("utf-8"))
        sha.update(b"\0")
        sha.update(bytes.fromhex(digest(path)))
    return dict(sha256=sha.hexdigest(), file_count=len(paths))


def runtime(root, _spec):
    record = read(root / RUNTIME)
    require(record["source_fingerprint"] == source_fingerprint(root),
            "Runtime evidence covers different application inputs; capture fresh isolated results before reaccepting stage 5")
    expected = {"quick", "full", "dependency-scan"}
    require({c["id"] for c in record["checks"] if c["status"] == "PASS" and c["source"] == "TOOL"
             and c["exit_code"] == 0} == expected, "Runtime evidence is incomplete")
    return result("runtime-evidence", "PASS", "Verified scope of retained local Maven and OSV results",
                  files=[RUNTIME], inputs=[RUNTIME])


def local_gate(root, _spec):
    return command_check(root, "local-gate", ["infra/quality/gate.py", "lint"],
                         ["infra/quality/gate.py", ".github/workflows/quality.yml"])


def rehearsal(root, _spec):
    text = (root / SCENARIOS).read_text(encoding="utf-8")
    cases = re.findall(r"^## (Requirement Analysis|Technical Design|Design Review|Implementation|Verification|Delivery)$",
                       text, re.MULTILINE)
    require(cases == ["Requirement Analysis", "Technical Design", "Design Review", "Implementation",
                      "Verification", "Delivery"] * 2, "Missing or out-of-order business rehearsal phases")
    for key in ("REQUIRED", "NOT_REQUIRED", "transactional Outbox", "DLQ", "eventId"):
        require(key in text, f"Missing rehearsal coverage: {key}")
    return result("business-rehearsals", "PASS", "Verified two six-step reports and required coverage markers",
                  files=[SCENARIOS], inputs=[SCENARIOS])


def replay(root, _spec):
    return command_check(root, "recovery-tests",
                         ["-m", "unittest", "discover", "-s", "infra/quality", "-v"],
                         ["infra/quality/workflow.py", "infra/quality/test_workflow.py",
                          "infra/quality/router_policy.py", "infra/quality/test_router.py",
                          f"{HOME}/evidence/router-cases.json"])


def command_check(root, check_id, arguments, files):
    log = f"target/harness-workflow/{check_id}-{uuid4().hex}.log"
    path = root / log
    path.parent.mkdir(parents=True, exist_ok=True)
    command = [sys.executable, "-X", "utf8", *arguments]
    with path.open("wb") as output:
        try:
            code = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, timeout=90).returncode
            status, summary = ("PASS", "Executed local verifier") if code == 0 else ("FAIL", "Local verifier failed")
        except (OSError, subprocess.TimeoutExpired) as error:
            code, status, summary = None, "NOT_VERIFIED", type(error).__name__
    return result(check_id, status, summary,
                  command=["python", "-X", "utf8", *arguments], exit_code=code,
                  log=log, files=files, inputs=files)


CHECKS = {"maven-reactor": project, "local-links": links, "skill-discovery": skills,
          "local-gate": local_gate, "runtime-evidence": runtime, "business-rehearsals": rehearsal,
          "recovery-tests": replay}


def discover(root, executable):
    log = root / "target/harness-workflow/skill-discovery.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    hashes = {name: digest(root / name) for name in SKILL_PATHS}
    status = "FAIL"
    summary = "App Server did not return repository skills"
    command = [executable, "app-server", "--stdio", "-c",
               "sqlite_home=" + json.dumps(str(log.parent / "codex-state"))]
    try:
        with log.open("w", encoding="utf-8") as errors, subprocess.Popen(
            command, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors,
            text=True, encoding="utf-8"
        ) as server:
            output = Queue()

            def read_responses():
                for line in server.stdout:
                    output.put(line)
                output.put(None)

            Thread(target=read_responses, daemon=True).start()
            try:
                messages = [
                    {"id": 1, "method": "initialize",
                     "params": {"clientInfo": {"name": "shop-harness", "version": "1.0"}}},
                    {"method": "initialized", "params": {}},
                    {"id": 2, "method": "skills/list", "params": {"cwds": [str(root)], "forceReload": True}},
                ]
                server.stdin.write("".join(json.dumps(message) + "\n" for message in messages))
                server.stdin.flush()
                for _ in range(100):
                    line = output.get(timeout=20)
                    require(line is not None, summary)
                    response = json.loads(line)
                    if response.get("id") == 2:
                        require("error" not in response, f"skills/list failed: {response.get('error')}")
                        break
                else:
                    raise ValueError("skills/list result not found")
                observed = set()
                for item in response["result"]["data"]:
                    for skill in item["skills"]:
                        name = skill["name"]
                        if name in SKILLS and skill.get("enabled") is True and (
                            Path(skill["path"]).resolve() == (root / f".agents/skills/{name}/SKILL.md").resolve()
                        ):
                            observed.add(name)
                require(observed == set(SKILLS), f"Missing enabled repository skills: {set(SKILLS) - observed}")
                require(hashes == {name: digest(root / name) for name in SKILL_PATHS}, "Skill files changed during discovery")
                status, summary = "PASS", f"App Server found {len(observed)} enabled repository skills"
            finally:
                if server.poll() is None:
                    server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
    except (OSError, ValueError, KeyError, Empty) as error:
        summary = f"{summary} ({error})"
    record = result("codex-app-server", status, summary, command=["codex", "app-server", "--stdio"],
                    rpc="skills/list; cwd=repository root; forceReload=true",
                    exit_code=0 if status == "PASS" else 1, log=log.relative_to(root).as_posix(),
                    files=SKILL_PATHS, observed_hashes=hashes, recorded_at=now(),
                    automatic_trigger="NOT_VERIFIED")
    write(root / DISCOVERY, record)
    print(summary)
    return record["exit_code"]


def capture_runtime(args):
    fingerprint = source_fingerprint(ROOT)
    checks = []
    for mode in ("quick", "full", "dependency-scan"):
        arguments = ["infra/quality/gate.py", mode]
        for key in ("maven", "maven_repo", "docker_context", "rocketmq_image", "osv"):
            value = getattr(args, key)
            if value:
                arguments.extend(["--" + key.replace("_", "-"), value])
        if args.offline:
            arguments.append("--offline")
        log = f"target/harness-workflow/{mode}-{uuid4().hex}.log"
        path = ROOT / log
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"Running {mode} | {log}", flush=True)
        with path.open("wb") as output:
            code = subprocess.run([sys.executable, "-X", "utf8", *arguments],
                                  cwd=ROOT, stdout=output, stderr=subprocess.STDOUT).returncode
        checks.append(result(mode, "PASS" if code == 0 else "FAIL", "Local quality command completed",
                             command=["python", "-X", "utf8", *arguments], exit_code=code, log=log))
        if code:
            write(path.with_suffix(".json"), checks)
            return 1
    require(source_fingerprint(ROOT) == fingerprint, "Application inputs changed during runtime verification")
    write(ROOT / RUNTIME, dict(origin="LIVE", recorded_at=now(), source_fingerprint=fingerprint, checks=checks))
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("discover").add_argument("--codex", default="codex")
    runtime_parser = commands.add_parser("runtime")
    for flag in ("maven", "maven-repo", "docker-context", "rocketmq-image", "osv"):
        runtime_parser.add_argument("--" + flag)
    runtime_parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        sys.exit(discover(ROOT, args.codex) if args.action == "discover" else capture_runtime(args))
    except (OSError, ValueError) as error:
        print(f"Evidence capture: {error}", file=sys.stderr)
        sys.exit(1)
