#!/usr/bin/env python3
"""Fail-closed admission gate for experimental Iris performance lanes."""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
NATIVE_ARGUMENT_TABLE_AUTHORITY = "native-metal4-argument-tables"


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def positive(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def native_argument_tables(argument: dict[str, Any]) -> bool:
    """Accept only execution evidence, never Java snapshot bookkeeping."""
    return (
        argument.get("executionAuthority") == NATIVE_ARGUMENT_TABLE_AUTHORITY
        and argument.get("nativeMainRendererEngaged") is True
    )


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
    binding_path = obj(admission.get("bindingPathRuntime"))

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
            "admitted": native_argument_tables(argument),
            "evidence": argument,
            "requirement": (
                "argumentBindingRuntime.executionAuthority == native-metal4-argument-tables "
                "and nativeMainRendererEngaged == true; Java snapshot/patch counters are diagnostics only"
            ),
        },
        "binding-tokens": {
            "admitted": (
                binding_path.get("tokenizedBindings") is True
                and positive(binding_path.get("renderForwardedCalls"))
                and positive(binding_path.get("renderSuppressedCalls"))
                and positive(binding_path.get("packetCalls"))
                and positive(binding_path.get("packetEntries"))
            ),
            "evidence": binding_path,
            "requirement": (
                "bindingPathRuntime.tokenizedBindings == true and renderForwardedCalls > 0 "
                "and renderSuppressedCalls > 0 and packetCalls > 0 and packetEntries >= packetCalls"
            ),
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
        selected = list(lanes)
        admitted = any(bool(lanes[name]["admitted"]) for name in selected)
        reason = "at least one selected Iris optimization lane must prove runtime activation"
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
        payload = {
            "complete": True,
            "source_report_sha256": "abc",
            "measured_frames": 200,
            "admission": {
                "renderFusionRuntime": {"admissions": 0, "admissionCandidates": 1602},
                "computeGroupingRuntime": {"admissions": 0, "deferredPassCloses": 0},
                "depthLivenessRuntime": {"prunedPairs": 0, "captureSkips": 0},
                "argumentBindingRuntime": {
                    "enabled": True,
                    "encodedSnapshots": 10,
                    "patchSnapshots": 10,
                    "executionAuthority": "java-patch-seam-native-not-negotiated",
                    "nativeMainRendererEngaged": False,
                },
            },
        }
        path.write_text(json.dumps(payload), encoding="utf-8")
        rejected, code = evaluate(path, "pass-fusion")
        assert code == 3 and rejected["state"] == "rejected-no-admission"

        # Snapshot/patch activity must never prove native argument-table use.
        rejected_argument, code = evaluate(path, "argument-tables")
        assert code == 3 and rejected_argument["state"] == "rejected-no-admission"

        payload["admission"]["argumentBindingRuntime"] = {
            "executionAuthority": NATIVE_ARGUMENT_TABLE_AUTHORITY,
            "nativeMainRendererEngaged": True,
            "snapshotDiagnosticsEnabled": False,
        }
        path.write_text(json.dumps(payload), encoding="utf-8")
        admitted, code = evaluate(path, "argument-tables")
        assert code == 0 and admitted["state"] == "admitted"
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
