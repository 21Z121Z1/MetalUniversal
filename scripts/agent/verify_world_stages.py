#!/usr/bin/env python3
"""Fail-closed oracle for bounded vanilla world-stage observations.

The report is process diagnostic evidence. Its per-stage durations and work
counts do not establish renderer correctness, performance, or full P0 stage
coverage. A vanilla run can omit a stage; omission is surfaced as unobserved.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import re
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 2
SCOPE = "process-observation-including-warmup"
CLOCK = "System.nanoTime"
MINECRAFT_VERSION = "26.3"
EVIDENCE_CLASS = "diagnostic"
MEASUREMENT_LIMITS = "bounded begin/end observations; active calls retained at snapshot; nested wall-clock intervals are inclusive"

I64_MAX = (1 << 63) - 1
SHA40 = re.compile(r"^[0-9a-f]{40}$")
MAX_CAPACITY = 65_536
MAX_CONTEXTS = 128
STAGES = frozenset({
    "CLIENT_PACKETS", "CHUNK_INSTALL", "LIGHT_ENQUEUE", "LIGHT_POLL",
    "LIGHT_TASK", "LIGHT_UPDATE", "SERVER_PACKETS", "SERVER_TICK",
})


def _i64(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= I64_MAX


def _signed_i64(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and -I64_MAX - 1 <= value <= I64_MAX


def _nearest_rank(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(1, math.ceil(fraction * len(ordered))) - 1]


def _required_fields(value: Any, fields: tuple[str, ...], label: str, errors: list[str]) -> bool:
    if not isinstance(value, dict):
        errors.append(f"{label} must be an object")
        return False
    missing = sorted(set(fields) - set(value))
    if missing:
        errors.append(f"{label} is missing fields: {missing}")
    return not missing


def _validate_event(raw: Any, index: int, errors: list[str]) -> dict[str, Any] | None:
    error_count = len(errors)
    label = f"snapshot.events[{index}]"
    fields = (
        "sequence", "invocationId", "stage", "contextId", "threadId", "startOffsetNanos",
        "endOffsetNanos", "completed", "queueBefore", "queueAfter", "workCount", "resultCode",
    )
    if not _required_fields(raw, fields, label, errors):
        return None
    assert isinstance(raw, dict)
    sequence = raw.get("sequence")
    if not _i64(sequence) or sequence == 0:
        errors.append(f"{label}.sequence must be a positive signed Int64 JSON integer")
    if not isinstance(raw.get("stage"), str) or raw["stage"] not in STAGES:
        errors.append(f"{label}.stage is not in the stage allowlist: {raw.get('stage')!r}")
    for name in ("invocationId", "contextId", "threadId"):
        value = raw.get(name)
        if not _i64(value) or value <= 0:
            errors.append(f"{label}.{name} must be a positive signed Int64 JSON integer")
    for name in ("startOffsetNanos", "endOffsetNanos"):
        if not _i64(raw.get(name)):
            errors.append(f"{label}.{name} must be a non-negative signed Int64 JSON integer")
    if _i64(raw.get("startOffsetNanos")) and _i64(raw.get("endOffsetNanos")) \
            and raw["endOffsetNanos"] < raw["startOffsetNanos"]:
        errors.append(f"{label}.endOffsetNanos must be >= startOffsetNanos")
    if not isinstance(raw.get("completed"), bool):
        errors.append(f"{label}.completed must be boolean")
    for name in ("queueBefore", "queueAfter", "workCount"):
        value = raw.get(name)
        if not _signed_i64(value) or value < -1:
            errors.append(f"{label}.{name} must be -1 or a non-negative signed Int64 JSON integer")
    result = raw.get("resultCode")
    if not _signed_i64(result) or result not in (-1, 0, 1):
        errors.append(f"{label}.resultCode must be one of -1, 0, or 1")
    if len(errors) != error_count:
        return None
    stage = raw["stage"]
    before, after, work, result = (raw[key] for key in ("queueBefore", "queueAfter", "workCount", "resultCode"))
    completed = raw["completed"]
    contracts = {
        "CLIENT_PACKETS": (before, after, work, result) == (-1, -1, -1, -1),
        "SERVER_PACKETS": (before, after, work, result) == (-1, -1, -1, -1),
        "CHUNK_INSTALL": before == after == -1 and work == 1 and result in ((0, 1) if completed else (-1,)),
        "LIGHT_ENQUEUE": before >= 0 and after >= 0 and work == int(completed) and result == -1,
        "LIGHT_POLL": before >= 0 and after >= 0 and work == result == -1,
        "LIGHT_UPDATE": before >= 0 and after >= 0 and work == result == -1,
        "LIGHT_TASK": before >= 0 and after >= 0 and work == 1 and result == -1,
        "SERVER_TICK": before == after == -1 and work == 1 and result in (0, 1),
    }
    if not contracts[stage]:
        errors.append(f"{label} violates {stage} gauge/work/result contract")
        return None
    return raw


def _validate_active(raw: Any, index: int, errors: list[str]) -> dict[str, Any] | None:
    label = f"activeInvocations[{index}]"
    count = len(errors)
    if not _required_fields(raw, ("invocationId", "stage", "contextId", "threadId", "startOffsetNanos", "queueBefore"), label, errors):
        return None
    for key in ("invocationId", "contextId", "threadId"):
        if not _i64(raw[key]) or raw[key] == 0:
            errors.append(f"{label}.{key} must be positive Int64")
    if not _i64(raw["startOffsetNanos"]):
        errors.append(f"{label}.startOffsetNanos must be nonnegative Int64")
    stage = raw["stage"]
    if not isinstance(stage, str) or stage not in STAGES:
        errors.append(f"{label}.stage invalid")
    queue = raw["queueBefore"]
    if not _signed_i64(queue) or queue < -1:
        errors.append(f"{label}.queueBefore invalid")
    if len(errors) != count:
        return None
    if (stage.startswith("LIGHT_") and queue < 0) or (not stage.startswith("LIGHT_") and queue != -1):
        errors.append(f"{label}.queueBefore violates stage contract")
        return None
    return raw


def evaluate(payload: Any, required_stages: tuple[str, ...] = ()) -> dict[str, Any]:
    errors: list[str] = []
    def result(summary: dict[str, Any]) -> dict[str, Any]:
        return {"schema_version": 2, "accepted": not errors, "complete": not errors,
                "state": "incomplete-invalid-evidence" if errors else "accepted-diagnostic-only",
                "errors": errors, "summary": {"status": "diagnostic-only", "wholeP0Closure": False, **summary}}
    if not isinstance(payload, dict):
        errors.append("report root must be object")
        return result({})
    for key, expected in (("schemaVersion", 2), ("evidenceClass", "diagnostic"),
                          ("performanceEligible", False), ("clock", CLOCK),
                          ("minecraftVersion", MINECRAFT_VERSION), ("status", "passed"),
                          ("measurementLimits", MEASUREMENT_LIMITS)):
        if type(payload.get(key)) is not type(expected) or payload[key] != expected:
            errors.append(f"{key} must equal {expected!r}")
    sha = payload.get("sourceSha")
    trial = payload.get("trialId")
    if not isinstance(sha, str) or not SHA40.fullmatch(sha):
        errors.append("sourceSha must be lowercase 40 hex")
    if not isinstance(trial, str) or not trial.strip() or len(trial) > 160:
        errors.append("trialId must be nonblank and <=160 chars")
    snap = payload.get("snapshot")
    if not isinstance(snap, dict):
        errors.append("snapshot must be object")
        return result({})
    counts = ("eventCapacity", "eventCount", "droppedEvents", "invalidEvents", "contextOverflowEvents",
              "liveContextCount", "startedInvocations", "finishedInvocations", "activeOverflowEvents", "timestampOffsetNanos")
    for key in counts:
        if not _i64(snap.get(key)):
            errors.append(f"snapshot.{key} must be nonnegative Int64")
    if type(snap.get("schemaVersion")) is not int or snap["schemaVersion"] != 2:
        errors.append("snapshot.schemaVersion must be 2")
    if errors:
        return result({})
    if not 1 <= snap["eventCapacity"] <= MAX_CAPACITY or snap["liveContextCount"] > MAX_CONTEXTS:
        errors.append("snapshot capacity/context bounds invalid")
    for key in ("droppedEvents", "invalidEvents", "contextOverflowEvents", "activeOverflowEvents"):
        if snap[key] != 0:
            errors.append(f"snapshot.{key} must be zero")
    window = snap.get("window")
    start = 0
    end = snap["timestampOffsetNanos"]
    active_start = 0
    if window is not None:
        if not isinstance(window, dict):
            errors.append("window must be object")
            return result({})
        for key in ("startFrameInclusive", "endFrameExclusive", "startOffsetNanos", "endOffsetNanos", "activeAtStart"):
            if not _i64(window.get(key)):
                errors.append(f"window.{key} must be nonnegative Int64")
        if not isinstance(window.get("id"), str) or not window["id"].strip() or len(window["id"]) > 160:
            errors.append("window.id invalid")
        if window.get("closed") is not True:
            errors.append("window must be closed")
        if errors:
            return result({})
        start, end, active_start = window["startOffsetNanos"], window["endOffsetNanos"], window["activeAtStart"]
        if end <= start or end > snap["timestampOffsetNanos"] or window["endFrameExclusive"] <= window["startFrameInclusive"] or active_start > 256:
            errors.append("window boundaries invalid")
    scope = "measurement-window" if window is not None else SCOPE
    if payload.get("scope") != scope:
        errors.append("scope does not match window")
    events, active = snap.get("events"), snap.get("activeInvocations")
    if not isinstance(events, list) or not isinstance(active, list):
        errors.append("events and activeInvocations must be arrays")
        return result({})
    if len(events) != snap["eventCount"] or len(events) > snap["eventCapacity"] or len(active) > 256:
        errors.append("event/active count or capacity mismatch")
    valid_events = [row for i, raw in enumerate(events) if (row := _validate_event(raw, i, errors)) is not None]
    valid_active = [row for i, raw in enumerate(active) if (row := _validate_active(raw, i, errors)) is not None]
    if errors:
        return result({})
    if [row["sequence"] for row in events] != list(range(1, len(events) + 1)):
        errors.append("event exit sequence must be contiguous from1")
    ids = [row["invocationId"] for row in events + active]
    if len(set(ids)) != len(ids):
        errors.append("duplicate invocationId in ended/active rows")
    if active_start + snap["startedInvocations"] != snap["finishedInvocations"] + len(active):
        errors.append("begin/end/active conservation mismatch")
    if snap["finishedInvocations"] != snap["eventCount"] + snap["droppedEvents"]:
        errors.append("finished/rows/loss conservation mismatch")
    if not events and not active:
        errors.append("no observations")
    if any(row["startOffsetNanos"] > snap["timestampOffsetNanos"] for row in events + active) or any(row["endOffsetNanos"] > snap["timestampOffsetNanos"] for row in events):
        errors.append("event timestamp after snapshot")
    observed = {row["stage"] for row in events + active}
    for stage in required_stages:
        if not isinstance(stage, str) or stage not in STAGES or stage not in observed:
            errors.append(f"required stage not observed: {stage!r}")
    summaries = {}
    for stage in sorted(observed):
        ended = [row for row in events if row["stage"] == stage]
        pending = [row for row in active if row["stage"] == stage]
        overlap = lambda row, stop: max(0, min(stop, end) - max(row["startOffsetNanos"], start))
        durations = [d for row in ended if (d := overlap(row, row["endOffsetNanos"])) > 0]
        active_durations = [overlap(row, end) for row in pending]
        total = sum(durations)
        active_total = sum(active_durations)
        work = sum(row["workCount"] for row in ended if row["workCount"] >= 0)
        if max(total, active_total, work) > I64_MAX:
            errors.append(f"{stage} aggregate overflows Int64")
        summaries[stage] = {
            "endedInvocations": len(ended), "normalReturns": sum(row["completed"] for row in ended),
            "activeAtSnapshot": len(pending),
            "leftCensoredIntervals": sum(row["startOffsetNanos"] < start for row in ended + pending),
            "positiveOverlapEndedInvocations": len(durations),
            "endedOverlapTotalNanos": total, "activeOverlapTotalNanos": active_total,
            "p50EndedOverlapNanos": _nearest_rank(durations, .50),
            "p95EndedOverlapNanos": _nearest_rank(durations, .95),
            "maxEndedOverlapNanos": max(durations, default=None),
            "observedWorkCount": work,
            "observedWorkCountSamples": sum(row["workCount"] >= 0 for row in ended),
        }
    return result({"sourceSha": sha, "trialId": trial, "scope": scope,
                   "measurementLimits": MEASUREMENT_LIMITS, "window": window,
                   "snapshot": {key: snap[key] for key in counts},
                   "activeAtSnapshot": len(active), "observedStages": sorted(observed),
                   "unobservedStages": sorted(STAGES - observed), "stages": summaries})


def _fixture() -> dict[str, Any]:
    return {
        "schemaVersion": 2, "evidenceClass": "diagnostic", "performanceEligible": False,
        "scope": "measurement-window", "clock": CLOCK, "minecraftVersion": MINECRAFT_VERSION,
        "sourceSha": "0123456789abcdef0123456789abcdef01234567", "trialId": "world-stage-fixture",
        "status": "passed", "measurementLimits": MEASUREMENT_LIMITS,
        "snapshot": {
            "schemaVersion": 2, "eventCapacity": 64, "eventCount": 1, "droppedEvents": 0,
            "invalidEvents": 0, "contextOverflowEvents": 0, "liveContextCount": 1,
            "startedInvocations": 1, "finishedInvocations": 1, "activeOverflowEvents": 0,
            "timestampOffsetNanos": 100,
            "window": {"id": "fixture", "startFrameInclusive": 2, "endFrameExclusive": 4,
                       "startOffsetNanos": 20, "endOffsetNanos": 80, "activeAtStart": 1, "closed": True},
            "events": [{"sequence": 1, "invocationId": 1, "stage": "LIGHT_TASK", "contextId": 1,
                        "threadId": 2, "startOffsetNanos": 10, "endOffsetNanos": 40,
                        "completed": False, "queueBefore": 2, "queueAfter": 1, "workCount": 1, "resultCode": -1}],
            "activeInvocations": [{"invocationId": 2, "stage": "SERVER_TICK", "contextId": 1,
                                   "threadId": 3, "startOffsetNanos": 50, "queueBefore": -1}],
        },
    }


def self_test() -> None:
    valid = evaluate(_fixture())
    assert valid["complete"], valid
    assert valid["summary"]["stages"]["LIGHT_TASK"]["endedOverlapTotalNanos"] == 20
    assert valid["summary"]["stages"]["SERVER_TICK"]["activeOverlapTotalNanos"] == 30
    assert valid["summary"]["stages"]["LIGHT_TASK"]["leftCensoredIntervals"] == 1
    process = _fixture(); process["scope"] = SCOPE; del process["snapshot"]["window"]
    process["snapshot"]["startedInvocations"] = 2
    assert evaluate(process)["complete"]
    mutations = []
    for route in (("snapshot", "events", 0), ("snapshot", "activeInvocations", 0)):
        original = _fixture()
        row = original[route[0]][route[1]][route[2]]
        for key in row:
            mutant = copy.deepcopy(original); del mutant[route[0]][route[1]][route[2]][key]
            mutations.append((f"missing {route}/{key}", mutant))
            for value in (None, [], {}, "bad", True, -2, 1 << 64):
                if key == "completed" and value is True:
                    continue
                mutant = copy.deepcopy(original); mutant[route[0]][route[1]][route[2]][key] = value
                mutations.append((f"malformed {route}/{key}", mutant))
    for key, value in (("startedInvocations", 2), ("finishedInvocations", 2), ("activeOverflowEvents", 1),
                       ("droppedEvents", 1), ("invalidEvents", 1), ("contextOverflowEvents", 1), ("timestampOffsetNanos", 25)):
        mutant = _fixture(); mutant["snapshot"][key] = value; mutations.append((key, mutant))
    for key, value in (("closed", False), ("startOffsetNanos", 90), ("endFrameExclusive", 1), ("activeAtStart", 0)):
        mutant = _fixture(); mutant["snapshot"]["window"][key] = value; mutations.append((key, mutant))
    mutant = _fixture(); mutant["snapshot"]["activeInvocations"][0]["invocationId"] = 1
    mutations.append(("duplicate active/ended id", mutant))
    for route in ((), ("snapshot",), ("snapshot", "window")):
        original = _fixture()
        target = original
        for part in route:
            target = target[part]
        for key in target:
            for value in (None, [], {}, -1, 1 << 64):
                if type(value) is type(target[key]) and value == target[key]:
                    continue
                mutant = copy.deepcopy(original)
                destination = mutant
                for part in route:
                    destination = destination[part]
                destination[key] = value
                mutations.append((f"malformed envelope {route}/{key}", mutant))
    for label, mutant in mutations:
        assert not evaluate(mutant)["complete"], label
    assert not evaluate(_fixture(), ("CHUNK_INSTALL",))["complete"]
    print(f"World stage observation self-test: PASS ({len(mutations)} rejecting mutations)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-stage", action="append", default=[])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.report is None:
        parser.error("report is required unless --self-test is used")
    try:
        payload = json.loads(args.report.read_text(encoding="utf-8"))
        checked = evaluate(payload, tuple(args.require_stage))
    except (OSError, json.JSONDecodeError) as exc:
        checked = {
            "schema_version": SCHEMA_VERSION, "accepted": False, "complete": False,
            "state": "incomplete-invalid-evidence", "errors": [f"could not read report: {exc}"],
            "summary": {"status": "diagnostic-only", "wholeP0Closure": False},
        }
    if args.output:
        args.output.write_text(json.dumps(checked, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    else:
        print(json.dumps(checked, indent=2, sort_keys=True))
    return 0 if checked["complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
