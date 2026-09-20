#!/usr/bin/env python3
"""Validate the renderer-owned live-resource allocation snapshot.

This is a diagnostic integrity checker for the renderer's own allocation
registry.  Its total is the sum of the live rows in that registry; it is not a
resident-memory, residency, window-peak, or physical-DRAM measurement.
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
SCOPE = "module-created-live-metal-resources"
SNAPSHOT_PHASE = "after-final-gpu-drain"
ROW_FIELDS = (
    "resourceId",
    "kind",
    "allocatedBytes",
    "storageMode",
    "memoryless",
    "reserved0",
    "reserved1",
    "reserved2",
)


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _i64(value: Any) -> bool:
    return _is_int(value) and 0 <= value <= I64_MAX


def _field(obj: dict[str, Any], name: str, label: str, errors: list[str]) -> Any:
    if name not in obj:
        errors.append(f"{label}.{name} is missing")
        return None
    return obj[name]


def _check_measurement_window(report: dict[str, Any], errors: list[str]) -> tuple[int | None, int | None]:
    raw = report.get("measurementWindow")
    if not isinstance(raw, dict):
        errors.append("measurementWindow is missing or is not an object")
        return None, None
    window_id = _field(raw, "id", "measurementWindow", errors)
    end_frame = _field(raw, "endFrameExclusive", "measurementWindow", errors)
    if not _i64(window_id) or window_id <= 0:
        errors.append("measurementWindow.id must be a positive signed Int64 JSON integer")
        window_id = None
    if not _i64(end_frame) or end_frame <= 0:
        errors.append("measurementWindow.endFrameExclusive must be a positive signed Int64 JSON integer")
        end_frame = None
    return window_id, end_frame


def validate_renderer_owned_allocations(report: Any) -> tuple[dict[str, Any] | None, list[str]]:
    """Return a diagnostic-only summary, or ``(None, [])`` when absent.

    A present field is never silently downgraded: malformed or incomplete
    snapshots return errors and no accepted total.
    """
    if not isinstance(report, dict):
        return None, ["report must be an object"]
    if "rendererOwnedAllocations" not in report:
        return None, []

    raw = report.get("rendererOwnedAllocations")
    errors: list[str] = []
    if not isinstance(raw, dict):
        return None, ["rendererOwnedAllocations is present but is not an object"]

    window_id, end_frame = _check_measurement_window(report, errors)
    snapshot_window = _field(raw, "snapshotWindowId", "rendererOwnedAllocations", errors)
    snapshot_end = _field(raw, "snapshotEndFrameExclusive", "rendererOwnedAllocations", errors)
    snapshot_phase = _field(raw, "snapshotPhase", "rendererOwnedAllocations", errors)
    if not _i64(snapshot_window) or snapshot_window <= 0:
        errors.append("rendererOwnedAllocations.snapshotWindowId must be a positive signed Int64 JSON integer")
    elif window_id is not None and snapshot_window != window_id:
        errors.append("rendererOwnedAllocations.snapshotWindowId does not match measurementWindow.id")
    if not _i64(snapshot_end) or snapshot_end <= 0:
        errors.append("rendererOwnedAllocations.snapshotEndFrameExclusive must be a positive signed Int64 JSON integer")
    elif end_frame is not None and snapshot_end != end_frame:
        errors.append("rendererOwnedAllocations.snapshotEndFrameExclusive does not match measurementWindow.endFrameExclusive")
    if snapshot_phase != SNAPSHOT_PHASE:
        errors.append(f"rendererOwnedAllocations.snapshotPhase must be {SNAPSHOT_PHASE}")

    if raw.get("schemaVersion") != 1 or not _is_int(raw.get("schemaVersion")):
        errors.append("rendererOwnedAllocations.schemaVersion must be 1")
    if raw.get("scope") != SCOPE:
        errors.append(f"rendererOwnedAllocations.scope must be {SCOPE}")
    if raw.get("enabled") is not True:
        errors.append("rendererOwnedAllocations.enabled must be true")

    capacity = _field(raw, "capacityRows", "rendererOwnedAllocations", errors)
    if not _i64(capacity) or capacity != MAX_ROWS:
        errors.append("rendererOwnedAllocations.capacityRows must be exactly 65536")

    counters: dict[str, int | None] = {}
    for name in (
        "droppedRows",
        "invalidEvents",
        "createdResources",
        "totalAllocatedBytes",
        "liveRowCount",
    ):
        value = _field(raw, name, "rendererOwnedAllocations", errors)
        counters[name] = value if _i64(value) else None
        if not _i64(value):
            errors.append(f"rendererOwnedAllocations.{name} must be a non-negative signed Int64 JSON integer")
        elif name in ("droppedRows", "invalidEvents") and value != 0:
            errors.append(f"rendererOwnedAllocations.{name} must be zero")

    rows = raw.get("rows")
    if not isinstance(rows, list):
        errors.append("rendererOwnedAllocations.rows must be an array")
        rows = []
    if len(rows) > MAX_ROWS:
        errors.append("rendererOwnedAllocations.rows exceeds capacityRows")
    if counters["liveRowCount"] is not None and counters["liveRowCount"] != len(rows):
        errors.append("rendererOwnedAllocations.liveRowCount does not equal rows length")
    if counters["liveRowCount"] is not None and counters["createdResources"] is not None \
            and counters["createdResources"] < counters["liveRowCount"]:
        errors.append("rendererOwnedAllocations.createdResources must be at least liveRowCount")

    seen_ids: set[int] = set()
    total = 0
    total_valid = True
    for index, item in enumerate(rows):
        label = f"rendererOwnedAllocations.rows[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{label} must be an object")
            total_valid = False
            continue
        values = {name: _field(item, name, label, errors) for name in ROW_FIELDS}
        valid_row = True
        for name in ("resourceId", "kind", "allocatedBytes", "storageMode", "reserved0", "reserved1", "reserved2"):
            if not _i64(values[name]):
                errors.append(f"{label}.{name} must be a non-negative signed Int64 JSON integer")
                valid_row = False
        if not isinstance(values["memoryless"], bool):
            errors.append(f"{label}.memoryless must be boolean")
            valid_row = False
        if _i64(values["resourceId"]):
            resource_id = values["resourceId"]
            if resource_id in seen_ids:
                errors.append(f"{label}.resourceId duplicates another live row")
                valid_row = False
            seen_ids.add(resource_id)
            if counters["createdResources"] is not None and resource_id > counters["createdResources"]:
                errors.append(f"{label}.resourceId exceeds createdResources")
                valid_row = False
        if _i64(values["kind"]) and values["kind"] not in (0, 1, 2):
            errors.append(f"{label}.kind must be 0(buffer), 1(texture), or 2(ICB)")
            valid_row = False
        if _i64(values["storageMode"]) and values["storageMode"] not in (0, 1, 2, 3):
            errors.append(f"{label}.storageMode must be in 0..3")
            valid_row = False
        for name in ("reserved0", "reserved1", "reserved2"):
            if _i64(values[name]) and values[name] != 0:
                errors.append(f"{label}.{name} must be zero")
                valid_row = False
        memoryless = values["memoryless"] is True
        if memoryless:
            if values["kind"] != 1:
                errors.append(f"{label}.memoryless requires texture kind 1")
                valid_row = False
            if values["storageMode"] != 3:
                errors.append(f"{label}.memoryless requires storageMode 3")
                valid_row = False
            if values["allocatedBytes"] != 0:
                errors.append(f"{label}.memoryless requires allocatedBytes 0")
                valid_row = False
        elif values["storageMode"] == 3:
            errors.append(f"{label}.non-memoryless resource cannot use storageMode 3")
            valid_row = False
        if valid_row and _i64(values["allocatedBytes"]):
            if total > I64_MAX - values["allocatedBytes"]:
                errors.append("rendererOwnedAllocations row byte sum overflows signed Int64")
                total_valid = False
            else:
                total += values["allocatedBytes"]
        else:
            total_valid = False

    if total_valid and counters["totalAllocatedBytes"] is not None and total != counters["totalAllocatedBytes"]:
        errors.append("rendererOwnedAllocations.totalAllocatedBytes does not equal live row sum")
    if errors:
        return None, errors
    summary = {
        # This is deliberately not a resident/peak acceptance metric.  Keep
        # the diagnostic-only boundary visible in the primary status field.
        "status": "diagnostic-only",
        "validation": "pass",
        "acceptance": "diagnostic-only",
        "scope": {
            "kind": "module-created-live-metal-resources",
            "ownedAllocationFootprint": True,
            "residentMetric": False,
            "windowPeak": False,
            "physicalDRAM": False,
        },
        "measurementWindow": {
            "id": window_id,
            "endFrameExclusive": end_frame,
        },
        "snapshot": {
            "snapshotWindowId": snapshot_window,
            "snapshotEndFrameExclusive": snapshot_end,
            "snapshotPhase": snapshot_phase,
            "capacityRows": capacity,
            "createdResources": counters["createdResources"],
            "liveRowCount": counters["liveRowCount"],
            "totalAllocatedBytes": total,
        },
        "errors": [],
    }
    return summary, []


# A short compatibility alias for callers that use the other verifier names.
validate_report = validate_renderer_owned_allocations


def _fixture() -> dict[str, Any]:
    return {
        "measurementWindow": {
            "id": 19,
            "startFrameInclusive": 20,
            "endFrameExclusive": 24,
            "completedFrames": 4,
        },
        "rendererOwnedAllocations": {
            "schemaVersion": 1,
            "scope": SCOPE,
            "enabled": True,
            "capacityRows": MAX_ROWS,
            "droppedRows": 0,
            "invalidEvents": 0,
            "createdResources": 4,
            "totalAllocatedBytes": 1024,
            "liveRowCount": 3,
            "snapshotWindowId": 19,
            "snapshotEndFrameExclusive": 24,
            "snapshotPhase": SNAPSHOT_PHASE,
            "rows": [
                {"resourceId": 1, "kind": 0, "allocatedBytes": 512, "storageMode": 2, "memoryless": False, "reserved0": 0, "reserved1": 0, "reserved2": 0},
                {"resourceId": 2, "kind": 1, "allocatedBytes": 512, "storageMode": 2, "memoryless": False, "reserved0": 0, "reserved1": 0, "reserved2": 0},
                {"resourceId": 3, "kind": 1, "allocatedBytes": 0, "storageMode": 3, "memoryless": True, "reserved0": 0, "reserved1": 0, "reserved2": 0},
            ],
        },
    }


def run_self_test() -> None:
    base = _fixture()
    summary, errors = validate_renderer_owned_allocations(base)
    if errors or summary is None or summary["snapshot"]["totalAllocatedBytes"] != 1024:
        raise AssertionError(f"valid fixture rejected: {errors}")
    missing = copy.deepcopy(base)
    del missing["rendererOwnedAllocations"]
    summary, errors = validate_renderer_owned_allocations(missing)
    if summary is not None or errors:
        raise AssertionError("missing optional field must be unavailable without error")
    mutations: list[tuple[str, dict[str, Any]]] = []
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["snapshotWindowId"] = 20; mutations.append(("window mismatch", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["snapshotPhase"] = "before-final-gpu-drain"; mutations.append(("wrong phase", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][1]["resourceId"] = 1; mutations.append(("duplicate resource id", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["totalAllocatedBytes"] = 1; mutations.append(("forged total", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["createdResources"] = 2; mutations.append(("created less than live", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][2]["allocatedBytes"] = 1; mutations.append(("memoryless bytes", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][2]["kind"] = 0; mutations.append(("memoryless buffer", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][0]["storageMode"] = 3; mutations.append(("ordinary storage mode", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][0]["reserved1"] = 1; mutations.append(("reserved word", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["droppedRows"] = 1; mutations.append(("dropped rows", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["enabled"] = False; mutations.append(("disabled snapshot", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][0]["allocatedBytes"] = I64_MAX; mutations.append(("sum overflow/mismatch", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"][0]["resourceId"] = True; mutations.append(("boolean id", mutation))
    mutation = copy.deepcopy(base); mutation["rendererOwnedAllocations"]["rows"] = [copy.deepcopy(base["rendererOwnedAllocations"]["rows"][0])] * (MAX_ROWS + 1); mutation["rendererOwnedAllocations"]["liveRowCount"] = MAX_ROWS + 1; mutations.append(("row capacity", mutation))
    for label, mutated in mutations:
        _, failures = validate_renderer_owned_allocations(mutated)
        if not failures:
            raise AssertionError(f"hostile mutation unexpectedly accepted: {label}")
    print(f"Renderer-owned allocation self-test: PASS ({len(mutations)} rejecting mutations)")


def _write_summary(summary: dict[str, Any], output: str | None) -> None:
    if output:
        Path(output).write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    else:
        json.dump(summary, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", help="authoritative report JSON")
    parser.add_argument("--output", help="write summary JSON to this path")
    parser.add_argument("--self-test", action="store_true", help="run bounded hostile fixture tests")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
        return 0
    if not args.input:
        parser.error("input report JSON is required unless --self-test is used")
    try:
        report = json.loads(Path(args.input).read_text())
    except Exception as exc:
        _write_summary({"status": "invalid", "acceptance": "rejected", "errors": [f"could not read/parse input JSON: {exc}"]}, args.output)
        return 2
    try:
        summary, errors = validate_renderer_owned_allocations(report)
    except Exception as exc:
        summary, errors = None, [f"malformed allocation snapshot rejected: {type(exc).__name__}: {exc}"]
    if summary is None and not errors:
        summary = {"status": "unavailable", "acceptance": "diagnostic-only", "scope": {"ownedAllocationFootprint": True, "residentMetric": False}, "errors": []}
        _write_summary(summary, args.output)
        return 0
    if summary is None:
        summary = {"status": "invalid", "acceptance": "rejected", "scope": {"ownedAllocationFootprint": True, "residentMetric": False}, "errors": errors}
    _write_summary(summary, args.output)
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
