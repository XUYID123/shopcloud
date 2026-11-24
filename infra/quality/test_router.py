import json
from pathlib import Path
import unittest

from router_policy import ROUTES, decide

CASES = Path(__file__).resolve().parents[2] / "docs/agent-engineering/evidence/router-cases.json"


class RouterPolicyTest(unittest.TestCase):
    def test_route_and_boundary_regressions(self):
        cases = json.loads(CASES.read_text(encoding="utf-8"))
        self.assertEqual(set(ROUTES.values()), {case["expected"]["route"] for case in cases})
        for case in cases:
            with self.subTest(task=case["prompt"]):
                outcome = decide(**case["interpreted"])
                self.assertEqual(vars(outcome), case["expected"])

    def test_critical_risk_does_not_widen_read_only(self):
        decision = decide("feature", "COMPLEX", "CRITICAL", boundary="READ_ONLY", critical_approved=True)
        self.assertEqual((decision.route, decision.mode, decision.confirmation_required), ("ROUTE-08", "READ", False))

    def test_mixed_children_keep_their_own_boundaries(self):
        plan = [
            decide("optimize", "STANDARD", "LOW", boundary="READ_ONLY"),
            decide("fix", "STANDARD", "MEDIUM"),
            decide("test", "STANDARD", "LOW"),
        ]
        self.assertEqual([step.route for step in plan], ["ROUTE-06", "ROUTE-04", "ROUTE-10"])
        self.assertEqual([step.mode for step in plan], ["READ", "BUSINESS_WRITE", "ISOLATED_TEST"])
        self.assertFalse(any(step.construction for step in plan))
