#!/usr/bin/env python3
"""Fail-closed activation gate for P1 Metal 4 main-renderer performance trials."""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1


def positive(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def evaluate(report_path: Path, expected: str) -> tuple[dict[str, Any], int]:
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-admission-evidence",
            "expected": expected,
            "reason": f"could not read native fullscreen report: {exc}",
        }, 2

    renderer = report.get("metal4MainRenderer")
    if not isinstance(renderer, dict):
        return {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-admission-evidence",
            "expected": expected,
            "reason": "native fullscreen report has no metal4MainRenderer object",
        }, 2

    engaged = report.get("metal4MainRendererEngaged") is True
    residency = report.get("residencySetEnabled") is True
    created = renderer.get("commandBuffersCreated")
    leases = renderer.get("leasesBegun")
    submissions = renderer.get("submissions")
    avoided = renderer.get("commandBufferFactoryCallsAvoided")

    evidence = {
        "engaged": engaged,
        "residencySetEnabled": residency,
        "commandBuffersCreated": created,
        "leasesBegun": leases,
        "submissions": submissions,
        "commandBufferFactoryCallsAvoided": avoided,
    }

    if expected == "candidate":
        checks = {
            "main_renderer_engaged": engaged,
            "explicit_residency_engaged": residency,
            "three_reusable_command_buffers": created == 3,
            "leases_exercised": positive(leases),
            "submissions_exercised": positive(submissions),
            "reuse_exercised": positive(avoided),
        }
        state = "admitted" if all(checks.values()) else "rejected-no-admission"
        reason = (
            "Metal 4 main renderer, explicit residency, three-slot reuse and submissions are active"
            if state == "admitted"
            else "candidate did not prove the complete Metal 4 main-renderer execution path"
        )
    elif expected == "baseline":
        checks = {
            "main_renderer_not_engaged": not engaged,
            "explicit_residency_engaged": residency,
            "no_metal4_main_command_buffers": created == 0,
        }
        state = "admitted" if all(checks.values()) else "rejected-no-admission"
        reason = (
            "baseline keeps the same Metal 4 compiler/present/residency dependencies but main renderer is off"
            if state == "admitted"
            else "baseline isolation contract was not preserved"
        )
    else:
        raise ValueError(f"unsupported expected lane: {expected}")

    result = {
        "schema_version": SCHEMA_VERSION,
        "state": state,
        "expected": expected,
        "reason": reason,
        "checks": checks,
        "evidence": evidence,
    }
    return result, 0 if state == "admitted" else 3


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        candidate = root / "candidate.json"
        candidate.write_text(json.dumps({
            "metal4MainRendererEngaged": True,
            "residencySetEnabled": True,
            "metal4MainRenderer": {
                "commandBuffersCreated": 3,
                "leasesBegun": 100,
                "submissions": 99,
                "commandBufferFactoryCallsAvoided": 97,
            },
        }), encoding="utf-8")
        result, code = evaluate(candidate, "candidate")
        assert code == 0 and result["state"] == "admitted"

        baseline = root / "baseline.json"
        baseline.write_text(json.dumps({
            "metal4MainRendererEngaged": False,
            "residencySetEnabled": True,
            "metal4MainRenderer": {
                "commandBuffersCreated": 0,
                "leasesBegun": 0,
                "submissions": 0,
                "commandBufferFactoryCallsAvoided": 0,
            },
        }), encoding="utf-8")
        result, code = evaluate(baseline, "baseline")
        assert code == 0 and result["state"] == "admitted"
    print("check_metal4_main_trial self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", nargs="?", type=Path)
    parser.add_argument("--expected", choices=("baseline", "candidate"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.report is None or args.expected is None:
        parser.error("report and --expected are required unless --self-test is used")
    result, code = evaluate(args.report, args.expected)
    output = args.output or args.report.with_name("metal4-main-admission.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"P1 Metal 4 admission: {result['state']} — {result['reason']}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
