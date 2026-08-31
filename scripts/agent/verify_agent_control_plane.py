#!/usr/bin/env python3
"""Static consistency checks for the MetalUniversal agent control plane."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/agent/system-registry.json"


def fail(message: str) -> None:
    raise SystemExit(f"agent-control-plane: FAIL: {message}")


def require_exists(path: str) -> None:
    if not (ROOT / path).exists():
        fail(f"registered path does not exist: {path}")


def main() -> int:
    try:
        registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot parse {REGISTRY.relative_to(ROOT)}: {exc}")

    if registry.get("schema_version") != 1:
        fail("unsupported system-registry schema_version")

    canonical = registry.get("canonical", {})
    development = canonical.get("development_branch")
    stable = canonical.get("stable_branch")
    if not development or not stable:
        fail("canonical development/stable branches must be declared")

    canonical_docs = registry.get("canonical_documents", [])
    if not canonical_docs:
        fail("canonical_documents must not be empty")
    for path in canonical_docs:
        require_exists(path)

    historical = set(registry.get("historical_or_advisory", []))
    overlap = historical.intersection(canonical_docs)
    if overlap:
        fail(f"documents cannot be both canonical and historical: {sorted(overlap)}")

    components = registry.get("components", [])
    ids = [component.get("id") for component in components]
    if None in ids or len(ids) != len(set(ids)):
        fail("component ids must be non-empty and unique")

    for component in components:
        if not component.get("source_roots"):
            fail(f"component {component['id']} has no source_roots")
        if not component.get("verification"):
            fail(f"component {component['id']} has no verification route")
        for path in component.get("source_roots", []):
            require_exists(path)
        for path in component.get("canonical_docs", []):
            require_exists(path)

    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    if "python3 scripts/agent/context.py" not in agents:
        fail("AGENTS.md must route bootstrap through context.py")
    if development not in agents or stable not in agents:
        fail("AGENTS.md canonical branch names disagree with registry")

    doctor = (ROOT / "scripts/agent/doctor.sh").read_text(encoding="utf-8")
    if "system-registry.json" not in doctor:
        fail("doctor.sh must obtain canonical branch policy from system-registry.json")
    if "feature/iris-metal-performance" in doctor:
        fail("doctor.sh contains retired hard-coded feature/iris-metal-performance authority")

    system_model = (ROOT / "docs/agent/system-model.md").read_text(encoding="utf-8")
    for marker in ("abstraction tower", "OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL", "Context-budget model"):
        if marker not in system_model:
            fail(f"system model missing required control-plane marker: {marker}")

    subprocess.run(
        [sys.executable, str(ROOT / "scripts/agent/context.py"), "--self-test"],
        cwd=ROOT,
        check=True,
    )

    print("Agent control plane verification: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
