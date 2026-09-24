#!/usr/bin/env python3
"""Fail-closed cross-lane validator for P1 physical Minecraft E2E evidence."""
from __future__ import annotations

import argparse
import json
import math
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
UNRECORDED = "unrecorded"
FRAMEBUFFER_SCENARIO = "p1-stationary-framebuffer-equivalence-v2"
FRAMEBUFFER_GAME_TIME = 6000
FRAMEBUFFER_WIDTH = 854
FRAMEBUFFER_HEIGHT = 480
FRAMEBUFFER_SAMPLE_COUNT = 8
FRAMEBUFFER_BYTES = FRAMEBUFFER_WIDTH * FRAMEBUFFER_HEIGHT * 4


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
    if (
        runtime.get("minecraft") != "26.3"
        or world.get("minecraftVersion") != "26.3"
        or world.get("framebufferEquivalenceScenario") != FRAMEBUFFER_SCENARIO
        or runtime.get("worldLoaded") is not True
        or runtime.get("chunksRendered") is not True
    ):
        raise ValueError(f"runtime evidence does not prove the fixed Minecraft 26.3 P1 scene: {evidence_root}")
    if (
        world.get("simulationFrozenDuringFramebufferCapture") is not True
        or world.get("serverSimulationFrozenDuringFramebufferCapture") is not True
        or world.get("clientSimulationFrozenDuringFramebufferCapture") is not True
    ):
        raise ValueError(f"P1 world and client simulation were not frozen: {evidence_root}")
    expected_rules = {
        "advance_time": False,
        "advance_weather": False,
        "random_tick_speed": 0,
        "spawn_mobs": False,
    }
    if world.get("p1GameRules") != expected_rules:
        raise ValueError(f"P1 gamerules differ from the fixed scene contract: {world.get('p1GameRules')!r}")
    if (
        world.get("p1FixedGameTime") != FRAMEBUFFER_GAME_TIME
        or world.get("serverGameTimeAtCapture") != FRAMEBUFFER_GAME_TIME
        or world.get("clientGameTimeAtCapture") != FRAMEBUFFER_GAME_TIME
    ):
        raise ValueError("P1 server/client game time is not pinned to the fixed framebuffer scene value")

    waypoints = world.get("waypoints")
    if not isinstance(waypoints, list) or len(waypoints) != 1:
        raise ValueError(f"P1 scene must have exactly one fixed camera waypoint: {waypoints!r}")
    waypoint = waypoints[0]
    expected_waypoint = {
        "frameId": 1,
        "x": 832,
        "y": 128,
        "z": 496,
        "yaw": -65.0,
        "pitch": 25.0,
    }
    if waypoint != expected_waypoint:
        raise ValueError(f"P1 camera waypoint differs from the fixed scene contract: {waypoint!r}")

    camera_samples = world.get("cameraSamples")
    if not isinstance(camera_samples, list) or len(camera_samples) != FRAMEBUFFER_SAMPLE_COUNT:
        raise ValueError(f"P1 camera evidence needs {FRAMEBUFFER_SAMPLE_COUNT} per-frame poses")
    pose_fields = ("x", "y", "z", "yaw", "pitch")
    normalized_camera_samples = []
    for frame_id, sample in enumerate(camera_samples, start=1):
        if not isinstance(sample, dict) or sample.get("frameId") != frame_id:
            raise ValueError(f"P1 camera sample sequence is incomplete at frame {frame_id}")
        if sample.get("mouseGrabbed") is not False:
            raise ValueError(f"P1 camera mouse input remained grabbed at frame {frame_id}")
        pose = {field: sample.get(field) for field in pose_fields}
        if not all(isinstance(value, (int, float)) and math.isfinite(value) for value in pose.values()):
            raise ValueError(f"P1 camera sample has invalid pose at frame {frame_id}: {sample!r}")
        if abs(pose["x"] - 832) >= 1 or abs(pose["y"] - 128) >= 1 or abs(pose["z"] - 496) >= 1:
            raise ValueError(f"P1 camera position drifted at frame {frame_id}: {pose!r}")
        if pose["yaw"] != -65.0 or pose["pitch"] != 25.0:
            raise ValueError(f"P1 camera orientation drifted at frame {frame_id}: {pose!r}")
        if sample.get("gameTime") != FRAMEBUFFER_GAME_TIME:
            raise ValueError(f"P1 game time drifted at frame {frame_id}: {sample!r}")
        normalized_camera_samples.append({
            "frameId": frame_id,
            **pose,
            "gameTime": FRAMEBUFFER_GAME_TIME,
            "mouseGrabbed": False,
        })

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
        "p1FixedGameTime": world.get("p1FixedGameTime"),
        "serverGameTimeAtCapture": world.get("serverGameTimeAtCapture"),
        "clientGameTimeAtCapture": world.get("clientGameTimeAtCapture"),
        "p1GameRules": world.get("p1GameRules"),
        "waypoints": waypoints,
        "cameraSamples": normalized_camera_samples,
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
            raise ValueError("framebuffer capture entry is not an object")
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
            or resource.get("width") != FRAMEBUFFER_WIDTH
            or resource.get("height") != FRAMEBUFFER_HEIGHT
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
        if len(raw) != FRAMEBUFFER_BYTES:
            raise ValueError(f"frame {frame_id} raw payload has unexpected size {len(raw)}")
        payloads[frame_id] = raw

    if set(payloads) != set(range(1, FRAMEBUFFER_SAMPLE_COUNT + 1)):
        raise ValueError(f"P1 framebuffer captures must have frame ids 1..{FRAMEBUFFER_SAMPLE_COUNT}")
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

    def visible_rgb(payload: bytes) -> bytes:
        rgb = bytearray(len(payload) // 4 * 3)
        rgb[0::3] = payload[0::4]
        rgb[1::3] = payload[1::4]
        rgb[2::3] = payload[2::4]
        return bytes(rgb)

    baseline_rgb = {frame_id: visible_rgb(payload) for frame_id, payload in baseline.items()}
    candidate_rgb = {frame_id: visible_rgb(payload) for frame_id, payload in candidate.items()}
    baseline_stable = len(set(baseline_rgb.values())) == 1
    candidate_stable = len(set(candidate_rgb.values())) == 1
    frames = []
    for frame_id in range(1, FRAMEBUFFER_SAMPLE_COUNT + 1):
        before = baseline[frame_id]
        after = candidate[frame_id]
        differing_bytes = 0
        differing_rgb_bytes = 0
        differing_alpha_bytes = 0
        changed_pixels = 0
        changed_rgb_pixels = 0
        changed_alpha_pixels = 0
        first_byte = None
        first_rgb_byte = None
        maximum_delta = 0
        maximum_rgb_delta = 0
        maximum_alpha_delta = 0
        for index, (left, right) in enumerate(zip(before, after)):
            if left != right:
                differing_bytes += 1
                if first_byte is None:
                    first_byte = index
                maximum_delta = max(maximum_delta, abs(left - right))
                if index % 4 == 3:
                    differing_alpha_bytes += 1
                    maximum_alpha_delta = max(maximum_alpha_delta, abs(left - right))
                else:
                    differing_rgb_bytes += 1
                    maximum_rgb_delta = max(maximum_rgb_delta, abs(left - right))
                    if first_rgb_byte is None:
                        first_rgb_byte = index
        for index in range(0, len(before), 4):
            rgb_changed = before[index:index + 3] != after[index:index + 3]
            alpha_changed = before[index + 3] != after[index + 3]
            if rgb_changed or alpha_changed:
                changed_pixels += 1
            if rgb_changed:
                changed_rgb_pixels += 1
            if alpha_changed:
                changed_alpha_pixels += 1
        first_difference = None
        if first_byte is not None:
            pixel = first_byte // 4
            first_difference = {
                "x": pixel % FRAMEBUFFER_WIDTH,
                "y": pixel // FRAMEBUFFER_WIDTH,
                "channel": ("r", "g", "b", "a")[first_byte % 4],
                "baseline": before[first_byte],
                "candidate": after[first_byte],
            }
        first_rgb_difference = None
        if first_rgb_byte is not None:
            pixel = first_rgb_byte // 4
            first_rgb_difference = {
                "x": pixel % FRAMEBUFFER_WIDTH,
                "y": pixel // FRAMEBUFFER_WIDTH,
                "channel": ("r", "g", "b")[first_rgb_byte % 4],
                "baseline": before[first_rgb_byte],
                "candidate": after[first_rgb_byte],
            }
        frames.append({
            "frameId": frame_id,
            "status": "visible-rgb-equivalent" if differing_rgb_bytes == 0 else "pixel-mismatch",
            "differingBytes": differing_bytes,
            "differingRgbBytes": differing_rgb_bytes,
            "differingAlphaBytes": differing_alpha_bytes,
            "changedPixels": changed_pixels,
            "changedRgbPixels": changed_rgb_pixels,
            "changedAlphaPixels": changed_alpha_pixels,
            "maximumChannelDelta": maximum_delta,
            "maximumRgbChannelDelta": maximum_rgb_delta,
            "maximumAlphaChannelDelta": maximum_alpha_delta,
            "firstDifference": first_difference,
            "firstRgbDifference": first_rgb_difference,
        })
    exact = all(frame["status"] == "visible-rgb-equivalent" for frame in frames)
    stable = baseline_stable and candidate_stable
    state = "pass" if exact and stable else (
        "rejected-pixel-difference" if not exact else "rejected-nonstationary-framebuffer"
    )
    return {
        "state": state,
        "scenario": FRAMEBUFFER_SCENARIO,
        "extent": [FRAMEBUFFER_WIDTH, FRAMEBUFFER_HEIGHT],
        "format": "RGBA8_UNORM",
        "sampleCount": FRAMEBUFFER_SAMPLE_COUNT,
        "comparison": "byte-identical FINAL_DRAWABLE RGB channels after opaque-surface projection; raw alpha deltas are diagnostic",
        "opaqueSurfaceProjection": "discard alpha and compare every RGB byte exactly; CAMetalLayer is configured isOpaque=true",
        "baselineStableAcrossSamples": baseline_stable,
        "candidateStableAcrossSamples": candidate_stable,
        "baselineAlphaStableAcrossSamples": len(set(payload[3::4] for payload in baseline.values())) == 1,
        "candidateAlphaStableAcrossSamples": len(set(payload[3::4] for payload in candidate.values())) == 1,
        "baselineScene": baseline_scene,
        "candidateScene": candidate_scene,
        "frames": frames,
        "firstDivergentFrame": next((frame["frameId"] for frame in frames if frame["status"] != "visible-rgb-equivalent"), None),
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
            "baseline and candidate prove exact production identity, framebuffer equivalence, Metal 4 + residency, and isolated main-renderer activation"
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
        make_framebuffer_fixture(baseline_root)
        make_framebuffer_fixture(candidate_root)
        baseline_path.write_text(json.dumps(make_evidence("baseline", ids)), encoding="utf-8")
        candidate_path.write_text(json.dumps(make_evidence("candidate", ids)), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 0 and result["state"] == "pass", result

        changed_alpha = candidate_root / "metal-framebuffer/render-contract/frames/frame-000001/actual.bin"
        payload = bytearray(changed_alpha.read_bytes())
        payload[3] ^= 0x7F
        changed_alpha.write_bytes(payload)
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 0 and result["state"] == "pass", result
        assert result["framebufferEquivalence"]["frames"][0]["changedRgbPixels"] == 0, result
        assert result["framebufferEquivalence"]["frames"][0]["changedAlphaPixels"] == 1, result
        make_framebuffer_fixture(candidate_root)

        changed_payload = candidate_root / "metal-framebuffer/render-contract/frames/frame-000001/actual.bin"
        payload = bytearray(changed_payload.read_bytes())
        payload[0] ^= 1
        changed_payload.write_bytes(payload)
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 3 and result["checks"]["framebuffer_equivalence"] is False, result
        make_framebuffer_fixture(candidate_root)

        runtime_path = candidate_root / "runtime-evidence.json"
        runtime = load(runtime_path)
        runtime["world"]["clientGameTimeAtCapture"] = FRAMEBUFFER_GAME_TIME + 1
        runtime_path.write_text(json.dumps(runtime), encoding="utf-8")
        result, code = evaluate(baseline_path, candidate_path, baseline_root, candidate_root)
        assert code == 2 and "game time is not pinned" in result["reason"], result
        make_framebuffer_fixture(candidate_root)

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

    print("check_metal4_main_e2e_pair self-test: PASS")


def make_framebuffer_fixture(evidence_root: Path) -> None:
    contract_root = evidence_root / "metal-framebuffer" / "render-contract"
    contract_root.mkdir(parents=True, exist_ok=True)
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
            "p1FixedGameTime": FRAMEBUFFER_GAME_TIME,
            "serverGameTimeAtCapture": FRAMEBUFFER_GAME_TIME,
            "clientGameTimeAtCapture": FRAMEBUFFER_GAME_TIME,
            "p1GameRules": {
                "advance_time": False,
                "advance_weather": False,
                "random_tick_speed": 0,
                "spawn_mobs": False,
            },
            "waypoints": [{"frameId": 1, "x": 832, "y": 128, "z": 496, "yaw": -65.0, "pitch": 25.0}],
            "cameraSamples": [
                {"frameId": frame_id, "x": 832.0, "y": 128.0, "z": 496.0,
                 "yaw": -65.0, "pitch": 25.0, "gameTime": FRAMEBUFFER_GAME_TIME,
                 "mouseGrabbed": False}
                for frame_id in range(1, FRAMEBUFFER_SAMPLE_COUNT + 1)
            ],
        },
    }), encoding="utf-8")
    raw = bytes((7, 1, 2, 255)) * (FRAMEBUFFER_WIDTH * FRAMEBUFFER_HEIGHT)
    captures = []
    for frame_id in range(1, FRAMEBUFFER_SAMPLE_COUNT + 1):
        relative = f"frames/frame-{frame_id:06d}/actual.bin"
        raw_path = contract_root / relative
        raw_path.parent.mkdir(parents=True, exist_ok=True)
        raw_path.write_bytes(raw)
        captures.append({
            "frameId": frame_id,
            "status": "passed",
            "semanticPassId": "metallum/present",
            "capturePoint": "FINAL_DRAWABLE",
            "resources": [{
                "semanticName": "final-drawable",
                "width": FRAMEBUFFER_WIDTH,
                "height": FRAMEBUFFER_HEIGHT,
                "captureFormat": {"name": "RGBA8_UNORM"},
                "actual": relative,
            }],
        })
    (contract_root / "results.json").write_text(json.dumps({
        "status": "passed",
        "requestedCaptures": FRAMEBUFFER_SAMPLE_COUNT,
        "captures": captures,
    }), encoding="utf-8")


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
        parser.error("both framebuffer evidence roots are required for the physical P1 comparison")
    result, code = evaluate(
        args.baseline,
        args.candidate,
        args.baseline_evidence_root,
        args.candidate_evidence_root,
    )
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
