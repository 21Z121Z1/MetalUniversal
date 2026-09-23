#!/usr/bin/env python3
"""Fail-closed cross-lane validator for P1 physical Minecraft E2E evidence."""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
UNRECORDED = "unrecorded"


def load(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"could not read {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"evidence is not an object: {path}")
    return data


def number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def positive(value: Any) -> bool:
    return number(value) and value > 0


def zero(value: Any) -> bool:
    return number(value) and value == 0


def identity(data: dict[str, Any]) -> tuple[str, str, str] | None:
    obj = data.get("identity")
    if not isinstance(obj, dict):
        return None
    values = (
        obj.get("sourceSha"),
        obj.get("productionJarSha256"),
        obj.get("nativeDylibSha256"),
    )
    if not all(isinstance(value, str) and value and value != UNRECORDED for value in values):
        return None
    source, jar, dylib = values
    if len(source) != 40 or len(jar) != 64 or len(dylib) != 64:
        return None
    if not all(all(ch in "0123456789abcdef" for ch in value) for value in values):
        return None
    return source, jar, dylib


def evaluate(baseline_path: Path, candidate_path: Path) -> tuple[dict[str, Any], int]:
    try:
        baseline = load(baseline_path)
        candidate = load(candidate_path)
    except ValueError as exc:
        return {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-evidence",
            "reason": str(exc),
        }, 2

    baseline_identity = identity(baseline)
    candidate_identity = identity(candidate)
    baseline_metrics = baseline.get("metrics")
    candidate_metrics = candidate.get("metrics")
    baseline_raw = baseline.get("rawWindow")
    candidate_raw = candidate.get("rawWindow")

    checks = {
        "baseline_schema": baseline.get("schema") == 3,
        "candidate_schema": candidate.get("schema") == 3,
        "baseline_status": baseline.get("status") == "pass",
        "candidate_status": candidate.get("status") == "pass",
        "baseline_lane": baseline.get("lane") == "baseline",
        "candidate_lane": candidate.get("lane") == "candidate",
        "baseline_identity_recorded": baseline_identity is not None,
        "candidate_identity_recorded": candidate_identity is not None,
        "identical_source_and_binaries": baseline_identity is not None
        and baseline_identity == candidate_identity,
        "baseline_metal4": baseline.get("metal4Supported") is True,
        "candidate_metal4": candidate.get("metal4Supported") is True,
        "baseline_residency": baseline.get("residencySetEnabled") is True,
        "candidate_residency": candidate.get("residencySetEnabled") is True,
        "baseline_renderer_disabled": baseline.get("mainRendererEnabled") is False
        and baseline.get("mainRendererEngaged") is False
        and baseline.get("mainRendererEngagementFraction") == 0.0,
        "candidate_renderer_enabled": candidate.get("mainRendererEnabled") is True
        and candidate.get("mainRendererEngaged") is True
        and candidate.get("mainRendererEngagementFraction") == 1.0,
        "baseline_presented": positive(baseline.get("presentFrames"))
        and baseline.get("presentationHealthy") is True,
        "candidate_presented": positive(candidate.get("presentFrames"))
        and candidate.get("presentationHealthy") is True,
        "metrics_objects": isinstance(baseline_metrics, dict) and isinstance(candidate_metrics, dict),
        "raw_objects": isinstance(baseline_raw, dict) and isinstance(candidate_raw, dict),
    }

    if isinstance(baseline_metrics, dict) and isinstance(baseline_raw, dict):
        checks.update({
            "baseline_no_main_renderer_java_work": zero(baseline_raw.get("commandBufferBegins"))
            and zero(baseline_raw.get("commitCalls")),
            "baseline_no_main_renderer_native_work": zero(baseline_raw.get("nativeBegun"))
            and zero(baseline_raw.get("nativeSubmitted")),
            "baseline_no_allocator_resets": zero(baseline_metrics.get("metal4.commandAllocatorResets")),
            "baseline_drained": zero(baseline_raw.get("outstandingSubmissionsAfterDrain")),
        })
    else:
        checks.update({
            "baseline_no_main_renderer_java_work": False,
            "baseline_no_main_renderer_native_work": False,
            "baseline_no_allocator_resets": False,
            "baseline_drained": False,
        })

    if isinstance(candidate_metrics, dict) and isinstance(candidate_raw, dict):
        begins = candidate_raw.get("commandBufferBegins")
        commits = candidate_raw.get("commitCalls")
        checks.update({
            "candidate_main_renderer_exercised": positive(begins) and positive(commits),
            "candidate_java_native_begin_match": begins == candidate_raw.get("nativeBegun") and positive(begins),
            "candidate_java_native_submit_match": commits == candidate_raw.get("nativeSubmitted") and positive(commits),
            "candidate_allocator_reset_match": candidate_metrics.get("metal4.commandAllocatorResets") == begins
            and positive(begins),
            "candidate_no_argument_table_allocations": zero(
                candidate_metrics.get("metal4.argumentTableAllocationsDuringEncoding")
            ),
            "candidate_no_compute_overflow": zero(candidate_metrics.get("metal4.computeTableOverflow")),
            "candidate_render_table_high_water": candidate_metrics.get("metal4.renderTableHighWater") == 1,
            "candidate_drained": zero(candidate_raw.get("outstandingSubmissionsAfterDrain")),
        })
    else:
        checks.update({
            "candidate_main_renderer_exercised": False,
            "candidate_java_native_begin_match": False,
            "candidate_java_native_submit_match": False,
            "candidate_allocator_reset_match": False,
            "candidate_no_argument_table_allocations": False,
            "candidate_no_compute_overflow": False,
            "candidate_render_table_high_water": False,
            "candidate_drained": False,
        })

    passed = all(checks.values())
    result = {
        "schema_version": SCHEMA_VERSION,
        "stage": "P1-metal4-main-production",
        "state": "pass" if passed else "rejected-evidence",
        "reason": (
            "baseline and candidate prove the same exact production bits, Metal 4 + residency, and isolated main-renderer activation"
            if passed
            else "one or more P1 physical E2E pair invariants failed"
        ),
        "checks": checks,
        "identity": None if baseline_identity is None else {
            "sourceSha": baseline_identity[0],
            "productionJarSha256": baseline_identity[1],
            "nativeDylibSha256": baseline_identity[2],
        },
    }
    return result, 0 if passed else 3


def make_evidence(lane: str, identity_values: tuple[str, str, str]) -> dict[str, Any]:
    candidate = lane == "candidate"
    begins = 12 if candidate else 0
    commits = 12 if candidate else 0
    source, jar, dylib = identity_values
    return {
        "schema": 3,
        "status": "pass",
        "lane": lane,
        "identity": {
            "sourceSha": source,
            "productionJarSha256": jar,
            "nativeDylibSha256": dylib,
        },
        "metal4Supported": True,
        "residencySetEnabled": True,
        "mainRendererEnabled": candidate,
        "mainRendererEngaged": candidate,
        "mainRendererEngagementFraction": 1.0 if candidate else 0.0,
        "presentFrames": 60,
        "metrics": {
            "metal4.commandAllocatorResets": begins,
            "metal4.slotWaitNanos": 0,
            "metal4.slotWaitCount": 0,
            "metal4.commandBuffersPerFrame": begins / 60,
            "metal4.commitCallsPerFrame": commits / 60,
            "metal4.argumentTableAllocationsDuringEncoding": 0,
            "metal4.computeTableOverflow": 0,
            "metal4.renderTableHighWater": 1,
        },
        "rawWindow": {
            "nativeBegun": begins,
            "nativeSubmitted": commits,
            "commandBufferBegins": begins,
            "commitCalls": commits,
            "outstandingSubmissionsAfterDrain": 0,
        },
        "presentationHealthy": True,
    }


def self_test() -> None:
    source = "1" * 40
    jar = "2" * 64
    dylib = "3" * 64
    ids = (source, jar, dylib)
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        baseline_path = root / "baseline.json"
        candidate_path = root / "candidate.json"
        baseline_path.write_text(json.dumps(make_evidence("baseline", ids)), encoding="utf-8")
        candidate_path.write_text(json.dumps(make_evidence("candidate", ids)), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path)
        assert code == 0 and result["state"] == "pass", result

        wrong = make_evidence("candidate", ("4" * 40, jar, dylib))
        candidate_path.write_text(json.dumps(wrong), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path)
        assert code == 3 and result["checks"]["identical_source_and_binaries"] is False, result

        missing = make_evidence("candidate", ids)
        missing["identity"]["productionJarSha256"] = UNRECORDED
        candidate_path.write_text(json.dumps(missing), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path)
        assert code == 3 and result["checks"]["candidate_identity_recorded"] is False, result

        broken = make_evidence("candidate", ids)
        broken["metrics"]["metal4.argumentTableAllocationsDuringEncoding"] = 1
        candidate_path.write_text(json.dumps(broken), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path)
        assert code == 3 and result["checks"]["candidate_no_argument_table_allocations"] is False, result

    print("check_metal4_main_e2e_pair self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", nargs="?", type=Path)
    parser.add_argument("candidate", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.baseline is None or args.candidate is None:
        parser.error("baseline and candidate evidence paths are required unless --self-test is used")
    result, code = evaluate(args.baseline, args.candidate)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
