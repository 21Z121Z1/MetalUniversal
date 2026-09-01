#!/usr/bin/env python3
"""Record and verify the Git identity actually tested by a CI proof."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
ALLOWED_SUBJECTS = {"candidate-head", "merge-result"}


def run_git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def load_event(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    event_path = Path(path)
    if not event_path.is_file():
        return {}
    return json.loads(event_path.read_text(encoding="utf-8"))


def subject_identity(
    *, proof_subject: str, event_name: str, event_sha: str | None,
    event: dict[str, Any], tested_commit_sha: str, tested_tree_sha: str,
) -> dict[str, Any]:
    if proof_subject not in ALLOWED_SUBJECTS:
        raise ValueError(f"unsupported proof subject: {proof_subject}")

    pull = event.get("pull_request") or {}
    head = pull.get("head") or {}
    base = pull.get("base") or {}
    candidate_head_sha = head.get("sha")
    base_sha = base.get("sha")

    if not candidate_head_sha and event_name != "pull_request":
        candidate_head_sha = event_sha

    expected = candidate_head_sha or event_sha if proof_subject == "candidate-head" else event_sha
    if not expected:
        raise ValueError(
            f"cannot resolve expected commit for proof_subject={proof_subject} event_name={event_name!r}"
        )

    exact_match = tested_commit_sha.lower() == str(expected).lower()
    return {
        "schema_version": 1,
        "proof_subject": proof_subject,
        "event_name": event_name or None,
        "candidate_head_sha": candidate_head_sha,
        "base_sha": base_sha,
        "event_sha": event_sha,
        "expected_tested_commit_sha": expected,
        "tested_commit_sha": tested_commit_sha,
        "tested_tree_sha": tested_tree_sha,
        "exact_match": exact_match,
    }


def self_test() -> int:
    event = {"pull_request": {"head": {"sha": "a" * 40}, "base": {"sha": "b" * 40}}}
    head = subject_identity(
        proof_subject="candidate-head", event_name="pull_request", event_sha="c" * 40,
        event=event, tested_commit_sha="a" * 40, tested_tree_sha="d" * 40,
    )
    assert head["exact_match"] and head["expected_tested_commit_sha"] == "a" * 40

    merge = subject_identity(
        proof_subject="merge-result", event_name="pull_request", event_sha="c" * 40,
        event=event, tested_commit_sha="c" * 40, tested_tree_sha="e" * 40,
    )
    assert merge["exact_match"] and merge["candidate_head_sha"] == "a" * 40

    push = subject_identity(
        proof_subject="candidate-head", event_name="push", event_sha="f" * 40,
        event={}, tested_commit_sha="f" * 40, tested_tree_sha="1" * 40,
    )
    assert push["exact_match"] and push["candidate_head_sha"] == "f" * 40
    print("CI proof-subject self-test: PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proof-subject", choices=sorted(ALLOWED_SUBJECTS))
    parser.add_argument("--output")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not args.proof_subject or not args.output:
        parser.error("--proof-subject and --output are required outside --self-test")

    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event_sha = os.environ.get("GITHUB_SHA")
    event = load_event(os.environ.get("GITHUB_EVENT_PATH"))
    tested_commit = run_git("rev-parse", "HEAD")
    tested_tree = run_git("rev-parse", "HEAD^{tree}")

    try:
        result = subject_identity(
            proof_subject=args.proof_subject,
            event_name=event_name,
            event_sha=event_sha,
            event=event,
            tested_commit_sha=tested_commit,
            tested_tree_sha=tested_tree,
        )
    except ValueError as exc:
        raise SystemExit(f"ci-proof-subject: FAIL: {exc}") from exc

    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    if not result["exact_match"]:
        raise SystemExit("ci-proof-subject: FAIL: tested commit does not match declared proof subject")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
