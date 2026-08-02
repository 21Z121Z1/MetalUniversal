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

SCHEMA_VERSION = 2


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


def metric(value: Any, unit: str, direction: str, sample_count: int, reason: str | None = None) -> dict[str, Any]:
    number = finite_number(value)
    if number is None:
        return {
            "available": False,
            "sample_count": 0,
            "unit": unit,
            "direction": direction,
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
    }


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

    measured_frames = int(report.get("measuredFrameIntervals") or 0) if report else 0
    cpu = report.get("cpuRenderEncodeFrameMilliseconds", {}) if isinstance(report.get("cpuRenderEncodeFrameMilliseconds"), dict) else {}
    cpu_samples = int(cpu.get("samples") or 0)
    cpu_window_matches = measured_frames > 0 and cpu_samples == measured_frames
    encoders = report.get("nativeEncoderCountsPerMeasuredFrame", {}) if isinstance(report.get("nativeEncoderCountsPerMeasuredFrame"), dict) else {}
    encoder_frames = int(encoders.get("measuredFrames") or 0)
    encoder_window_matches = measured_frames > 0 and encoder_frames == measured_frames
    unavailable = report.get("unavailableMetrics", {}) if isinstance(report.get("unavailableMetrics"), dict) else {}
    render_per_frame = finite_number(encoders.get("renderPerFrame"))
    blit_per_frame = finite_number(encoders.get("blitPerFrame"))
    encoder_total = None if render_per_frame is None and blit_per_frame is None else (render_per_frame or 0.0) + (blit_per_frame or 0.0)

    metrics = {
        "fps_median": metric(report.get("sourceFpsFromP50"), "FPS", "higher", measured_frames),
        "gpu_frame_time_ms_median": metric(report.get("gpuP50Milliseconds"), "ms", "lower", measured_frames),
        "cpu_render_encode_time_ms_median": metric(
            cpu.get("p50Milliseconds") if cpu_window_matches else None,
            "ms", "lower", cpu_samples,
            None if cpu_window_matches else (
                f"CPU sample window mismatch: cpu samples={cpu_samples}, measured frames={measured_frames}; "
                "warmup and measurement data must not be mixed"
            ),
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
        "frame_time_stutter_count": metric(report.get("frameTimeStutterCount"), "events", "lower", measured_frames),
    }

    identity_errors: list[str] = []
    if measured_frames <= 0:
        identity_errors.append("measuredFrameIntervals is missing or zero")
    if not metrics["fps_median"]["available"]:
        identity_errors.append("sourceFpsFromP50 is missing or non-finite")
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
        "metrics": metrics,
        "admission": {
            "renderFusionRuntime": report.get("renderFusionRuntime"),
            "computeGroupingRuntime": report.get("computeGroupingRuntime"),
            "depthLivenessRuntime": report.get("depthLivenessRuntime"),
            "argumentBindingRuntime": report.get("argumentBindingRuntime"),
            "irisPerformanceCounters": report.get("irisPerformanceCounters"),
        },
        "source_summary": {
            "frame_interval_p50_ms": report.get("frameIntervalP50Milliseconds"),
            "frame_interval_p95_ms": report.get("frameIntervalP95Milliseconds"),
            "gpu_p95_ms": report.get("gpuP95Milliseconds"),
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
            "measuredFrameIntervals": 300,
            "sourceFpsFromP50": 40.0,
            "gpuP50Milliseconds": 20.0,
            "frameTimeStutterCount": 2,
            "cpuRenderEncodeFrameMilliseconds": {"samples": 300, "p50Milliseconds": 24.0},
            "nativeEncoderCountsPerMeasuredFrame": {"measuredFrames": 300, "renderPerFrame": 6.0, "blitPerFrame": 2.0},
            "renderFusionRuntime": {"admissions": 1},
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
        assert result["metrics"]["native_encoder_count_per_frame_median"]["median"] == 8.0
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
