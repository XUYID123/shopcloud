import contextlib
from io import StringIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from workflow import CATALOG, PROGRESS, Workflow, read, result, write, writer_lock


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name).resolve()
        specs = []
        for i in range(1, 9):
            artifact, review = f"stage-{i}.txt", f"review-{i}.md"
            (self.root / artifact).write_text("ready", encoding="utf-8")
            (self.root / review).write_text(f"[stage-{i:02d}]: ACCEPTED", encoding="utf-8")
            specs.append(dict(id=i, depends_on=i - 1 or None, goal="goal", input="input", execution="execution",
                              acceptance="criteria", failure="repair", exit="pass",
                              checks=["fixture"], artifacts=[artifact], review=review))
        write(self.root / CATALOG, dict(workflow="construction", version="test", stages=specs))
        self.workflow = self.reopen()
        self.workflow.initialize()

    def reopen(self):
        def check(root, spec):
            ready = (root / spec["artifacts"][0]).read_text(encoding="utf-8").strip() == "ready"
            return result("fixture", "PASS" if ready else "FAIL", "fixture readiness")
        return Workflow(self.root, {"fixture": check})

    def run_to(self, stage, workflow=None):
        workflow = workflow or self.workflow
        with contextlib.redirect_stdout(StringIO()):
            return workflow.advance(workflow.load(), stage)

    def test_fail_repair_auto_advance_and_recover_interrupted_verification(self):
        artifact = self.root / "stage-1.txt"
        artifact.write_text("fault", encoding="utf-8")
        self.assertFalse(self.run_to(3))
        failed = self.workflow.validate()
        self.assertEqual(failed["stages"][0]["status"], "FAILED")
        self.assertEqual(failed["stages"][1]["status"], "PENDING")
        original_evidence = self.root / failed["stages"][0]["evidence"]
        artifact.write_text("ready", encoding="utf-8")
        self.assertTrue(self.run_to(2))
        self.assertTrue(original_evidence.exists())
        passed = self.workflow.validate()
        self.assertEqual(passed["current_stage"], 3)
        self.assertEqual(passed["stages"][0]["retry_count"], 1)
        with patch.object(self.workflow, "evaluate", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.run_to(3)
        self.assertEqual(self.workflow.load()["stages"][2]["status"], "VERIFYING")
        resumed = self.reopen()
        self.assertTrue(self.run_to(8, resumed))
        self.assertIsNone(resumed.validate()["current_stage"])

    def test_drift_and_tampered_evidence_invalidate_suffix(self):
        self.assertTrue(self.run_to(4))
        evidence = self.root / self.workflow.load()["stages"][1]["evidence"]
        evidence.write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "modified acceptance evidence"):
            self.workflow.validate()
        self.assertTrue(self.run_to(2))
        self.assertEqual(self.workflow.validate()["stages"][2]["status"], "PENDING")
        (self.root / "stage-1.txt").write_text("fault", encoding="utf-8")
        self.assertFalse(self.run_to(2))
        self.assertEqual(self.workflow.validate()["current_stage"], 1)

    def test_retry_limit_and_explicit_recovery(self):
        artifact = self.root / "stage-1.txt"
        artifact.write_text("fault", encoding="utf-8")
        for _ in range(3):
            self.assertFalse(self.run_to(8))
        state = self.workflow.validate()
        self.assertEqual(state["stages"][0]["status"], "BLOCKED")
        self.assertEqual(state["stages"][0]["retry_count"], 2)
        self.assertFalse(self.run_to(8))
        artifact.write_text("ready", encoding="utf-8")
        self.workflow.reset_from(state, 1, "fixture repaired")
        self.assertTrue(self.run_to(8))

    def test_unreviewed_artifacts_cannot_pass(self):
        (self.root / "review-1.md").write_text("review still pending", encoding="utf-8")
        self.assertFalse(self.run_to(8))
        attempt = self.workflow.read_attempt(self.workflow.validate()["stages"][0])
        self.assertIn("NOT_VERIFIED", {check["status"] for check in attempt["checks"]})

    def test_changed_artifact_requires_fresh_review_even_if_automated_check_passes(self):
        self.assertTrue(self.run_to(1))
        (self.root / "stage-1.txt").write_text("ready\n", encoding="utf-8")
        self.assertFalse(self.run_to(2))
        self.assertEqual(self.workflow.validate()["stages"][0]["status"], "FAILED")
        (self.root / "review-1.md").write_text("[stage-01]: ACCEPTED · reviewed updated input", encoding="utf-8")
        self.assertTrue(self.run_to(2))

    def test_invalid_order_is_rejected(self):
        path = self.root / PROGRESS
        state = read(path)
        state["stages"][2]["status"] = "PASS"
        write(path, state)
        with self.assertRaisesRegex(ValueError, "before its prerequisite"):
            self.workflow.validate()

    def test_writer_lock_blocks_concurrent_writer(self):
        with writer_lock(self.root):
            with self.assertRaisesRegex(ValueError, "state lock"):
                with writer_lock(self.root):
                    self.fail("Second writer acquired the same lock")
