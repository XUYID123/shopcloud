"""Apply route and authorization rules to intent already interpreted by the Agent."""
from dataclasses import dataclass

ROUTES = {
    "knowledge": "ROUTE-01", "explain-code": "ROUTE-02", "diagnose": "ROUTE-03",
    "fix": "ROUTE-04", "feature": "ROUTE-05", "optimize": "ROUTE-06",
    "refactor": "ROUTE-07", "design": "ROUTE-08", "review": "ROUTE-09",
    "test": "ROUTE-10", "documentation": "ROUTE-11", "harness": "ROUTE-12",
}
BOUNDARY_ROUTE = {"DESIGN_ONLY": "design", "REVIEW_ONLY": "review", "TEST_ONLY": "test"}
READ_ONLY_FALLBACK = {"fix": "diagnose", "feature": "design", "refactor": "design"}
BUSINESS_WRITES = {"fix", "feature", "optimize", "refactor"}
COMPLEXITIES = {"SIMPLE", "STANDARD", "COMPLEX"}
RISKS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}


@dataclass(frozen=True)
class RouteDecision:
    route: str
    mode: str
    complexity: str
    risk: str
    enhanced_verification: bool
    confirmation_required: bool
    construction: bool


def decide(intent, complexity, risk, *, boundary="DEFAULT", unresolved_decision=False,
           construction_requested=False, critical_approved=False):
    if intent not in ROUTES or complexity not in COMPLEXITIES or risk not in RISKS:
        raise ValueError("Unknown interpreted intent, complexity or risk")
    if boundary not in {"DEFAULT", "READ_ONLY", *BOUNDARY_ROUTE}:
        raise ValueError("Unknown explicit boundary")
    intent = BOUNDARY_ROUTE.get(boundary, intent)
    if boundary == "READ_ONLY":
        intent = READ_ONLY_FALLBACK.get(intent, intent)
        mode = "READ"
    elif intent in BUSINESS_WRITES:
        mode = "BUSINESS_WRITE"
    elif intent in {"documentation", "harness"}:
        mode = "PROJECT_DOCS" if intent == "documentation" else "HARNESS"
    elif intent == "test":
        mode = "ISOLATED_TEST"
    else:
        mode = "READ"
    needs_confirmation = unresolved_decision or (risk == "CRITICAL" and not critical_approved)
    if needs_confirmation:
        mode = "READ"
    return RouteDecision(ROUTES[intent], mode, complexity, risk,
                         risk in {"HIGH", "CRITICAL"}, needs_confirmation,
                         mode == "HARNESS" and construction_requested)
