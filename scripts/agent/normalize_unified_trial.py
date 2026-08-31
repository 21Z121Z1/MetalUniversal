#!/usr/bin/env python3
"""Normalize one authoritative native fullscreen performance report.

Unlike the legacy discovery path, this script never recursively aggregates
metric-looking keys from unrelated JSON. Duplicate copies are accepted only
when their bytes are identical.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 3
PERFORMANCE_SAMPLE_SCHEMA_VERSION = 1
# GPU completion callbacks can legally lag the render thread by a frame or
# two when the client is asked to stop.  Keep the performance source strict:
# only a common frame-ID window is usable, at least 95% of the frame interval
# IDs must survive, and any missing IDs in the interior of that window remain
# an error.  This admits an asynchronous prefix/suffix without allowing a
# report to cherry-pick arbitrary frames.
MIN_FRAME_ALIGNMENT_COVERAGE = 0.95


def finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def metric(
    value: Any,
    unit: str,
    direction: str,
    sample_count: int,
    reason: str | None = None,
    statistic: str = "median",
) -> dict[str, Any]:
    number = finite_number(value)
    if number is None:
        return {
            "available": False,
            "sample_count": 0,
            "unit": unit,
            "direction": direction,
            "statistic": statistic,
            "reason": reason or "authoritative source report did not emit this metric",
        }
    return {
        "available": True,
        "median": number,
        "sample_count": max(1, int(sample_count)),
        "minimum": number,
        "maximum": number,
        "unit": unit,
        "direction": direction,
        "statistic": statistic,
    }


def nonnegative_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


def summarize(values: list[float]) -> dict[str, float | int | None]:
    return {
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "minimum": min(values) if values else None,
        "maximum": max(values) if values else None,
        "sample_count": len(values),
    }


def parse_timing_samples(
    value: Any,
    label: str,
    allow_duplicate_frame_ids: bool = False,
) -> tuple[list[tuple[int, float]], list[str]]:
    if not isinstance(value, list):
        return [], [f"{label} must be an array of frameId/milliseconds samples"]
    parsed: list[tuple[int, float]] = []
    errors: list[str] = []
    seen: set[int] = set()
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            errors.append(f"{label}[{index}] is not an object")
            continue
        frame_id = nonnegative_int(item.get("frameId"))
        milliseconds = finite_number(item.get("milliseconds"))
        if frame_id is None:
            errors.append(f"{label}[{index}] has a missing or invalid non-negative frameId")
            continue
        if milliseconds is None or milliseconds <= 0.0:
            errors.append(f"{label}[{index}] has a missing or non-positive finite milliseconds value")
            continue
        if not allow_duplicate_frame_ids and frame_id in seen:
            errors.append(f"{label} contains duplicate frameId={frame_id}")
            continue
        seen.add(frame_id)
        parsed.append((frame_id, milliseconds))
    parsed.sort(key=lambda sample: sample[0])
    return parsed, errors


def validate_performance_sample_window(
    report: dict[str, Any], measured_frames: int
) -> tuple[dict[str, Any], list[str]]:
    window = report.get("performanceSampleWindow")
    if not isinstance(window, dict):
        return {}, ["performanceSampleWindow is missing; frame-aligned samples are required"]
    version = window.get("schemaVersion")
    errors: list[str] = []
    if version != PERFORMANCE_SAMPLE_SCHEMA_VERSION:
        errors.append(
            f"performanceSampleWindow.schemaVersion={version!r} is unsupported; "
            f"expected {PERFORMANCE_SAMPLE_SCHEMA_VERSION}"
        )

    frame_samples, frame_errors = parse_timing_samples(window.get("frameIntervals"), "frameIntervals")
    cpu_samples, cpu_errors = parse_timing_samples(window.get("cpuRenderEncode"), "cpuRenderEncode")
    gpu_frame_samples, gpu_frame_errors = parse_timing_samples(window.get("gpuFrameTimes"), "gpuFrameTimes")
    command_samples, command_errors = parse_timing_samples(
        window.get("gpuCommandBuffers"), "gpuCommandBuffers", allow_duplicate_frame_ids=True
    )
    errors.extend(frame_errors)
    errors.extend(cpu_errors)
    errors.extend(gpu_frame_errors)
    errors.extend(command_errors)

    frame_ids = [frame_id for frame_id, _ in frame_samples]
    frame_id_set = set(frame_ids)
    if measured_frames <= 0:
        errors.append("measuredFrameIntervals is missing or zero")
    elif len(frame_samples) != measured_frames:
        errors.append(
            f"frameIntervals sample count mismatch: samples={len(frame_samples)}, "
            f"measuredFrameIntervals={measured_frames}"
        )
    if window.get("frameCount") != len(frame_samples):
        errors.append(
            f"performanceSampleWindow.frameCount={window.get('frameCount')!r} "
            f"does not match frameIntervals count={len(frame_samples)}"
        )
    if frame_ids:
        if window.get("frameIdStart") != frame_ids[0] or window.get("frameIdEnd") != frame_ids[-1]:
            errors.append(
                "performanceSampleWindow frameIdStart/frameIdEnd do not match the sorted frameIntervals IDs"
            )

    cpu_ids = {frame_id for frame_id, _ in cpu_samples}
    gpu_ids = {frame_id for frame_id, _ in gpu_frame_samples}
    command_ids = {frame_id for frame_id, _ in command_samples}
    common_ids = frame_id_set & cpu_ids & gpu_ids & command_ids
    aligned_frame_ids = [frame_id for frame_id in frame_ids if frame_id in common_ids]
    dropped_frame_ids = [frame_id for frame_id in frame_ids if frame_id not in common_ids]
    alignment_coverage = (
        len(aligned_frame_ids) / len(frame_ids) if frame_ids else 0.0
    )
    if frame_ids and alignment_coverage < MIN_FRAME_ALIGNMENT_COVERAGE:
        errors.append(
            "frame-ID alignment coverage is below the minimum: "
            f"aligned={len(aligned_frame_ids)}, raw={len(frame_ids)}, "
            f"coverage={alignment_coverage:.4f}, minimum={MIN_FRAME_ALIGNMENT_COVERAGE:.4f}; "
            f"cpuRenderEncode={len(cpu_ids)}, gpuFrameTimes={len(gpu_ids)}, "
            f"gpuCommandBuffers={len(command_ids)}"
        )
    if aligned_frame_ids:
        first_aligned = frame_ids.index(aligned_frame_ids[0])
        last_aligned = frame_ids.index(aligned_frame_ids[-1])
        interior_drops = [
            frame_id for frame_id in frame_ids[first_aligned:last_aligned + 1]
            if frame_id not in common_ids
        ]
        if interior_drops:
            errors.append(
                "frame-ID alignment has interior gaps; only an asynchronous prefix/suffix is allowed: "
                f"dropped={interior_drops[:16]}"
            )

    # Restrict every source to the same common frame-ID window.  Command
    # buffers may contain older frames and may contain multiple submissions
    # for one frame; both are valid only when their frame ID is in this set.
    aligned_set = set(aligned_frame_ids)
    aligned_frame_samples = [sample for sample in frame_samples if sample[0] in aligned_set]
    aligned_cpu_samples = [sample for sample in cpu_samples if sample[0] in aligned_set]
    aligned_gpu_samples = [sample for sample in gpu_frame_samples if sample[0] in aligned_set]
    aligned_command_samples = [sample for sample in command_samples if sample[0] in aligned_set]
    if len(aligned_frame_samples) != len(aligned_cpu_samples):
        errors.append(
            "aligned cpuRenderEncode contains more or fewer than one sample per common frame ID: "
            f"cpu={len(aligned_cpu_samples)}, aligned_frames={len(aligned_frame_samples)}"
        )
    if len(aligned_frame_samples) != len(aligned_gpu_samples):
        errors.append(
            "aligned gpuFrameTimes contains more or fewer than one sample per common frame ID: "
            f"gpu={len(aligned_gpu_samples)}, aligned_frames={len(aligned_frame_samples)}"
        )
    command_count = nonnegative_int(window.get("gpuCommandBufferCount"))
    gpu_frame_count = nonnegative_int(window.get("gpuFrameCount"))
    if command_count is not None and command_count < len(aligned_command_samples):
        errors.append(
            f"gpuCommandBufferCount={command_count} is smaller than aligned command samples={len(aligned_command_samples)}"
        )
    if gpu_frame_count is not None and gpu_frame_count < len(aligned_gpu_samples):
        errors.append(
            f"gpuFrameCount={gpu_frame_count} is smaller than aligned gpuFrameTimes samples={len(aligned_gpu_samples)}"
        )

    frame_values = {frame_id: milliseconds for frame_id, milliseconds in aligned_frame_samples}
    cpu_values = {frame_id: milliseconds for frame_id, milliseconds in aligned_cpu_samples}
    gpu_values = {frame_id: milliseconds for frame_id, milliseconds in aligned_gpu_samples}
    return {
        "available": not errors and bool(aligned_frame_ids),
        "window": window,
        "frame_ids": aligned_frame_ids,
        "raw_frame_ids": frame_ids,
        "dropped_frame_ids": dropped_frame_ids,
        "alignment_coverage": alignment_coverage,
        "aligned_command_samples": aligned_command_samples,
        "frame_values": frame_values,
        "cpu_values": cpu_values,
        "gpu_values": gpu_values,
        "gpu_command_buffer_count": len(aligned_command_samples),
        "gpu_frame_count": len(aligned_gpu_samples),
        "errors": errors,
    }, errors


def authoritative_report(trial_dir: Path) -> tuple[Path | None, list[Path], str | None]:
    paths = sorted(trial_dir.rglob("native-fullscreen-baseline.json"))
    if not paths:
        return None, [], "no native-fullscreen-baseline.json was produced"
    by_hash: dict[str, list[Path]] = {}
    for path in paths:
        by_hash.setdefault(digest(path), []).append(path)
    if len(by_hash) != 1:
        detail = {key: [str(p.relative_to(trial_dir)) for p in values] for key, values in by_hash.items()}
        return None, paths, f"multiple non-identical source reports were found: {detail}"
    return paths[0], paths, None


def normalize(trial_dir: Path) -> dict[str, Any]:
    status_path = trial_dir / "exit-status.txt"
    try:
        exit_status = int(status_path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        exit_status = None

    source, copies, source_error = authoritative_report(trial_dir)
    parse_error = None
    report: dict[str, Any] = {}
    if source is not None:
        try:
            loaded = json.loads(source.read_text(encoding="utf-8"))
            if not isinstance(loaded, dict):
                raise TypeError("source report root must be an object")
            report = loaded
        except (OSError, json.JSONDecodeError, TypeError) as exc:
            parse_error = str(exc)

    measured_frames = nonnegative_int(report.get("measuredFrameIntervals")) if report else 0
    measured_frames = measured_frames or 0
    sample_window, sample_window_errors = validate_performance_sample_window(report, measured_frames)
    frame_values = sample_window.get("frame_values", {})
    cpu_values = sample_window.get("cpu_values", {})
    gpu_values = sample_window.get("gpu_values", {})
    frame_summary = summarize(list(frame_values.values())) if sample_window.get("available") else summarize([])
    cpu_summary = summarize(list(cpu_values.values())) if sample_window.get("available") else summarize([])
    gpu_summary = summarize(list(gpu_values.values())) if sample_window.get("available") else summarize([])
    cpu = report.get("cpuRenderEncodeFrameMilliseconds", {}) if isinstance(report.get("cpuRenderEncodeFrameMilliseconds"), dict) else {}
    cpu_samples = nonnegative_int(cpu.get("samples")) or 0
    aligned_frame_count = len(frame_values)
    cpu_window_matches = (
        bool(sample_window.get("available"))
        and aligned_frame_count > 0
        and len(cpu_values) == aligned_frame_count
    )
    encoders = report.get("nativeEncoderCountsPerMeasuredFrame", {}) if isinstance(report.get("nativeEncoderCountsPerMeasuredFrame"), dict) else {}
    encoder_frames = nonnegative_int(encoders.get("measuredFrames")) or 0
    encoder_window_matches = measured_frames > 0 and encoder_frames == measured_frames
    unavailable = report.get("unavailableMetrics", {}) if isinstance(report.get("unavailableMetrics"), dict) else {}
    render_per_frame = finite_number(encoders.get("renderPerFrame"))
    blit_per_frame = finite_number(encoders.get("blitPerFrame"))
    encoder_total = None if render_per_frame is None and blit_per_frame is None else (render_per_frame or 0.0) + (blit_per_frame or 0.0)

    metrics = {
        "fps_median": metric(
            1_000.0 / float(frame_summary["p50"]) if frame_summary["p50"] and frame_summary["p50"] > 0.0 else None,
            "FPS", "higher", int(frame_summary["sample_count"]),
            "frame-aligned frameIntervals samples are unavailable" if not sample_window.get("available") else None,
        ),
        "frame_time_ms_p95": metric(
            frame_summary["p95"], "ms", "lower", int(frame_summary["sample_count"]),
            "frame-aligned frameIntervals samples are unavailable" if not sample_window.get("available") else None,
            "p95",
        ),
        "frame_time_ms_p99": metric(
            frame_summary["p99"], "ms", "lower", int(frame_summary["sample_count"]),
            "frame-aligned frameIntervals samples are unavailable" if not sample_window.get("available") else None,
            "p99",
        ),
        "gpu_frame_time_ms_median": metric(
            gpu_summary["p50"], "ms", "lower", int(gpu_summary["sample_count"]),
            "frame-aligned gpuFrameTimes samples are unavailable" if not sample_window.get("available") else None,
        ),
        "gpu_frame_time_ms_p95": metric(
            gpu_summary["p95"], "ms", "lower", int(gpu_summary["sample_count"]),
            "frame-aligned gpuFrameTimes samples are unavailable" if not sample_window.get("available") else None,
            "p95",
        ),
        "gpu_frame_time_ms_p99": metric(
            gpu_summary["p99"], "ms", "lower", int(gpu_summary["sample_count"]),
            "frame-aligned gpuFrameTimes samples are unavailable" if not sample_window.get("available") else None,
            "p99",
        ),
        "cpu_render_encode_time_ms_median": metric(
            cpu_summary["p50"] if cpu_window_matches else None,
            "ms", "lower", int(cpu_summary["sample_count"]),
            None if cpu_window_matches else (
                f"CPU frame-ID sample window mismatch: cpu samples={len(cpu_values)}, "
                f"aligned frames={aligned_frame_count}; warmup and measurement data must not be mixed"
            ),
        ),
        "cpu_render_encode_time_ms_p95": metric(
            cpu_summary["p95"] if cpu_window_matches else None,
            "ms", "lower", int(cpu_summary["sample_count"]),
            None if cpu_window_matches else (
                f"CPU frame-ID sample window mismatch: cpu samples={len(cpu_values)}, aligned frames={aligned_frame_count}"
            ),
            "p95",
        ),
        "cpu_render_encode_time_ms_p99": metric(
            cpu_summary["p99"] if cpu_window_matches else None,
            "ms", "lower", int(cpu_summary["sample_count"]),
            None if cpu_window_matches else (
                f"CPU frame-ID sample window mismatch: cpu samples={len(cpu_values)}, aligned frames={aligned_frame_count}"
            ),
            "p99",
        ),
        "native_encoder_count_per_frame_median": metric(
            encoder_total if encoder_window_matches else None,
            "encoders/frame", "lower", encoder_frames,
            None if encoder_window_matches else (
                f"encoder sample window mismatch: encoder frames={encoder_frames}, measured frames={measured_frames}"
            ),
        ),
        "render_pass_store_load_bytes_estimate_median": metric(
            None, "bytes/frame", "lower", 0,
            str(unavailable.get("attachmentStoreLoadBytes") or "attachment load/store accounting is unavailable"),
        ),
        "resident_render_resource_bytes": metric(
            None, "bytes", "lower", 0,
            str(unavailable.get("residentRenderResourceBytes") or "resident render-resource accounting is unavailable"),
        ),
        "peak_resident_memory_bytes": metric(
            None, "bytes", "lower", 0,
            str(unavailable.get("peakResidentMemoryBytes") or "peak resident memory accounting is unavailable"),
        ),
        "frame_time_stutter_count": metric(
            sum(
                1 for value in frame_values.values()
                if frame_summary["p50"] and value > float(frame_summary["p50"]) * 2.0
            ) if sample_window.get("available") else report.get("frameTimeStutterCount"),
            "events", "lower", aligned_frame_count or measured_frames,
        ),
    }

    identity_errors: list[str] = []
    if measured_frames <= 0:
        identity_errors.append("measuredFrameIntervals is missing or zero")
    drawable_width = nonnegative_int(report.get("drawableWidth"))
    drawable_height = nonnegative_int(report.get("drawableHeight"))
    if drawable_width is None or drawable_width <= 0:
        identity_errors.append("drawableWidth is missing or non-positive")
    if drawable_height is None or drawable_height <= 0:
        identity_errors.append("drawableHeight is missing or non-positive")
    if not metrics["fps_median"]["available"]:
        identity_errors.append("sourceFpsFromP50 is missing or non-finite")
    identity_errors.extend(sample_window_errors)
    if source_error:
        identity_errors.append(source_error)
    if parse_error:
        identity_errors.append(f"source report is unreadable: {parse_error}")

    result = {
        "schema_version": SCHEMA_VERSION,
        "trial_dir": str(trial_dir),
        "exit_status": exit_status,
        "complete": exit_status == 0 and not identity_errors,
        "source_report": str(source.relative_to(trial_dir)) if source is not None else None,
        "source_report_copies": [str(path.relative_to(trial_dir)) for path in copies],
        "source_report_sha256": digest(source) if source is not None and source.is_file() else None,
        "identity_errors": identity_errors,
        "measured_frames": measured_frames,
        "sample_window": {
            "schema_version": PERFORMANCE_SAMPLE_SCHEMA_VERSION,
            "available": sample_window.get("available", False),
            "raw_frame_id_start": sample_window.get("raw_frame_ids", [None])[0]
            if sample_window.get("raw_frame_ids") else None,
            "raw_frame_id_end": sample_window.get("raw_frame_ids", [None])[-1]
            if sample_window.get("raw_frame_ids") else None,
            "raw_frame_count": len(sample_window.get("raw_frame_ids", [])),
            "frame_id_start": sample_window.get("frame_ids", [None])[0]
            if sample_window.get("frame_ids") else None,
            "frame_id_end": sample_window.get("frame_ids", [None])[-1]
            if sample_window.get("frame_ids") else None,
            "frame_count": len(sample_window.get("frame_ids", [])),
            "alignment_coverage": sample_window.get("alignment_coverage", 0.0),
            "dropped_frame_ids": sample_window.get("dropped_frame_ids", []),
            "cpu_frame_count": len(cpu_values),
            "gpu_frame_count": len(gpu_values),
            "gpu_command_buffer_count": sample_window.get("gpu_command_buffer_count", 0),
            "errors": sample_window_errors,
        },
        "metrics": metrics,
        "admission": {
            "renderFusionRuntime": report.get("renderFusionRuntime"),
            "computeGroupingRuntime": report.get("computeGroupingRuntime"),
            "depthLivenessRuntime": report.get("depthLivenessRuntime"),
            "argumentBindingRuntime": report.get("argumentBindingRuntime"),
            "bindingPathRuntime": report.get("bindingPathRuntime"),
            "irisPerformanceCounters": report.get("irisPerformanceCounters"),
        },
        "source_summary": {
            "drawable_width": drawable_width,
            "drawable_height": drawable_height,
            "frame_interval_p50_ms": report.get("frameIntervalP50Milliseconds"),
            "frame_interval_p95_ms": report.get("frameIntervalP95Milliseconds"),
            "frame_interval_p99_ms": report.get("frameIntervalP99Milliseconds"),
            "gpu_p95_ms": report.get("gpuP95Milliseconds"),
            "gpu_p99_ms": report.get("gpuP99Milliseconds"),
            "readback": report.get("nativeMainReadback"),
            "unavailable_metrics": unavailable,
        },
    }
    (trial_dir / "metrics.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temp:
        trial = Path(temp)
        (trial / "exit-status.txt").write_text("0\n", encoding="utf-8")
        report = {
            "drawableWidth": 1920,
            "drawableHeight": 1080,
            "measuredFrameIntervals": 3,
            "sourceFpsFromP50": 40.0,
            "gpuP50Milliseconds": 20.0,
            "frameTimeStutterCount": 0,
            "cpuRenderEncodeFrameMilliseconds": {"samples": 3, "p50Milliseconds": 25.0},
            "nativeEncoderCountsPerMeasuredFrame": {"measuredFrames": 3, "renderPerFrame": 6.0, "blitPerFrame": 2.0},
            "performanceSampleWindow": {
                "schemaVersion": 1,
                "frameIdStart": 41,
                "frameIdEnd": 43,
                "frameCount": 3,
                "frameIntervals": [
                    {"frameId": 41, "milliseconds": 20.0},
                    {"frameId": 42, "milliseconds": 25.0},
                    {"frameId": 43, "milliseconds": 30.0},
                ],
                "cpuRenderEncode": [
                    {"frameId": 41, "milliseconds": 24.0},
                    {"frameId": 42, "milliseconds": 25.0},
                    {"frameId": 43, "milliseconds": 26.0},
                ],
                "gpuFrameTimes": [
                    {"frameId": 41, "milliseconds": 10.0},
                    {"frameId": 42, "milliseconds": 20.0},
                    {"frameId": 43, "milliseconds": 30.0},
                ],
                "gpuCommandBuffers": [
                    {"frameId": 41, "submitIndex": 101, "milliseconds": 10.0},
                    {"frameId": 42, "submitIndex": 102, "milliseconds": 20.0},
                    {"frameId": 43, "submitIndex": 103, "milliseconds": 30.0},
                ],
                "gpuCommandBufferCount": 3,
                "gpuFrameCount": 3,
            },
            "renderFusionRuntime": {"admissions": 1},
            "bindingPathRuntime": {"renderForwardedCalls": 30, "renderSuppressedCalls": 12, "packetCalls": 9},
        }
        first = trial / "artifacts" / "validation" / "native-fullscreen-baseline.json"
        second = trial / "artifacts" / "copy" / "native-fullscreen-baseline.json"
        first.parent.mkdir(parents=True)
        second.parent.mkdir(parents=True)
        payload = json.dumps(report, sort_keys=True)
        first.write_text(payload, encoding="utf-8")
        second.write_text(payload, encoding="utf-8")
        result = normalize(trial)
        assert result["complete"]
        assert result["metrics"]["fps_median"]["median"] == 40.0
        assert result["metrics"]["frame_time_ms_p95"]["median"] == 30.0
        assert result["metrics"]["gpu_frame_time_ms_p95"]["median"] == 30.0
        assert result["metrics"]["cpu_render_encode_time_ms_p95"]["median"] == 26.0
        assert result["metrics"]["native_encoder_count_per_frame_median"]["median"] == 8.0

        # A report with a missing CPU frame must not be rescued by the legacy
        # top-level p95 fields.  The frame-ID contract is the acceptance
        # source, so the trial is incomplete until the sample window is fixed.
        invalid_trial = trial / "invalid-cpu-window"
        invalid_source = invalid_trial / "native-fullscreen-baseline.json"
        invalid_source.parent.mkdir(parents=True)
        invalid_report = json.loads(payload)
        invalid_report["performanceSampleWindow"]["cpuRenderEncode"] = (
            invalid_report["performanceSampleWindow"]["cpuRenderEncode"][:-1]
        )
        (invalid_trial / "exit-status.txt").write_text("0\n", encoding="utf-8")
        invalid_source.write_text(json.dumps(invalid_report), encoding="utf-8")
        invalid_result = normalize(invalid_trial)
        assert not invalid_result["complete"]
        assert any("cpuRenderEncode" in error for error in invalid_result["identity_errors"])
    print("normalize_unified_trial self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trial_dir", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.trial_dir is None:
        parser.error("trial_dir is required unless --self-test is used")
    result = normalize(args.trial_dir)
    return 0 if result["complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
