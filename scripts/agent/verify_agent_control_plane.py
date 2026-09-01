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
ROUTING_FIXTURES = ROOT / "docs/agent/routing-fixtures.json"
BRANCH_TOPOLOGY = ROOT / "scripts/agent/branch_topology.py"
CI_SUBJECT = ROOT / "scripts/agent/record_ci_subject.py"


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
        require(profile.get("cost_class"), f"proof {profile_id} must declare cost_class")
        require(profile.get("environment"), f"proof {profile_id} must declare environment")
        require(profile.get("command"), f"proof {profile_id} must declare command/authority route")
        require(profile.get("proves"), f"proof {profile_id} must declare what it proves")
        for dependency in profile.get("depends_on", []):
            require(dependency in profiles, f"proof {profile_id} depends on unknown proof {dependency}")
            require(
                profiles[dependency].get("rank", 999) <= profile.get("rank", -1),
                f"proof {profile_id} depends on a later-ranked proof {dependency}",
            )
        for covered in profile.get("covers", []):
            require(covered in profiles, f"proof {profile_id} covers unknown proof {covered}")
            require(covered != profile_id, f"proof {profile_id} cannot cover itself")
            require(
                profiles[covered].get("rank", 999) <= profile.get("rank", -1),
                f"proof {profile_id} covers later-ranked proof {covered}",
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

    preflights = [
        profile_id for profile_id, profile in profiles.items() if profile.get("always_preflight")
    ]
    require(preflights, "proof graph must retain at least one cheap fail-fast preflight")
    for profile_id in preflights:
        require(
            profiles[profile_id]["rank"] <= 1,
            f"always_preflight proof {profile_id} must remain cheap/early-ranked",
        )


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


def validate_ci_identity_contracts() -> None:
    exact_expr = "github.event.pull_request.head.sha"
    unified = (ROOT / ".github/workflows/unified-eval-static.yml").read_text(encoding="utf-8")
    minecraft_ref = (ROOT / ".github/workflows/minecraft-reference.yml").read_text(encoding="utf-8")
    metal_cap = (ROOT / ".github/workflows/metal-capabilities.yml").read_text(encoding="utf-8")
    minecraft_e2e = (ROOT / ".github/workflows/minecraft-client-e2e.yml").read_text(encoding="utf-8")
    build = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")

    for name, text in (
        ("unified-eval-static", unified),
        ("minecraft-reference", minecraft_ref),
        ("metal-capabilities", metal_cap),
        ("minecraft-client-e2e", minecraft_e2e),
    ):
        require(exact_expr in text, f"{name} must derive the PR candidate-head SHA explicitly")

    for name, text in (("unified-eval-static", unified), ("minecraft-reference", minecraft_ref)):
        require("record_ci_subject.py" in text, f"{name} must record its tested CI subject")
        require("candidate-head" in text, f"{name} must declare candidate-head proof")

    require(
        "METALLUM_SOURCE_SHA" in metal_cap and "ref: ${{ env.METALLUM_SOURCE_SHA }}" in metal_cap,
        "metal-capabilities must checkout its declared candidate source SHA",
    )
    require(
        "Checkout exact candidate SHA" in minecraft_e2e and "git rev-parse HEAD" in minecraft_e2e,
        "minecraft-client-e2e must verify its exact candidate checkout",
    )
    require("record_ci_subject.py" in build, "build workflow must record its tested CI subject")
    require("merge-result" in build, "PR build must declare merge-result proof")
    require(
        "github.event_name" in build and "pull_request" in build,
        "build workflow must distinguish PR merge-result from push candidate-head",
    )


def main() -> int:
    try:
        registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot parse {REGISTRY.relative_to(ROOT)}: {exc}")

    require(registry.get("schema_version") == 3, "unsupported system-registry schema_version")

    canonical = registry.get("canonical", {})
    development = canonical.get("development_branch")
    stable = canonical.get("stable_branch")
    require(bool(development and stable), "canonical development/stable branches must be declared")
    require(int(canonical.get("max_persistent_branches", 0)) >= 3, "persistent branch budget is malformed")

    bootstrap = registry.get("bootstrap", {})
    require(
        bootstrap.get("routing_fixtures") == "docs/agent/routing-fixtures.json",
        "bootstrap must register routing fixtures",
    )
    require_exists(bootstrap["routing_fixtures"])

    for path in (
        "scripts/agent/branch_topology.py",
        "scripts/agent/record_ci_subject.py",
        "docs/agent/branch-topology.md",
        "docs/agent/ci-proof-identity.md",
        "docs/agent/decisions/0003-branch-topology-and-ci-proof-subjects.md",
    ):
        require_exists(path)

    try:
        fixtures = json.loads(ROUTING_FIXTURES.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot parse {ROUTING_FIXTURES.relative_to(ROOT)}: {exc}")
    require(fixtures.get("schema_version") == 1, "unsupported routing-fixtures schema_version")
    require(fixtures.get("path_cases"), "routing fixtures must include path cases")
    require(fixtures.get("execution_cases"), "routing fixtures must include execution cases")

    canonical_docs = registry.get("canonical_documents", [])
    require(bool(canonical_docs), "canonical_documents must not be empty")
    for path in canonical_docs:
        require_exists(path)

    historical = set(registry.get("historical_or_advisory", []))
    overlap = historical.intersection(canonical_docs)
    require(not overlap, f"documents cannot be both canonical and historical: {sorted(overlap)}")
    require(
        "docs/agent/branch-migration-matrix.json" in historical,
        "branch migration matrix must remain historical/advisory",
    )

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
    require(
        "build/agent-state/**" in generated.get("never_commit", []),
        "agent checkpoints must remain generated/ignored state",
    )
    require(generated.get("run_manifest"), "existing unified run manifest must be registered")
    require(generated.get("decision"), "existing unified decision artifact must be registered")

    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    require("python3 scripts/agent/context.py" in agents, "AGENTS.md must route bootstrap through context.py")
    require("python3 scripts/agent/branch_topology.py --refresh" in agents, "AGENTS.md must expose live branch topology")
    require("ci-proof-identity.md" in agents, "AGENTS.md must expose CI proof-subject identity")
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
        "Proof obligations vs execution schedule",
        "Epistemic labels",
        "Recoverable task state",
    ):
        require(marker in system_model, f"system model missing required control-plane marker: {marker}")

    validate_ci_identity_contracts()

    for script in (
        ROOT / "scripts/agent/context.py",
        ROOT / "scripts/agent/checkpoint.py",
        BRANCH_TOPOLOGY,
        CI_SUBJECT,
    ):
        subprocess.run([sys.executable, str(script), "--self-test"], cwd=ROOT, check=True)

    print("Agent control plane verification: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
