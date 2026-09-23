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
FRAMEBUFFER_SCENARIO = "p1-stationary-framebuffer-equivalence-v1"
FRAMEBUFFER_SAMPLE_COUNT = 8


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


def framebuffer_payloads(evidence_root: Path) -> tuple[dict[int, bytes], dict[str, Any]]:
    runtime = load(evidence_root / "runtime-evidence.json")
    world = runtime.get("world")
    if not isinstance(world, dict):
        raise ValueError(f"runtime evidence has no world identity: {evidence_root}")
    if world.get("framebufferEquivalenceScenario") != FRAMEBUFFER_SCENARIO:
        raise ValueError(
            "runtime evidence did not use the stationary P1 framebuffer scenario "
            f"{FRAMEBUFFER_SCENARIO!r}: {world.get('framebufferEquivalenceScenario')!r}"
        )
    if runtime.get("minecraft") != "26.3" or world.get("minecraftVersion") != "26.3":
        raise ValueError("P1 framebuffer evidence must come from Minecraft 26.3")
    if runtime.get("worldLoaded") is not True or runtime.get("chunksRendered") is not True:
        raise ValueError("P1 framebuffer evidence did not load and render a world")
    if (
        world.get("simulationFrozenDuringFramebufferCapture") is not True
        or world.get("serverSimulationFrozenDuringFramebufferCapture") is not True
        or world.get("clientSimulationFrozenDuringFramebufferCapture") is not True
    ):
        raise ValueError("P1 stationary framebuffer scene did not prove both simulations frozen")

    waypoints = world.get("waypoints")
    if not isinstance(waypoints, list) or len(waypoints) != 1:
        raise ValueError(f"stationary framebuffer scenario needs exactly one camera pose: {waypoints!r}")
    scene = {
        "minecraft": runtime.get("minecraft"),
        "sodiumLoaded": runtime.get("sodiumLoaded"),
        "irisLoaded": runtime.get("irisLoaded"),
        "generator": world.get("generator"),
        "seed": world.get("seed"),
        "preset": world.get("preset"),
        "generateStructures": world.get("generateStructures"),
        "minecraftVersion": world.get("minecraftVersion"),
        "framebufferEquivalenceScenario": world.get("framebufferEquivalenceScenario"),
        "simulationFrozenDuringFramebufferCapture": world.get("simulationFrozenDuringFramebufferCapture"),
        "serverSimulationFrozenDuringFramebufferCapture": world.get("serverSimulationFrozenDuringFramebufferCapture"),
        "clientSimulationFrozenDuringFramebufferCapture": world.get("clientSimulationFrozenDuringFramebufferCapture"),
        "waypoints": waypoints,
        "replaySourceSnapshotSha256": world.get("replaySourceSnapshotSha256"),
    }

    contract_root = evidence_root / "metal-framebuffer" / "render-contract"
    results = load(contract_root / "results.json")
    captures = results.get("captures")
    if results.get("status") != "passed" or not isinstance(captures, list):
        raise ValueError(f"render-contract capture did not pass: {contract_root / 'results.json'}")
    if results.get("requestedCaptures") != FRAMEBUFFER_SAMPLE_COUNT or len(captures) != FRAMEBUFFER_SAMPLE_COUNT:
        raise ValueError(
            f"expected {FRAMEBUFFER_SAMPLE_COUNT} framebuffer captures, got "
            f"requested={results.get('requestedCaptures')} completed={len(captures)}"
        )
    payloads: dict[int, bytes] = {}
    for capture in captures:
        if not isinstance(capture, dict):
            raise ValueError("render-contract capture entry is not an object")
        frame_id = capture.get("frameId")
        if not isinstance(frame_id, int) or frame_id in payloads:
            raise ValueError(f"invalid or duplicate framebuffer frame id: {frame_id!r}")
        if capture.get("status") != "passed" or capture.get("semanticPassId") != "metallum/present":
            raise ValueError(f"frame {frame_id} is not a passed final presentation capture")
        if capture.get("capturePoint") != "FINAL_DRAWABLE":
            raise ValueError(f"frame {frame_id} is not captured at FINAL_DRAWABLE")
        resources = capture.get("resources")
        if not isinstance(resources, list) or len(resources) != 1 or not isinstance(resources[0], dict):
            raise ValueError(f"frame {frame_id} does not contain one final drawable resource")
        resource = resources[0]
        capture_format = resource.get("captureFormat")
        if (
            resource.get("semanticName") != "final-drawable"
            or resource.get("width") != 854
            or resource.get("height") != 480
            or not isinstance(capture_format, dict)
            or capture_format.get("name") != "RGBA8_UNORM"
        ):
            raise ValueError(f"frame {frame_id} has an unexpected final drawable format or extent")
        relative = resource.get("actual")
        if not isinstance(relative, str) or not relative:
            raise ValueError(f"frame {frame_id} has no raw framebuffer payload")
        raw_path = (contract_root / relative).resolve()
        try:
            raw_path.relative_to(contract_root.resolve())
        except ValueError as exc:
            raise ValueError(f"frame {frame_id} payload escapes its evidence directory") from exc
        raw = raw_path.read_bytes()
        if len(raw) != 854 * 480 * 4:
            raise ValueError(f"frame {frame_id} raw payload has unexpected size {len(raw)}")
        payloads[frame_id] = raw

    if set(payloads) != set(range(1, FRAMEBUFFER_SAMPLE_COUNT + 1)):
        raise ValueError(f"stationary P1 captures must have frame ids 1..{FRAMEBUFFER_SAMPLE_COUNT}")
    return payloads, scene


def compare_framebuffers(baseline_root: Path, candidate_root: Path) -> dict[str, Any]:
    baseline, baseline_scene = framebuffer_payloads(baseline_root)
    candidate, candidate_scene = framebuffer_payloads(candidate_root)
    if baseline_scene != candidate_scene:
        return {
            "state": "rejected-scene-mismatch",
            "baselineScene": baseline_scene,
            "candidateScene": candidate_scene,
            "frames": [],
        }

    frames = []
    baseline_stable = len(set(baseline.values())) == 1
    candidate_stable = len(set(candidate.values())) == 1
    for frame_id in range(1, FRAMEBUFFER_SAMPLE_COUNT + 1):
        before = baseline[frame_id]
        after = candidate[frame_id]
        if before == after:
            differing_bytes = 0
            first_byte = None
            max_delta = 0
            changed_pixels = 0
        else:
            differing_bytes = 0
            first_byte = None
            max_delta = 0
            changed_pixels = 0
            for index, (left, right) in enumerate(zip(before, after)):
                if left != right:
                    differing_bytes += 1
                    if first_byte is None:
                        first_byte = index
                    max_delta = max(max_delta, abs(left - right))
            for index in range(0, len(before), 4):
                if any(before[index + channel] != after[index + channel] for channel in range(4)):
                    changed_pixels += 1
        first_difference = None
        if first_byte is not None:
            byte_per_pixel = 4
            pixel = first_byte // byte_per_pixel
            first_difference = {
                "x": pixel % 854,
                "y": pixel // 854,
                "channel": ("r", "g", "b", "a")[first_byte % byte_per_pixel],
                "baseline": before[first_byte],
                "candidate": after[first_byte],
            }
        frames.append({
            "frameId": frame_id,
            "status": "exact-equivalent" if differing_bytes == 0 else "pixel-mismatch",
            "differingBytes": differing_bytes,
            "changedPixels": changed_pixels,
            "maximumChannelDelta": max_delta,
            "firstDifference": first_difference,
        })
    exact = all(frame["status"] == "exact-equivalent" for frame in frames)
    state = "pass" if exact and baseline_stable and candidate_stable else (
        "rejected-pixel-difference" if not exact else "rejected-nonstationary-framebuffer"
    )
    return {
        "state": state,
        "scenario": FRAMEBUFFER_SCENARIO,
        "extent": [854, 480],
        "format": "RGBA8_UNORM",
        "sampleCount": FRAMEBUFFER_SAMPLE_COUNT,
        "comparison": "byte-identical raw FINAL_DRAWABLE payloads matched by frame id",
        "baselineStableAcrossSamples": baseline_stable,
        "candidateStableAcrossSamples": candidate_stable,
        "baselineScene": baseline_scene,
        "candidateScene": candidate_scene,
        "frames": frames,
        "firstDivergentFrame": next((frame["frameId"] for frame in frames if frame["status"] != "exact-equivalent"), None),
    }


def evaluate(
    baseline_path: Path,
    candidate_path: Path,
    baseline_evidence_root: Path,
    candidate_evidence_root: Path,
) -> tuple[dict[str, Any], int]:
    try:
        baseline = load(baseline_path)
        candidate = load(candidate_path)
        framebuffer = compare_framebuffers(baseline_evidence_root, candidate_evidence_root)
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
        "framebuffer_equivalence": framebuffer.get("state") == "pass",
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
            "baseline and candidate prove exact production identity, Metal 4 + residency, isolated main-renderer activation, and byte-identical final framebuffers"
            if passed
            else "one or more P1 physical E2E pair invariants failed"
        ),
        "checks": checks,
        "framebufferEquivalence": framebuffer,
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
        baseline_root = root / "baseline-evidence"
        candidate_root = root / "candidate-evidence"
        for evidence_root in (baseline_root, candidate_root):
            contract_root = evidence_root / "metal-framebuffer" / "render-contract"
            contract_root.mkdir(parents=True)
            (evidence_root / "runtime-evidence.json").write_text(json.dumps({
                "minecraft": "26.3",
                "worldLoaded": True,
                "chunksRendered": True,
                "sodiumLoaded": True,
                "irisLoaded": True,
                "world": {
                    "generator": "NoiseBasedChunkGenerator",
                    "seed": 1,
                    "preset": "minecraft:normal",
                    "generateStructures": True,
                    "minecraftVersion": "26.3",
                    "framebufferEquivalenceScenario": FRAMEBUFFER_SCENARIO,
                    "simulationFrozenDuringFramebufferCapture": True,
                    "serverSimulationFrozenDuringFramebufferCapture": True,
                    "clientSimulationFrozenDuringFramebufferCapture": True,
                    "waypoints": [{"frameId": 1, "x": 832, "y": 128, "z": 496, "yaw": -65, "pitch": 25}],
                },
            }), encoding="utf-8")
            captures = []
            for frame_id in range(1, FRAMEBUFFER_SAMPLE_COUNT + 1):
                relative = f"frames/frame-{frame_id:06d}/actual.bin"
                raw_path = contract_root / relative
                raw_path.parent.mkdir(parents=True, exist_ok=True)
                raw_path.write_bytes(bytes((7, 1, 2, 255)) * (854 * 480))
                captures.append({
                    "frameId": frame_id,
                    "status": "passed",
                    "semanticPassId": "metallum/present",
                    "capturePoint": "FINAL_DRAWABLE",
                    "resources": [{
                        "semanticName": "final-drawable",
                        "width": 854,
                        "height": 480,
                        "captureFormat": {"name": "RGBA8_UNORM"},
                        "actual": relative,
                    }],
                })
            (contract_root / "results.json").write_text(json.dumps({
                "status": "passed",
                "requestedCaptures": FRAMEBUFFER_SAMPLE_COUNT,
                "captures": captures,
            }), encoding="utf-8")
        baseline_path.write_text(json.dumps(make_evidence("baseline", ids)), encoding="utf-8")
        candidate_path.write_text(json.dumps(make_evidence("candidate", ids)), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 0 and result["state"] == "pass" and result["checks"]["framebuffer_equivalence"], result

        wrong = make_evidence("candidate", ("4" * 40, jar, dylib))
        candidate_path.write_text(json.dumps(wrong), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 3 and result["checks"]["identical_source_and_binaries"] is False, result

        missing = make_evidence("candidate", ids)
        missing["identity"]["productionJarSha256"] = UNRECORDED
        candidate_path.write_text(json.dumps(missing), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 3 and result["checks"]["candidate_identity_recorded"] is False, result

        broken = make_evidence("candidate", ids)
        broken["metrics"]["metal4.argumentTableAllocationsDuringEncoding"] = 1
        candidate_path.write_text(json.dumps(broken), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 3 and result["checks"]["candidate_no_argument_table_allocations"] is False, result

        payload = candidate_root / "metal-framebuffer" / "render-contract" / "frames/frame-000004/actual.bin"
        changed = bytearray(payload.read_bytes())
        changed[0] ^= 1
        payload.write_bytes(changed)
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 3 and result["framebufferEquivalence"]["firstDivergentFrame"] == 4, result
        assert result["framebufferEquivalence"]["frames"][3]["firstDifference"]["x"] == 0, result

    print("check_metal4_main_e2e_pair self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", nargs="?", type=Path)
    parser.add_argument("candidate", nargs="?", type=Path)
    parser.add_argument("--baseline-evidence-root", type=Path)
    parser.add_argument("--candidate-evidence-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.baseline is None or args.candidate is None:
        parser.error("baseline and candidate evidence paths are required unless --self-test is used")
    if args.baseline_evidence_root is None or args.candidate_evidence_root is None:
        parser.error("--baseline-evidence-root and --candidate-evidence-root are required")
    result, code = evaluate(args.baseline, args.candidate, args.baseline_evidence_root, args.candidate_evidence_root)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
