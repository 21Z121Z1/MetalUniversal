#!/usr/bin/env python3
"""Fail-closed admission gate for experimental Iris performance lanes."""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 2


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def positive(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def non_negative(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0


def evaluate(metrics_path: Path, profile: str) -> tuple[dict[str, Any], int]:
    try:
        metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        result = {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-admission-evidence",
            "profile": profile,
            "reason": f"could not read normalized metrics: {exc}",
            "lanes": {},
        }
        return result, 2

    if not metrics.get("complete"):
        result = {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-admission-evidence",
            "profile": profile,
            "reason": "admission probe trial was incomplete",
            "identity_errors": metrics.get("identity_errors", []),
            "lanes": {},
        }
        return result, 2

    admission = obj(metrics.get("admission"))
    fusion = obj(admission.get("renderFusionRuntime"))
    compute = obj(admission.get("computeGroupingRuntime"))
    depth = obj(admission.get("depthLivenessRuntime"))
    argument = obj(admission.get("argumentBindingRuntime"))
    hot = obj(admission.get("hotPathCounters"))

    lanes = {
        "pass-fusion": {
            "admitted": positive(fusion.get("admissions")),
            "evidence": fusion,
            "requirement": "renderFusionRuntime.admissions > 0",
        },
        "compute-grouping": {
            "admitted": positive(compute.get("admissions")) and positive(compute.get("deferredPassCloses")),
            "evidence": compute,
            "requirement": "computeGroupingRuntime.admissions > 0 and deferredPassCloses > 0",
        },
        "depth-liveness": {
            "admitted": positive(depth.get("prunedPairs")) or positive(depth.get("captureSkips")),
            "evidence": depth,
            "requirement": "depthLivenessRuntime.prunedPairs > 0 or captureSkips > 0",
        },
        "argument-tables": {
            "admitted": argument.get("enabled") is True and positive(argument.get("encodedSnapshots")),
            "evidence": argument,
            "requirement": "argumentBindingRuntime.enabled == true and encodedSnapshots > 0",
        },
        "render-command-packet": {
            "admitted": (
                positive(hot.get("renderCommandPacketCalls"))
                and positive(hot.get("renderCommandPacketOperations"))
                and hot.get("renderCommandPacketReplays") == 0
            ),
            "evidence": hot,
            "requirement": "render packet calls/operations > 0 and replays == 0",
        },
        "compute-command-packet": {
            "admitted": (
                positive(hot.get("computeCommandPacketCalls"))
                and positive(hot.get("computeCommandPacketOperations"))
                and hot.get("computeCommandPacketReplays") == 0
            ),
            "evidence": hot,
            "requirement": "compute packet calls/operations > 0 and replays == 0",
        },
        "terrain-icb": {
            "admitted": (
                positive(hot.get("terrainIcbAttempts"))
                and positive(hot.get("terrainIcbAccepted"))
                and hot.get("terrainIcbAttempts") == hot.get("terrainIcbAccepted")
                and positive(hot.get("terrainIcbDraws"))
                and hot.get("terrainIcbFallbacks") == 0
                and non_negative(hot.get("terrainIcbBudgetSkips"))
                and non_negative(hot.get("terrainIcbBudgetSkipDraws"))
                and hot.get("terrainIcbNativeStatsAvailable") is True
                and hot.get("terrainIcbAllocations") == hot.get("terrainIcbAccepted")
                and non_negative(hot.get("terrainIcbCompletionReleases"))
                and abs(
                    hot.get("terrainIcbAllocations")
                    - hot.get("terrainIcbCompletionReleases")
                ) <= 3
                and hot.get("terrainIcbBudgetFallbacks") == 0
                and hot.get("terrainIcbZeroAllocationFallbacks") == 0
            ),
            "evidence": hot,
            "requirement": (
                "terrain ICB attempts/accepted/draws > 0; attempts equal accepted batches; "
                "native allocations equal accepted batches; completion lag stays within three "
                "in-flight submissions; exhausted-budget batches are skipped before FFM; native "
                "budget and zero-sized-allocation fallbacks are forbidden"
            ),
        },
        "pipeline-prewarm": {
            "admitted": (
                isinstance(hot.get("runtimePipelineCompiles"), (int, float))
                and not isinstance(hot.get("runtimePipelineCompiles"), bool)
                and hot.get("runtimePipelineCompiles") == 0
            ),
            "evidence": {
                "runtimePipelineCompiles": hot.get("runtimePipelineCompiles"),
                "runtimePipelineCompileIdentities": hot.get("runtimePipelineCompileIdentities"),
            },
            "requirement": "runtimePipelineCompiles is present and equals 0 after warmup",
        },
    }

    if profile == "baseline":
        admitted = True
        selected = []
        reason = "baseline profile does not require experimental-lane admission"
    elif profile in lanes:
        selected = [profile]
        admitted = bool(lanes[profile]["admitted"])
        reason = lanes[profile]["requirement"]
    elif profile in {"all-safe-lanes", "all-safe-plus-terrain"}:
        selected = ["pass-fusion", "compute-grouping", "depth-liveness", "argument-tables"]
        admitted = any(bool(lanes[name]["admitted"]) for name in selected)
        reason = "at least one selected Iris optimization lane must prove runtime activation"
    elif profile == "mobilegl-hotpath":
        selected = ["render-command-packet", "pipeline-prewarm"]
        admitted = all(bool(lanes[name]["admitted"]) for name in selected)
        reason = "the production render command packet must execute with zero replay"
    elif profile == "mobilegl-complete":
        selected = [
            "pass-fusion", "compute-grouping", "depth-liveness", "argument-tables",
            "render-command-packet", "terrain-icb", "pipeline-prewarm",
        ]
        required = ["render-command-packet", "terrain-icb", "pipeline-prewarm"]
        admitted = (
            all(bool(lanes[name]["admitted"]) for name in required)
            and any(bool(lanes[name]["admitted"]) for name in selected[:4])
        )
        reason = (
            "render packet and bounded terrain ICB must execute without replay or allocation failure, runtime pipeline "
            "compiles must be zero, and at least one compiled Iris optimization lane must activate"
        )
    elif profile == "terrain-adaptive":
        result = {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-admission-evidence",
            "profile": profile,
            "reason": "terrain scheduling telemetry is not present in the authoritative fullscreen report",
            "lanes": lanes,
        }
        return result, 2
    else:
        result = {
            "schema_version": SCHEMA_VERSION,
            "state": "inconclusive-admission-evidence",
            "profile": profile,
            "reason": "unknown profile",
            "lanes": lanes,
        }
        return result, 2

    state = "admitted" if admitted else "rejected-no-admission"
    result = {
        "schema_version": SCHEMA_VERSION,
        "state": state,
        "profile": profile,
        "reason": reason,
        "selected_lanes": selected,
        "admitted_lanes": [name for name in selected if lanes[name]["admitted"]],
        "lanes": lanes,
        "source_report_sha256": metrics.get("source_report_sha256"),
        "measured_frames": metrics.get("measured_frames"),
    }
    return result, 0 if admitted else 3


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temp:
        path = Path(temp) / "metrics.json"
        path.write_text(json.dumps({
            "complete": True,
            "source_report_sha256": "abc",
            "measured_frames": 200,
            "admission": {
                "renderFusionRuntime": {"admissions": 0, "admissionCandidates": 1602},
                "computeGroupingRuntime": {"admissions": 0, "deferredPassCloses": 0},
                "depthLivenessRuntime": {"prunedPairs": 0, "captureSkips": 0},
                "argumentBindingRuntime": {"enabled": True, "encodedSnapshots": 10},
                "hotPathCounters": {
                    "runtimePipelineCompiles": 0,
                    "terrainIcbAttempts": 4,
                    "terrainIcbAccepted": 4,
                    "terrainIcbDraws": 64,
                    "terrainIcbFallbacks": 0,
                    "terrainIcbBudgetSkips": 8,
                    "terrainIcbBudgetSkipDraws": 192,
                    "terrainIcbNativeStatsAvailable": True,
                    "terrainIcbAllocations": 4,
                    "terrainIcbCompletionReleases": 3,
                    "terrainIcbBudgetFallbacks": 0,
                    "terrainIcbZeroAllocationFallbacks": 0,
                },
            },
        }), encoding="utf-8")
        rejected, code = evaluate(path, "pass-fusion")
        assert code == 3 and rejected["state"] == "rejected-no-admission"
        admitted, code = evaluate(path, "argument-tables")
        assert code == 0 and admitted["state"] == "admitted"
        admitted, code = evaluate(path, "pipeline-prewarm")
        assert code == 0 and admitted["state"] == "admitted"
        admitted, code = evaluate(path, "terrain-icb")
        assert code == 0 and admitted["state"] == "admitted"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["admission"]["hotPathCounters"]["terrainIcbZeroAllocationFallbacks"] = 1
        path.write_text(json.dumps(payload), encoding="utf-8")
        rejected, code = evaluate(path, "terrain-icb")
        assert code == 3 and rejected["state"] == "rejected-no-admission"
    print("check_unified_eval_admission self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("metrics", nargs="?", type=Path)
    parser.add_argument("--profile")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.metrics is None or not args.profile:
        parser.error("metrics and --profile are required unless --self-test is used")
    result, code = evaluate(args.metrics, args.profile)
    output = args.output or args.metrics.with_name("admission.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Admission: {result['state']} — {result['reason']}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
