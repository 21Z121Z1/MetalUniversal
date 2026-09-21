#!/usr/bin/env python3
"""Compile a compact, epistemically-labelled agent view of MetalUniversal."""

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
FIXTURES_PATH = ROOT / "docs/agent/routing-fixtures.json"
CHECKPOINT_PATH = ROOT / "build/agent-state/current.json"


def run_git(*args: str) -> str | None:
    try:
        return subprocess.check_output(
            ["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def load_registry() -> dict[str, Any]:
    return json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))


def load_fixtures() -> dict[str, Any]:
    return json.loads(FIXTURES_PATH.read_text(encoding="utf-8"))


def existing_ref(name: str) -> str | None:
    candidates = [name]
    if not name.startswith("origin/"):
        candidates.append(f"origin/{name}")
    for ref in candidates:
        if run_git("rev-parse", "--verify", ref):
            return ref
    return None


def relation_to_canonical(branch: str) -> tuple[str, str | None]:
    ref = existing_ref(branch)
    if not ref:
        return "unresolved-canonical-ref", None
    head = run_git("rev-parse", "HEAD")
    canonical = run_git("rev-parse", ref)
    if not head or not canonical:
        return "unresolved-git-state", ref
    if head == canonical:
        return "at-canonical", ref
    for relation, older, newer in (
        ("descendant-of-canonical", canonical, head),
        ("behind-canonical", head, canonical),
    ):
        if subprocess.run(
            ["git", "merge-base", "--is-ancestor", older, newer],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        ).returncode == 0:
            return relation, ref
    return "diverged-from-canonical", ref


def changed_files(base_ref: str | None) -> list[str]:
    if not base_ref:
        return []
    raw = run_git("diff", "--name-only", f"{base_ref}...HEAD") or ""
    return [line for line in raw.splitlines() if line]


def tokenize(text: str) -> set[str]:
    return {
        token
        for token in re.findall(r"[a-z0-9_.+-]+", text.lower())
        if len(token) >= 2
    }


def path_matches(path: str, pattern: str) -> bool:
    if fnmatch.fnmatchcase(path, pattern):
        return True
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3].rstrip("/") + "/")
    return path == pattern


def specificity(pattern: str) -> int:
    return len(pattern.replace("*", "").replace("?", ""))


def component_map(registry: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {item["id"]: item for item in registry.get("components", [])}


def file_component_scores(registry: dict[str, Any], files: list[str]) -> dict[str, int]:
    components = registry.get("components", [])
    scores = {item["id"]: 0 for item in components}
    for path in files:
        if path == "AGENTS.md" or path.endswith("/AGENTS.md"):
            if "evaluation.control" in scores:
                scores["evaluation.control"] += 100
            continue
        matches: list[tuple[int, str]] = []
        for component in components:
            for pattern in component.get("owned_paths", []):
                pattern = str(pattern)
                if path_matches(path, pattern):
                    matches.append((specificity(pattern), component["id"]))
        if not matches:
            continue
        best = max(score for score, _ in matches)
        for score, component_id in matches:
            if score == best:
                scores[component_id] += 10
    return scores


def changed_component_ids(registry: dict[str, Any], files: list[str]) -> list[str]:
    scores = file_component_scores(registry, files)
    result = [item for item, score in scores.items() if score > 0]
    return sorted(result, key=lambda item: (-scores[item], item))


def task_component_scores(registry: dict[str, Any], task: str) -> dict[str, int]:
    tokens = tokenize(task)
    scores: dict[str, int] = {}
    for component in registry.get("components", []):
        keywords = {str(item).lower() for item in component.get("keywords", [])}
        score = 0
        for token in tokens:
            if token in keywords:
                score += 5
            elif any(token in keyword or keyword in token for keyword in keywords):
                score += 2
        scores[component["id"]] = score
    return scores


def task_route_ids(registry: dict[str, Any], task: str, limit: int = 2) -> list[str]:
    scores = task_component_scores(registry, task)
    result = [item for item, score in scores.items() if score > 0]
    result.sort(key=lambda item: (-scores[item], item))
    return result[:limit]


def active_route(
    registry: dict[str, Any], task: str, files: list[str]
) -> tuple[list[str], str, list[str], list[str]]:
    changed = changed_component_ids(registry, files)
    inferred = task_route_ids(registry, task)
    if changed:
        return changed, "diff", changed, inferred
    if inferred:
        return inferred, "task-inference", changed, inferred
    return [], "unresolved", changed, inferred


def routed_components(
    registry: dict[str, Any], task: str, files: list[str], active_ids: list[str]
) -> list[dict[str, Any]]:
    components = component_map(registry)
    file_scores = file_component_scores(registry, files)
    task_scores = task_component_scores(registry, task)
    ids = set(active_ids)
    ranked = sorted(
        components,
        key=lambda item: (-(file_scores.get(item, 0) + task_scores.get(item, 0)), item),
    )
    for item in ranked:
        if file_scores.get(item, 0) + task_scores.get(item, 0) > 0:
            ids.add(item)
        if len(ids) >= 4:
            break
    ordered = sorted(
        ids,
        key=lambda item: (-(file_scores.get(item, 0) + task_scores.get(item, 0)), item),
    )
    return [components[item] for item in ordered[:4]]


def impact_closure(registry: dict[str, Any], active_ids: list[str]) -> list[str]:
    components = component_map(registry)
    seen = set(active_ids)
    queue = list(active_ids)
    while queue:
        current = queue.pop(0)
        for target in components.get(current, {}).get("impact_targets", []):
            if target not in seen:
                seen.add(target)
                queue.append(target)
    return [item for item in components if item in seen]


def activated_boundaries(
    registry: dict[str, Any], active_ids: list[str], impacted_ids: list[str]
) -> list[dict[str, Any]]:
    active = set(active_ids)
    impacted = set(impacted_ids)
    result: list[dict[str, Any]] = []
    for boundary in registry.get("boundaries", []):
        sources = set(boundary.get("from", []))
        targets = set(boundary.get("to", []))
        source_active = sorted(sources & active)
        target_active = sorted(targets & active)
        target_impacted = sorted(targets & impacted)
        if source_active and target_impacted:
            item = dict(boundary)
            item["reason"] = (
                "modified-both-sides" if target_active else "downstream-contract-at-risk"
            )
            item["active_from"] = source_active
            item["active_to"] = target_active
            result.append(item)
    return result


def infer_claim(task: str, active_ids: list[str], requested: str) -> str:
    if requested != "auto":
        return requested
    if active_ids and set(active_ids) <= {"evaluation.control"}:
        return "control"
    tokens = tokenize(task)
    if tokens & {
        "performance", "perf", "fps", "latency", "throughput",
        "optimization", "optimize", "hillclimb", "p95",
    }:
        return "performance"
    if tokens & {"presentation", "present", "windowserver", "swap", "framepacing", "pacing"}:
        return "presentation"
    if tokens & {"ios", "iphoneos", "amethyst", "mobile"} or "platform.mobile" in active_ids:
        return "platform"
    return "correctness"


def proof_obligations(
    registry: dict[str, Any], active_ids: list[str], boundaries: list[dict[str, Any]], claim: str
) -> list[dict[str, Any]]:
    components = component_map(registry)
    profiles = registry.get("proof_profiles", {})
    selected: set[str] = set()
    for component_id in active_ids:
        selected.update(components.get(component_id, {}).get("required_proofs", []))
    for boundary in boundaries:
        selected.update(boundary.get("required_proofs", []))
    selected.update(registry.get("claim_proofs", {}).get(claim, []))

    def add_dependencies(profile_id: str) -> None:
        profile = profiles.get(profile_id)
        if not profile:
            return
        for dependency in profile.get("depends_on", []):
            if dependency not in selected:
                selected.add(dependency)
                add_dependencies(dependency)

    for profile_id in list(selected):
        add_dependencies(profile_id)

    ordered = sorted(
        selected,
        key=lambda item: (profiles.get(item, {}).get("rank", 999), item),
    )
    return [{"id": item, **profiles[item]} for item in ordered]


def minimal_execution_plan(
    registry: dict[str, Any], obligations: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], dict[str, list[str]]]:
    profiles = registry.get("proof_profiles", {})
    obligation_ids = {item["id"] for item in obligations}
    coverage: dict[str, list[str]] = {item: [] for item in obligation_ids}
    for executor_id in obligation_ids:
        for covered in profiles.get(executor_id, {}).get("covers", []):
            if covered in obligation_ids:
                coverage[covered].append(executor_id)

    keep: set[str] = set(obligation_ids)
    for profile_id in obligation_ids:
        profile = profiles[profile_id]
        if profile.get("always_preflight"):
            continue
        if coverage.get(profile_id):
            keep.discard(profile_id)

    ordered = sorted(keep, key=lambda item: (profiles[item].get("rank", 999), item))
    plan = [{"id": item, **profiles[item]} for item in ordered]
    coverage = {key: value for key, value in coverage.items() if value}
    return plan, coverage


def dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    output: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            output.append(item)
    return output


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
    relation, canonical_ref = relation_to_canonical(canonical["development_branch"])
    base_ref = existing_ref(since) if since else canonical_ref
    files = changed_files(base_ref)
    active_ids, ownership_basis, changed_ids, task_ids = active_route(registry, task, files)
    impacted_ids = impact_closure(registry, active_ids)
    boundaries = activated_boundaries(registry, active_ids, impacted_ids)
    claim = infer_claim(task, active_ids, requested_claim)
    obligations = proof_obligations(registry, active_ids, boundaries, claim)
    execution, coverage = minimal_execution_plan(registry, obligations)
    routes = routed_components(registry, task, files, active_ids)

    branch = run_git("branch", "--show-current") or "detached/unknown"
    head = run_git("rev-parse", "HEAD") or "unknown"
    status = run_git("status", "--porcelain=v1") or ""
    local_raw = run_git("for-each-ref", "--format=%(refname:short)", "refs/heads") or ""
    local_branches = [line for line in local_raw.splitlines() if line]

    read_now = ["AGENTS.md", registry["bootstrap"]["system_model"]]
    for component in routes:
        if component.get("agent_guide"):
            read_now.append(component["agent_guide"])
        read_now.extend(component.get("canonical_docs", []))
    read_now = dedupe(read_now)

    warnings: list[str] = []
    if since and not base_ref:
        warnings.append(f"requested --since ref could not be resolved: {since}")
    if status:
        warnings.append("working tree is dirty")
    if relation in {"behind-canonical", "diverged-from-canonical"}:
        warnings.append(f"HEAD relation is {relation}; re-orient before implementation")
    if ownership_basis == "task-inference":
        warnings.append("component route comes from task text only; treat it as inference until the diff establishes ownership")
    if ownership_basis == "diff" and len(changed_ids) > 2:
        warnings.append(
            f"diff directly owns {len(changed_ids)} components ({', '.join(changed_ids)}); split unless the boundary itself is the task"
        )
    if len(local_branches) > int(canonical.get("max_persistent_branches", 5)):
        warnings.append(
            f"local checkout exposes {len(local_branches)} branches above policy budget; report drift, do not auto-delete"
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
                warnings.append("checkpoint HEAD differs from current HEAD; older proof results are stale")

    components = component_map(registry)
    return {
        "schema_version": 3,
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
            "ownership_basis": ownership_basis,
            "changed_components": [components[item] for item in changed_ids if item in components],
            "task_route_ids": task_ids,
            "direct_components": [components[item] for item in active_ids if item in components],
            "impacted_component_ids": impacted_ids,
            "routes": routes,
            "activated_boundaries": boundaries,
        },
        "proof_obligations": obligations,
        "execution_plan": execution,
        "proof_coverage": coverage,
        "proof_plan": execution,
        "checkpoint": checkpoint,
        "warnings": warnings,
        "authority_order": registry.get("authority_order", []),
        "historical_or_advisory": registry.get("historical_or_advisory", []),
    }


def render_markdown(capsule: dict[str, Any]) -> str:
    git = capsule["git"]
    routing = capsule["routing"]
    lines = [
        "# MetalUniversal agent context",
        "",
        f"- branch: `{git['branch']}`",
        f"- HEAD: `{git['head']}`",
        f"- diff base: `{git['diff_base_ref']}`",
        f"- canonical relation: `{git['relation_to_canonical']}`",
        f"- claim: `{capsule['claim']}`",
        f"- ownership basis: `{routing['ownership_basis']}`",
        f"- dirty: `{str(git['dirty']).lower()}`",
    ]
    if capsule.get("task"):
        lines.append(f"- task: {capsule['task']}")
    if capsule["warnings"]:
        lines.extend(["", "## Warnings"])
        lines.extend(f"- {item}" for item in capsule["warnings"])

    lines.extend(["", "## Read now"])
    lines.extend(f"- `{path}`" for path in capsule["read_now"])

    if routing["ownership_basis"] == "diff":
        lines.extend(["", "## Changed-component ownership (path-derived fact)"])
    elif routing["ownership_basis"] == "task-inference":
        lines.extend(["", "## Planned component route (task-derived inference)"])
    else:
        lines.extend(["", "## Component route"])
    active = routing["direct_components"]
    lines.extend(
        [f"- `{item['id']}` — {item['summary']}" for item in active]
        if active
        else ["- unresolved; inspect the system model before editing"]
    )

    if routing.get("task_route_ids") and routing["ownership_basis"] == "diff":
        lines.extend(["", "## Task-routing hints"])
        lines.extend(f"- `{item}`" for item in routing["task_route_ids"])

    active_set = {item["id"] for item in active}
    downstream = [
        item for item in routing["impacted_component_ids"] if item not in active_set
    ]
    if downstream:
        lines.extend(["", "## Impact closure"])
        lines.extend(f"- `{item}`" for item in downstream)

    if routing["activated_boundaries"]:
        lines.extend(["", "## Boundary risk"])
        for boundary in routing["activated_boundaries"]:
            lines.append(f"- `{boundary['id']}` ({boundary['reason']}): {boundary['contract']}")

    lines.extend(["", "## Proof obligations"])
    obligations = capsule["proof_obligations"]
    lines.append(
        "- " + ", ".join(f"`{item['id']}`" for item in obligations)
        if obligations
        else "- none resolved; absence of a gate is not acceptance"
    )

    lines.extend(["", "## Minimum execution schedule"])
    if capsule["execution_plan"]:
        for item in capsule["execution_plan"]:
            preflight = " preflight" if item.get("always_preflight") else ""
            lines.append(
                f"{item['rank']}. `{item['id']}` [{item['environment']}{preflight}] — `{item['command']}`"
            )
            lines.append(f"   proves: {item['proves']}")
    else:
        lines.append("- no executable proof profile resolved")

    if capsule["proof_coverage"]:
        lines.extend(["", "## Integrated proof coverage"])
        for proof_id, executors in sorted(capsule["proof_coverage"].items()):
            lines.append(
                f"- `{proof_id}` is produced inside " + ", ".join(f"`{item}`" for item in executors)
            )

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

    changed = git["changed_files_from_base"]
    if changed:
        lines.extend(["", f"## Changed from base (showing {len(changed)} of {git['changed_file_count']})"])
        lines.extend(f"- `{path}`" for path in changed)

    lines.extend([
        "",
        "## Epistemic reminder",
        "Path-derived ownership and exact-SHA evidence are facts. Task routing, impact closure and hypotheses are inferences. Canonical prose is guidance; prompts/handoffs are provenance.",
    ])
    return "\n".join(lines) + "\n"


def self_test(registry: dict[str, Any]) -> None:
    assert registry["schema_version"] == 3
    fixtures = load_fixtures()
    assert fixtures.get("schema_version") == 1

    for case in fixtures.get("path_cases", []):
        actual = changed_component_ids(registry, [case["path"]])
        assert actual == case["expected_changed_components"], (case["path"], actual)
        impacted = impact_closure(registry, actual)
        boundaries = activated_boundaries(registry, actual, impacted)
        obligations = proof_obligations(registry, actual, boundaries, case.get("claim", "correctness"))
        execution, _ = minimal_execution_plan(registry, obligations)
        execution_ids = {item["id"] for item in execution}
        assert set(case.get("must_execute", [])) <= execution_ids, (case["path"], execution_ids)

    for case in fixtures.get("task_cases", []):
        task_ids = task_route_ids(registry, case["task"])
        if case.get("expected_any_primary"):
            assert task_ids and task_ids[0] in set(case["expected_any_primary"]), (case["task"], task_ids)
        active = changed_component_ids(registry, case.get("files", [])) or task_ids
        claim = infer_claim(case["task"], active, case.get("requested_claim", "auto"))
        if case.get("expected_claim"):
            assert claim == case["expected_claim"], (case["task"], claim)

    for case in fixtures.get("execution_cases", []):
        active = case["components"]
        impacted = impact_closure(registry, active)
        boundaries = activated_boundaries(registry, active, impacted)
        obligations = proof_obligations(registry, active, boundaries, case["claim"])
        execution, _ = minimal_execution_plan(registry, obligations)
        execution_ids = {item["id"] for item in execution}
        assert set(case.get("must_execute", [])) <= execution_ids, execution_ids
        assert not (set(case.get("must_not_execute", [])) & execution_ids), execution_ids

    print("Agent context self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", default="", help="short task description used for routing")
    parser.add_argument("--since", help="optional diff base; defaults to canonical development branch")
    parser.add_argument(
        "--claim",
        choices=("auto", "control", "correctness", "performance", "platform", "presentation"),
        default="auto",
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
