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
SIGNED_INT64_MAX = (1 << 63) - 1
NATIVE_ENCODER_MAX_ROWS = 65_536
PROCESS_MEMORY_SCHEMA_VERSION = 1
PROCESS_MEMORY_SOURCE = "mach_task_info(TASK_VM_INFO.resident_size)"
PROCESS_MEMORY_SCOPE = "current-process"
PROCESS_MEMORY_SAMPLING_POLICY = "frame-boundaries-and-final-drain"
PROCESS_MEMORY_PEAK_KIND = "sampled-maximum"
PROCESS_MEMORY_MAX_SAMPLES = 65_536


def finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def strict_integer(value: Any) -> int | None:
    """Return signed Java/native Int64 JSON integers only."""
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    if value < -(1 << 63) or value > SIGNED_INT64_MAX:
        return None
    return value


def checked_nonnegative_add(left: int, right: int) -> int | None:
    """Match Java Math.addExact for non-negative signed-Int64 counters."""
    if left < 0 or right < 0 or left > SIGNED_INT64_MAX - right:
        return None
    return left + right


def validate_measurement_window(
    report: dict[str, Any], measured_frames: int, gpu_submission_count: int
) -> tuple[dict[str, Any] | None, list[str]]:
    """Validate the shared frame window used by CPU, GPU, and encoder evidence."""
    raw = report.get("measurementWindow")
    if not isinstance(raw, dict):
        return None, [
            "measurementWindow is missing; historical reports cannot establish a shared measurement boundary"
        ]

    errors: list[str] = []
    window_id = strict_integer(raw.get("id"))
    start = strict_integer(raw.get("startFrameInclusive"))
    end = strict_integer(raw.get("endFrameExclusive"))
    completed = strict_integer(raw.get("completedFrames"))
    first_submit = strict_integer(raw.get("firstSubmitIndexInclusive"))
    last_submit = strict_integer(raw.get("lastSubmitIndexExclusive"))
    if window_id is None or window_id <= 0:
        errors.append("measurementWindow.id must be a positive integer")
    if start is None or start < 0:
        errors.append("measurementWindow.startFrameInclusive must be a non-negative integer")
    if end is None or end <= 0:
        errors.append("measurementWindow.endFrameExclusive must be a positive integer")
    if completed is None or completed <= 0:
        errors.append("measurementWindow.completedFrames must be a positive integer")
    if first_submit is None or first_submit < 0:
        errors.append("measurementWindow.firstSubmitIndexInclusive must be a non-negative integer")
    if last_submit is None or last_submit <= 0:
        errors.append("measurementWindow.lastSubmitIndexExclusive must be a positive integer")
    if start is not None and end is not None and end <= start:
        errors.append("measurementWindow frame bounds must be increasing")
    if start is not None and end is not None and completed is not None and end - start != completed:
        errors.append(
            "measurementWindow frame-bound difference does not equal completedFrames"
        )
    if measured_frames > 0 and completed is not None and completed != measured_frames:
        errors.append(
            f"measurementWindow.completedFrames={completed} does not equal "
            f"measuredFrameIntervals={measured_frames}"
        )
    if first_submit is not None and last_submit is not None and last_submit <= first_submit:
        errors.append("measurementWindow submit bounds must be increasing")
    if first_submit is not None and last_submit is not None and last_submit - first_submit != gpu_submission_count:
        errors.append(
            "measurementWindow submit-bound difference does not equal measuredGpuCommandBuffers"
        )
    if completed is not None and gpu_submission_count < completed:
        errors.append(
            f"measuredGpuCommandBuffers={gpu_submission_count} is less than completedFrames={completed}"
        )
    return raw, errors


def validate_process_memory(
    report: dict[str, Any], measurement_window: dict[str, Any] | None
) -> tuple[dict[str, Any] | None, list[str]]:
    """Validate process RSS samples without presenting them as a continuous peak.

    The producer emits two probes for every completed frame and one final drain
    probe.  All summary values are derived from those rows; no reported peak is
    trusted independently of the raw samples.
    """
    if "processMemory" not in report:
        # Older reports remain diagnostically usable, with this metric absent.
        return None, []
    raw = report.get("processMemory")
    if not isinstance(raw, dict):
        return None, ["processMemory is present but is not an object"]
    errors: list[str] = []
    if measurement_window is None:
        return None, ["processMemory cannot be checked without measurementWindow"]

    window_id = strict_integer(measurement_window.get("id"))
    first_frame = strict_integer(measurement_window.get("startFrameInclusive"))
    end_frame = strict_integer(measurement_window.get("endFrameExclusive"))
    completed = strict_integer(measurement_window.get("completedFrames"))
    if window_id is None or first_frame is None or end_frame is None or completed is None:
        errors.append("processMemory requires a valid measurementWindow")
    if window_id is None or window_id <= 0:
        errors.append("processMemory measurementWindow.id must be positive")
    if first_frame is None or first_frame < 0:
        errors.append("processMemory measurementWindow.startFrameInclusive must be non-negative")
    if end_frame is None or end_frame <= (first_frame if first_frame is not None else 0):
        errors.append("processMemory measurementWindow.endFrameExclusive must exceed startFrameInclusive")
    if completed is None or completed <= 0:
        errors.append("processMemory measurementWindow.completedFrames must be positive")
    if (
        first_frame is not None
        and end_frame is not None
        and completed is not None
        and end_frame > first_frame
        and end_frame - first_frame != completed
    ):
        errors.append("processMemory measurementWindow frame bounds do not equal completedFrames")

    if strict_integer(raw.get("schemaVersion")) != PROCESS_MEMORY_SCHEMA_VERSION:
        errors.append("processMemory.schemaVersion must be 1")
    if raw.get("source") != PROCESS_MEMORY_SOURCE:
        errors.append(f"processMemory.source must be {PROCESS_MEMORY_SOURCE}")
    if raw.get("scope") != PROCESS_MEMORY_SCOPE:
        errors.append("processMemory.scope must be current-process")
    if raw.get("samplingPolicy") != PROCESS_MEMORY_SAMPLING_POLICY:
        errors.append(
            "processMemory.samplingPolicy must be frame-boundaries-and-final-drain"
        )
    if raw.get("peakKind") != PROCESS_MEMORY_PEAK_KIND:
        errors.append("processMemory.peakKind must be sampled-maximum")
    if raw.get("complete") is not True:
        errors.append("processMemory.complete is not true")
    if raw.get("status") != "complete-sampled-process-rss":
        errors.append("processMemory.status must be complete-sampled-process-rss")

    process_window = strict_integer(raw.get("windowId"))
    process_first = strict_integer(raw.get("firstFrame"))
    process_end = strict_integer(raw.get("endFrameExclusive"))
    capacity = strict_integer(raw.get("capacitySamples"))
    dropped = strict_integer(raw.get("droppedSamples"))
    failed = strict_integer(raw.get("failedSamples"))
    invalid = strict_integer(raw.get("invalidEvents"))
    sample_count = strict_integer(raw.get("sampleCount"))
    if process_window != window_id:
        errors.append("processMemory.windowId does not match measurementWindow.id")
    if process_first != first_frame:
        errors.append("processMemory.firstFrame does not match measurementWindow.startFrameInclusive")
    if process_end != end_frame:
        errors.append("processMemory.endFrameExclusive does not match measurementWindow.endFrameExclusive")
    if capacity is None or not 1 <= capacity <= PROCESS_MEMORY_MAX_SAMPLES:
        errors.append("processMemory.capacitySamples must be an integer in 1..65536")
    if sample_count is None or sample_count < 0:
        errors.append("processMemory.sampleCount must be a non-negative JSON integer")
    for name, value in (
        ("droppedSamples", dropped),
        ("failedSamples", failed),
        ("invalidEvents", invalid),
    ):
        if value is None or value < 0:
            errors.append(f"processMemory.{name} must be a non-negative JSON integer")
        elif value != 0:
            errors.append(f"processMemory.{name} is non-zero")

    samples = raw.get("samples")
    if not isinstance(samples, list):
        errors.append("processMemory.samples is missing or is not an array")
        return None, errors
    if capacity is not None and len(samples) > capacity:
        errors.append("processMemory.samples exceeds capacitySamples")
    expected_count: int | None = None
    if completed is not None and completed > 0:
        expected_count = 2 * completed + 1
        if sample_count != expected_count:
            errors.append(
                f"processMemory.sampleCount={sample_count} does not equal 2*completedFrames+1={expected_count}"
            )
        if len(samples) != expected_count:
            errors.append(
                f"processMemory samples={len(samples)} does not equal 2*completedFrames+1={expected_count}"
            )
    elif completed is not None:
        errors.append("processMemory.completedFrames must be positive")
    if sample_count is not None and sample_count != len(samples):
        errors.append(f"processMemory.sampleCount={sample_count} does not equal samples={len(samples)}")

    summary_fields = (
        "peakResidentBytes",
        "peakPhysicalFootprintBytes",
        "lifetimeResidentPeakBytesLast",
        "totalProbeNanos",
        "maxProbeNanos",
        "endOffsetNanos",
    )
    summary: dict[str, int | None] = {}
    for field in summary_fields:
        value = strict_integer(raw.get(field))
        summary[field] = value
        if value is None or value < 0:
            errors.append(f"processMemory.{field} must be a non-negative signed Int64")

    if (
        expected_count is not None
        and capacity is not None
        and expected_count > capacity
    ):
        errors.append(
            "processMemory capacitySamples is smaller than 2*completedFrames+1"
        )

    resident_values: list[int] = []
    physical_values: list[int] = []
    lifetime_values: list[int] = []
    durations: list[int] = []
    previous_end: int | None = None
    valid_rows = True
    for index, sample in enumerate(samples):
        prefix = f"processMemory.samples[{index}]"
        if not isinstance(sample, dict):
            errors.append(f"{prefix} is not an object")
            valid_rows = False
            continue
        sequence = strict_integer(sample.get("sequence"))
        row_window = strict_integer(sample.get("windowId"))
        frame_id = strict_integer(sample.get("frameId"))
        phase = sample.get("phase")
        begin = strict_integer(sample.get("beginOffsetNanos"))
        end = strict_integer(sample.get("endOffsetNanos"))
        kernel_status = strict_integer(sample.get("kernelStatus"))
        returned_words = strict_integer(sample.get("returnedWordCount"))
        resident = strict_integer(sample.get("residentBytes"))
        physical = strict_integer(sample.get("physicalFootprintBytes"))
        lifetime = strict_integer(sample.get("lifetimeResidentPeakBytes"))
        if sequence is None or sequence != index:
            errors.append(f"{prefix}.sequence must be {index}")
            valid_rows = False
        if row_window != window_id:
            errors.append(f"{prefix}.windowId does not match measurementWindow.id")
            valid_rows = False
        if (
            expected_count is not None
            and first_frame is not None
            and end_frame is not None
            and index < expected_count
        ):
            if index == expected_count - 1:
                expected_frame, expected_phase = end_frame, "window-drain"
            else:
                expected_frame = first_frame + index // 2
                expected_phase = "frame-begin" if index % 2 == 0 else "frame-end"
            if frame_id != expected_frame or phase != expected_phase:
                errors.append(
                    f"{prefix} must be {expected_phase} for frame {expected_frame}"
                )
                valid_rows = False
        else:
            errors.append(f"{prefix} is outside the expected frame/drain sequence")
            valid_rows = False
        if kernel_status != 0:
            errors.append(f"{prefix}.kernelStatus must be 0")
            valid_rows = False
        if returned_words is None or returned_words < 38:
            errors.append(f"{prefix}.returnedWordCount must be at least 38")
            valid_rows = False
        if begin is None or begin < 0 or end is None or end < 0 or end < begin:
            errors.append(f"{prefix} has invalid non-negative probe offsets")
            valid_rows = False
        if previous_end is not None and begin is not None and begin < previous_end:
            errors.append(f"{prefix}.beginOffsetNanos overlaps the preceding probe")
            valid_rows = False
        if begin is not None and end is not None:
            if summary["endOffsetNanos"] is not None and end > summary["endOffsetNanos"]:
                errors.append(f"{prefix}.endOffsetNanos exceeds processMemory.endOffsetNanos")
                valid_rows = False
            duration = end - begin
            durations.append(duration)
            previous_end = end
        if resident is None or resident <= 0:
            errors.append(f"{prefix}.residentBytes must be positive")
            valid_rows = False
        else:
            resident_values.append(resident)
        if physical is None or physical < 0:
            errors.append(f"{prefix}.physicalFootprintBytes must be non-negative")
            valid_rows = False
        else:
            physical_values.append(physical)
        if lifetime is None or lifetime < 0:
            errors.append(f"{prefix}.lifetimeResidentPeakBytes must be non-negative")
            valid_rows = False
        else:
            lifetime_values.append(lifetime)

    if expected_count is not None and len(samples) != expected_count:
        valid_rows = False
    total_probe = 0
    max_probe = 0
    for duration in durations:
        next_total = checked_nonnegative_add(total_probe, duration)
        if next_total is None:
            errors.append("processMemory probe duration sum overflows signed Int64")
            valid_rows = False
            break
        total_probe = next_total
        max_probe = max(max_probe, duration)
    if valid_rows and resident_values and summary["peakResidentBytes"] != max(resident_values):
        errors.append("processMemory.peakResidentBytes does not match sampled RSS maximum")
    if valid_rows and physical_values and summary["peakPhysicalFootprintBytes"] != max(physical_values):
        errors.append(
            "processMemory.peakPhysicalFootprintBytes does not match sampled physical-footprint maximum"
        )
    if valid_rows and lifetime_values and summary["lifetimeResidentPeakBytesLast"] != lifetime_values[-1]:
        errors.append("processMemory.lifetimeResidentPeakBytesLast does not match the final sample")
    if valid_rows and summary["totalProbeNanos"] != total_probe:
        errors.append("processMemory.totalProbeNanos does not match the raw duration sum")
    if valid_rows and summary["maxProbeNanos"] != max_probe:
        errors.append("processMemory.maxProbeNanos does not match the raw maximum duration")

    if errors:
        return None, errors
    return raw, []


def validate_gpu_submission_samples(
    report: dict[str, Any],
    measurement_window: dict[str, Any] | None,
    measured_gpu_frames: int,
    measured_gpu_command_buffers: int,
) -> tuple[float | None, list[str]]:
    """Validate frame/submit identity and return the independent frame-time p50."""
    raw = report.get("gpuSubmissionSamples")
    if not isinstance(raw, list):
        return None, ["gpuSubmissionSamples is missing or is not an array"]
    if measurement_window is None:
        return None, ["gpuSubmissionSamples cannot be checked without measurementWindow"]

    window_id = strict_integer(measurement_window.get("id"))
    first_frame = strict_integer(measurement_window.get("startFrameInclusive"))
    last_frame = strict_integer(measurement_window.get("endFrameExclusive"))
    first_submit = strict_integer(measurement_window.get("firstSubmitIndexInclusive"))
    last_submit = strict_integer(measurement_window.get("lastSubmitIndexExclusive"))
    errors: list[str] = []
    submit_ids: list[int] = []
    frame_ids: list[int] = []
    frame_durations: dict[int, list[float]] = {}
    for index, sample in enumerate(raw):
        if not isinstance(sample, dict):
            errors.append(f"gpuSubmissionSamples[{index}] is not an object")
            continue
        submit_id = strict_integer(sample.get("submitIndex"))
        sample_window_id = strict_integer(sample.get("windowId"))
        frame_id = strict_integer(sample.get("frameId"))
        start = finite_number(sample.get("gpuStartTime"))
        end = finite_number(sample.get("gpuEndTime"))
        if submit_id is None or submit_id < 0:
            errors.append(f"gpuSubmissionSamples[{index}].submitIndex is invalid")
        else:
            submit_ids.append(submit_id)
        if sample_window_id != window_id:
            errors.append(f"gpuSubmissionSamples[{index}].windowId does not match measurementWindow.id")
        if frame_id is None:
            errors.append(f"gpuSubmissionSamples[{index}].frameId is invalid")
        else:
            frame_ids.append(frame_id)
        if start is None or end is None or end <= start:
            errors.append(f"gpuSubmissionSamples[{index}] has an invalid GPU duration")
        elif frame_id is not None:
            frame_durations.setdefault(frame_id, []).append((end - start) * 1_000.0)

    if len(raw) != measured_gpu_command_buffers:
        errors.append(
            f"gpuSubmissionSamples count={len(raw)} does not equal "
            f"measuredGpuCommandBuffers={measured_gpu_command_buffers}"
        )
    if len(submit_ids) != len(set(submit_ids)):
        errors.append("gpuSubmissionSamples contains duplicate submitIndex values")
    if first_submit is not None and last_submit is not None:
        ordered_submits = sorted(set(submit_ids))
        if len(ordered_submits) != last_submit - first_submit or any(
            value != first_submit + index for index, value in enumerate(ordered_submits)
        ):
            errors.append("gpuSubmissionSamples submitIndex set does not cover the declared submit window")
    if first_frame is not None and last_frame is not None:
        ordered_frames = sorted(set(frame_ids))
        if len(ordered_frames) != last_frame - first_frame or any(
            value != first_frame + index for index, value in enumerate(ordered_frames)
        ):
            errors.append("gpuSubmissionSamples frameId set does not cover the declared frame window")
    if len(set(frame_ids)) != measured_gpu_frames:
        errors.append(
            f"gpuSubmissionSamples unique frames={len(set(frame_ids))} does not equal "
            f"measuredGpuFrames={measured_gpu_frames}"
        )
    if errors:
        return None, errors
    frame_service_times = [sum(values) for values in frame_durations.values()]
    if not frame_service_times or len(frame_service_times) != measured_gpu_frames:
        return None, ["gpuSubmissionSamples did not produce one service-time total per frame"]
    # The report's percentile contract uses nearest rank, including even sample counts.
    ordered_times = sorted(frame_service_times)
    return ordered_times[math.ceil(len(ordered_times) * 0.5) - 1], []


NATIVE_ENCODER_LEDGER_SCHEMA_VERSION = 1
NATIVE_ENCODER_LEDGER_SCOPE = "main-queue-native-encoders"
_NATIVE_ENCODER_KINDS = ("renderCreated", "blitCreated", "computeCreated")


def validate_native_encoder_ledger(
    report: dict[str, Any],
    measurement_window: dict[str, Any] | None,
    measured_frames: int,
    measured_gpu_command_buffers: int,
) -> tuple[float | None, list[str]]:
    """Validate frame-bound native encoder counts independently of timestamps.

    The ledger is deliberately stricter than the legacy timestamp-only report:
    one row must represent each main command-buffer/Metal 4 lease submission,
    and all identity and lifecycle counters must be complete before counts are
    admitted as a metric.
    """
    errors: list[str] = []
    raw = report.get("nativeEncoderLedger")
    if not isinstance(raw, dict):
        return None, [
            "nativeEncoderLedger is missing; timestamp-only reports cannot prove encoder coverage"
        ]
    if measurement_window is None:
        return None, ["nativeEncoderLedger cannot be checked without measurementWindow"]

    if report.get("mode") != "native-metalfx-off":
        errors.append("native encoder ledger requires report.mode=native-metalfx-off")
    off = report.get("metalFxOffDiagnostics")
    if not isinstance(off, dict):
        errors.append("metalFxOffDiagnostics is missing for native encoder ledger")
    else:
        if off.get("modeOff") is not True:
            errors.append("metalFxOffDiagnostics.modeOff is not true")
        if off.get("allWorkEliminated") is not True:
            errors.append("metalFxOffDiagnostics.allWorkEliminated is not true")

    schema = strict_integer(raw.get("schemaVersion"))
    if schema != NATIVE_ENCODER_LEDGER_SCHEMA_VERSION:
        errors.append("nativeEncoderLedger.schemaVersion must be 1")
    if raw.get("enabled") is not True:
        errors.append("nativeEncoderLedger.enabled is not true")
    if raw.get("scope") != NATIVE_ENCODER_LEDGER_SCOPE:
        errors.append(
            "nativeEncoderLedger.scope must be main-queue-native-encoders"
        )

    capacity = strict_integer(raw.get("capacityRows"))
    dropped = strict_integer(raw.get("droppedRows"))
    invalid = strict_integer(raw.get("invalidEvents"))
    active_command_buffers = strict_integer(raw.get("activeCommandBuffers"))
    active_encoders = strict_integer(raw.get("activeEncoders"))
    row_count = strict_integer(raw.get("rowCount"))
    if capacity is None or not 1 <= capacity <= NATIVE_ENCODER_MAX_ROWS:
        errors.append("nativeEncoderLedger.capacityRows must be an integer in 1..65536")
    for name, value in (
        ("droppedRows", dropped),
        ("invalidEvents", invalid),
        ("activeCommandBuffers", active_command_buffers),
        ("activeEncoders", active_encoders),
        ("rowCount", row_count),
    ):
        if value is None or value < 0:
            errors.append(f"nativeEncoderLedger.{name} must be a non-negative JSON integer")
    if dropped not in (None, 0):
        errors.append("nativeEncoderLedger.droppedRows is non-zero")
    if invalid not in (None, 0):
        errors.append("nativeEncoderLedger.invalidEvents is non-zero")
    if active_command_buffers not in (None, 0):
        errors.append("nativeEncoderLedger.activeCommandBuffers is non-zero")
    if active_encoders not in (None, 0):
        errors.append("nativeEncoderLedger.activeEncoders is non-zero")

    rows = raw.get("rows")
    if not isinstance(rows, list):
        errors.append("nativeEncoderLedger.rows is missing or is not an array")
        return None, errors
    if capacity is not None and len(rows) > capacity:
        errors.append("nativeEncoderLedger.rows exceeds capacityRows")
    if row_count is not None and row_count != len(rows):
        errors.append(
            f"nativeEncoderLedger.rowCount={row_count} does not equal rows={len(rows)}"
        )

    window_id = strict_integer(measurement_window.get("id"))
    first_frame = strict_integer(measurement_window.get("startFrameInclusive"))
    last_frame = strict_integer(measurement_window.get("endFrameExclusive"))
    first_submit = strict_integer(measurement_window.get("firstSubmitIndexInclusive"))
    last_submit = strict_integer(measurement_window.get("lastSubmitIndexExclusive"))
    row_ids: list[tuple[int, int, int]] = []
    frame_totals: dict[int, int] = {}
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append(f"nativeEncoderLedger.rows[{index}] is not an object")
            continue
        row_window = strict_integer(row.get("windowId"))
        frame_id = strict_integer(row.get("frameId"))
        submit_id = strict_integer(row.get("submitIndex"))
        backend = strict_integer(row.get("backend"))
        if row_window is None or row_window != window_id:
            errors.append(f"nativeEncoderLedger.rows[{index}].windowId does not match measurementWindow.id")
        if frame_id is None:
            errors.append(f"nativeEncoderLedger.rows[{index}].frameId is invalid")
        if submit_id is None:
            errors.append(f"nativeEncoderLedger.rows[{index}].submitIndex is invalid")
        if backend not in (3, 4):
            errors.append(f"nativeEncoderLedger.rows[{index}].backend must be 3 or 4")
        if row_window is not None and frame_id is not None and submit_id is not None:
            row_ids.append((row_window, frame_id, submit_id))

        counts: dict[str, int] = {}
        for field in (
            "attempted", "created", "ended", *_NATIVE_ENCODER_KINDS,
            "createFailures", "unsupportedEncodes", "invalidEvents",
        ):
            value = strict_integer(row.get(field))
            counts[field] = value if value is not None else -1
            if value is None or value < 0:
                errors.append(
                    f"nativeEncoderLedger.rows[{index}].{field} must be a non-negative JSON integer"
                )
        if counts["createFailures"] != 0:
            errors.append(f"nativeEncoderLedger.rows[{index}].createFailures is non-zero")
        if counts["unsupportedEncodes"] != 0:
            errors.append(f"nativeEncoderLedger.rows[{index}].unsupportedEncodes is non-zero")
        if counts["invalidEvents"] != 0:
            errors.append(f"nativeEncoderLedger.rows[{index}].invalidEvents is non-zero")
        if counts["attempted"] >= 0 and counts["created"] >= 0 and counts["attempted"] != counts["created"]:
            errors.append(f"nativeEncoderLedger.rows[{index}] attempted does not equal created")
        if counts["created"] >= 0 and counts["ended"] >= 0 and counts["created"] != counts["ended"]:
            errors.append(f"nativeEncoderLedger.rows[{index}] created does not equal ended")
        if all(counts[field] >= 0 for field in _NATIVE_ENCODER_KINDS):
            kind_total = 0
            kind_overflow = False
            for field in _NATIVE_ENCODER_KINDS:
                next_total = checked_nonnegative_add(kind_total, counts[field])
                if next_total is None:
                    kind_overflow = True
                    errors.append(f"nativeEncoderLedger.rows[{index}] encoder-kind total overflows signed Int64")
                    break
                kind_total = next_total
            if not kind_overflow and counts["created"] >= 0 and kind_total != counts["created"]:
                errors.append(
                    f"nativeEncoderLedger.rows[{index}] created does not equal the three encoder-kind totals"
                )
            if not kind_overflow and frame_id is not None:
                previous = frame_totals.get(frame_id, 0)
                next_total = checked_nonnegative_add(previous, counts["created"])
                if next_total is None:
                    errors.append(
                        f"nativeEncoderLedger frame {frame_id} total overflows signed Int64"
                    )
                else:
                    frame_totals[frame_id] = next_total

    if len(row_ids) != len(set(row_ids)):
        errors.append("nativeEncoderLedger contains duplicate window/frame/submit identities")
    if len(rows) != measured_gpu_command_buffers:
        errors.append(
            f"nativeEncoderLedger rows={len(rows)} does not equal measuredGpuCommandBuffers={measured_gpu_command_buffers}"
        )
    if first_submit is not None and last_submit is not None:
        actual_submit_ids = sorted({submit_id for _, _, submit_id in row_ids})
        expected_count = last_submit - first_submit
        if len(actual_submit_ids) != expected_count or any(
            value != first_submit + index for index, value in enumerate(actual_submit_ids)
        ):
            errors.append("nativeEncoderLedger submitIndex set does not cover the declared submit window")

    gpu_raw = report.get("gpuSubmissionSamples")
    gpu_ids: set[tuple[int, int, int]] = set()
    if isinstance(gpu_raw, list):
        for index, sample in enumerate(gpu_raw):
            if isinstance(sample, dict):
                sample_window = strict_integer(sample.get("windowId"))
                sample_frame = strict_integer(sample.get("frameId"))
                sample_submit = strict_integer(sample.get("submitIndex"))
                if sample_window is not None and sample_frame is not None and sample_submit is not None:
                    gpu_ids.add((sample_window, sample_frame, sample_submit))
                else:
                    errors.append(f"gpuSubmissionSamples[{index}] lacks a valid encoder identity")
            else:
                errors.append(f"gpuSubmissionSamples[{index}] is not an object")
        if len(gpu_raw) != measured_gpu_command_buffers:
            errors.append(
                f"gpuSubmissionSamples count={len(gpu_raw)} does not equal measuredGpuCommandBuffers={measured_gpu_command_buffers}"
            )
        if len(gpu_ids) != len(gpu_raw):
            errors.append("gpuSubmissionSamples contains duplicate or incomplete encoder identities")
    else:
        errors.append("gpuSubmissionSamples is missing or is not an array")
    if set(row_ids) != gpu_ids:
        errors.append("nativeEncoderLedger identities do not exactly match gpuSubmissionSamples")

    if first_frame is not None and last_frame is not None:
        actual_frame_ids = sorted(frame_totals)
        expected_count = last_frame - first_frame
        if len(actual_frame_ids) != expected_count or any(
            value != first_frame + index for index, value in enumerate(actual_frame_ids)
        ):
            errors.append("nativeEncoderLedger frameId set does not cover the declared frame window")
    if len(frame_totals) != measured_frames:
        errors.append(
            f"nativeEncoderLedger unique frames={len(frame_totals)} does not equal measuredFrameIntervals={measured_frames}"
        )

    summary = report.get("nativeEncoderCountsPerMeasuredFrame")
    if not isinstance(summary, dict):
        errors.append("nativeEncoderCountsPerMeasuredFrame is missing or is not an object")
        return None, errors
    if summary.get("complete") is not True:
        errors.append("nativeEncoderCountsPerMeasuredFrame.complete is not true")
    if summary.get("status") != "complete-main-queue-native-encoders":
        errors.append(
            "nativeEncoderCountsPerMeasuredFrame.status is not complete-main-queue-native-encoders"
        )
    summary_frames = strict_integer(summary.get("measuredFrames"))
    if summary_frames != measured_frames:
        errors.append("nativeEncoderCountsPerMeasuredFrame.measuredFrames does not equal measuredFrameIntervals")
    frame_render = frame_blit = frame_compute = 0
    aggregate_overflow = False
    for row in rows:
        if isinstance(row, dict):
            for field in ("renderCreated", "blitCreated", "computeCreated"):
                value = strict_integer(row.get(field))
                if value is None:
                    continue
                if field == "renderCreated":
                    next_total = checked_nonnegative_add(frame_render, value)
                elif field == "blitCreated":
                    next_total = checked_nonnegative_add(frame_blit, value)
                else:
                    next_total = checked_nonnegative_add(frame_compute, value)
                if next_total is None:
                    aggregate_overflow = True
                    errors.append(f"nativeEncoderLedger {field} aggregate overflows signed Int64")
                elif field == "renderCreated":
                    frame_render = next_total
                elif field == "blitCreated":
                    frame_blit = next_total
                else:
                    frame_compute = next_total
    if aggregate_overflow:
        frame_render = frame_blit = frame_compute = -1
    for field, expected in (
        ("renderTotal", frame_render),
        ("blitTotal", frame_blit),
        ("computeTotal", frame_compute),
    ):
        actual = strict_integer(summary.get(field))
        if actual != expected:
            errors.append(f"{field}={actual} does not match ledger total={expected}")
    for field, total in (
        ("renderPerFrame", frame_render),
        ("blitPerFrame", frame_blit),
        ("computePerFrame", frame_compute),
    ):
        actual = finite_number(summary.get(field))
        expected = total / measured_frames if measured_frames > 0 else None
        if actual is None or expected is None or not math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-9):
            errors.append(f"{field} does not match the ledger mean")
    per_frame_totals = [frame_totals[frame_id] for frame_id in sorted(frame_totals)]
    recomputed_p50 = (
        sorted(per_frame_totals)[math.ceil(len(per_frame_totals) * 0.5) - 1]
        if per_frame_totals else None
    )
    reported_p50 = finite_number(summary.get("p50PerFrame"))
    if recomputed_p50 is None or reported_p50 is None or not math.isclose(
        reported_p50, recomputed_p50, rel_tol=1e-9, abs_tol=1e-9
    ):
        errors.append("p50PerFrame does not match the nearest-rank ledger median")
    return recomputed_p50, errors


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

    measured_frames = 0
    if report:
        measured_frames = strict_integer(report.get("measuredFrameIntervals")) or 0
    cpu = report.get("cpuRenderEncodeFrameMilliseconds", {}) if isinstance(report.get("cpuRenderEncodeFrameMilliseconds"), dict) else {}
    cpu_samples = strict_integer(cpu.get("samples")) or 0
    encoders = report.get("nativeEncoderCountsPerMeasuredFrame", {}) if isinstance(report.get("nativeEncoderCountsPerMeasuredFrame"), dict) else {}
    encoder_frames = strict_integer(encoders.get("measuredFrames")) or 0
    gpu_submission_count = strict_integer(report.get("measuredGpuCommandBuffers")) or 0
    measurement_window, window_errors = validate_measurement_window(
        report, measured_frames, gpu_submission_count
    )
    window_valid = measurement_window is not None and not window_errors
    completed_frames = (
        strict_integer(measurement_window.get("completedFrames"))
        if measurement_window is not None
        else None
    )
    gpu_identity_complete = (
        measurement_window is not None
        and measurement_window.get("gpuSubmissionIdentityComplete") is True
    )
    encoder_identity_complete = (
        measurement_window is not None
        and measurement_window.get("nativeEncoderIdentityComplete") is True
    )
    gpu_frames = strict_integer(report.get("measuredGpuFrames")) or 0
    gpu_submission_p50, gpu_sample_errors = validate_gpu_submission_samples(
        report,
        measurement_window,
        gpu_frames,
        gpu_submission_count,
    )
    native_encoder_p50, native_encoder_errors = validate_native_encoder_ledger(
        report,
        measurement_window,
        measured_frames,
        gpu_submission_count,
    )
    process_memory, process_memory_errors = validate_process_memory(
        report, measurement_window
    )
    reported_gpu_p50 = finite_number(report.get("gpuP50Milliseconds"))
    gpu_numeric_matches = (
        gpu_submission_p50 is not None
        and reported_gpu_p50 is not None
        and math.isclose(reported_gpu_p50, gpu_submission_p50, rel_tol=1e-9, abs_tol=1e-9)
    )
    cpu_window_matches = window_valid and completed_frames == measured_frames and cpu_samples == completed_frames
    gpu_window_matches = (
        window_valid
        and gpu_identity_complete
        and completed_frames == measured_frames
        and gpu_frames == completed_frames
        and gpu_submission_count >= completed_frames
        and not gpu_sample_errors
    )
    encoder_window_matches = (
        window_valid
        and encoder_identity_complete
        and completed_frames == measured_frames
        and encoder_frames == completed_frames
        and not native_encoder_errors
    )
    process_memory_matches = (
        process_memory is not None
        and not process_memory_errors
        and window_valid
        and completed_frames == measured_frames
    )
    unavailable = report.get("unavailableMetrics", {}) if isinstance(report.get("unavailableMetrics"), dict) else {}

    process_memory_definition = {
        "source": PROCESS_MEMORY_SOURCE,
        "scope": PROCESS_MEMORY_SCOPE,
        "unit": "bytes",
        "sampling_policy": PROCESS_MEMORY_SAMPLING_POLICY,
        "peak_kind": PROCESS_MEMORY_PEAK_KIND,
        "definition": (
            "peakResidentBytes is the maximum resident_size observed in the two "
            "frame-boundary samples per completed frame plus one final window-drain sample; "
            "it is a sampled maximum, not a continuous process peak"
        ),
    }
    process_memory_metric_reason = (
        None
        if process_memory_matches
        else (
            "processMemory is missing; sampled process RSS is unavailable"
            if "processMemory" not in report
            else "processMemory raw samples or derived summary failed strict validation"
        )
    )
    process_memory_sample_count = (
        len(process_memory.get("samples", []))
        if isinstance(process_memory, dict) and isinstance(process_memory.get("samples"), list)
        else 0
    )
    process_memory_metric = metric(
        process_memory.get("peakResidentBytes") if process_memory_matches else None,
        "bytes",
        "lower",
        process_memory_sample_count,
        process_memory_metric_reason,
    )
    process_memory_metric["source"] = dict(process_memory_definition)

    metrics = {
        "fps_median": metric(report.get("sourceFpsFromP50"), "FPS", "higher", measured_frames),
        "gpu_frame_time_ms_median": metric(
            report.get("gpuP50Milliseconds") if gpu_window_matches and gpu_numeric_matches else None,
            "ms", "lower", gpu_frames,
            None if gpu_window_matches and gpu_numeric_matches else (
                "GPU sample window lacks a valid measurementWindow or identified "
                f"GPU frames={gpu_frames}, submissions={gpu_submission_count}, "
                f"completed frames={completed_frames} are inconsistent"
            ) if not gpu_window_matches else (
                "reported gpuP50Milliseconds does not match the independently recomputed "
                f"per-frame GPU service-time median={gpu_submission_p50}ms"
            ),
        ),
        "cpu_render_encode_time_ms_median": metric(
            cpu.get("p50Milliseconds") if cpu_window_matches else None,
            "ms", "lower", cpu_samples,
            None if cpu_window_matches else (
                f"CPU sample window mismatch: cpu samples={cpu_samples}, measured frames={measured_frames}, "
                f"completed frames={completed_frames}; warmup and measurement data must not be mixed"
            ),
        ),
        "native_encoder_count_per_frame_median": metric(
            native_encoder_p50 if encoder_window_matches else None,
            "encoders/frame", "lower", encoder_frames,
            None if encoder_window_matches else (
                f"encoder sample window mismatch: encoder frames={encoder_frames}, "
                f"measured frames={measured_frames}, completed frames={completed_frames}; "
                "native encoder ledger identity and summary validation are required"
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
        "peak_resident_memory_bytes": process_memory_metric,
        "frame_time_stutter_count": metric(report.get("frameTimeStutterCount"), "events", "lower", measured_frames),
    }

    identity_errors: list[str] = []
    if measured_frames <= 0:
        identity_errors.append("measuredFrameIntervals is missing or zero")
    identity_errors.extend(window_errors)
    if measurement_window is not None and not gpu_identity_complete:
        identity_errors.append("measurementWindow.gpuSubmissionIdentityComplete is not true")
    if measurement_window is not None and not encoder_identity_complete:
        identity_errors.append("measurementWindow.nativeEncoderIdentityComplete is not true")
    if measured_frames > 0 and not cpu_window_matches:
        identity_errors.append(
            f"CPU samples={cpu_samples} do not match the completed measurement window={completed_frames}"
        )
    if measured_frames > 0 and not gpu_window_matches:
        identity_errors.append(
            f"GPU frames={gpu_frames} or submissions={gpu_submission_count} do not match "
            f"the identified measurement window={completed_frames}"
        )
    if measured_frames > 0 and gpu_window_matches and not gpu_numeric_matches:
        identity_errors.append(
            "reported gpuP50Milliseconds does not match gpuSubmissionSamples"
        )
    identity_errors.extend(f"GPU sample evidence: {error}" for error in gpu_sample_errors)
    identity_errors.extend(f"Native encoder ledger evidence: {error}" for error in native_encoder_errors)
    if process_memory_errors:
        identity_errors.extend(
            f"Process memory evidence: {error}" for error in process_memory_errors
        )
    if measured_frames > 0 and not encoder_window_matches:
        identity_errors.append(
            f"native encoder evidence is not identity-complete for measurement window={completed_frames}"
        )
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
        "measurement_window": measurement_window,
        "measurement_window_errors": window_errors,
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
            "frame_interval_p50_ms": report.get("frameIntervalP50Milliseconds"),
            "frame_interval_p95_ms": report.get("frameIntervalP95Milliseconds"),
            "gpu_p95_ms": report.get("gpuP95Milliseconds"),
            "gpu_submission_p50_ms_recomputed": gpu_submission_p50,
            "gpu_submission_sample_errors": gpu_sample_errors,
            "readback": report.get("nativeMainReadback"),
            "measurement_window": measurement_window,
            "measurement_window_errors": window_errors,
            "process_memory_errors": process_memory_errors,
            "process_memory_definition": process_memory_definition,
            "process_memory": {
                "present": "processMemory" in report,
                "sample_count": process_memory_sample_count,
                "validated": process_memory_matches,
            },
            "unavailable_metrics": unavailable,
        },
    }
    (trial_dir / "metrics.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temp:
        trial = Path(temp)
        (trial / "exit-status.txt").write_text("0\n", encoding="utf-8")
        process_memory_samples: list[dict[str, Any]] = []
        process_offset = 0
        process_sequence = 0
        for index in range(300):
            frame_id = 40 + index
            for phase in ("frame-begin", "frame-end"):
                begin = process_offset
                process_offset += 10
                process_memory_samples.append(
                    {
                        "sequence": process_sequence,
                        "windowId": 1,
                        "frameId": frame_id,
                        "phase": phase,
                        "beginOffsetNanos": begin,
                        "endOffsetNanos": process_offset,
                        "kernelStatus": 0,
                        "returnedWordCount": 38,
                        "residentBytes": 1 + index,
                        "physicalFootprintBytes": 2 + index,
                        "lifetimeResidentPeakBytes": 10000,
                    }
                )
                process_sequence += 1
        process_memory_samples.append(
            {
                "sequence": process_sequence,
                "windowId": 1,
                "frameId": 340,
                "phase": "window-drain",
                "beginOffsetNanos": process_offset,
                "endOffsetNanos": process_offset + 10,
                "kernelStatus": 0,
                "returnedWordCount": 38,
                "residentBytes": 300,
                "physicalFootprintBytes": 301,
                "lifetimeResidentPeakBytes": 10000,
            }
        )
        process_offset += 10
        report = {
            "mode": "native-metalfx-off",
            "measuredFrameIntervals": 300,
            "measuredGpuFrames": 300,
            "measuredGpuCommandBuffers": 300,
            "gpuSubmissionSamples": [
                {
                    "submitIndex": 100 + index,
                    "windowId": 1,
                    "frameId": 40 + index,
                    "gpuStartTime": float(index),
                    "gpuEndTime": float(index) + 0.020,
                }
                for index in range(300)
            ],
            "sourceFpsFromP50": 40.0,
            "gpuP50Milliseconds": 20.0,
            "frameTimeStutterCount": 2,
            "cpuRenderEncodeFrameMilliseconds": {"samples": 300, "p50Milliseconds": 24.0},
            "nativeEncoderCountsPerMeasuredFrame": {
                "status": "complete-main-queue-native-encoders",
                "complete": True,
                "measuredFrames": 300,
                "renderTotal": 1800,
                "blitTotal": 600,
                "computeTotal": 0,
                "renderPerFrame": 6.0,
                "blitPerFrame": 2.0,
                "computePerFrame": 0.0,
                "p50PerFrame": 8.0,
            },
            "nativeEncoderLedger": {
                "schemaVersion": 1,
                "enabled": True,
                "capacityRows": 300,
                "droppedRows": 0,
                "invalidEvents": 0,
                "activeCommandBuffers": 0,
                "activeEncoders": 0,
                "rowCount": 300,
                "scope": NATIVE_ENCODER_LEDGER_SCOPE,
                "rows": [
                    {
                        "windowId": 1,
                        "frameId": 40 + index,
                        "submitIndex": 100 + index,
                        "backend": 3 if index % 2 == 0 else 4,
                        "attempted": 8,
                        "created": 8,
                        "ended": 8,
                        "renderCreated": 6,
                        "blitCreated": 2,
                        "computeCreated": 0,
                        "createFailures": 0,
                        "unsupportedEncodes": 0,
                        "invalidEvents": 0,
                    }
                    for index in range(300)
                ],
            },
            "metalFxOffDiagnostics": {"modeOff": True, "allWorkEliminated": True},
            "measurementWindow": {
                "id": 1,
                "startFrameInclusive": 40,
                "endFrameExclusive": 340,
                "completedFrames": 300,
                "firstSubmitIndexInclusive": 100,
                "lastSubmitIndexExclusive": 400,
                "gpuSubmissionIdentityComplete": True,
                "nativeEncoderIdentityComplete": True,
            },
            "processMemory": {
                "schemaVersion": PROCESS_MEMORY_SCHEMA_VERSION,
                "source": PROCESS_MEMORY_SOURCE,
                "scope": PROCESS_MEMORY_SCOPE,
                "samplingPolicy": PROCESS_MEMORY_SAMPLING_POLICY,
                "peakKind": PROCESS_MEMORY_PEAK_KIND,
                "windowId": 1,
                "firstFrame": 40,
                "endFrameExclusive": 340,
                "capacitySamples": 601,
                "droppedSamples": 0,
                "failedSamples": 0,
                "invalidEvents": 0,
                "sampleCount": 601,
                "complete": True,
                "status": "complete-sampled-process-rss",
                "peakResidentBytes": 300,
                "peakPhysicalFootprintBytes": 301,
                "lifetimeResidentPeakBytesLast": 10000,
                "totalProbeNanos": process_offset,
                "maxProbeNanos": 10,
                "endOffsetNanos": process_offset,
                "samples": process_memory_samples,
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
        assert result["metrics"]["gpu_frame_time_ms_median"]["available"]
        assert result["metrics"]["native_encoder_count_per_frame_median"]["median"] == 8.0
        assert result["metrics"]["peak_resident_memory_bytes"]["available"]
        assert result["metrics"]["peak_resident_memory_bytes"]["median"] == 300

        legacy_report = dict(report)
        legacy_report.pop("measurementWindow")
        legacy = trial / "legacy" / "native-fullscreen-baseline.json"
        legacy.parent.mkdir(parents=True)
        # Keep the authoritative copy set homogeneous for this independent case.
        second.unlink()
        first.write_text(json.dumps(legacy_report, sort_keys=True), encoding="utf-8")
        legacy.write_text(json.dumps(legacy_report, sort_keys=True), encoding="utf-8")
        legacy_result = normalize(trial)
        assert not legacy_result["complete"]
        assert not legacy_result["metrics"]["cpu_render_encode_time_ms_median"]["available"]
        assert not legacy_result["metrics"]["gpu_frame_time_ms_median"]["available"]
        assert not legacy_result["metrics"]["native_encoder_count_per_frame_median"]["available"]

        forged_report = dict(report)
        forged_report["measurementWindow"] = dict(report["measurementWindow"])
        forged_report["measurementWindow"]["nativeEncoderIdentityComplete"] = False
        forged_report["nativeEncoderCountsPerMeasuredFrame"] = {
            "measuredFrames": 300,
            "renderPerFrame": 600.0,
            "blitPerFrame": 200.0,
        }
        first.write_text(json.dumps(forged_report, sort_keys=True), encoding="utf-8")
        legacy.unlink()
        forged = normalize(trial)
        assert not forged["complete"]
        assert not forged["metrics"]["native_encoder_count_per_frame_median"]["available"]
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
