#!/usr/bin/env python3
"""Generate a compact, diff-aware context/proof capsule for MetalUniversal agents."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY_PATH = ROOT / "docs/agent/system-registry.json"
CHECKPOINT_PATH = ROOT / "build/agent-state/current.json"


def run_git(*args: str) -> str | None:
    try:
        return subprocess.check_output(
            ["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def load_registry() -> dict[str, Any]:
    with REGISTRY_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def existing_ref(ref_name: str) -> str | None:
    candidates = [ref_name]
    if not ref_name.startswith("origin/"):
        candidates.append(f"origin/{ref_name}")
    for ref in candidates:
        if run_git("rev-parse", "--verify", ref):
            return ref
    return None


def relation_to_canonical(canonical_branch: str) -> tuple[str, str | None]:
    ref = existing_ref(canonical_branch)
    if not ref:
        return "unresolved-canonical-ref", None

    head = run_git("rev-parse", "HEAD")
    canonical = run_git("rev-parse", ref)
    if not head or not canonical:
        return "unresolved-git-state", ref
    if head == canonical:
        return "at-canonical", ref

    try:
        if subprocess.run(
            ["git", "merge-base", "--is-ancestor", canonical, head],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        ).returncode == 0:
            return "descendant-of-canonical", ref
        if subprocess.run(
            ["git", "merge-base", "--is-ancestor", head, canonical],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        ).returncode == 0:
            return "behind-canonical", ref
    except OSError:
        pass
    return "diverged-from-canonical", ref


def tokenize(text: str) -> set[str]:
    return {
        token
        for token in re.findall(r"[a-z0-9_.+-]+", text.lower())
        if len(token) >= 2
    }


def changed_files(base_ref: str | None) -> list[str]:
    if not base_ref:
        return []
    output = run_git("diff", "--name-only", f"{base_ref}...HEAD")
    if not output:
        return []
    return [line for line in output.splitlines() if line]


def pattern_specificity(pattern: str) -> int:
    return len(pattern.replace("*", "").replace("?", ""))


def path_matches(path: str, pattern: str) -> bool:
    if fnmatch.fnmatchcase(path, pattern):
        return True
    if pattern.endswith("/**") and path.startswith(pattern[:-3].rstrip("/") + "/"):
        return True
    return path == pattern


def file_component_scores(registry: dict[str, Any], files: list[str]) -> dict[str, int]:
    scores = {component["id"]: 0 for component in registry.get("components", [])}
    components = registry.get("components", [])
    for path in files:
        # AGENTS files describe control/ownership; editing one is not a renderer behavior change.
        if path == "AGENTS.md" or path.endswith("/AGENTS.md"):
            if "evaluation.control" in scores:
                scores["evaluation.control"] += 100
            continue

        matches: list[tuple[int, str]] = []
        for component in components:
            for pattern in component.get("owned_paths", []):
                if path_matches(path, str(pattern)):
                    matches.append((pattern_specificity(str(pattern)), component["id"]))
        if not matches:
            continue
        most_specific = max(score for score, _ in matches)
        for score, component_id in matches:
            if score == most_specific:
                scores[component_id] += 10
    return scores


def task_component_scores(registry: dict[str, Any], task: str) -> dict[str, int]:
    task_tokens = tokenize(task)
    scores: dict[str, int] = {}
    for component in registry.get("components", []):
        score = 0
        keywords = {str(item).lower() for item in component.get("keywords", [])}
        for token in task_tokens:
            if token in keywords:
                score += 5
            elif any(token in keyword or keyword in token for keyword in keywords):
                score += 2
        scores[component["id"]] = score
    return scores


def component_map(registry: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {component["id"]: component for component in registry.get("components", [])}


def direct_component_ids(registry: dict[str, Any], task: str, files: list[str]) -> list[str]:
    file_scores = file_component_scores(registry, files)
    task_scores = task_component_scores(registry, task)
    direct = [component_id for component_id, score in file_scores.items() if score > 0]
    if direct:
        return sorted(direct, key=lambda component_id: (-file_scores[component_id], -task_scores.get(component_id, 0), component_id))

    routed = [component_id for component_id, score in task_scores.items() if score > 0]
    routed.sort(key=lambda component_id: (-task_scores[component_id], component_id))
    return routed[:2]


def route_components(registry: dict[str, Any], task: str, files: list[str]) -> list[dict[str, Any]]:
    components = component_map(registry)
    file_scores = file_component_scores(registry, files)
    task_scores = task_component_scores(registry, task)
    ids = set(direct_component_ids(registry, task, files))
    ranked = sorted(
        components,
        key=lambda component_id: (-(file_scores.get(component_id, 0) + task_scores.get(component_id, 0)), component_id),
    )
    for component_id in ranked:
        if file_scores.get(component_id, 0) + task_scores.get(component_id, 0) > 0:
            ids.add(component_id)
        if len(ids) >= 4:
            break
    ordered = sorted(
        ids,
        key=lambda component_id: (-(file_scores.get(component_id, 0) + task_scores.get(component_id, 0)), component_id),
    )
    return [components[component_id] for component_id in ordered[:4]]


def impact_closure(registry: dict[str, Any], direct_ids: list[str]) -> list[str]:
    components = component_map(registry)
    seen = set(direct_ids)
    queue = list(direct_ids)
    while queue:
        current = queue.pop(0)
        component = components.get(current, {})
        for target in component.get("impact_targets", []):
            if target not in seen:
                seen.add(target)
                queue.append(target)
    return [component_id for component_id in components if component_id in seen]


def activated_boundaries(
    registry: dict[str, Any], direct_ids: list[str], impacted_ids: list[str]
) -> list[dict[str, Any]]:
    direct = set(direct_ids)
    impacted = set(impacted_ids)
    result: list[dict[str, Any]] = []
    for boundary in registry.get("boundaries", []):
        sources = set(boundary.get("from", []))
        targets = set(boundary.get("to", []))
        source_direct = sorted(sources & direct)
        target_direct = sorted(targets & direct)
        target_impacted = sorted(targets & impacted)
        if source_direct and target_impacted:
            item = dict(boundary)
            item["reason"] = "modified-both-sides" if target_direct else "downstream-contract-at-risk"
            item["direct_from"] = source_direct
            item["direct_to"] = target_direct
            result.append(item)
    return result


def infer_claim(task: str, direct_ids: list[str], requested: str) -> str:
    if requested != "auto":
        return requested
    tokens = tokenize(task)
    if tokens & {"performance", "perf", "fps", "latency", "throughput", "optimization", "optimize", "hillclimb", "p95"}:
        return "performance"
    if tokens & {"presentation", "present", "windowserver", "swap", "framepacing", "pacing"}:
        return "presentation"
    if tokens & {"ios", "iphoneos", "amethyst", "mobile"} or "platform.mobile" in direct_ids:
        return "platform"
    if direct_ids and set(direct_ids) <= {"evaluation.control"}:
        return "control"
    return "correctness"


def proof_plan(
    registry: dict[str, Any], direct_ids: list[str], boundaries: list[dict[str, Any]], claim: str
) -> list[dict[str, Any]]:
    components = component_map(registry)
    profiles = registry.get("proof_profiles", {})
    selected: set[str] = set()
    for component_id in direct_ids:
        selected.update(components.get(component_id, {}).get("required_proofs", []))
    for boundary in boundaries:
        selected.update(boundary.get("required_proofs", []))
    selected.update(registry.get("claim_proofs", {}).get(claim, []))

    def add_dependencies(profile_id: str) -> None:
        if profile_id not in profiles:
            return
        for dependency in profiles[profile_id].get("depends_on", []):
            if dependency not in selected:
                selected.add(dependency)
                add_dependencies(dependency)

    for profile_id in list(selected):
        add_dependencies(profile_id)

    ordered = sorted(selected, key=lambda profile_id: (profiles.get(profile_id, {}).get("rank", 999), profile_id))
    result = []
    for profile_id in ordered:
        profile = dict(profiles[profile_id])
        profile["id"] = profile_id
        result.append(profile)
    return result


def dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def load_checkpoint() -> dict[str, Any] | None:
    if not CHECKPOINT_PATH.is_file():
        return None
    try:
        data = json.loads(CHECKPOINT_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"invalid": True, "error": str(exc), "path": str(CHECKPOINT_PATH.relative_to(ROOT))}
    data["path"] = str(CHECKPOINT_PATH.relative_to(ROOT))
    return data


def build_capsule(
    registry: dict[str, Any], task: str, *, since: str | None = None, requested_claim: str = "auto"
) -> dict[str, Any]:
    canonical = registry["canonical"]
    development_branch = canonical["development_branch"]
    relation, canonical_ref = relation_to_canonical(development_branch)
    base_ref = existing_ref(since) if since else canonical_ref
    files = changed_files(base_ref)
    direct_ids = direct_component_ids(registry, task, files)
    impacted_ids = impact_closure(registry, direct_ids)
    boundaries = activated_boundaries(registry, direct_ids, impacted_ids)
    claim = infer_claim(task, direct_ids, requested_claim)
    proofs = proof_plan(registry, direct_ids, boundaries, claim)
    routes = route_components(registry, task, files)

    branch = run_git("branch", "--show-current") or "detached/unknown"
    head = run_git("rev-parse", "HEAD") or "unknown"
    status = run_git("status", "--porcelain=v1")
    local_branches_raw = run_git("for-each-ref", "--format=%(refname:short)", "refs/heads") or ""
    local_branches = [line for line in local_branches_raw.splitlines() if line]

    read_now = ["AGENTS.md", registry["bootstrap"]["system_model"]]
    for component in routes:
        guide = component.get("agent_guide")
        if guide:
            read_now.append(guide)
        read_now.extend(component.get("canonical_docs", []))
    read_now = dedupe(read_now)

    warnings: list[str] = []
    if since and not base_ref:
        warnings.append(f"requested --since ref could not be resolved: {since}")
    if status:
        warnings.append("working tree is dirty")
    if relation in {"behind-canonical", "diverged-from-canonical"}:
        warnings.append(f"HEAD relation is {relation}; re-orient before implementation")
    if len(direct_ids) > 2:
        warnings.append(
            f"diff directly owns {len(direct_ids)} components ({', '.join(direct_ids)}); split unless the boundary itself is the task"
        )
    if len(local_branches) > int(canonical.get("max_persistent_branches", 5)):
        warnings.append(
            f"local checkout exposes {len(local_branches)} branches, above the persistent-branch budget; "
            "this is a local signal only and is not a repository-global inventory"
        )

    checkpoint = load_checkpoint()
    if checkpoint:
        if checkpoint.get("invalid"):
            warnings.append(f"checkpoint is unreadable: {checkpoint.get('error')}")
        else:
            checkpoint_branch = checkpoint.get("git", {}).get("branch")
            checkpoint_head = checkpoint.get("git", {}).get("current_sha")
            if checkpoint_branch and checkpoint_branch != branch:
                warnings.append(f"checkpoint belongs to branch {checkpoint_branch}, current branch is {branch}")
            if checkpoint_head and checkpoint_head != head:
                warnings.append("checkpoint HEAD differs from current HEAD; review it as a handoff, not current truth")

    components = component_map(registry)
    return {
        "schema_version": 2,
        "task": task or None,
        "claim": claim,
        "git": {
            "branch": branch,
            "head": head,
            "dirty": bool(status),
            "relation_to_canonical": relation,
            "canonical_ref_used": canonical_ref,
            "diff_base_ref": base_ref,
            "changed_files_from_base": files[:100],
            "changed_file_count": len(files),
            "local_branch_ref_count": len(local_branches),
        },
        "canonical": canonical,
        "read_now": read_now,
        "routing": {
            "direct_components": [components[component_id] for component_id in direct_ids if component_id in components],
            "impacted_component_ids": impacted_ids,
            "routes": routes,
            "activated_boundaries": boundaries,
        },
        "proof_plan": proofs,
        "checkpoint": checkpoint,
        "warnings": warnings,
        "authority_order": registry.get("authority_order", []),
        "historical_or_advisory": registry.get("historical_or_advisory", []),
    }


def render_markdown(capsule: dict[str, Any]) -> str:
    git = capsule["git"]
    lines = [
        "# MetalUniversal agent context",
        "",
        f"- branch: `{git['branch']}`",
        f"- HEAD: `{git['head']}`",
        f"- diff base: `{git['diff_base_ref']}`",
        f"- canonical relation: `{git['relation_to_canonical']}`",
        f"- claim: `{capsule['claim']}`",
        f"- dirty: `{str(git['dirty']).lower()}`",
    ]
    if capsule.get("task"):
        lines.append(f"- task: {capsule['task']}")

    if capsule["warnings"]:
        lines.extend(["", "## Warnings"])
        lines.extend(f"- {warning}" for warning in capsule["warnings"])

    lines.extend(["", "## Read now"])
    lines.extend(f"- `{path}`" for path in capsule["read_now"])

    routing = capsule["routing"]
    lines.extend(["", "## Direct ownership"])
    direct = routing["direct_components"]
    if direct:
        for component in direct:
            lines.append(f"- `{component['id']}` — {component['summary']}")
    else:
        lines.append("- unresolved; route by the system model before editing")

    impacted = routing["impacted_component_ids"]
    if impacted:
        lines.extend(["", "## Impact closure", "Potentially affected downstream contracts/components:"])
        lines.extend(f"- `{component_id}`" for component_id in impacted if component_id not in {c['id'] for c in direct})

    boundaries = routing["activated_boundaries"]
    if boundaries:
        lines.extend(["", "## Boundary risk"])
        for boundary in boundaries:
            lines.append(f"- `{boundary['id']}` ({boundary['reason']}): {boundary['contract']}")

    lines.extend(["", "## Minimum proof ladder"])
    if capsule["proof_plan"]:
        for profile in capsule["proof_plan"]:
            lines.append(
                f"{profile['rank']}. `{profile['id']}` [{profile['environment']}] — `{profile['command']}`"
            )
            lines.append(f"   proves: {profile['proves']}")
    else:
        lines.append("- no proof profile resolved; do not infer acceptance from absence of a gate")

    checkpoint = capsule.get("checkpoint")
    if checkpoint and not checkpoint.get("invalid"):
        lines.extend(["", "## Recoverable checkpoint"])
        lines.append(f"- status: `{checkpoint.get('status', 'unknown')}`")
        if checkpoint.get("hypothesis"):
            lines.append(f"- hypothesis: {checkpoint['hypothesis']}")
        if checkpoint.get("next_command"):
            lines.append(f"- next command: `{checkpoint['next_command']}`")
        for blocker in checkpoint.get("blockers", []):
            lines.append(f"- blocker: {blocker}")

    changed = git.get("changed_files_from_base", [])
    if changed:
        lines.extend(["", f"## Changed from base (showing {len(changed)} of {git['changed_file_count']})"])
        lines.extend(f"- `{path}`" for path in changed)

    lines.extend(
        [
            "",
            "## Authority reminder",
            "Executable source/tests/schemas and exact-SHA structured evidence outrank canonical prose; history and prompts are provenance/recipes only.",
        ]
    )
    return "\n".join(lines) + "\n"


def self_test(registry: dict[str, Any]) -> None:
    assert registry["schema_version"] == 2
    assert registry["canonical"]["development_branch"]
    ids = [component["id"] for component in registry["components"]]
    assert len(ids) == len(set(ids))

    routed = route_components(registry, "Iris BSL shader semantic divergence", [])
    assert routed and routed[0]["id"] in {"product.semantics", "validation.contract"}
    routed = route_components(registry, "Swift FFM ABI bridge symbol", [])
    assert routed and routed[0]["id"] in {"native.abi", "native.execution"}

    native_direct = direct_component_ids(registry, "", ["src/main/native/MetallumNative.swift"])
    assert native_direct == ["native.execution"]
    bridge_direct = direct_component_ids(
        registry, "", ["src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"]
    )
    assert "native.abi" in bridge_direct
    guide_direct = direct_component_ids(
        registry, "", ["src/main/java/com/metallum/client/metal/render/AGENTS.md"]
    )
    assert guide_direct == ["evaluation.control"]

    impacted = impact_closure(registry, ["native.abi"])
    assert "native.execution" in impacted and "validation.contract" in impacted
    boundaries = activated_boundaries(registry, ["native.abi"], impacted)
    assert any(boundary["id"] == "java-native-abi" for boundary in boundaries)
    proofs = proof_plan(registry, ["native.abi"], boundaries, "correctness")
    proof_ids = [profile["id"] for profile in proofs]
    assert "render.gpu" in proof_ids and "hosted.exact-head" in proof_ids and "minecraft.e2e" in proof_ids
    print("Agent context self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", default="", help="short task description used for component routing")
    parser.add_argument("--since", help="optional diff base ref; defaults to the canonical development branch")
    parser.add_argument(
        "--claim",
        choices=("auto", "control", "correctness", "performance", "platform", "presentation"),
        default="auto",
        help="claim being made; auto infers from task/ownership",
    )
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    registry = load_registry()
    if args.self_test:
        self_test(registry)
        return 0

    capsule = build_capsule(registry, args.task, since=args.since, requested_claim=args.claim)
    if args.format == "json":
        json.dump(capsule, sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(render_markdown(capsule))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
