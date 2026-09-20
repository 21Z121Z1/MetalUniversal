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


SCHEMA_VERSION = 1
SCOPE = "process-observation-including-warmup"
CLOCK = "System.nanoTime"
MINECRAFT_VERSION = "26.3"
EVIDENCE_CLASS = "diagnostic"
MEASUREMENT_LIMITS = (
    "activeInvocations=unavailable: post-invocation recording; snapshot can censor calls still active; "
    "rawScope=completed-and-failed-exits-observed"
)
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
        "sequence", "stage", "contextId", "threadId", "startOffsetNanos",
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
    for name in ("contextId", "threadId"):
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


def evaluate(payload: Any, required_stages: tuple[str, ...] = ()) -> dict[str, Any]:
    errors: list[str] = []
    if not isinstance(payload, dict):
        return {
            "schema_version": SCHEMA_VERSION, "accepted": False, "complete": False,
            "state": "incomplete-invalid-evidence", "errors": ["report root must be an object"],
            "summary": {"status": "diagnostic-only", "wholeP0Closure": False},
        }
    root_fields = (
        "schemaVersion", "evidenceClass", "performanceEligible", "scope", "clock",
        "minecraftVersion", "sourceSha", "trialId", "status", "measurementLimits", "snapshot",
    )
    _required_fields(payload, root_fields, "report", errors)
    if payload.get("schemaVersion") != 1 or type(payload.get("schemaVersion")) is not int:
        errors.append("schemaVersion must be 1")
    if payload.get("evidenceClass") != EVIDENCE_CLASS:
        errors.append("evidenceClass must be diagnostic")
    if payload.get("performanceEligible") is not False:
        errors.append("performanceEligible must be false")
    if payload.get("scope") != SCOPE:
        errors.append(f"scope must be {SCOPE}")
    if payload.get("clock") != CLOCK:
        errors.append(f"clock must be {CLOCK}")
    if payload.get("minecraftVersion") != MINECRAFT_VERSION:
        errors.append(f"minecraftVersion must be {MINECRAFT_VERSION}")
    source_sha = payload.get("sourceSha")
    if not isinstance(source_sha, str) or SHA40.fullmatch(source_sha) is None:
        errors.append("sourceSha must be a lowercase 40-hex commit")
        source_sha = None
    trial_id = payload.get("trialId")
    if not isinstance(trial_id, str) or not trial_id.strip() or len(trial_id) > 160:
        errors.append("trialId must be a non-empty string <= 160 characters")
        trial_id = None
    if payload.get("status") != "passed":
        errors.append("status must be passed")
    if payload.get("measurementLimits") != MEASUREMENT_LIMITS:
        errors.append("measurementLimits does not preserve post-invocation/right-censoring boundary")

    snapshot = payload.get("snapshot")
    snapshot_fields = (
        "schemaVersion", "eventCapacity", "eventCount", "droppedEvents", "invalidEvents",
        "contextOverflowEvents", "liveContextCount", "events",
    )
    _required_fields(snapshot, snapshot_fields, "snapshot", errors)
    if not isinstance(snapshot, dict):
        snapshot = {}
    if snapshot.get("schemaVersion") != 1 or type(snapshot.get("schemaVersion")) is not int:
        errors.append("snapshot.schemaVersion must be 1")
    capacity = snapshot.get("eventCapacity")
    if not _i64(capacity) or not 1 <= capacity <= MAX_CAPACITY:
        errors.append("snapshot.eventCapacity must be an integer in 1..65536")
    event_count = snapshot.get("eventCount")
    if not _i64(event_count):
        errors.append("snapshot.eventCount must be a non-negative signed Int64 JSON integer")
    for name in ("droppedEvents", "invalidEvents", "contextOverflowEvents"):
        value = snapshot.get(name)
        if not _i64(value):
            errors.append(f"snapshot.{name} must be a non-negative signed Int64 JSON integer")
        elif value != 0:
            errors.append(f"snapshot.{name} must be zero")
    live_contexts = snapshot.get("liveContextCount")
    if not _i64(live_contexts) or live_contexts > MAX_CONTEXTS:
        errors.append("snapshot.liveContextCount must be a non-negative integer <= 128")
    events = snapshot.get("events")
    if not isinstance(events, list):
        errors.append("snapshot.events must be an array")
        events = []
    if _i64(capacity) and len(events) > capacity:
        errors.append("snapshot.events exceeds eventCapacity")
    if _i64(event_count) and event_count != len(events):
        errors.append("snapshot.eventCount must equal snapshot.events length")
    if not events:
        errors.append("snapshot.events must contain at least one observation")

    valid_events: list[dict[str, Any]] = []
    for index, raw in enumerate(events):
        event = _validate_event(raw, index, errors)
        if event is not None:
            valid_events.append(event)
    sequences = [event["sequence"] for event in valid_events if _i64(event.get("sequence"))]
    if sequences:
        if len(set(sequences)) != len(sequences):
            errors.append("snapshot event sequence values must be unique")
        if any(right <= left for left, right in zip(sequences, sequences[1:])):
            errors.append("snapshot event sequence values must be strictly increasing")
        ordered = sorted(sequences)
        if ordered != list(range(ordered[0], ordered[0] + len(ordered))):
            errors.append("snapshot event sequence values must be contiguous")
        if ordered[0] != 1:
            errors.append("snapshot event sequence must start at 1")

    by_stage: dict[str, list[dict[str, Any]]] = {}
    for event in valid_events:
        if event.get("stage") in STAGES and _i64(event.get("startOffsetNanos")) \
                and _i64(event.get("endOffsetNanos")) and event["endOffsetNanos"] >= event["startOffsetNanos"]:
            by_stage.setdefault(event["stage"], []).append(event)
    observed = set(by_stage)
    for stage in required_stages:
        if stage not in STAGES:
            errors.append(f"--require-stage is not in the stage allowlist: {stage!r}")
        elif stage not in observed:
            errors.append(f"required stage was not observed: {stage}")

    stage_summary: dict[str, Any] = {}
    for stage in sorted(by_stage):
        durations = [event["endOffsetNanos"] - event["startOffsetNanos"] for event in by_stage[stage]]
        total = sum(durations)
        if total > I64_MAX:
            errors.append(f"{stage} total inclusive duration overflows signed Int64")
        executed = 0
        executed_samples = 0
        completed_count = 0
        for event in by_stage[stage]:
            if event["completed"]:
                completed_count += 1
            count = event["workCount"]
            if _i64(count):
                if executed > I64_MAX - count:
                    errors.append(f"{stage} summed workCount overflows signed Int64")
                else:
                    executed += count
                    executed_samples += 1
        stage_summary[stage] = {
            "invocations": len(durations),
            "completedInvocations": completed_count,
            "totalInclusiveDurationNanos": total,
            "p50InclusiveDurationNanos": _nearest_rank(durations, 0.50),
            "p95InclusiveDurationNanos": _nearest_rank(durations, 0.95),
            "maxInclusiveDurationNanos": max(durations),
            "observedWorkCount": executed,
            "observedWorkCountSamples": executed_samples,
        }

    summary = {
        "status": "diagnostic-only", "sourceSha": source_sha, "trialId": trial_id,
        "scope": SCOPE, "measurementLimits": MEASUREMENT_LIMITS,
        "wholeP0Closure": False, "observedStages": sorted(observed),
        "unobservedStages": sorted(STAGES - observed), "requiredStages": list(required_stages),
        "snapshot": {
            "eventCapacity": capacity,
            "eventCount": event_count,
            "droppedEvents": snapshot.get("droppedEvents"),
            "invalidEvents": snapshot.get("invalidEvents"),
            "contextOverflowEvents": snapshot.get("contextOverflowEvents"),
            "liveContextCount": live_contexts,
        },
        "stages": stage_summary,
    }
    complete = not errors
    return {
        "schema_version": SCHEMA_VERSION, "accepted": complete, "complete": complete,
        "state": "accepted-diagnostic-only" if complete else "incomplete-invalid-evidence",
        "errors": errors, "summary": summary,
    }


def _fixture() -> dict[str, Any]:
    return {
        "schemaVersion": 1, "evidenceClass": "diagnostic", "performanceEligible": False,
        "scope": SCOPE, "clock": CLOCK, "minecraftVersion": MINECRAFT_VERSION,
        "sourceSha": "0123456789abcdef0123456789abcdef01234567", "trialId": "world-stage-fixture",
        "status": "passed", "measurementLimits": MEASUREMENT_LIMITS,
        "snapshot": {
            "schemaVersion": 1, "eventCapacity": 64, "eventCount": 1,
            "droppedEvents": 0, "invalidEvents": 0, "contextOverflowEvents": 0,
            "liveContextCount": 1,
            "events": [{
                "sequence": 1, "stage": "LIGHT_TASK", "contextId": 1, "threadId": 2,
                "startOffsetNanos": 10, "endOffsetNanos": 40, "completed": False,
                "queueBefore": 2, "queueAfter": 1, "workCount": 1, "resultCode": -1,
            }],
        },
    }


def self_test() -> None:
    valid = evaluate(_fixture())
    assert valid["complete"]
    assert valid["summary"]["stages"]["LIGHT_TASK"]["observedWorkCount"] == 1
    assert not valid["summary"]["wholeP0Closure"]
    mutations: list[tuple[str, dict[str, Any]]] = []
    mutation = copy.deepcopy(_fixture()); mutation["status"] = "failed"; mutations.append(("rejected status", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["sourceSha"] = "bad"; mutations.append(("bad sha", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"][0]["sequence"] = 2; mutations.append(("noncontiguous sequence", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"][0]["stage"] = "COMPRESSION"; mutations.append(("unknown stage", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"][0]["contextId"] = 0; mutations.append(("zero context", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"][0]["queueBefore"] = -2; mutations.append(("bad queue", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"][0]["resultCode"] = 2; mutations.append(("bad result", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"][0]["endOffsetNanos"] = 9; mutations.append(("time inversion", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["droppedEvents"] = 1; mutations.append(("dropped rows", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["contextOverflowEvents"] = 1; mutations.append(("context overflow", mutation))
    mutation = copy.deepcopy(_fixture()); mutation["snapshot"]["events"] = []; mutation["snapshot"]["eventCount"] = 0; mutations.append(("empty evidence", mutation))
    for key in _fixture()["snapshot"]["events"][0]:
        mutation = copy.deepcopy(_fixture())
        del mutation["snapshot"]["events"][0][key]
        mutations.append((f"missing {key}", mutation))
        for value in (None, [], {}, "bad", True, -2, 1 << 64):
            mutation = copy.deepcopy(_fixture())
            mutation["snapshot"]["events"][0][key] = value
            # True is valid only for the completion field.
            if key == "completed" and value is True:
                continue
            mutations.append((f"malformed {key}: {value!r}", mutation))
    for label, mutant in mutations:
        if evaluate(mutant)["complete"]:
            raise AssertionError(f"hostile mutation unexpectedly accepted: {label}")
    assert evaluate(_fixture(), ("LIGHT_TASK",))["complete"]
    assert not evaluate(_fixture(), ("SERVER_TICK",))["complete"]
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
