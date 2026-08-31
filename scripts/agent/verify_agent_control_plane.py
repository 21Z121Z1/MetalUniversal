#!/usr/bin/env python3
"""Static consistency checks for the MetalUniversal agent control graph."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/agent/system-registry.json"


def fail(message: str) -> None:
    raise SystemExit(f"agent-control-plane: FAIL: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def require_exists(path: str) -> None:
    if not (ROOT / path).exists():
        fail(f"registered path does not exist: {path}")


def validate_proof_graph(profiles: dict[str, dict[str, Any]]) -> None:
    for profile_id, profile in profiles.items():
        require(isinstance(profile.get("rank"), int), f"proof {profile_id} must have integer rank")
        require(profile.get("environment"), f"proof {profile_id} must declare environment")
        require(profile.get("command"), f"proof {profile_id} must declare command/authority route")
        require(profile.get("proves"), f"proof {profile_id} must declare what it proves")
        for dependency in profile.get("depends_on", []):
            require(dependency in profiles, f"proof {profile_id} depends on unknown proof {dependency}")
            require(
                profiles[dependency].get("rank", 999) <= profile.get("rank", -1),
                f"proof {profile_id} depends on a later-ranked proof {dependency}",
            )

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(profile_id: str) -> None:
        if profile_id in visiting:
            fail(f"proof dependency cycle contains {profile_id}")
        if profile_id in visited:
            return
        visiting.add(profile_id)
        for dependency in profiles[profile_id].get("depends_on", []):
            visit(dependency)
        visiting.remove(profile_id)
        visited.add(profile_id)

    for profile_id in profiles:
        visit(profile_id)


def validate_impact_graph(components: list[dict[str, Any]]) -> set[str]:
    ids = [component.get("id") for component in components]
    require(None not in ids and len(ids) == len(set(ids)), "component ids must be non-empty and unique")
    known = set(ids)
    for component in components:
        component_id = component["id"]
        require(component.get("source_roots"), f"component {component_id} has no source_roots")
        require(component.get("owned_paths"), f"component {component_id} has no owned_paths")
        require(component.get("required_proofs"), f"component {component_id} has no required_proofs")
        require("impact_targets" in component, f"component {component_id} must declare impact_targets")
        for target in component.get("impact_targets", []):
            require(target in known, f"component {component_id} impacts unknown component {target}")
        for path in component.get("source_roots", []):
            require_exists(path)
        for path in component.get("canonical_docs", []):
            require_exists(path)
        guide = component.get("agent_guide")
        if guide:
            require_exists(guide)
    return known


def main() -> int:
    try:
        registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot parse {REGISTRY.relative_to(ROOT)}: {exc}")

    require(registry.get("schema_version") == 2, "unsupported system-registry schema_version")

    canonical = registry.get("canonical", {})
    development = canonical.get("development_branch")
    stable = canonical.get("stable_branch")
    require(bool(development and stable), "canonical development/stable branches must be declared")
    require(int(canonical.get("max_persistent_branches", 0)) >= 3, "persistent branch budget is malformed")

    canonical_docs = registry.get("canonical_documents", [])
    require(bool(canonical_docs), "canonical_documents must not be empty")
    for path in canonical_docs:
        require_exists(path)

    historical = set(registry.get("historical_or_advisory", []))
    overlap = historical.intersection(canonical_docs)
    require(not overlap, f"documents cannot be both canonical and historical: {sorted(overlap)}")

    profiles = registry.get("proof_profiles", {})
    require(bool(profiles), "proof_profiles must not be empty")
    validate_proof_graph(profiles)

    known_components = validate_impact_graph(registry.get("components", []))
    proof_ids = set(profiles)
    for component in registry.get("components", []):
        unknown = set(component.get("required_proofs", [])) - proof_ids
        require(not unknown, f"component {component['id']} references unknown proofs: {sorted(unknown)}")

    for claim, claims in registry.get("claim_proofs", {}).items():
        unknown = set(claims) - proof_ids
        require(not unknown, f"claim {claim} references unknown proofs: {sorted(unknown)}")

    boundary_ids: set[str] = set()
    for boundary in registry.get("boundaries", []):
        boundary_id = boundary.get("id")
        require(boundary_id and boundary_id not in boundary_ids, "boundary ids must be non-empty and unique")
        boundary_ids.add(boundary_id)
        sources = set(boundary.get("from", []))
        targets = set(boundary.get("to", []))
        require(bool(sources and targets), f"boundary {boundary_id} must have from/to components")
        require(not (sources - known_components), f"boundary {boundary_id} has unknown from component")
        require(not (targets - known_components), f"boundary {boundary_id} has unknown to component")
        unknown_proofs = set(boundary.get("required_proofs", [])) - proof_ids
        require(not unknown_proofs, f"boundary {boundary_id} has unknown proofs: {sorted(unknown_proofs)}")
        require(boundary.get("contract"), f"boundary {boundary_id} must state its contract")

    generated = registry.get("generated_evidence", {})
    require("build/agent-state/**" in generated.get("never_commit", []), "agent checkpoints must remain generated/ignored state")
    require(generated.get("run_manifest"), "existing unified run manifest must be registered")
    require(generated.get("decision"), "existing unified decision artifact must be registered")

    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    require("python3 scripts/agent/context.py" in agents, "AGENTS.md must route bootstrap through context.py")
    require(development in agents and stable in agents, "AGENTS.md canonical branch names disagree with registry")

    doctor = (ROOT / "scripts/agent/doctor.sh").read_text(encoding="utf-8")
    require("system-registry.json" in doctor, "doctor.sh must obtain canonical branch policy from system-registry.json")
    require("feature/iris-metal-performance" not in doctor, "doctor.sh contains retired branch authority")

    system_model = (ROOT / "docs/agent/system-model.md").read_text(encoding="utf-8")
    for marker in (
        "abstraction tower",
        "OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL",
        "Context-budget model",
        "Impact graph and proof closure",
        "Recoverable task state",
    ):
        require(marker in system_model, f"system model missing required control-plane marker: {marker}")

    subprocess.run(
        [sys.executable, str(ROOT / "scripts/agent/context.py"), "--self-test"],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(
        [sys.executable, str(ROOT / "scripts/agent/checkpoint.py"), "--self-test"],
        cwd=ROOT,
        check=True,
    )

    print("Agent control plane verification: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
