#!/usr/bin/env python3
"""Validate raw native attachment facts against the bounded GPU window.

The validator accepts descriptor/action facts only as diagnostic raw facts.  It
does not estimate bytes, infer a pixel-format size, or claim semantic or GPU
performance completeness.  It deliberately iterates only arrays present in the
input; reported counts are never used to allocate a list or construct a range.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


I64_MAX = (1 << 63) - 1
MAX_ROWS = 65_536
ATTACHMENT_SCOPE = "main-queue-render-attachment-actions"
ENCODER_SCOPE = "main-queue-native-encoders"

ATTACHMENT_FIELDS = (
    "windowId", "frameId", "submitIndex", "backend", "encoderSequence", "aspect", "slot",
    "pixelFormat", "width", "height", "depth", "arrayLength", "textureType", "sampleCount",
    "storageMode", "level", "slice", "depthPlane", "renderTargetWidth", "renderTargetHeight",
    "renderTargetArrayLength", "loadAction", "initialStoreAction", "finalStoreAction",
    "resolvePixelFormat", "resolveWidth", "resolveHeight", "resolveDepth", "resolveArrayLength",
    "resolveTextureType", "resolveSampleCount", "resolveStorageMode", "resolveLevel", "resolveSlice",
    "resolveDepthPlane", "resolveFilter", "storeActionOptions", "ended", "errorBits", "reserved",
)
ENCODER_FIELDS = (
    "windowId", "frameId", "submitIndex", "backend", "attempted", "created", "ended",
    "renderCreated", "blitCreated", "computeCreated", "createFailures", "unsupportedEncodes",
    "invalidEvents",
)
SAMPLE_FIELDS = ("windowId", "frameId", "submitIndex")


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _nonnegative_i64(value: Any) -> bool:
    return _is_int(value) and 0 <= value <= I64_MAX


def _positive_i64(value: Any) -> bool:
    return _is_int(value) and 0 < value <= I64_MAX


def _boolean(value: Any) -> bool:
    return isinstance(value, bool)


def _obj(value: Any, label: str, errors: list[str]) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        errors.append(f"{label} must be an object")
        return None
    return value


def _field(obj: dict[str, Any], name: str, label: str, errors: list[str]) -> Any:
    if name not in obj:
        errors.append(f"{label}.{name} is missing")
        return None
    return obj[name]


def _check_i64_fields(obj: dict[str, Any], fields: tuple[str, ...], label: str, errors: list[str]) -> bool:
    valid = True
    for name in fields:
        value = _field(obj, name, label, errors)
        if not _nonnegative_i64(value):
            errors.append(f"{label}.{name} must be a non-negative signed Int64 JSON integer")
            valid = False
    return valid


def _check_measurement_window(report: dict[str, Any], errors: list[str]) -> dict[str, Any] | None:
    window = _obj(report.get("measurementWindow"), "measurementWindow", errors)
    if window is None:
        return None
    required = (
        "id", "startFrameInclusive", "endFrameExclusive", "completedFrames",
        "firstSubmitIndexInclusive", "lastSubmitIndexExclusive",
        "gpuSubmissionIdentityComplete", "nativeEncoderIdentityComplete",
    )
    for name in required:
        _field(window, name, "measurementWindow", errors)
    for name in required[:6]:
        if not _nonnegative_i64(window.get(name)):
            errors.append(f"measurementWindow.{name} must be a non-negative signed Int64 JSON integer")
    if not _positive_i64(window.get("id")):
        errors.append("measurementWindow.id must be positive")
    start = window.get("startFrameInclusive")
    end = window.get("endFrameExclusive")
    completed = window.get("completedFrames")
    first_submit = window.get("firstSubmitIndexInclusive")
    last_submit = window.get("lastSubmitIndexExclusive")
    if all(_nonnegative_i64(v) for v in (start, end, completed)):
        if end <= start or end - start != completed or completed == 0:
            errors.append("measurementWindow frame bounds do not equal positive completedFrames")
    if all(_nonnegative_i64(v) for v in (first_submit, last_submit)):
        if last_submit <= first_submit:
            errors.append("measurementWindow submit bounds must be increasing")
    if not _boolean(window.get("gpuSubmissionIdentityComplete")) or not window.get("gpuSubmissionIdentityComplete"):
        errors.append("measurementWindow.gpuSubmissionIdentityComplete must be true")
    if not _boolean(window.get("nativeEncoderIdentityComplete")) or not window.get("nativeEncoderIdentityComplete"):
        errors.append("measurementWindow.nativeEncoderIdentityComplete must be true")
    measured_commands = report.get("measuredGpuCommandBuffers")
    measured_frames = report.get("measuredGpuFrames")
    if not _nonnegative_i64(measured_commands):
        errors.append("measuredGpuCommandBuffers must be a non-negative signed Int64 JSON integer")
    if not _nonnegative_i64(measured_frames):
        errors.append("measuredGpuFrames must be a non-negative signed Int64 JSON integer")
    if _nonnegative_i64(measured_frames) and completed != measured_frames:
        errors.append("measuredGpuFrames must equal measurementWindow.completedFrames")
    if _nonnegative_i64(measured_commands) and _nonnegative_i64(first_submit) and _nonnegative_i64(last_submit):
        if last_submit - first_submit != measured_commands:
            errors.append("measurementWindow submit span must equal measuredGpuCommandBuffers")
    return window


def _check_samples(report: dict[str, Any], window: dict[str, Any], errors: list[str]) -> set[tuple[int, int, int]]:
    value = report.get("gpuSubmissionSamples")
    if not isinstance(value, list):
        errors.append("gpuSubmissionSamples must be an array")
        return set()
    if len(value) > MAX_ROWS:
        errors.append("gpuSubmissionSamples exceeds the bounded Int64 submission limit")
    expected_window = window.get("id")
    first = window.get("firstSubmitIndexInclusive")
    last = window.get("lastSubmitIndexExclusive")
    identities: set[tuple[int, int, int]] = set()
    for index, item in enumerate(value):
        row = _obj(item, f"gpuSubmissionSamples[{index}]", errors)
        if row is None:
            continue
        for name in SAMPLE_FIELDS:
            if not _nonnegative_i64(row.get(name)):
                errors.append(f"gpuSubmissionSamples[{index}].{name} must be a non-negative signed Int64 JSON integer")
        if not all(_nonnegative_i64(row.get(name)) for name in SAMPLE_FIELDS):
            continue
        key = (row.get("windowId"), row.get("frameId"), row.get("submitIndex"))
        if key in identities:
            errors.append(f"gpuSubmissionSamples[{index}] duplicates window/frame/submit identity")
        identities.add(key)
        if row.get("windowId") != expected_window:
            errors.append(f"gpuSubmissionSamples[{index}] has foreign windowId")
        if _nonnegative_i64(first) and _nonnegative_i64(last) and _nonnegative_i64(row.get("submitIndex")):
            if not first <= row["submitIndex"] < last:
                errors.append(f"gpuSubmissionSamples[{index}] submitIndex is outside measurement window")
        if _nonnegative_i64(window.get("startFrameInclusive")) and _nonnegative_i64(window.get("endFrameExclusive")):
            if not _nonnegative_i64(row.get("frameId")) or not window["startFrameInclusive"] <= row["frameId"] < window["endFrameExclusive"]:
                errors.append(f"gpuSubmissionSamples[{index}] frameId is outside measurement window")
    measured = report.get("measuredGpuCommandBuffers")
    if _nonnegative_i64(measured) and len(value) != measured:
        errors.append("gpuSubmissionSamples length must equal measuredGpuCommandBuffers")
    if _nonnegative_i64(first) and _nonnegative_i64(last):
        actual_submits = {key[2] for key in identities if _is_int(key[2])}
        contiguous = False
        if actual_submits and last > first and len(actual_submits) == last - first:
            ordered = sorted(actual_submits)
            contiguous = ordered[0] == first and ordered[-1] == last - 1 and all(
                right == left + 1 for left, right in zip(ordered, ordered[1:])
            )
        if not contiguous:
            errors.append("gpuSubmissionSamples submitIndex set does not exactly cover the measurement window")
        actual_frames = {key[1] for key in identities if _is_int(key[1])}
        frame_start = window.get("startFrameInclusive")
        frame_end = window.get("endFrameExclusive")
        frame_contiguous = False
        if actual_frames and _nonnegative_i64(frame_start) and _nonnegative_i64(frame_end) \
                and frame_end > frame_start and len(actual_frames) == frame_end - frame_start:
            ordered_frames = sorted(actual_frames)
            frame_contiguous = ordered_frames[0] == frame_start and ordered_frames[-1] == frame_end - 1 and all(
                right == left + 1 for left, right in zip(ordered_frames, ordered_frames[1:])
            )
        if not frame_contiguous:
            errors.append("gpuSubmissionSamples frameId set does not exactly cover the measurement window")
    return identities


def _check_encoder_ledger(report: dict[str, Any], window: dict[str, Any], sample_ids: set[tuple[int, int, int]], errors: list[str]) -> tuple[dict[tuple[int, int, int, int], dict[str, Any]], int]:
    ledger = _obj(report.get("nativeEncoderLedger"), "nativeEncoderLedger", errors)
    if ledger is None:
        return {}, 0
    if not _is_int(ledger.get("schemaVersion")) or ledger.get("schemaVersion") != 1:
        errors.append("nativeEncoderLedger.schemaVersion must be 1")
    if ledger.get("enabled") is not True:
        errors.append("nativeEncoderLedger.enabled must be true")
    if ledger.get("scope") != ENCODER_SCOPE:
        errors.append(f"nativeEncoderLedger.scope must be {ENCODER_SCOPE}")
    capacity = ledger.get("capacityRows")
    if not _is_int(capacity) or isinstance(capacity, bool) or not 1 <= capacity <= MAX_ROWS:
        errors.append("nativeEncoderLedger.capacityRows must be an integer in 1..65536")
    for name in ("droppedRows", "invalidEvents", "activeCommandBuffers", "activeEncoders", "rowCount"):
        if not _nonnegative_i64(ledger.get(name)):
            errors.append(f"nativeEncoderLedger.{name} must be a non-negative signed Int64 JSON integer")
        elif ledger[name] != 0 and name != "rowCount":
            errors.append(f"nativeEncoderLedger.{name} must be zero")
    rows = ledger.get("rows")
    if not isinstance(rows, list):
        errors.append("nativeEncoderLedger.rows must be an array")
        return {}, 0
    if len(rows) > MAX_ROWS:
        errors.append("nativeEncoderLedger.rows exceeds 65536")
    if _nonnegative_i64(ledger.get("rowCount")) and ledger["rowCount"] != len(rows):
        errors.append("nativeEncoderLedger.rowCount does not equal the actual rows array length")
    if _is_int(capacity) and len(rows) > capacity:
        errors.append("nativeEncoderLedger.rows exceeds capacityRows")
    by_identity: dict[tuple[int, int, int, int], dict[str, Any]] = {}
    render_total = 0
    for index, item in enumerate(rows):
        row = _obj(item, f"nativeEncoderLedger.rows[{index}]", errors)
        if row is None:
            continue
        _check_i64_fields(row, ENCODER_FIELDS, f"nativeEncoderLedger.rows[{index}]", errors)
        if not all(_nonnegative_i64(row.get(name)) for name in ("windowId", "frameId", "submitIndex", "backend")):
            continue
        key = (row.get("windowId"), row.get("frameId"), row.get("submitIndex"), row.get("backend"))
        if key in by_identity:
            errors.append(f"nativeEncoderLedger.rows[{index}] duplicates window/frame/submit identity")
        by_identity[key] = row
        if row.get("windowId") != window.get("id"):
            errors.append(f"nativeEncoderLedger.rows[{index}] has foreign windowId")
        if not (_nonnegative_i64(row.get("frameId")) and _nonnegative_i64(window.get("startFrameInclusive"))
                and _nonnegative_i64(window.get("endFrameExclusive"))
                and window["startFrameInclusive"] <= row["frameId"] < window["endFrameExclusive"]):
            errors.append(f"nativeEncoderLedger.rows[{index}] frameId is outside measurement window")
        if not (_nonnegative_i64(row.get("submitIndex")) and _nonnegative_i64(window.get("firstSubmitIndexInclusive"))
                and _nonnegative_i64(window.get("lastSubmitIndexExclusive"))
                and window["firstSubmitIndexInclusive"] <= row["submitIndex"] < window["lastSubmitIndexExclusive"]):
            errors.append(f"nativeEncoderLedger.rows[{index}] submitIndex is outside measurement window")
        if row.get("backend") not in (3, 4):
            errors.append(f"nativeEncoderLedger.rows[{index}].backend must be 3 or 4")
        if sample_ids and key[:3] not in sample_ids:
            errors.append(f"nativeEncoderLedger.rows[{index}] has no matching GPU submission sample")
        for name in ("attempted", "created", "ended"):
            if _nonnegative_i64(row.get(name)) and row[name] != row.get("attempted"):
                errors.append(f"nativeEncoderLedger.rows[{index}] attempted/created/ended mismatch")
        for name in ("createFailures", "unsupportedEncodes", "invalidEvents"):
            if row.get(name) != 0:
                errors.append(f"nativeEncoderLedger.rows[{index}].{name} must be zero")
        kinds = [row.get("renderCreated"), row.get("blitCreated"), row.get("computeCreated")]
        if all(_nonnegative_i64(value) for value in kinds):
            total = sum(kinds)
            if total > I64_MAX:
                errors.append(f"nativeEncoderLedger.rows[{index}] kind total overflows signed Int64")
            elif total != row.get("created"):
                errors.append(f"nativeEncoderLedger.rows[{index}] created does not equal encoder kind total")
            render_total += row.get("renderCreated", 0)
            if render_total > I64_MAX:
                errors.append("nativeEncoderLedger renderCreated aggregate overflows signed Int64")
    expected_commands = report.get("measuredGpuCommandBuffers")
    if _nonnegative_i64(expected_commands) and len(rows) != expected_commands:
        errors.append("nativeEncoderLedger row count must equal measuredGpuCommandBuffers")
    if sample_ids and {key[:3] for key in by_identity} != sample_ids:
        errors.append("nativeEncoderLedger identities do not exactly match gpuSubmissionSamples")
    return by_identity, render_total


def _validate_attachment_row(row: dict[str, Any], label: str, window: dict[str, Any], errors: list[str]) -> bool:
    # ``aspect=-1`` is the descriptor marker; every other ABI word is a
    # non-negative signed Int64 fact.
    _check_i64_fields(row, tuple(name for name in ATTACHMENT_FIELDS if name != "aspect"), label, errors)
    aspect = row.get("aspect")
    if not _is_int(aspect) or aspect not in (-1, 0, 1, 2):
        errors.append(f"{label}.aspect must be -1, 0, 1, or 2")
    if row.get("windowId") != window.get("id"):
        errors.append(f"{label} has foreign windowId")
    if not (_nonnegative_i64(row.get("frameId")) and _nonnegative_i64(window.get("startFrameInclusive"))
            and _nonnegative_i64(window.get("endFrameExclusive"))
            and window["startFrameInclusive"] <= row["frameId"] < window["endFrameExclusive"]):
        errors.append(f"{label}.frameId is outside measurementWindow")
    if not (_nonnegative_i64(row.get("submitIndex")) and _nonnegative_i64(window.get("firstSubmitIndexInclusive"))
            and _nonnegative_i64(window.get("lastSubmitIndexExclusive"))
            and window["firstSubmitIndexInclusive"] <= row["submitIndex"] < window["lastSubmitIndexExclusive"]):
        errors.append(f"{label}.submitIndex is outside measurementWindow")
    if row.get("backend") not in (3, 4):
        errors.append(f"{label}.backend must be 3 or 4")
    if not _positive_i64(row.get("encoderSequence")):
        errors.append(f"{label}.encoderSequence must be a positive signed Int64")
    if row.get("ended") != 1:
        errors.append(f"{label}.ended must be 1")
    if row.get("errorBits") != 0:
        errors.append(f"{label}.errorBits must be zero")
    if row.get("reserved") != 0:
        errors.append(f"{label}.reserved must be zero")
    identity_fields_valid = all(_nonnegative_i64(row.get(name)) for name in ("windowId", "frameId", "submitIndex", "backend"))
    slot_valid = _nonnegative_i64(row.get("slot"))
    sequence_valid = _positive_i64(row.get("encoderSequence"))
    return identity_fields_valid and slot_valid and sequence_valid and _is_int(aspect) and aspect in (-1, 0, 1, 2)


def _check_attachment_ledger(
    report: dict[str, Any],
    window: dict[str, Any],
    encoder_rows: dict[tuple[int, int, int, int], dict[str, Any]],
    render_total: int,
    errors: list[str],
) -> tuple[int, int]:
    ledger = _obj(report.get("nativeAttachmentLedger"), "nativeAttachmentLedger", errors)
    if ledger is None:
        return 0, 0
    if not _is_int(ledger.get("schemaVersion")) or ledger.get("schemaVersion") != 1:
        errors.append("nativeAttachmentLedger.schemaVersion must be 1")
    if ledger.get("enabled") is not True:
        errors.append("nativeAttachmentLedger.enabled must be true")
    if ledger.get("scope") != ATTACHMENT_SCOPE:
        errors.append(f"nativeAttachmentLedger.scope must be {ATTACHMENT_SCOPE}")
    capacity = ledger.get("capacityRows")
    if not _is_int(capacity) or isinstance(capacity, bool) or not 1 <= capacity <= MAX_ROWS:
        errors.append("nativeAttachmentLedger.capacityRows must be an integer in 1..65536")
    for name in ("droppedRows", "invalidEvents", "activeRenderEncoders", "createdRenderEncoders", "rowCount"):
        if not _nonnegative_i64(ledger.get(name)):
            errors.append(f"nativeAttachmentLedger.{name} must be a non-negative signed Int64 JSON integer")
    for name in ("droppedRows", "invalidEvents", "activeRenderEncoders"):
        if ledger.get(name) != 0:
            errors.append(f"nativeAttachmentLedger.{name} must be zero")
    rows = ledger.get("rows")
    if not isinstance(rows, list):
        errors.append("nativeAttachmentLedger.rows must be an array")
        return 0, 0
    if len(rows) > MAX_ROWS:
        errors.append("nativeAttachmentLedger.rows exceeds 65536")
    if _nonnegative_i64(ledger.get("rowCount")) and ledger["rowCount"] != len(rows):
        errors.append("nativeAttachmentLedger.rowCount does not equal actual rows array length")
    if _is_int(capacity) and len(rows) > capacity:
        errors.append("nativeAttachmentLedger.rows exceeds capacityRows")
    sequences: dict[int, list[dict[str, Any]]] = {}
    identity_marker_count: dict[tuple[int, int, int, int], int] = {}
    seen_child_slots: set[tuple[int, int, int, int, int, int]] = set()
    for index, item in enumerate(rows):
        row = _obj(item, f"nativeAttachmentLedger.rows[{index}]", errors)
        if row is None:
            continue
        row_valid = _validate_attachment_row(row, f"nativeAttachmentLedger.rows[{index}]", window, errors)
        if not row_valid:
            continue
        sequence = row.get("encoderSequence")
        sequences.setdefault(sequence, []).append(row)
        key = (row.get("windowId"), row.get("frameId"), row.get("submitIndex"), row.get("backend"))
        if row.get("aspect") == -1:
            identity_marker_count[key] = identity_marker_count.get(key, 0) + 1
        else:
            child_key = (sequence, row.get("aspect"), row.get("slot"), row.get("frameId"), row.get("submitIndex"), row.get("backend"))
            if child_key in seen_child_slots:
                errors.append(f"nativeAttachmentLedger.rows[{index}] duplicates encoder/aspect/slot")
            seen_child_slots.add(child_key)
    marker_count = 0
    child_count = 0
    for sequence, group in sequences.items():
        markers = [row for row in group if row.get("aspect") == -1]
        children = [row for row in group if row.get("aspect") != -1]
        if len(markers) != 1:
            errors.append(f"encoderSequence {sequence} must have exactly one marker")
            continue
        marker_count += 1
        marker = markers[0]
        marker_identity = tuple(marker.get(name) for name in ("windowId", "frameId", "submitIndex", "backend"))
        for fact in group:
            fact_identity = tuple(fact.get(name) for name in ("windowId", "frameId", "submitIndex", "backend"))
            if fact_identity != marker_identity:
                errors.append(f"encoderSequence {sequence} has a child with mismatched window/frame/submit/backend")
        mask = marker.get("slot")
        if not _is_int(mask) or not 0 <= mask <= 0x3FF:
            errors.append(f"encoderSequence {sequence} marker slot must be an attachment mask in 0..0x3ff")
            mask = -1
        expected_children: set[tuple[int, int]] = set()
        if _is_int(mask):
            expected_children = {(0, bit) for bit in range(8) if mask & (1 << bit)}
            if mask & (1 << 8):
                expected_children.add((1, 0))
            if mask & (1 << 9):
                expected_children.add((2, 0))
        actual_children: set[tuple[int, int]] = set()
        for child in children:
            child_count += 1
            aspect, slot = child.get("aspect"), child.get("slot")
            if aspect == 0 and _is_int(slot) and 0 <= slot <= 7:
                pair = (0, slot)
            elif aspect in (1, 2) and slot == 0:
                pair = (aspect, 0)
            else:
                errors.append(f"encoderSequence {sequence} has illegal child aspect/slot")
                continue
            if pair in actual_children:
                errors.append(f"encoderSequence {sequence} has duplicate child aspect/slot")
            actual_children.add(pair)
            if _is_int(mask) and pair not in expected_children:
                errors.append(f"encoderSequence {sequence} child is absent from marker attachment mask")
            if child.get("loadAction") not in (0, 1, 2):
                errors.append(f"encoderSequence {sequence} child loadAction is not a legal Metal load action")
            if child.get("initialStoreAction") not in (0, 1, 2, 3, 4, 5):
                errors.append(f"encoderSequence {sequence} child initialStoreAction is not a legal Metal store action")
            if child.get("finalStoreAction") not in (0, 1, 2, 3, 5):
                errors.append(f"encoderSequence {sequence} child finalStoreAction is unknown or illegal")
        if actual_children != expected_children:
            errors.append(f"encoderSequence {sequence} marker mask does not exactly match child rows")
        # Marker-only descriptors are valid: all descriptor fact fields except
        # identity/mask/target dimensions/end/error/reserved must be zero.
        for name in ATTACHMENT_FIELDS[7:18] + ATTACHMENT_FIELDS[21:37]:
            if marker.get(name) != 0:
                errors.append(f"encoderSequence {sequence} marker field {name} must be zero")
    if _nonnegative_i64(ledger.get("createdRenderEncoders")) and ledger["createdRenderEncoders"] != marker_count:
        errors.append("nativeAttachmentLedger.createdRenderEncoders must equal marker count")
    if render_total != marker_count:
        errors.append("attachment marker count must equal nativeEncoderLedger renderCreated total, including zero-render command buffers")
    for key, count in identity_marker_count.items():
        encoder = encoder_rows.get(key)
        if encoder is None:
            errors.append(f"attachment marker identity {key} has no native encoder ledger row")
        elif count != encoder.get("renderCreated"):
            errors.append(f"attachment markers for {key} do not equal that command buffer renderCreated")
    for key, encoder in encoder_rows.items():
        marker_count_for_key = identity_marker_count.get(key, 0)
        if marker_count_for_key != encoder.get("renderCreated"):
            errors.append(f"native encoder identity {key} has renderCreated without matching attachment markers")
    return marker_count, child_count


def validate_report(report: Any) -> tuple[dict[str, Any], list[str]]:
    errors: list[str] = []
    root = _obj(report, "report", errors)
    if root is None:
        return {"status": "invalid", "errors": errors}, errors
    window = _check_measurement_window(root, errors)
    if window is None:
        return {"status": "invalid", "errors": errors}, errors
    sample_ids = _check_samples(root, window, errors)
    encoder_rows, render_total = _check_encoder_ledger(root, window, sample_ids, errors)
    marker_count, child_count = _check_attachment_ledger(root, window, encoder_rows, render_total, errors)
    summary = {
        "status": "pass" if not errors else "invalid",
        "acceptance": "diagnostic-integrity-raw-only" if not errors else "rejected-raw-facts",
        "scope": {
            "kind": "native attachment descriptor and final store action facts",
            "attachmentLedger": ATTACHMENT_SCOPE,
            "encoderLedger": ENCODER_SCOPE,
            "rawOnly": True,
            "byteEstimates": False,
            "semanticCompleteness": False,
        },
        "measurementWindow": {
            name: window.get(name)
            for name in ("id", "startFrameInclusive", "endFrameExclusive", "completedFrames",
                         "firstSubmitIndexInclusive", "lastSubmitIndexExclusive")
        },
        "rawSummary": {
            "gpuSubmissionSamples": len(root.get("gpuSubmissionSamples", [])) if isinstance(root.get("gpuSubmissionSamples"), list) else None,
            "nativeEncoderRows": len(root.get("nativeEncoderLedger", {}).get("rows", [])) if isinstance(root.get("nativeEncoderLedger"), dict) and isinstance(root["nativeEncoderLedger"].get("rows"), list) else None,
            "nativeEncoderRenderCreatedTotal": render_total,
            "nativeAttachmentRows": len(root.get("nativeAttachmentLedger", {}).get("rows", [])) if isinstance(root.get("nativeAttachmentLedger"), dict) and isinstance(root["nativeAttachmentLedger"].get("rows"), list) else None,
            "nativeAttachmentMarkerCount": marker_count,
            "nativeAttachmentChildRowCount": child_count,
        },
        "errors": errors,
    }
    return summary, errors


def _fixture() -> dict[str, Any]:
    window = {
        "id": 7, "startFrameInclusive": 100, "endFrameExclusive": 102, "completedFrames": 2,
        "firstSubmitIndexInclusive": 10, "lastSubmitIndexExclusive": 13,
        "gpuSubmissionIdentityComplete": True, "nativeEncoderIdentityComplete": True,
    }
    samples = [
        {"windowId": 7, "frameId": 100, "submitIndex": 10},
        {"windowId": 7, "frameId": 100, "submitIndex": 11},
        {"windowId": 7, "frameId": 101, "submitIndex": 12},
    ]
    encoder_rows = []
    for frame, submit, render in ((100, 10, 2), (100, 11, 0), (101, 12, 1)):
        encoder_rows.append({
            "windowId": 7, "frameId": frame, "submitIndex": submit, "backend": 3,
            "attempted": 2 if render else 1, "created": 2 if render else 1, "ended": 2 if render else 1,
            "renderCreated": render, "blitCreated": 2 - render if render else 1,
            "computeCreated": 0, "createFailures": 0, "unsupportedEncodes": 0, "invalidEvents": 0,
        })
    def row(seq: int, frame: int, submit: int, aspect: int, slot: int, mask: int, ended: int = 1, final: int = 1) -> dict[str, Any]:
        values = {name: 0 for name in ATTACHMENT_FIELDS}
        values.update(windowId=7, frameId=frame, submitIndex=submit, backend=3,
                      encoderSequence=seq, aspect=aspect, slot=mask if aspect == -1 else slot,
                      renderTargetWidth=1, renderTargetHeight=1, renderTargetArrayLength=1,
                      ended=ended, errorBits=0, reserved=0)
        if aspect != -1:
            values.update(pixelFormat=70, width=1, height=1, depth=1, arrayLength=1,
                          textureType=2, sampleCount=1, storageMode=2, loadAction=2,
                          initialStoreAction=4, finalStoreAction=final)
        return values
    attachment_rows = [
        row(1, 100, 10, -1, 0, 0x101), row(1, 100, 10, 0, 0, 0), row(1, 100, 10, 1, 0, 0),
        row(2, 100, 10, -1, 0, 0x1), row(2, 100, 10, 0, 0, 0),
        row(3, 101, 12, -1, 0, 0x1), row(3, 101, 12, 0, 0, 0),
    ]
    return {
        "measuredGpuCommandBuffers": 3, "measuredGpuFrames": 2, "measurementWindow": window,
        "gpuSubmissionSamples": samples,
        "nativeEncoderLedger": {
            "schemaVersion": 1, "enabled": True, "capacityRows": 64, "droppedRows": 0,
            "invalidEvents": 0, "activeCommandBuffers": 0, "activeEncoders": 0, "rowCount": 3,
            "scope": ENCODER_SCOPE, "rows": encoder_rows,
        },
        "nativeAttachmentLedger": {
            "schemaVersion": 1, "enabled": True, "capacityRows": 64, "droppedRows": 0,
            "invalidEvents": 0, "activeRenderEncoders": 0, "createdRenderEncoders": 3,
            "rowCount": len(attachment_rows), "scope": ATTACHMENT_SCOPE, "rows": attachment_rows,
        },
    }


def run_self_test() -> None:
    base = _fixture()
    summary, errors = validate_report(base)
    if errors:
        raise AssertionError("valid fixture rejected: " + "; ".join(errors))
    mutations: list[tuple[str, Any]] = []
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"].pop(1); mutation["nativeAttachmentLedger"]["rowCount"] = len(mutation["nativeAttachmentLedger"]["rows"]); mutations.append(("missing attachment row with forged rowCount", mutation))
    mutation = copy.deepcopy(base); mutation["nativeEncoderLedger"]["rows"].pop(); mutation["nativeEncoderLedger"]["rowCount"] = 2; mutations.append(("missing encoder row", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"] = mutation["nativeAttachmentLedger"]["rows"][:5]; mutation["nativeAttachmentLedger"]["rowCount"] = 5; mutation["nativeAttachmentLedger"]["createdRenderEncoders"] = 2; mutations.append(("missing whole attachment encoder group", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][1]["frameId"] = 101; mutations.append(("cross-frame child", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][3]["encoderSequence"] = 1; mutations.append(("duplicate marker sequence", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"].append(copy.deepcopy(mutation["nativeAttachmentLedger"]["rows"][1])); mutation["nativeAttachmentLedger"]["rowCount"] += 1; mutations.append(("duplicate child", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][0]["slot"] = 0x401; mutations.append(("wrong attachment mask", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["enabled"] = False; mutations.append(("disabled header", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][2]["ended"] = 0; mutations.append(("unfinished encoder", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][2]["finalStoreAction"] = 4; mutations.append(("unknown final store", mutation))
    mutation = copy.deepcopy(base); del mutation["nativeEncoderLedger"]; mutations.append(("missing native counter", mutation))
    mutation = copy.deepcopy(base); mutation["measurementWindow"]["id"] = I64_MAX + 1; mutations.append(("Int64 overflow", mutation))
    mutation = copy.deepcopy(base); mutation["nativeEncoderLedger"]["rows"][0]["backend"] = 5; mutations.append(("invalid backend", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][0]["backend"] = 4; mutations.append(("marker backend mismatch", mutation))
    mutation = copy.deepcopy(base); mutation["nativeAttachmentLedger"]["rows"][0]["slot"] = True; mutations.append(("boolean mask", mutation))
    mutation = copy.deepcopy(base); mutation["nativeEncoderLedger"]["schemaVersion"] = True; mutations.append(("boolean schema version", mutation))
    mutation = copy.deepcopy(base); mutation["gpuSubmissionSamples"][2]["frameId"] = 100; mutations.append(("missing observed frame", mutation))
    for label, mutated in mutations:
        _, failures = validate_report(mutated)
        if not failures:
            raise AssertionError(f"malicious mutation unexpectedly accepted: {label}")
    print(f"Native attachment facts self-test: PASS ({len(mutations)} rejecting mutations)")


def _write_summary(summary: dict[str, Any], output: str | None) -> None:
    if output:
        Path(output).write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    else:
        json.dump(summary, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", help="baseline report JSON")
    parser.add_argument("--output", help="write the raw-only summary JSON to this path")
    parser.add_argument("--self-test", action="store_true", help="run the bounded hostile fixture tests")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
        return 0
    if not args.input:
        parser.error("input baseline JSON is required unless --self-test is used")
    try:
        report = json.loads(Path(args.input).read_text())
    except Exception as exc:  # malformed input is a rejected raw receipt
        summary = {
            "status": "invalid", "acceptance": "rejected-raw-facts",
            "scope": {"rawOnly": True, "byteEstimates": False, "semanticCompleteness": False},
            "errors": [f"could not read/parse input JSON: {exc}"],
        }
        _write_summary(summary, args.output)
        return 2
    try:
        summary, errors = validate_report(report)
    except Exception as exc:  # malformed nested shapes must be a bounded rejection, never a traceback acceptance
        summary = {
            "status": "invalid", "acceptance": "rejected-raw-facts",
            "scope": {"rawOnly": True, "byteEstimates": False, "semanticCompleteness": False},
            "errors": [f"malformed raw facts rejected: {type(exc).__name__}: {exc}"],
        }
        errors = summary["errors"]
    _write_summary(summary, args.output)
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
