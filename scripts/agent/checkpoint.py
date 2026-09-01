#!/usr/bin/env python3
"""Maintain an ignored, exact-SHA-scoped checkpoint for resumable agent work."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import context as context_tool

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "build/agent-state/current.json"
ALLOWED_STATUS = {
    "oriented", "implementing", "verifying", "blocked", "rejected",
    "ready-for-review", "completed",
}
ALLOWED_CHECK_STATUS = {"pass", "fail", "blocked", "pending", "inconclusive"}


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_state() -> dict[str, Any]:
    if not STATE.is_file():
        raise SystemExit("No checkpoint exists. Run checkpoint.py init first.")
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SystemExit(f"Cannot parse {STATE.relative_to(ROOT)}: {exc}") from exc


def write_state(state: dict[str, Any]) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(
        json.dumps(state, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def git_identity() -> dict[str, str]:
    return {
        "branch": context_tool.run_git("branch", "--show-current") or "detached/unknown",
        "current_sha": context_tool.run_git("rev-parse", "HEAD") or "unknown",
    }


def parse_check(value: str) -> dict[str, str]:
    parts = value.split("|", 2)
    if len(parts) < 2:
        raise argparse.ArgumentTypeError("--check must be NAME|STATUS or NAME|STATUS|EVIDENCE")
    name, status = parts[0].strip(), parts[1].strip()
    evidence = parts[2].strip() if len(parts) == 3 else ""
    if not name or status not in ALLOWED_CHECK_STATUS:
        raise argparse.ArgumentTypeError(
            f"invalid --check {value!r}; status must be one of {sorted(ALLOWED_CHECK_STATUS)}"
        )
    item = {"name": name, "status": status}
    if evidence:
        item["evidence"] = evidence
    return item


def bind_checks_to_sha(checks: list[dict[str, str]], source_sha: str) -> list[dict[str, str]]:
    return [{**item, "source_sha": source_sha, "recorded_utc": now()} for item in checks]


def upsert_checks(existing: list[dict[str, str]], updates: list[dict[str, str]]) -> list[dict[str, str]]:
    by_name = {item["name"]: dict(item) for item in existing if item.get("name")}
    for item in updates:
        by_name[item["name"]] = item
    return [by_name[name] for name in sorted(by_name)]


def render_markdown(state: dict[str, Any]) -> str:
    current_sha = state["git"]["current_sha"]
    lines = [
        "# MetalUniversal task checkpoint",
        "",
        f"- task: {state['task']}",
        f"- status: `{state['status']}`",
        f"- claim: `{state['claim']}`",
        f"- routing basis: `{state.get('routing_basis', 'legacy/unknown')}`",
        f"- branch: `{state['git']['branch']}`",
        f"- start SHA: `{state['git']['start_sha']}`",
        f"- current SHA: `{current_sha}`",
        f"- updated: `{state['updated_utc']}`",
    ]
    if state.get("hypothesis"):
        lines.extend(["", "## Hypothesis", state["hypothesis"]])
    if state.get("changed_component_ids"):
        lines.extend(["", "## Changed-component ownership"])
        lines.extend(f"- `{item}`" for item in state["changed_component_ids"])
    elif state.get("active_component_ids") or state.get("direct_component_ids"):
        lines.extend(["", "## Planned/active route"])
        lines.extend(
            f"- `{item}`" for item in state.get("active_component_ids", state.get("direct_component_ids", []))
        )
    if state.get("proof_obligation_ids"):
        lines.extend(["", "## Proof obligations"])
        lines.append("- " + ", ".join(f"`{item}`" for item in state["proof_obligation_ids"]))
    if state.get("execution_profile_ids"):
        lines.extend(["", "## Execution schedule"])
        lines.extend(f"- `{item}`" for item in state["execution_profile_ids"])
    if state.get("completed_checks"):
        lines.extend(["", "## Checks"])
        for check in state["completed_checks"]:
            check_sha = check.get("source_sha", "unbound")
            validity = "current" if check_sha == current_sha else "stale"
            suffix = f" — {check['evidence']}" if check.get("evidence") else ""
            lines.append(f"- `{check['status']}` `{validity}` {check['name']} @ `{check_sha[:12]}`{suffix}")
    if state.get("blockers"):
        lines.extend(["", "## Blockers"])
        lines.extend(f"- {item}" for item in state["blockers"])
    if state.get("next_command"):
        lines.extend(["", "## Next command", f"`{state['next_command']}`"])
    return "\n".join(lines) + "\n"


def init_state(args: argparse.Namespace) -> dict[str, Any]:
    registry = context_tool.load_registry()
    capsule = context_tool.build_capsule(registry, args.task, since=args.since, requested_claim=args.claim)
    git = git_identity()
    active_ids = [item["id"] for item in capsule["routing"]["direct_components"]]
    changed_ids = [item["id"] for item in capsule["routing"].get("changed_components", [])]
    execution_ids = [item["id"] for item in capsule.get("execution_plan", capsule["proof_plan"])]
    obligation_ids = [item["id"] for item in capsule.get("proof_obligations", capsule["proof_plan"])]
    return {
        "schema_version": 2,
        "task": args.task,
        "status": "oriented",
        "claim": capsule["claim"],
        "routing_basis": capsule["routing"].get("ownership_basis", "legacy/unknown"),
        "git": {
            "branch": git["branch"],
            "start_sha": git["current_sha"],
            "current_sha": git["current_sha"],
            "diff_base_ref": capsule["git"]["diff_base_ref"],
        },
        "changed_component_ids": changed_ids,
        "active_component_ids": active_ids,
        "direct_component_ids": active_ids,
        "impacted_component_ids": capsule["routing"]["impacted_component_ids"],
        "activated_boundary_ids": [item["id"] for item in capsule["routing"]["activated_boundaries"]],
        "proof_obligation_ids": obligation_ids,
        "execution_profile_ids": execution_ids,
        "proof_profile_ids": execution_ids,
        "hypothesis": args.hypothesis or "",
        "completed_checks": [],
        "blockers": [],
        "next_command": args.next_command or "",
        "created_utc": now(),
        "updated_utc": now(),
    }


def command_init(args: argparse.Namespace) -> int:
    if STATE.exists() and not args.force:
        raise SystemExit(f"Checkpoint already exists at {STATE.relative_to(ROOT)}; use --force or update/show it.")
    state = init_state(args)
    write_state(state)
    sys.stdout.write(render_markdown(state))
    return 0


def command_update(args: argparse.Namespace) -> int:
    state = load_state()
    identity = git_identity()
    if args.status:
        state["status"] = args.status
    if args.hypothesis is not None:
        state["hypothesis"] = args.hypothesis
    if args.next_command is not None:
        state["next_command"] = args.next_command
    if args.blocker:
        state["blockers"] = list(dict.fromkeys([*state.get("blockers", []), *args.blocker]))
    if args.clear_blockers:
        state["blockers"] = []
    if args.check:
        bound = bind_checks_to_sha(args.check, identity["current_sha"])
        state["completed_checks"] = upsert_checks(state.get("completed_checks", []), bound)
    state["git"]["current_sha"] = identity["current_sha"]
    state["git"]["branch"] = identity["branch"]
    state["updated_utc"] = now()
    write_state(state)
    sys.stdout.write(render_markdown(state))
    return 0


def command_show(args: argparse.Namespace) -> int:
    state = load_state()
    if args.format == "json":
        json.dump(state, sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(render_markdown(state))
    return 0


def self_test() -> int:
    checks = bind_checks_to_sha(
        [parse_check("static|pass|build/report.json"), parse_check("gpu|blocked")],
        "a" * 40,
    )
    checks = upsert_checks([{"name": "static", "status": "pending"}], checks)
    assert [item["name"] for item in checks] == ["gpu", "static"]
    assert all(item["source_sha"] == "a" * 40 for item in checks)
    state = {
        "task": "test",
        "status": "verifying",
        "claim": "correctness",
        "routing_basis": "diff",
        "git": {"branch": "branch", "start_sha": "a", "current_sha": "b" * 40},
        "updated_utc": now(),
        "changed_component_ids": ["validation.contract"],
        "active_component_ids": ["validation.contract"],
        "proof_obligation_ids": ["repo.static", "render.synthetic"],
        "execution_profile_ids": ["repo.static"],
        "completed_checks": checks,
        "blockers": [],
        "next_command": "next",
    }
    rendered = render_markdown(state)
    assert "validation.contract" in rendered and "build/report.json" in rendered
    assert "stale" in rendered and "Proof obligations" in rendered
    print("Agent checkpoint self-test: PASS")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="command")
    init = sub.add_parser("init", help="create a task checkpoint from the current context/proof plan")
    init.add_argument("--task", required=True)
    init.add_argument("--since")
    init.add_argument(
        "--claim",
        choices=("auto", "control", "correctness", "performance", "platform", "presentation"),
        default="auto",
    )
    init.add_argument("--hypothesis")
    init.add_argument("--next-command")
    init.add_argument("--force", action="store_true")
    init.set_defaults(func=command_init)
    update = sub.add_parser("update", help="update status, evidence, blockers or next action")
    update.add_argument("--status", choices=sorted(ALLOWED_STATUS))
    update.add_argument("--hypothesis")
    update.add_argument("--next-command")
    update.add_argument("--blocker", action="append", default=[])
    update.add_argument("--clear-blockers", action="store_true")
    update.add_argument("--check", type=parse_check, action="append", default=[])
    update.set_defaults(func=command_update)
    show = sub.add_parser("show", help="render the current checkpoint")
    show.add_argument("--format", choices=("markdown", "json"), default="markdown")
    show.set_defaults(func=command_show)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not getattr(args, "command", None):
        parser.print_help()
        return 2
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
