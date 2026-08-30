#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEEP = {
    "master",
    "integration/iris-metal-next",
    "feature/ios-amethyst-runtime",
    "research/modernization-backlog",
}
WORKFLOW = ".github/workflows/one-shot-branch-consolidation.yml"
SCRIPT = "scripts/agent/one_shot_branch_consolidation.py"


def run(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=ROOT,
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def out(*args: str) -> str:
    return run(*args, capture=True).stdout.strip()


def branch_tips() -> list[tuple[str, str]]:
    raw = out(
        "git",
        "for-each-ref",
        "--format=%(refname:strip=3)\t%(objectname)",
        "refs/remotes/origin",
    )
    result: list[tuple[str, str]] = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        branch, sha = line.split("\t", 1)
        if branch == "HEAD":
            continue
        result.append((branch, sha))
    return sorted(result)


def is_master_ancestor(sha: str) -> bool:
    return run("git", "merge-base", "--is-ancestor", sha, "origin/master", check=False).returncode == 0


def install_agents_policy() -> None:
    path = ROOT / "AGENTS.md"
    text = path.read_text(encoding="utf-8")
    old_intro = (
        "The canonical continued-development base is `integration/iris-metal-next`. "
        "Create one bounded feature branch from it for each task. Superseded `agent/*`, "
        "`codex/*`, archive, bootstrap-test and pre-Iris feature branches are historical "
        "inputs only unless the operator explicitly requests extraction from them."
    )
    new_intro = (
        "The canonical continued-development base is `integration/iris-metal-next`. "
        "Create one bounded feature branch from it for each task. The repository intentionally "
        "keeps only a small set of long-lived branches; disposable task branches must be merged "
        "or deleted before the task is considered complete. Historical work is preserved by exact "
        "commit SHA and the single `research/modernization-backlog` history anchor, not by "
        "accumulating branch refs."
    )
    if old_intro not in text:
        raise SystemExit("AGENTS.md canonical intro changed; refusing blind policy edit")
    text = text.replace(old_intro, new_intro, 1)

    policy = """## Branch lifecycle policy

Branch count is an explicit repository invariant. Unless the operator explicitly authorizes another long-lived line, the only persistent branches are:

- `master`: stable/promoted tree;
- `integration/iris-metal-next`: canonical continued-development base;
- `feature/ios-amethyst-runtime`: isolated Apple-mobile/Amethyst platform line;
- `research/modernization-backlog`: history-only anchor for retired experimental branch tips and unlanded research.

Keep the repository at **3–5 total branches**. A branch created for one task is disposable even if its name starts with `feature/`, `fix/`, `codex/`, `agent/`, `ci/`, `perf/`, `chore/`, `tooling/`, `archive/`, or `research/`. The prefix does not grant permanence.

Mandatory end-of-task rule for every disposable branch:

1. If the change is accepted, land it into the appropriate long-lived branch through the repository's required validation/merge path, then delete the disposable branch.
2. If the experiment is rejected, superseded, diagnostic-only, or no longer needed, delete the branch instead of leaving it as an archive.
3. If useful work is not ready to land, record the exact commit SHA, purpose, validation boundary and follow-up in `docs/agent/retired-branch-backlog.md`; make sure the commit remains reachable from `research/modernization-backlog`; then delete the disposable branch.
4. Close or mark superseded any PR whose head branch is retired. Do not keep an open PR solely to preserve history.
5. `*-staging-*`, `*-clean-*`, `*-audit-*`, `*-probe-*`, `*-replay-*`, bootstrap CI and one-shot workflow branches are never long-lived. Remove them as part of the same task that created them.
6. Do not create per-task `archive/*` branches. Git history, exact SHAs, PRs, tags when appropriate, and the single research history anchor are the archive.
7. Before the final report, run a branch inventory. If the task leaves more than five branches, it is incomplete unless the operator explicitly approved the additional persistent branch.

Merging into a shared long-lived branch still requires whatever human/CI authorization the task and repository policy require. That does not relax the cleanup rule: a disposable branch may wait only for that explicit decision, and after the decision it must be merged-and-deleted or simply deleted.

"""
    marker = "## Repository objective\n"
    if "## Branch lifecycle policy\n" not in text:
        if marker not in text:
            raise SystemExit("AGENTS.md insertion marker missing")
        text = text.replace(marker, policy + marker, 1)
    path.write_text(text, encoding="utf-8")


def write_backlog(tips: list[tuple[str, str]]) -> None:
    path = ROOT / "docs/agent/retired-branch-backlog.md"
    lines = [
        "# Retired branch backlog",
        "",
        "This document is the recovery map for work intentionally removed from the branch namespace during the 2026-08-30 consolidation. A deleted branch is not lost: its exact tip is listed below and every unique retired tip is reachable from `research/modernization-backlog`.",
        "",
        "The active development rule is to re-implement or selectively cherry-pick still-relevant work onto a fresh bounded branch from `integration/iris-metal-next`, validate it against the current tree, merge it, and delete that bounded branch. Do not resurrect the old branch namespace.",
        "",
        "## Highest-priority unlanded work",
        "",
        "- **Correctness first:** PR #39 (`fix/p2-mrt-store-liveness-review`) contains the attachment-local MRT deferred-store fix. Port its product/test delta before more aggressive load/store, memoryless, pass-fusion or aliasing work.",
        "- **Shadow correctness:** PR #38 (`fix/bsl-shadow-attachment-format`) was still primarily a reproduction/diagnostic lane; implement the real authoritative shadow render-pass/pipeline attachment-format fix fresh on the current integration tree.",
        "- **Terrain ownership/lifetime:** PR #24 (`fix/metal4-arena-lifetime`) contains useful `TerrainMeshGeneration` generation ownership and Sodium arena-lifetime protection. Selectively port those pieces; do not wholesale merge its old renderer/cache changes.",
        "- **Iris/TBDR resource stack:** PRs #44, #45, #47, #49, #50, #52 and #54 cover constrained memoryless allocation, residency of pipeline allocations, single argument-table authority, exact attachment death points, generation-safe alias recipes/runtime and placement-heap execution. Re-land in dependency order after the MRT correctness fix.",
        "- **GPU terrain submission:** PRs #42, #46, #48, #51, #55 and #57 cover sparse visible ICB authoring, persistent terrain scene, frame-slot scratch reuse, draw authority, self-contained admission and fused visibility+ICB authoring. Treat this as one stacked experimental line and revalidate on physical Metal 4 before default-on promotion.",
        "- **Pipeline compilation:** PR #56 parallelizes stable MSL artifact lookup/prewarm; PR #58 removes structurally duplicate Metal 4 depth/stencil PSO variants. These are good bounded follow-ups after correctness convergence.",
        "- **Semantic alternate architecture:** PR #25 is a design/migration source only. Mine tests/contracts and current-valid semantics; do not wholesale merge the divergent old tree.",
        "",
        "## Retired branch tips",
        "",
        "`master-ancestor` means the exact branch tip was already reachable from `master` at consolidation time. `history-preserved-only` means it was not in the promoted tree and is preserved only through the research history anchor for selective recovery.",
        "",
        "| Retired branch | Exact tip | Status at consolidation |",
        "| --- | --- | --- |",
    ]
    for branch, sha in tips:
        if branch in KEEP:
            continue
        state = "master-ancestor" if is_master_ancestor(sha) else "history-preserved-only"
        lines.append(f"| `{branch}` | `{sha}` | {state} |")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    run("git", "config", "user.name", "github-actions[bot]")
    run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    run("git", "fetch", "--prune", "origin", "+refs/heads/*:refs/remotes/origin/*")
    tips = branch_tips()

    install_agents_policy()
    write_backlog(tips)

    run("git", "rm", WORKFLOW, SCRIPT)
    run("git", "add", "AGENTS.md", "docs/agent/retired-branch-backlog.md")
    run("git", "diff", "--check", "--cached")
    run("git", "commit", "-m", "repo: enforce disposable branch lifecycle")
    clean_master = out("git", "rev-parse", "HEAD")
    run("git", "push", "origin", "HEAD:master")

    # Preserve all unique old tips as parents of one history-only commit while
    # keeping the working tree exactly equal to the cleaned master tree.
    tree = out("git", "rev-parse", f"{clean_master}^{{tree}}")
    unique_tips = sorted({sha for _, sha in tips})
    cmd = ["git", "commit-tree", tree, "-p", clean_master]
    for sha in unique_tips:
        if sha != clean_master:
            cmd.extend(["-p", sha])
    proc = subprocess.run(
        cmd,
        cwd=ROOT,
        check=True,
        text=True,
        input=(
            "Consolidate retired branch history\n\n"
            "History-only anchor: tree equals current master; parents preserve all pre-cleanup branch tips.\n"
        ),
        stdout=subprocess.PIPE,
    )
    history_sha = proc.stdout.strip()
    run("git", "push", "--force", "origin", f"{history_sha}:refs/heads/research/modernization-backlog")

    old_integration = next(sha for branch, sha in tips if branch == "integration/iris-metal-next")
    run(
        "git",
        "push",
        f"--force-with-lease=refs/heads/integration/iris-metal-next:{old_integration}",
        "origin",
        f"{clean_master}:refs/heads/integration/iris-metal-next",
    )

    retired = [branch for branch, _ in tips if branch not in KEEP]
    for offset in range(0, len(retired), 20):
        chunk = retired[offset : offset + 20]
        if chunk:
            run("git", "push", "origin", "--delete", *chunk)

    final_raw = out("git", "ls-remote", "--heads", "origin")
    final = sorted(line.split("refs/heads/", 1)[1] for line in final_raw.splitlines())
    print("Final remote heads:")
    for branch in final:
        print(f"  {branch}")
    if set(final) != KEEP:
        raise SystemExit(f"expected exactly {sorted(KEEP)}, found {final}")


if __name__ == "__main__":
    main()
