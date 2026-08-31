#!/usr/bin/env python3
"""Generate a compact, task-routed context capsule for MetalUniversal agents."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY_PATH = ROOT / "docs/agent/system-registry.json"


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


def existing_ref(canonical_branch: str) -> str | None:
    for ref in (canonical_branch, f"origin/{canonical_branch}"):
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


def changed_files(canonical_ref: str | None) -> list[str]:
    if not canonical_ref:
        return []
    output = run_git("diff", "--name-only", f"{canonical_ref}...HEAD")
    if not output:
        return []
    return [line for line in output.splitlines() if line]


def component_score(component: dict[str, Any], task_tokens: set[str], files: list[str]) -> int:
    score = 0
    keywords = {str(item).lower() for item in component.get("keywords", [])}
    for token in task_tokens:
        if token in keywords:
            score += 5
        elif any(token in keyword or keyword in token for keyword in keywords):
            score += 2

    for path in files:
        for root in component.get("source_roots", []):
            normalized = str(root).rstrip("/")
            if path == normalized or path.startswith(normalized + "/"):
                score += 8
    return score


def route_components(registry: dict[str, Any], task: str, files: list[str]) -> list[dict[str, Any]]:
    task_tokens = tokenize(task)
    scored: list[tuple[int, dict[str, Any]]] = []
    for component in registry.get("components", []):
        score = component_score(component, task_tokens, files)
        if score > 0:
            scored.append((score, component))
    scored.sort(key=lambda item: (-item[0], item[1]["id"]))
    return [component for _, component in scored[:3]]


def dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def build_capsule(registry: dict[str, Any], task: str) -> dict[str, Any]:
    canonical = registry["canonical"]
    development_branch = canonical["development_branch"]
    relation, canonical_ref = relation_to_canonical(development_branch)
    files = changed_files(canonical_ref)
    routes = route_components(registry, task, files)

    branch = run_git("branch", "--show-current") or "detached/unknown"
    head = run_git("rev-parse", "HEAD") or "unknown"
    status = run_git("status", "--porcelain=v1")
    local_branches_raw = run_git("for-each-ref", "--format=%(refname:short)", "refs/heads") or ""
    local_branches = [line for line in local_branches_raw.splitlines() if line]

    read_now = ["AGENTS.md", registry["bootstrap"]["system_model"]]
    if routes:
        for component in routes:
            read_now.extend(component.get("canonical_docs", []))
    read_now = dedupe(read_now)

    warnings: list[str] = []
    if status:
        warnings.append("working tree is dirty")
    if relation in {"behind-canonical", "diverged-from-canonical"}:
        warnings.append(f"HEAD relation is {relation}; re-orient before implementation")
    if len(local_branches) > int(canonical.get("max_persistent_branches", 5)):
        warnings.append(
            f"local checkout exposes {len(local_branches)} branches, above the persistent-branch budget; "
            "this is a local signal only and is not a repository-global inventory"
        )

    return {
        "schema_version": 1,
        "task": task or None,
        "git": {
            "branch": branch,
            "head": head,
            "dirty": bool(status),
            "relation_to_canonical": relation,
            "canonical_ref_used": canonical_ref,
            "changed_files_from_canonical": files[:50],
            "local_branch_ref_count": len(local_branches),
        },
        "canonical": canonical,
        "read_now": read_now,
        "routes": routes,
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
        f"- canonical relation: `{git['relation_to_canonical']}`",
        f"- dirty: `{str(git['dirty']).lower()}`",
    ]
    if capsule.get("task"):
        lines.append(f"- task: {capsule['task']}")

    if capsule["warnings"]:
        lines.extend(["", "## Warnings"])
        lines.extend(f"- {warning}" for warning in capsule["warnings"])

    lines.extend(["", "## Read now"])
    lines.extend(f"- `{path}`" for path in capsule["read_now"])

    routes = capsule["routes"]
    if routes:
        lines.extend(["", "## Task routes"])
        for component in routes:
            lines.append(f"### `{component['id']}`")
            lines.append(component["summary"])
            lines.append("Source roots:")
            lines.extend(f"- `{path}`" for path in component.get("source_roots", []))
            lines.append("Verification:")
            lines.extend(f"- `{command}`" for command in component.get("verification", []))
    else:
        lines.extend(
            [
                "",
                "## Task routes",
                "No component matched strongly. Read the system model, then route by ownership instead of preloading history.",
            ]
        )

    changed = git.get("changed_files_from_canonical", [])
    if changed:
        lines.extend(["", "## Changed from canonical (first 50)"])
        lines.extend(f"- `{path}`" for path in changed)

    lines.extend(
        [
            "",
            "## Authority reminder",
            "Executable source/tests/schemas and structured evidence outrank canonical prose; historical/advisory documents are provenance only.",
        ]
    )
    return "\n".join(lines) + "\n"


def self_test(registry: dict[str, Any]) -> None:
    assert registry["schema_version"] == 1
    assert registry["canonical"]["development_branch"]
    ids = [component["id"] for component in registry["components"]]
    assert len(ids) == len(set(ids))
    routed = route_components(registry, "Iris BSL shader semantic divergence", [])
    assert routed and routed[0]["id"] in {"product.semantics", "validation.contract"}
    routed = route_components(registry, "Swift FFM ABI bridge symbol", [])
    assert routed and routed[0]["id"] == "native.abi"
    print("Agent context self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", default="", help="short task description used for component routing")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    registry = load_registry()
    if args.self_test:
        self_test(registry)
        return 0

    capsule = build_capsule(registry, args.task)
    if args.format == "json":
        json.dump(capsule, sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(render_markdown(capsule))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
