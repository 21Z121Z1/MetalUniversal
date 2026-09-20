#!/usr/bin/env python3
"""Fail-closed semantic checker for diagnostic vanilla terrain generation evidence."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
ACCEPTANCE = "diagnostic-integrity"
SHA40 = re.compile(r"^[0-9a-f]{40}$")
U64_TEXT = re.compile(r"^(0|[1-9][0-9]{0,19})$")
I64_TEXT = re.compile(r"^-?(0|[1-9][0-9]{0,18})$")

FAIL_OPEN_REASONS = {
    "NONE",
    "SECTION_CAPACITY",
    "TASK_CAPACITY",
    "MESH_CAPACITY",
    "UNKNOWN_TASK",
    "UNKNOWN_PUBLICATION",
    "MESH_REBOUND",
}
DECISIONS = {"ALLOW_CURRENT", "REJECT_STALE", "BASELINE_ALLOW"}
EVENT_KINDS = {
    "TASK_REGISTERED",
    "MESH_BOUND",
    "WORLD_EPOCH_ADVANCED",
    "MATERIAL_GENERATION_ADVANCED",
    "SECTION_INVALIDATED",
    "PUBLICATION",
    "FAIL_OPEN",
}
EVENT_REASONS = {
    "NONE",
    "DISABLED",
    "FAIL_OPEN",
    "STALE_CONTENT",
    "SECTION_MISMATCH",
    "TASK_CANCELLED",
    "SECTION_CAPACITY",
    "TASK_CAPACITY",
    "MESH_CAPACITY",
    "UNKNOWN_TASK",
    "UNKNOWN_PUBLICATION",
    "MESH_REBOUND",
}
SNAPSHOT_BOOLEAN_FIELDS = ("requested", "active", "failOpen")
SNAPSHOT_NUMERIC_FIELDS = ("sectionVersionEntries", "trackedTasks", "trackedMeshes")
SNAPSHOT_LONG_FIELDS = (
    "worldEpoch",
    "materialGeneration",
    "untrackedInvalidations",
    "registeredTasks",
    "cancelledObsoleteTasks",
    "boundMeshes",
    "allowedPublications",
    "rejectedStalePublications",
    "baselinePublicationsAfterFailOpen",
    "failOpenCount",
    "sectionCapacityFailOpenCount",
    "taskCapacityFailOpenCount",
    "meshCapacityFailOpenCount",
    "unknownTaskFailOpenCount",
    "unknownPublicationFailOpenCount",
    "meshReboundFailOpenCount",
)
CONTENT_VERSION_FIELDS = (
    "worldEpoch",
    "sectionId",
    "geometryRevision",
    "lightingRevision",
    "materialGeneration",
)
EVENT_FIELDS = (
    "sequence",
    "kind",
    "worldEpoch",
    "materialGeneration",
    "sectionId",
    "captured",
    "current",
    "decision",
    "cancelled",
    "reason",
)


def _u64(value: Any, field: str, errors: list[str], *, positive: bool = False) -> int | None:
    if not isinstance(value, str) or U64_TEXT.fullmatch(value) is None:
        errors.append(f"{field} must be an unsigned decimal string")
        return None
    number = int(value)
    if number > (1 << 63) - 1:
        errors.append(f"{field} exceeds signed Java long range")
        return None
    if positive and number < 1:
        errors.append(f"{field} must be positive")
        return None
    return number


def _i64(value: Any, field: str, errors: list[str]) -> int | None:
    if not isinstance(value, str) or I64_TEXT.fullmatch(value) is None:
        errors.append(f"{field} must be a signed decimal string")
        return None
    number = int(value)
    if number < -(1 << 63) or number > (1 << 63) - 1:
        errors.append(f"{field} exceeds int64")
        return None
    return number


def _nonnegative_int(value: Any, field: str, errors: list[str]) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        errors.append(f"{field} must be a non-negative integer")
        return None
    return value


def _exact_keys(value: dict[str, Any], expected: tuple[str, ...], field: str, errors: list[str]) -> None:
    if set(value) != set(expected):
        errors.append(f"{field} must contain exactly {sorted(expected)}")


def _keys_with_optional(
    value: dict[str, Any],
    expected: tuple[str, ...],
    optional: set[str],
    field: str,
    errors: list[str],
) -> None:
    extra = set(value) - set(expected)
    missing = (set(expected) - set(value)) - optional
    if extra or missing:
        errors.append(
            f"{field} has unexpected/missing keys: extra={sorted(extra)}, missing={sorted(missing)}"
        )


def _content_version(value: Any, field: str, errors: list[str]) -> dict[str, int] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        errors.append(f"{field} must be null or an object")
        return None
    _exact_keys(value, CONTENT_VERSION_FIELDS, field, errors)
    parsed: dict[str, int] = {}
    for name in CONTENT_VERSION_FIELDS:
        parsed[name] = _u64(
            value.get(name), f"{field}.{name}", errors, positive=name != "sectionId"
        ) if name != "sectionId" else (_i64(value.get(name), f"{field}.{name}", errors) or 0)
    return parsed


def _same_version(left: dict[str, int] | None, right: dict[str, int] | None) -> bool:
    return left is not None and right is not None and left == right


def _validate_snapshot(value: Any, errors: list[str]) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        errors.append("evidence.snapshot must be an object")
        return None
    expected = (
        *SNAPSHOT_BOOLEAN_FIELDS,
        "failOpenReason",
        *SNAPSHOT_NUMERIC_FIELDS,
        *SNAPSHOT_LONG_FIELDS,
    )
    _exact_keys(value, expected, "evidence.snapshot", errors)
    for name in SNAPSHOT_BOOLEAN_FIELDS:
        if not isinstance(value.get(name), bool):
            errors.append(f"evidence.snapshot.{name} must be boolean")
    reason = value.get("failOpenReason")
    if reason not in FAIL_OPEN_REASONS:
        errors.append(f"evidence.snapshot.failOpenReason is invalid: {reason!r}")
    for name in SNAPSHOT_NUMERIC_FIELDS:
        _nonnegative_int(value.get(name), f"evidence.snapshot.{name}", errors)
    for name in SNAPSHOT_LONG_FIELDS:
        _u64(
            value.get(name),
            f"evidence.snapshot.{name}",
            errors,
            positive=name in {"worldEpoch", "materialGeneration"},
        )
    requested = value.get("requested")
    active = value.get("active")
    fail_open = value.get("failOpen")
    if isinstance(requested, bool) and isinstance(active, bool) and isinstance(fail_open, bool):
        if active != (requested and not fail_open):
            errors.append("evidence.snapshot.active must equal requested && !failOpen")
    if isinstance(fail_open, bool) and isinstance(reason, str):
        if fail_open and reason == "NONE":
            errors.append("failOpen=true requires a non-NONE failOpenReason")
        if not fail_open and reason != "NONE":
            errors.append("failOpen=false requires failOpenReason=NONE")
    return value


def _validate_event(value: Any, index: int, errors: list[str]) -> tuple[int | None, int | None, str | None, dict[str, int] | None, dict[str, int] | None]:
    field = f"evidence.events[{index}]"
    if not isinstance(value, dict):
        errors.append(f"{field} must be an object")
        return None, None, None, None, None
    # Gson serializeNulls emits nullable object fields. Older Java fixtures omitted a null
    # decision on non-publication events, so only that semantically null field remains optional.
    _keys_with_optional(value, EVENT_FIELDS, {"decision"}, field, errors)
    sequence = _u64(value.get("sequence"), f"{field}.sequence", errors)
    world_epoch = _u64(value.get("worldEpoch"), f"{field}.worldEpoch", errors, positive=True)
    material_generation = _u64(
        value.get("materialGeneration"), f"{field}.materialGeneration", errors, positive=True
    )
    section_id = value.get("sectionId")
    if section_id is not None:
        _i64(section_id, f"{field}.sectionId", errors)
    kind = value.get("kind")
    if kind not in EVENT_KINDS:
        errors.append(f"{field}.kind is invalid: {kind!r}")
    decision = value.get("decision")
    if decision is not None and decision not in DECISIONS:
        errors.append(f"{field}.decision is invalid: {decision!r}")
    cancelled = value.get("cancelled")
    if not isinstance(cancelled, bool):
        errors.append(f"{field}.cancelled must be boolean")
    reason = value.get("reason")
    if reason not in EVENT_REASONS:
        errors.append(f"{field}.reason is invalid: {reason!r}")
    captured = _content_version(value.get("captured"), f"{field}.captured", errors)
    current = _content_version(value.get("current"), f"{field}.current", errors)
    if kind == "PUBLICATION" and decision is None:
        errors.append(f"{field} PUBLICATION requires a decision")
    if kind != "PUBLICATION" and decision is not None:
        errors.append(f"{field} non-PUBLICATION event cannot carry a decision")
    event_section = _i64(section_id, f"{field}.sectionId", []) if section_id is not None else None
    current_matches_event = True
    if kind == "PUBLICATION" and current is not None:
        current_matches_event = (
            event_section is not None
            and current["sectionId"] == event_section
            and current["worldEpoch"] == world_epoch
            and current["materialGeneration"] == material_generation
        )
        if not current_matches_event:
            errors.append(f"{field}.current does not belong to the publication event section/epoch")
    if decision in {"ALLOW_CURRENT", "REJECT_STALE"} and isinstance(cancelled, bool):
        expected = (
            "ALLOW_CURRENT"
            if _same_version(captured, current)
            and current_matches_event
            and captured is not None
            and event_section is not None
            and captured["sectionId"] == event_section
            and not cancelled
            else "REJECT_STALE"
        )
        if decision != expected:
            errors.append(f"{field}.decision={decision} contradicts captured/current/cancelled")
    if decision == "BASELINE_ALLOW" and reason not in {"DISABLED", "FAIL_OPEN", "UNKNOWN_PUBLICATION"}:
        errors.append(f"{field} BASELINE_ALLOW requires a disabled/fail-open explanation")
    return sequence, world_epoch, kind if isinstance(kind, str) else None, captured, current


def evaluate(payload: Any) -> dict[str, Any]:
    errors: list[str] = []
    if not isinstance(payload, dict):
        errors.append("report root must be an object")
        return result(False, False, errors, {})

    expected_root = (
        "schemaVersion",
        "evidenceClass",
        "performanceEligible",
        "source",
        "validationStatus",
        "requested",
        "mixinHooksObserved",
        "scope",
        "limitation",
        "evidence",
    )
    _exact_keys(payload, expected_root, "report", errors)
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        errors.append("schemaVersion must be 1")
    if payload.get("evidenceClass") != "diagnostic":
        errors.append("evidenceClass must be diagnostic")
    if payload.get("performanceEligible") is not False:
        errors.append("performanceEligible must be false")
    if not isinstance(payload.get("scope"), str) or not payload.get("scope", "").strip():
        errors.append("scope must be a non-empty string")
    if not isinstance(payload.get("limitation"), str) or not payload.get("limitation", "").strip():
        errors.append("limitation must be a non-empty string")
    validation_status = payload.get("validationStatus")
    if not isinstance(validation_status, str) or not validation_status.strip():
        errors.append("validationStatus must be a non-empty string")
    requested = payload.get("requested")
    hooks_observed = payload.get("mixinHooksObserved")
    if not isinstance(requested, bool):
        errors.append("requested must be boolean")
    if not isinstance(hooks_observed, bool):
        errors.append("mixinHooksObserved must be boolean")

    source = payload.get("source")
    if not isinstance(source, dict):
        errors.append("source must be an object")
    else:
        _exact_keys(source, ("sourceSha", "minecraftVersion", "trialId"), "source", errors)
        if not isinstance(source.get("sourceSha"), str) or SHA40.fullmatch(source.get("sourceSha", "")) is None:
            errors.append("source.sourceSha must be a lowercase 40-hex commit")
        if source.get("minecraftVersion") != "26.3":
            errors.append("source.minecraftVersion must be 26.3")
        if not isinstance(source.get("trialId"), str) or not source.get("trialId", "").strip() or len(source.get("trialId", "")) > 160:
            errors.append("source.trialId must be a non-empty string <= 160 characters")

    evidence = payload.get("evidence")
    snapshot = None
    events: list[Any] = []
    dropped = None
    capacity = None
    if evidence is None:
        if hooks_observed is True:
            errors.append("evidence may be null only when mixinHooksObserved=false")
    else:
        if not isinstance(evidence, dict):
            errors.append("evidence must be null or an object")
        else:
            _exact_keys(evidence, ("snapshot", "events", "droppedEvents", "eventCapacity"), "evidence", errors)
            snapshot = _validate_snapshot(evidence.get("snapshot"), errors)
            events_value = evidence.get("events")
            if not isinstance(events_value, list):
                errors.append("evidence.events must be an array")
            else:
                events = events_value
            dropped = _u64(evidence.get("droppedEvents"), "evidence.droppedEvents", errors)
            capacity = _nonnegative_int(evidence.get("eventCapacity"), "evidence.eventCapacity", errors)
            if capacity == 0 and events:
                errors.append("eventCapacity=0 requires an empty event trace")
            if capacity is not None and len(events) > capacity:
                errors.append("evidence.events length cannot exceed eventCapacity")

    sequence_values: list[int] = []
    world_values: list[int] = []
    material_values: list[int] = []
    decisions: list[str] = []
    for index, event in enumerate(events):
        sequence, world_epoch, _kind, _captured, _current = _validate_event(event, index, errors)
        if sequence is not None:
            sequence_values.append(sequence)
        if world_epoch is not None:
            world_values.append(world_epoch)
        if isinstance(event, dict):
            material = _u64(event.get("materialGeneration"), f"evidence.events[{index}].materialGeneration", [])
            if material is not None:
                material_values.append(material)
            if event.get("decision") is not None:
                decisions.append(event["decision"])
    if len(sequence_values) != len(set(sequence_values)):
        errors.append("event sequence values must be unique")
    if sequence_values != list(range(1, len(events) + 1)):
        errors.append("event sequence must be contiguous starting at 1")
    if world_values != sorted(world_values):
        errors.append("event worldEpoch must be monotonic")
    if material_values != sorted(material_values):
        errors.append("event materialGeneration must be monotonic")

    if snapshot is not None:
        if requested is True and snapshot.get("requested") is not True:
            errors.append("requested=true requires snapshot.requested=true")
        if requested is False and snapshot.get("requested") is not False:
            errors.append("requested=false requires snapshot.requested=false")
        if hooks_observed is False and snapshot.get("active") is True:
            errors.append("snapshot.active cannot be true when mixinHooksObserved=false")
        registered = _u64(snapshot.get("registeredTasks"), "snapshot.registeredTasks", []) or 0
        publications = (
            _u64(snapshot.get("allowedPublications"), "snapshot.allowedPublications", []) or 0
        ) + (_u64(snapshot.get("rejectedStalePublications"), "snapshot.rejectedStalePublications", []) or 0)
        if snapshot.get("active") is True and hooks_observed is False and registered == 0 and publications == 0:
            errors.append("requested guard has no hook/task/publication evidence and cannot claim active")
        if snapshot.get("failOpen") is True and snapshot.get("active") is True:
            errors.append("fail-open guard cannot claim active")
        fail_open_count = _u64(snapshot.get("failOpenCount"), "snapshot.failOpenCount", []) or 0
        if snapshot.get("failOpen") is True and fail_open_count < 1:
            errors.append("failOpen=true requires failOpenCount >= 1")
        if snapshot.get("failOpen") is False and fail_open_count != 0:
            errors.append("failOpen=false requires failOpenCount=0")
        for index, event in enumerate(events):
            if not isinstance(event, dict):
                continue
            decision = event.get("decision")
            reason = event.get("reason")
            if decision == "BASELINE_ALLOW":
                if snapshot.get("requested") is True and snapshot.get("failOpen") is False:
                    errors.append(
                        f"evidence.events[{index}] BASELINE_ALLOW requires disabled or fail-open snapshot"
                    )
                if reason == "DISABLED" and snapshot.get("requested") is not False:
                    errors.append(f"evidence.events[{index}] DISABLED baseline requires requested=false")
                if reason in {"FAIL_OPEN", "UNKNOWN_PUBLICATION"} and snapshot.get("failOpen") is not True:
                    errors.append(f"evidence.events[{index}] fail-open baseline requires failOpen=true")
        snapshot_epoch = _u64(snapshot.get("worldEpoch"), "snapshot.worldEpoch", [])
        snapshot_material = _u64(snapshot.get("materialGeneration"), "snapshot.materialGeneration", [])
        if world_values and snapshot_epoch is not None and snapshot_epoch < world_values[-1]:
            errors.append("snapshot.worldEpoch regresses below the event trace")
        if material_values and snapshot_material is not None and snapshot_material < material_values[-1]:
            errors.append("snapshot.materialGeneration regresses below the event trace")

    dropped_value = dropped or 0
    trace_complete = capacity is not None and capacity > 0 and dropped_value == 0
    if snapshot is not None and trace_complete:
        event_counts = {
            "registeredTasks": sum(event.get("kind") == "TASK_REGISTERED" for event in events if isinstance(event, dict)),
            "boundMeshes": sum(event.get("kind") == "MESH_BOUND" for event in events if isinstance(event, dict)),
            "allowedPublications": sum(
                event.get("kind") == "PUBLICATION" and event.get("decision") == "ALLOW_CURRENT"
                for event in events if isinstance(event, dict)
            ),
            "rejectedStalePublications": sum(
                event.get("kind") == "PUBLICATION" and event.get("decision") == "REJECT_STALE"
                for event in events if isinstance(event, dict)
            ),
            "baselinePublicationsAfterFailOpen": sum(
                event.get("kind") == "PUBLICATION" and event.get("decision") == "BASELINE_ALLOW"
                for event in events if isinstance(event, dict)
            ),
            "failOpenCount": sum(event.get("kind") == "FAIL_OPEN" for event in events if isinstance(event, dict)),
        }
        for field, observed in event_counts.items():
            expected = _u64(snapshot.get(field), f"evidence.snapshot.{field}", [])
            if expected is not None and expected != observed:
                errors.append(
                    f"evidence.snapshot.{field}={expected} disagrees with complete event trace count={observed}"
                )
    complete = not errors and validation_status == "passed" and dropped_value == 0
    nonbaseline_decisions = [decision for decision in decisions if decision in {"ALLOW_CURRENT", "REJECT_STALE"}]
    guard_active = bool(
        snapshot is not None
        and snapshot.get("requested") is True
        and snapshot.get("active") is True
        and snapshot.get("failOpen") is False
        and hooks_observed is True
        and capacity is not None
        and capacity > 0
        and nonbaseline_decisions
        and dropped_value == 0
        and not errors
    )
    summary = {
        "requested": requested,
        "mixinHooksObserved": hooks_observed,
        "eventCount": len(events),
        "droppedEvents": dropped_value,
        "decisionCount": len(decisions),
        "nonbaselineDecisionCount": len(nonbaseline_decisions),
        "eventCapacity": capacity,
        "guardActive": guard_active,
        "failOpen": snapshot.get("failOpen") if snapshot else None,
        "failOpenReason": snapshot.get("failOpenReason") if snapshot else None,
    }
    return result(
        complete,
        guard_active,
        errors,
        summary,
        validation_status=validation_status,
        dropped_events=dropped_value,
    )


def result(
    complete: bool,
    guard_active: bool,
    errors: list[str],
    summary: dict[str, Any],
    *,
    validation_status: str | None = None,
    dropped_events: int = 0,
) -> dict[str, Any]:
    if errors:
        state = "rejected-invalid-evidence"
    elif dropped_events > 0:
        state = "valid-lossy"
    elif validation_status in {"failed", "performance-failed"}:
        state = "valid-failed-trial"
    elif validation_status == "environment-blocked":
        state = "valid-environment-blocked"
    elif validation_status != "passed":
        state = "valid-incomplete"
    else:
        state = "diagnostic-integrity"
    return {
        "schema_version": SCHEMA_VERSION,
        "acceptance": ACCEPTANCE,
        "state": state,
        "complete": complete,
        "guard_active": guard_active,
        "errors": errors,
        "summary": summary,
    }


def _fixture(*, fail_open: bool = False, duplicate_sequence: bool = False) -> dict[str, Any]:
    snapshot = {
        "requested": True,
        "active": not fail_open,
        "failOpen": fail_open,
        "failOpenReason": "TASK_CAPACITY" if fail_open else "NONE",
        "sectionVersionEntries": 1,
        "trackedTasks": 0,
        "trackedMeshes": 0,
        "worldEpoch": "2",
        "materialGeneration": "3",
        "registeredTasks": "1",
        "untrackedInvalidations": "0",
        "cancelledObsoleteTasks": "0",
        "boundMeshes": "0",
        "allowedPublications": "0" if fail_open else "1",
        "rejectedStalePublications": "0",
        "baselinePublicationsAfterFailOpen": "1" if fail_open else "0",
        "failOpenCount": "1" if fail_open else "0",
        "sectionCapacityFailOpenCount": "0",
        "taskCapacityFailOpenCount": "0",
        "meshCapacityFailOpenCount": "0",
        "unknownTaskFailOpenCount": "0",
        "unknownPublicationFailOpenCount": "0",
        "meshReboundFailOpenCount": "0",
    }
    current = {
        "worldEpoch": "2",
        "sectionId": "7",
        "geometryRevision": "4",
        "lightingRevision": "5",
        "materialGeneration": "3",
    }
    events = [
        {
            "sequence": "1",
            "kind": "TASK_REGISTERED",
            "worldEpoch": "2",
            "materialGeneration": "3",
            "sectionId": "7",
            "captured": current,
            "current": current,
            "decision": None,
            "cancelled": False,
            "reason": "NONE",
        },
        {
            "sequence": "3" if fail_open and not duplicate_sequence else ("2" if not duplicate_sequence else "1"),
            "kind": "PUBLICATION",
            "worldEpoch": "2",
            "materialGeneration": "3",
            "sectionId": "7",
            "captured": current,
            "current": None if fail_open else current,
            "decision": "ALLOW_CURRENT" if not fail_open else "BASELINE_ALLOW",
            "cancelled": False,
            "reason": "NONE" if not fail_open else "FAIL_OPEN",
        },
    ]
    if fail_open:
        events.insert(1, {
            "sequence": "2",
            "kind": "FAIL_OPEN",
            "worldEpoch": "2",
            "materialGeneration": "3",
            "sectionId": None,
            "captured": None,
            "current": None,
            "decision": None,
            "cancelled": False,
            "reason": "TASK_CAPACITY",
        })
    return {
        "schemaVersion": 1,
        "evidenceClass": "diagnostic",
        "performanceEligible": False,
        "source": {
            "sourceSha": "0123456789abcdef0123456789abcdef01234567",
            "minecraftVersion": "26.3",
            "trialId": "t1b-fixture",
        },
        "validationStatus": "passed",
        "requested": True,
        "mixinHooksObserved": True,
        "scope": "process-observation-including-warmup",
        "limitation": "decision evidence only; no task/mesh ownership chain or performance acceptance",
        "evidence": {
            "snapshot": snapshot,
            "events": events,
            "droppedEvents": "0",
            "eventCapacity": 64,
        },
    }


def self_test() -> None:
    valid = evaluate(_fixture())
    assert valid["complete"] and valid["guard_active"]
    fail_open = evaluate(_fixture(fail_open=True))
    assert fail_open["complete"] and not fail_open["guard_active"]
    duplicate = evaluate(_fixture(duplicate_sequence=True))
    assert not duplicate["complete"]
    dropped = _fixture()
    dropped["evidence"]["droppedEvents"] = "1"
    assert not evaluate(dropped)["complete"]
    unobserved = _fixture()
    unobserved["mixinHooksObserved"] = False
    unobserved["evidence"] = None
    checked = evaluate(unobserved)
    assert checked["complete"] and not checked["guard_active"]
    assert evaluate(unobserved)["summary"]["eventCapacity"] is None
    no_trace = _fixture()
    no_trace["evidence"] = {
        "snapshot": no_trace["evidence"]["snapshot"],
        "events": [],
        "droppedEvents": "0",
        "eventCapacity": 0,
    }
    no_trace["mixinHooksObserved"] = True
    checked = evaluate(no_trace)
    assert checked["complete"] and not checked["guard_active"]
    print("verify_terrain_generation self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-active", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.report is None:
        parser.error("report is required unless --self-test is used")
    try:
        payload = json.loads(args.report.read_text(encoding="utf-8"))
        checked = evaluate(payload)
    except (OSError, json.JSONDecodeError) as exc:
        checked = result(False, False, [f"could not read report: {exc}"], {})
    if args.output:
        args.output.write_text(json.dumps(checked, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    else:
        print(json.dumps(checked, indent=2, sort_keys=True))
    if not checked["complete"]:
        return 2
    if args.require_active and not checked["guard_active"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
