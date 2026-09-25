#!/usr/bin/env python3
"""Fail-closed semantic oracle for Minecraft 26.3 terrain work evidence.

This checker is deliberately independent from the runtime producer.  It judges
only the versioned JSON contract and causal invariants needed before any terrain
performance statistic is trusted.
"""
from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 2
U64_MAX = (1 << 64) - 1
I64_MIN = -(1 << 63)
I64_MAX = (1 << 63) - 1
SHA40 = re.compile(r"^[0-9a-f]{40}$")
U64_TEXT = re.compile(r"^(0|[1-9][0-9]{0,19})$")
I64_TEXT = re.compile(r"^-?(0|[1-9][0-9]{0,18})$")

STAGES = {
    "DATA_READY",
    "QUEUED",
    "BUILD_START",
    "BUILD_END",
    "UPLOAD_QUEUED",
    "GPU_ENCODED",
    "GPU_DEPENDENCY_READY",
    "PUBLISHED",
    "FIRST_VALID_DRAW",
    "DRAW_NOT_REQUIRED",
    "GPU_COMPLETED",
    "CANCELLED",
    "RETIRED",
}
CAUSAL_PAIRS = (
    ("DATA_READY", "QUEUED"),
    ("QUEUED", "BUILD_START"),
    ("BUILD_END", "UPLOAD_QUEUED"),
    ("BUILD_START", "BUILD_END"),
    ("UPLOAD_QUEUED", "GPU_ENCODED"),
    ("GPU_ENCODED", "GPU_DEPENDENCY_READY"),
    ("GPU_DEPENDENCY_READY", "PUBLISHED"),
    ("PUBLISHED", "FIRST_VALID_DRAW"),
    ("GPU_ENCODED", "GPU_COMPLETED"),
    ("PUBLISHED", "RETIRED"),
    ("GPU_COMPLETED", "RETIRED"),
)
REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_PATH = REPO_ROOT / "validation" / "terrain-work-events" / "oracle-fixtures.json"


def _u64(value: Any, field: str, errors: list[str]) -> int | None:
    if not isinstance(value, str) or U64_TEXT.fullmatch(value) is None:
        errors.append(f"{field} must be an unsigned 64-bit decimal string")
        return None
    number = int(value)
    if number > U64_MAX:
        errors.append(f"{field} exceeds uint64")
        return None
    return number


def _i64(value: Any, field: str, errors: list[str]) -> int | None:
    if not isinstance(value, str) or I64_TEXT.fullmatch(value) is None:
        errors.append(f"{field} must be a signed 64-bit decimal string")
        return None
    number = int(value)
    if number < I64_MIN or number > I64_MAX:
        errors.append(f"{field} exceeds int64")
        return None
    return number


def _work_key(event: dict[str, Any], index: int, errors: list[str]) -> tuple[str, ...] | None:
    key = event.get("key")
    if not isinstance(key, dict):
        errors.append(f"events[{index}].key must be an object")
        return None
    required = ("worldEpoch", "sectionId", "geometryRevision", "lightingRevision", "materialGeneration")
    if set(key) != set(required):
        errors.append(f"events[{index}].key must contain exactly {required}")
        return None
    _u64(key.get("worldEpoch"), f"events[{index}].key.worldEpoch", errors)
    _i64(key.get("sectionId"), f"events[{index}].key.sectionId", errors)
    _u64(key.get("geometryRevision"), f"events[{index}].key.geometryRevision", errors)
    _u64(key.get("lightingRevision"), f"events[{index}].key.lightingRevision", errors)
    _u64(key.get("materialGeneration"), f"events[{index}].key.materialGeneration", errors)
    return tuple(str(key[name]) for name in required)


def _nearest_rank_p95(values: list[int]) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(0.95 * len(ordered)))
    return ordered[rank - 1]


def evaluate(payload: Any) -> dict[str, Any]:
    errors: list[str] = []
    if not isinstance(payload, dict):
        return {
            "schema_version": SCHEMA_VERSION,
            "accepted": False,
            "state": "rejected-invalid-evidence",
            "errors": ["report root must be an object"],
            "summary": {},
        }

    version = payload.get("schemaVersion")
    if type(version) is not int or version not in (1, 2):
        errors.append("schemaVersion must be 1 or 2")

    expected_root = {
        "schemaVersion",
        "source",
        "status",
        "observationStartNanos",
        "observationEndNanos",
        "droppedEvents",
        "overflowed",
        "failureReason",
        "events",
    }
    if version == 2:
        expected_root.add("lossScope")
    if set(payload) != expected_root:
        errors.append(f"report must contain exactly {sorted(expected_root)}")
    if version == 2 and payload.get("lossScope") != "observation":
        errors.append("schemaVersion 2 requires lossScope='observation'")

    source = payload.get("source")
    source_epoch: str | None = None
    if not isinstance(source, dict):
        errors.append("source must be an object")
    else:
        expected_source = {"sourceSha", "minecraftVersion", "trialId", "worldEpoch", "clock"}
        if set(source) != expected_source:
            errors.append(f"source must contain exactly {sorted(expected_source)}")
        sha = source.get("sourceSha")
        if not isinstance(sha, str) or SHA40.fullmatch(sha) is None:
            errors.append("source.sourceSha must be a lowercase 40-hex commit")
        if source.get("minecraftVersion") != "26.3":
            errors.append("source.minecraftVersion must be 26.3")
        trial_id = source.get("trialId")
        if not isinstance(trial_id, str) or not trial_id.strip() or len(trial_id) > 160:
            errors.append("source.trialId must be a non-empty string <= 160 characters")
        source_epoch = source.get("worldEpoch")
        _u64(source_epoch, "source.worldEpoch", errors)
        if source.get("clock") != "System.nanoTime":
            errors.append("source.clock must be System.nanoTime")

    status = payload.get("status")
    if not isinstance(status, str) or status not in {"complete", "failed", "environment-blocked"}:
        errors.append("status must be complete, failed, or environment-blocked")
    failure_reason = payload.get("failureReason")
    if status == "complete":
        if failure_reason is not None:
            errors.append("complete reports must have failureReason=null")
    elif isinstance(status, str) and status in {"failed", "environment-blocked"}:
        if not isinstance(failure_reason, str) or not failure_reason.strip():
            errors.append("failed/environment-blocked reports require failureReason")

    start = _u64(payload.get("observationStartNanos"), "observationStartNanos", errors)
    end = _u64(payload.get("observationEndNanos"), "observationEndNanos", errors)
    if start is not None and end is not None and end < start:
        errors.append("observationEndNanos precedes observationStartNanos")

    dropped = payload.get("droppedEvents")
    if isinstance(dropped, bool) or not isinstance(dropped, int) or dropped < 0:
        errors.append("droppedEvents must be a non-negative integer")
        dropped = 0
    overflowed = payload.get("overflowed")
    if not isinstance(overflowed, bool):
        errors.append("overflowed must be boolean")
        overflowed = False
    if isinstance(dropped, int) and isinstance(overflowed, bool) and (dropped > 0) != overflowed:
        errors.append("overflowed must be true exactly when droppedEvents is non-zero")

    events = payload.get("events")
    if not isinstance(events, list):
        errors.append("events must be an array")
        events = []

    groups: dict[tuple[str, ...], list[dict[str, Any]]] = {}
    previous_sequence = -1
    seen_sequences: set[int] = set()
    work_id_owners: dict[str, tuple[str, ...]] = {}
    for index, raw in enumerate(events):
        if not isinstance(raw, dict):
            errors.append(f"events[{index}] must be an object")
            continue
        expected_event = {
            "sequence",
            "key",
            "stage",
            "monotonicNanos",
            "bytes",
            "reason",
            "domain",
            "frameIndex",
            "meshGeneration",
        }
        if version == 2:
            expected_event.add("workId")
        if set(raw) != expected_event:
            errors.append(f"events[{index}] must contain exactly {sorted(expected_event)}")

        sequence = raw.get("sequence")
        if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 0:
            errors.append(f"events[{index}].sequence must be a non-negative integer")
        else:
            if sequence in seen_sequences:
                errors.append(f"duplicate event sequence {sequence}")
            if sequence <= previous_sequence:
                errors.append("event sequence must be strictly increasing in report order")
            seen_sequences.add(sequence)
            previous_sequence = max(previous_sequence, sequence)

        key = _work_key(raw, index, errors)
        if key is not None:
            if source_epoch is not None and key[0] != str(source_epoch):
                errors.append(
                    f"events[{index}] crosses world epoch: report={source_epoch}, event={key[0]}"
                )
            if version == 2:
                work_id = raw.get("workId")
                if _u64(work_id, f"events[{index}].workId", errors) is not None:
                    previous_owner = work_id_owners.setdefault(work_id, key)
                    if previous_owner != key:
                        errors.append(f"workId {work_id} was rebound to a different content identity")
                    key = key + (work_id,)
            groups.setdefault(key, []).append(raw)

        stage = raw.get("stage")
        if not isinstance(stage, str) or stage not in STAGES:
            errors.append(f"events[{index}].stage is unknown: {stage!r}")
        timestamp = _u64(raw.get("monotonicNanos"), f"events[{index}].monotonicNanos", errors)
        if timestamp is not None and start is not None and timestamp < start:
            errors.append(f"events[{index}] predates observationStartNanos")
        if timestamp is not None and end is not None and timestamp > end:
            errors.append(f"events[{index}] exceeds observationEndNanos")

        byte_count = raw.get("bytes")
        if isinstance(byte_count, bool) or not isinstance(byte_count, int) or byte_count < 0:
            errors.append(f"events[{index}].bytes must be a non-negative integer")
        reason = raw.get("reason")
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 160:
            errors.append(f"events[{index}].reason must be a non-empty string <= 160 characters")
        domain = raw.get("domain")
        if (
            not isinstance(domain, str)
            or not domain
            or len(domain) > 96
            or re.fullmatch(r"[A-Za-z0-9._/-]+", domain) is None
        ):
            errors.append(f"events[{index}].domain is invalid")
        frame_index = raw.get("frameIndex")
        if frame_index is not None and (
            isinstance(frame_index, bool) or not isinstance(frame_index, int) or frame_index < 0
        ):
            errors.append(f"events[{index}].frameIndex must be null or a non-negative integer")
        mesh_generation = raw.get("meshGeneration")
        if mesh_generation is not None:
            _u64(mesh_generation, f"events[{index}].meshGeneration", errors)
        if isinstance(stage, str) and stage in {"PUBLISHED", "FIRST_VALID_DRAW", "DRAW_NOT_REQUIRED"} and mesh_generation is None:
            errors.append(f"events[{index}] {stage} requires meshGeneration")
        if stage == "FIRST_VALID_DRAW" and frame_index is None:
            errors.append(f"events[{index}] FIRST_VALID_DRAW requires frameIndex")

    # Schema errors are verdicts, not exceptions from subsequent arithmetic.
    # Never coerce an invalid timestamp/stage into a plausible lifecycle.
    if errors:
        return {
            "schema_version": SCHEMA_VERSION,
            "accepted": False,
            "state": "rejected-invalid-evidence",
            "errors": errors,
            "summary": {"observedWorkItems": len(groups), "latencySampleCount": 0},
        }

    latencies: list[int] = []
    published_items = 0
    first_draw_items = 0
    missing_first_draw_items = 0
    cancelled_items = 0
    retired_items = 0
    no_draw_items = 0
    unfinished_items = 0
    durations: dict[str, list[int]] = {name: [] for name in ("queue", "build", "uploadToPublish")}

    for key, work_events in groups.items():
        timestamps: list[int] = []
        positions: dict[str, list[int]] = {}
        for position, event in enumerate(work_events):
            timestamp_errors: list[str] = []
            timestamp = _u64(event.get("monotonicNanos"), "event.monotonicNanos", timestamp_errors)
            if timestamp is not None:
                timestamps.append(timestamp)
            positions.setdefault(str(event.get("stage")), []).append(position)
        if any(right < left for left, right in zip(timestamps, timestamps[1:])):
            errors.append(f"per-work monotonic timestamp inversion for key={key}")

        if len(positions.get("PUBLISHED", [])) > 1:
            errors.append(f"work key published more than once: key={key}")
        if len(positions.get("FIRST_VALID_DRAW", [])) > 1:
            errors.append(f"work key reported FIRST_VALID_DRAW more than once: key={key}")
        if len(positions.get("RETIRED", [])) > 1:
            errors.append(f"work key retired more than once: key={key}")
        for unique_stage in ("DATA_READY", "QUEUED", "BUILD_START", "BUILD_END", "CANCELLED", "DRAW_NOT_REQUIRED"):
            if len(positions.get(unique_stage, [])) > 1:
                errors.append(f"duplicate {unique_stage} for key={key}")

        for before, after in CAUSAL_PAIRS:
            if before in positions and after in positions and min(positions[before]) > min(positions[after]):
                errors.append(f"causal order violation {before}->{after} for key={key}")

        retired_positions = positions.get("RETIRED", [])
        if retired_positions and max(retired_positions) != len(work_events) - 1:
            errors.append(f"events occur after RETIRED for key={key}")

        cancelled_positions = positions.get("CANCELLED", [])
        if cancelled_positions:
            cancelled_items += 1
            # CANCELLED belongs to unpublished work. A published mesh is
            # invalidated through RETIRED, never relabelled as cancelled work.
            for forbidden in ("PUBLISHED", "FIRST_VALID_DRAW", "DRAW_NOT_REQUIRED"):
                if positions.get(forbidden):
                    errors.append(f"{forbidden} coexists with CANCELLED for key={key}")

        published = [event for event in work_events if event.get("stage") == "PUBLISHED"]
        first_draw = [event for event in work_events if event.get("stage") == "FIRST_VALID_DRAW"]
        no_draw = [event for event in work_events if event.get("stage") == "DRAW_NOT_REQUIRED"]
        if no_draw:
            no_draw_items += 1
            if first_draw or not published or no_draw[0].get("meshGeneration") != published[0].get("meshGeneration"):
                errors.append(f"DRAW_NOT_REQUIRED must identify a published generation with no draw: key={key}")
            elif positions["DRAW_NOT_REQUIRED"][0] < positions["PUBLISHED"][0]:
                errors.append(f"DRAW_NOT_REQUIRED precedes publication: key={key}")
        if published:
            published_items += 1
            # The runtime cannot publish before its build ends. Some producers
            # additionally expose a GPU dependency boundary; encoded work alone
            # must not be relabelled GPU-completed or ready.
            publication = positions["PUBLISHED"][0]
            ready = positions.get("GPU_DEPENDENCY_READY", positions.get("BUILD_END", []))
            if not ready or min(ready) >= publication:
                errors.append(f"PUBLISHED has no prior ready predecessor for key={key}")
        if first_draw:
            first_draw_items += 1
            if not published:
                errors.append(f"FIRST_VALID_DRAW has no prior PUBLISHED event for key={key}")
            else:
                published_generation = published[0].get("meshGeneration")
                draw_generation = first_draw[0].get("meshGeneration")
                if published_generation != draw_generation:
                    errors.append(
                        f"FIRST_VALID_DRAW mesh generation {draw_generation} does not match "
                        f"published generation {published_generation} for key={key}"
                    )
            data_ready = [event for event in work_events if event.get("stage") == "DATA_READY"]
            if not data_ready:
                errors.append(f"FIRST_VALID_DRAW has no DATA_READY origin for key={key}")
            else:
                start_nanos = int(data_ready[0]["monotonicNanos"])
                draw_nanos = int(first_draw[0]["monotonicNanos"])
                if draw_nanos >= start_nanos:
                    latencies.append(draw_nanos - start_nanos)

        if published and not first_draw and not no_draw and not cancelled_positions:
            missing_first_draw_items += 1
        if not first_draw and not no_draw and not cancelled_positions and not retired_positions:
            unfinished_items += 1
        if positions.get("RETIRED"):
            retired_items += 1
        for name, before, after in (("queue", "QUEUED", "BUILD_START"),
                                    ("build", "BUILD_START", "BUILD_END"),
                                    ("uploadToPublish", "UPLOAD_QUEUED", "PUBLISHED")):
            if before in positions and after in positions:
                left = work_events[positions[before][0]].get("monotonicNanos")
                right = work_events[positions[after][0]].get("monotonicNanos")
                if isinstance(left, str) and isinstance(right, str) and left.isdigit() and right.isdigit():
                    if int(right) >= int(left):
                        durations[name].append(int(right) - int(left))

    if errors:
        return {
            "schema_version": SCHEMA_VERSION,
            "accepted": False,
            "state": "rejected-invalid-evidence",
            "errors": errors,
            "summary": {
                "observedWorkItems": len(groups),
                "latencySampleCount": len(latencies),
            },
        }

    p95 = _nearest_rank_p95(latencies)
    lossy = bool(dropped) or bool(overflowed)
    performance_eligible = (
        status == "complete"
        and not lossy
        and len(latencies) > 0
        and missing_first_draw_items == 0
        and unfinished_items == 0
    )
    if status == "failed":
        state = "valid-failed-trial"
    elif status == "environment-blocked":
        state = "valid-environment-blocked"
    elif lossy:
        state = "valid-lossy"
    elif not performance_eligible:
        state = "valid-incomplete"
    else:
        state = "valid-complete"

    return {
        "schema_version": SCHEMA_VERSION,
        "accepted": True,
        "state": state,
        "errors": [],
        "performanceEligible": performance_eligible,
        "summary": {
            "observedWorkItems": len(groups),
            "publishedItems": published_items,
            "firstValidDrawItems": first_draw_items,
            "missingFirstValidDrawItems": missing_first_draw_items,
            "cancelledItems": cancelled_items,
            "retiredItems": retired_items,
            "noDrawRequiredItems": no_draw_items,
            "unfinishedItems": unfinished_items,
            "droppedEvents": int(dropped),
            "latencySampleCount": len(latencies),
            "firstValidDrawP95Nanos": None if p95 is None else str(p95),
            "stageDurationsNanos": {
                name: {"samples": len(values), **{
                    f"p{percentile}": str(sorted(values)[max(0, math.ceil(percentile / 100 * len(values)) - 1)]) if values else None
                    for percentile in (50, 95, 99)}}
                for name, values in durations.items()
            },
        },
    }


def _merge_report(defaults: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    merged = json.loads(json.dumps(defaults))
    for key, value in overrides.items():
        if key == "source" and isinstance(value, dict):
            merged.setdefault("source", {}).update(value)
        else:
            merged[key] = value
    return merged


def _value_at(result: dict[str, Any], dotted: str) -> Any:
    value: Any = result
    for part in dotted.split("."):
        if not isinstance(value, dict) or part not in value:
            raise AssertionError(f"missing result path {dotted}")
        value = value[part]
    return value


def validate_baseline(payload: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(payload, dict):
        return ["baseline manifest root must be an object"]
    if payload.get("schemaVersion") != 1:
        errors.append("baseline schemaVersion must be 1")
    if payload.get("repository") != "21Z121Z1/MetalUniversal":
        errors.append("baseline repository mismatch")
    if payload.get("referenceCommit") != "5694bdbd04d7754e0df8c523786a6a8ee95a246b":
        errors.append("baseline referenceCommit mismatch")
    if payload.get("referenceTree") != "d5bf91fcefc550acb62a91345544aaac88228b35":
        errors.append("baseline referenceTree mismatch")
    minecraft = payload.get("minecraft")
    if not isinstance(minecraft, dict):
        errors.append("baseline minecraft block missing")
    else:
        if minecraft.get("version") != "26.3":
            errors.append("baseline Minecraft version mismatch")
        if minecraft.get("decompiledJavaFileCount") != 7301:
            errors.append("baseline source file count mismatch")
        if minecraft.get("clientSha1") != "e877b6a07acd633fb3bb475002175cec036e7b87":
            errors.append("baseline client SHA-1 mismatch")
    evidence = payload.get("evidence")
    if not isinstance(evidence, dict) or evidence.get("pairedAppleSiliconPerformanceAvailable") is not False:
        errors.append("baseline must explicitly state paired performance is unavailable")
    order = payload.get("implementationOrder")
    if order != ["P0a", "P0b", "T1-or-R1-after-evidence"]:
        errors.append("baseline implementation order mismatch")
    return errors


def self_test() -> None:
    fixture_pack = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    assert fixture_pack.get("schemaVersion") == 1
    defaults = fixture_pack["defaults"]
    cases = fixture_pack["cases"]
    for case in cases:
        report = _merge_report(defaults, case.get("reportOverrides", {}))
        result = evaluate(report)
        expected = case["expect"]
        assert result["accepted"] is expected["accepted"], (case["name"], result)
        assert result["state"] == expected["state"], (case["name"], result)
        for dotted, value in expected.get("values", {}).items():
            assert _value_at(result, dotted) == value, (case["name"], dotted, result)
        contains = expected.get("errorContains")
        if contains is not None:
            assert any(contains in error for error in result["errors"]), (case["name"], result)

    print(f"terrain work oracle self-test: PASS ({len(cases)} fixtures)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--check-baseline", type=Path)
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    if args.check_baseline is not None:
        try:
            payload = json.loads(args.check_baseline.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"baseline manifest unreadable: {exc}")
            return 2
        errors = validate_baseline(payload)
        if errors:
            print(json.dumps({"state": "rejected-invalid-baseline", "errors": errors}, indent=2))
            return 2
        print("terrain P0 baseline manifest: PASS")
        return 0

    if args.report is None:
        parser.error("report is required unless --self-test or --check-baseline is used")
    try:
        payload = json.loads(args.report.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        result = {
            "schema_version": SCHEMA_VERSION,
            "accepted": False,
            "state": "rejected-invalid-evidence",
            "errors": [f"could not read report: {exc}"],
            "summary": {},
        }
    else:
        result = evaluate(payload)

    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["accepted"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
