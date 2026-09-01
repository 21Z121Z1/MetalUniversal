#!/usr/bin/env python3
"""Compile live Git branch refs into a compact, non-destructive topology view for agents."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY_PATH = ROOT / "docs/agent/system-registry.json"


def run_git(*args: str, check: bool = False) -> str | None:
    try:
        proc = subprocess.run(
            ["git", *args], cwd=ROOT, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=check,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    if proc.returncode != 0:
        return None
    return proc.stdout.strip()


def load_registry() -> dict[str, Any]:
    return json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))


def git_ok(*args: str) -> bool:
    try:
        return subprocess.run(
            ["git", *args], cwd=ROOT,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
        ).returncode == 0
    except OSError:
        return False


def refresh_remote() -> None:
    subprocess.run(
        ["git", "fetch", "--prune", "origin", "+refs/heads/*:refs/remotes/origin/*"],
        cwd=ROOT, check=True,
    )


def available_refs() -> tuple[str, dict[str, str]]:
    raw = run_git(
        "for-each-ref", "--format=%(refname:short)|%(objectname)", "refs/remotes/origin"
    ) or ""
    refs: dict[str, str] = {}
    for line in raw.splitlines():
        if not line or "|" not in line:
            continue
        ref, sha = line.split("|", 1)
        if ref == "origin/HEAD" or not ref.startswith("origin/"):
            continue
        refs[ref.removeprefix("origin/")] = sha
    if refs:
        return "remote", refs

    raw = run_git(
        "for-each-ref", "--format=%(refname:short)|%(objectname)", "refs/heads"
    ) or ""
    for line in raw.splitlines():
        if not line or "|" not in line:
            continue
        ref, sha = line.split("|", 1)
        refs[ref] = sha
    return "local", refs


def resolve_ref(branch: str, source: str) -> str:
    return f"origin/{branch}" if source == "remote" else branch


def tree_sha(ref: str) -> str | None:
    return run_git("rev-parse", f"{ref}^{{tree}}")


def ahead_behind(canonical_ref: str, branch_ref: str) -> tuple[int, int]:
    raw = run_git("rev-list", "--left-right", "--count", f"{canonical_ref}...{branch_ref}")
    if not raw:
        return 0, 0
    left, right = raw.split()
    return int(right), int(left)


def relation(canonical_ref: str, branch_ref: str) -> str:
    canonical_sha = run_git("rev-parse", canonical_ref)
    branch_sha = run_git("rev-parse", branch_ref)
    if not canonical_sha or not branch_sha:
        return "unresolved"
    if canonical_sha == branch_sha:
        return "same-commit"
    if git_ok("merge-base", "--is-ancestor", canonical_ref, branch_ref):
        return "descendant"
    if git_ok("merge-base", "--is-ancestor", branch_ref, canonical_ref):
        return "ancestor"
    return "diverged"


@dataclass(frozen=True)
class DeclaredRoles:
    stable: str
    development: str
    history_anchor: str
    platforms: frozenset[str]

    @property
    def persistent(self) -> frozenset[str]:
        return frozenset({self.stable, self.development, self.history_anchor, *self.platforms})


def declared_roles(registry: dict[str, Any]) -> DeclaredRoles:
    canonical = registry["canonical"]
    return DeclaredRoles(
        stable=canonical["stable_branch"],
        development=canonical["development_branch"],
        history_anchor=canonical["history_anchor_branch"],
        platforms=frozenset(canonical.get("platform_branches", [])),
    )


def role_for(
    branch: str, *, relation_to_canonical: str,
    tree_equal_to_canonical: bool, coverers: list[str], roles: DeclaredRoles,
) -> str:
    if branch == roles.development:
        return "development"
    if branch == roles.stable:
        return "stable"
    if branch == roles.history_anchor:
        return "history-anchor"
    if branch in roles.platforms:
        return "platform-line"
    if relation_to_canonical == "ancestor":
        return "absorbed-ancestor"
    if tree_equal_to_canonical:
        return "tree-equivalent-history"
    if relation_to_canonical == "descendant":
        return "covered-ancestor" if coverers else "lineage-tip"
    if relation_to_canonical == "diverged":
        return "divergent-tip"
    if relation_to_canonical == "same-commit":
        return "tree-equivalent-history"
    return "unresolved"


def compute_topology(
    registry: dict[str, Any], source: str, refs: dict[str, str]
) -> dict[str, Any]:
    roles = declared_roles(registry)
    if roles.development not in refs:
        raise SystemExit(
            f"branch-topology: canonical development branch {roles.development!r} "
            f"is missing from {source} refs"
        )

    canonical_ref = resolve_ref(roles.development, source)
    canonical_tree = tree_sha(canonical_ref)
    base: dict[str, dict[str, Any]] = {}

    for branch, sha in sorted(refs.items()):
        ref = resolve_ref(branch, source)
        rel = relation(canonical_ref, ref)
        ahead, behind = ahead_behind(canonical_ref, ref)
        t_sha = tree_sha(ref)
        base[branch] = {
            "branch": branch,
            "sha": sha,
            "tree_sha": t_sha,
            "relation_to_canonical": rel,
            "ahead_by": ahead,
            "behind_by": behind,
            "tree_equal_to_canonical": bool(canonical_tree and t_sha == canonical_tree),
        }

    non_history_coverers = [
        branch for branch in base
        if branch not in roles.persistent and not base[branch]["tree_equal_to_canonical"]
    ]

    for branch, item in base.items():
        coverers: list[str] = []
        if branch not in roles.persistent:
            branch_ref = resolve_ref(branch, source)
            for candidate in non_history_coverers:
                if candidate == branch:
                    continue
                candidate_ref = resolve_ref(candidate, source)
                if git_ok("merge-base", "--is-ancestor", branch_ref, candidate_ref):
                    coverers.append(candidate)

        nearest: list[str] = []
        for candidate in coverers:
            candidate_ref = resolve_ref(candidate, source)
            is_transitive = False
            for other in coverers:
                if other == candidate:
                    continue
                other_ref = resolve_ref(other, source)
                if git_ok("merge-base", "--is-ancestor", other_ref, candidate_ref):
                    is_transitive = True
                    break
            if not is_transitive:
                nearest.append(candidate)

        item["covered_by"] = sorted(nearest)
        item["role"] = role_for(
            branch,
            relation_to_canonical=item["relation_to_canonical"],
            tree_equal_to_canonical=item["tree_equal_to_canonical"],
            coverers=item["covered_by"], roles=roles,
        )
        item["retirement_advisory"] = (
            "covered-by-live-descendant" if item["role"] == "covered-ancestor" else None
        )

    branches = list(base.values())
    role_counts: dict[str, int] = {}
    for item in branches:
        role_counts[item["role"]] = role_counts.get(item["role"], 0) + 1

    max_persistent = int(registry["canonical"].get("max_persistent_branches", 5))
    return {
        "schema_version": 1,
        "source": source,
        "canonical_branch": roles.development,
        "canonical_sha": refs[roles.development],
        "canonical_tree_sha": canonical_tree,
        "live_branch_count": len(branches),
        "declared_persistent_branches": sorted(roles.persistent),
        "branch_budget": {
            "target_max_persistent_or_active_refs": max_persistent,
            "live_branch_count": len(branches),
            "within_budget_now": len(branches) <= max_persistent,
        },
        "role_counts": role_counts,
        "lineage_tips": [
            item["branch"] for item in branches
            if item["role"] in {"lineage-tip", "divergent-tip"}
        ],
        "covered_ancestors": [
            {"branch": item["branch"], "covered_by": item["covered_by"]}
            for item in branches if item["role"] == "covered-ancestor"
        ],
        "tree_equivalent_history": [
            item["branch"] for item in branches
            if item["tree_equal_to_canonical"] and item["branch"] != roles.development
        ],
        "branches": branches,
        "safety": {
            "destructive_actions_performed": False,
            "retirement_advisory_is_not_delete_authorization": True,
            "ahead_count_alone_is_not_role_authority": True,
        },
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# MetalUniversal live branch topology", "",
        f"- ref source: `{report['source']}`",
        f"- canonical: `{report['canonical_branch']}` @ `{report['canonical_sha'][:12]}`",
        f"- live branches: `{report['live_branch_count']}`",
        f"- branch budget currently satisfied: `{str(report['branch_budget']['within_budget_now']).lower()}`",
        "", "## Lineage tips",
    ]
    if report["lineage_tips"]:
        lines.extend(f"- `{item}`" for item in report["lineage_tips"])
    else:
        lines.append("- none")

    lines.extend(["", "## Covered ancestors"])
    if report["covered_ancestors"]:
        for item in report["covered_ancestors"]:
            lines.append(
                f"- `{item['branch']}` -> covered by "
                + ", ".join(f"`{name}`" for name in item["covered_by"])
            )
    else:
        lines.append("- none")

    lines.extend(["", "## Branches"])
    for item in report["branches"]:
        cover = " covered-by=" + ",".join(item["covered_by"]) if item["covered_by"] else ""
        lines.append(
            f"- `{item['branch']}` role=`{item['role']}` "
            f"relation=`{item['relation_to_canonical']}` "
            f"ahead={item['ahead_by']} behind={item['behind_by']} "
            f"tree-equal={str(item['tree_equal_to_canonical']).lower()}{cover}"
        )

    lines.extend([
        "", "## Interpretation",
        "This is generated Git state, not canonical documentation. A covered ancestor is only a topology observation; do not delete it without checking PR/evidence/ownership and operator intent. History anchors may be commit-ahead while tree-identical, so ahead count alone must never be used as development authority.",
    ])
    return "\n".join(lines) + "\n"


def self_test() -> int:
    roles = DeclaredRoles(
        stable="master", development="integration", history_anchor="research",
        platforms=frozenset({"ios"}),
    )
    assert role_for("integration", relation_to_canonical="same-commit", tree_equal_to_canonical=True, coverers=[], roles=roles) == "development"
    assert role_for("research", relation_to_canonical="descendant", tree_equal_to_canonical=True, coverers=[], roles=roles) == "history-anchor"
    assert role_for("stage-a", relation_to_canonical="descendant", tree_equal_to_canonical=False, coverers=["tip"], roles=roles) == "covered-ancestor"
    assert role_for("tip", relation_to_canonical="descendant", tree_equal_to_canonical=False, coverers=[], roles=roles) == "lineage-tip"
    assert role_for("ios", relation_to_canonical="diverged", tree_equal_to_canonical=False, coverers=[], roles=roles) == "platform-line"
    assert role_for("old", relation_to_canonical="ancestor", tree_equal_to_canonical=False, coverers=[], roles=roles) == "absorbed-ancestor"
    print("Branch topology self-test: PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh", action="store_true", help="fetch/prune all origin branch refs before compiling topology")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    registry = load_registry()
    if args.refresh:
        refresh_remote()
    source, refs = available_refs()
    if not refs:
        raise SystemExit("branch-topology: no local or remote branch refs are available")
    report = compute_topology(registry, source, refs)
    if args.format == "json":
        json.dump(report, sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(render_markdown(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
