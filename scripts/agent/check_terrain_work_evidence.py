#!/usr/bin/env python3
"""Validate and summarize bounded terrain work lifecycle evidence.

This checker is intentionally independent of the runtime recorder. It rejects
identity/timing/lifecycle contradictions before terrain metrics are allowed into
the unified evaluation path. It never infers missing stages or joins work across
world epochs.
"""
from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "docs/agent/terrain-work-evidence.schema.json"
FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures/terrain-work-evidence"

STAGES = (
    "DATA_READY", "QUEUED", "BUILD_START", "BUILD_END", "UPLOAD_QUEUED",
    "GPU_ENCODED", "GPU_DEPENDENCY_READY", "PUBLISHED", "FIRST_VALID_DRAW",
    "GPU_COMPLETED", "CANCELLED", "RETIRED",
)
KEY_FIELDS = (
    "worldEpoch", "sectionId", "geometryRevision",
    "lightingRevision", "materialGeneration",
)
REQUIRED_EVENT_FIELDS = {
    "sequence", "domain", "key", "stage", "monotonicNanos", "bytes",
    "reason", "frameId", "meshGeneration", "drawExpected",
}


class EvidenceError(ValueError):
    pass


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def _load(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"unreadable-json: {exc}") from exc
    _require(isinstance(data, dict), "root-not-object")
    return data


def _validate_schema_contract() -> None:
    schema = _load(SCHEMA_PATH)
    _require(
        schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema",
        "schema-draft-mismatch",
    )
    _require(
        schema.get("properties", {}).get("schemaVersion", {}).get("const")
        == SCHEMA_VERSION,
        "schema-version-mismatch",
    )
    event = schema.get("$defs", {}).get("event", {})
    _require(
        REQUIRED_EVENT_FIELDS.issubset(set(event.get("required", []))),
        "schema-event-required-fields-incomplete",
    )
    _require(
        tuple(event.get("properties", {}).get("stage", {}).get("enum", []))
        == STAGES,
        "schema-stage-enum-mismatch",
    )


def _validate_root(data: dict[str, Any]) -> None:
    required = {
        "schemaVersion", "sourceSha", "trialStatus", "complete",
        "droppedEvents", "observationWindowEndNanos", "events",
    }
    _require(required.issubset(data), f"missing-root-fields:{sorted(required - set(data))}")
    _require(data["schemaVersion"] == SCHEMA_VERSION, "unsupported-schema-version")
    source = data["sourceSha"]
    _require(
        isinstance(source, str)
        and len(source) == 40
        and all(ch in "0123456789abcdef" for ch in source),
        "invalid-source-sha",
    )
    _require(
        data["trialStatus"] in {"passed", "failed", "cancelled", "incomplete"},
        "invalid-trial-status",
    )
    _require(isinstance(data["complete"], bool), "invalid-complete")
    _require(
        _is_int(data["droppedEvents"]) and data["droppedEvents"] >= 0,
        "invalid-dropped-events",
    )
    _require(
        _is_int(data["observationWindowEndNanos"])
        and data["observationWindowEndNanos"] >= 0,
        "invalid-window-end",
    )
    _require(isinstance(data["events"], list), "events-not-array")


def _validate_event(event: Any) -> tuple[Any, ...]:
    _require(isinstance(event, dict), "event-not-object")
    missing = REQUIRED_EVENT_FIELDS - set(event)
    _require(not missing, f"missing-event-fields:{sorted(missing)}")
    _require(_is_int(event["sequence"]) and event["sequence"] >= 0, "invalid-sequence")
    _require(isinstance(event["domain"], str) and event["domain"], "invalid-domain")
    key = event["key"]
    _require(isinstance(key, dict), "key-not-object")
    _require(set(key) == set(KEY_FIELDS), "invalid-key-fields")
    for field in KEY_FIELDS:
        _require(_is_int(key[field]), f"invalid-key:{field}")
    _require(key["worldEpoch"] >= 0, "negative-world-epoch")
    _require(key["geometryRevision"] >= 0, "negative-geometry-revision")
    _require(key["lightingRevision"] >= 0, "negative-lighting-revision")
    _require(key["materialGeneration"] >= 0, "negative-material-generation")
    _require(event["stage"] in STAGES, "invalid-stage")
    _require(
        _is_int(event["monotonicNanos"]) and event["monotonicNanos"] >= 0,
        "invalid-monotonic-nanos",
    )
    _require(_is_int(event["bytes"]) and event["bytes"] >= 0, "invalid-bytes")
    _require(isinstance(event["reason"], str) and event["reason"], "invalid-reason")
    _require(
        event["frameId"] is None
        or (_is_int(event["frameId"]) and event["frameId"] >= 0),
        "invalid-frame-id",
    )
    _require(
        event["meshGeneration"] is None
        or (_is_int(event["meshGeneration"]) and event["meshGeneration"] >= 0),
        "invalid-mesh-generation",
    )
    _require(isinstance(event["drawExpected"], bool), "invalid-draw-expected")
    if event["stage"] in {"PUBLISHED", "FIRST_VALID_DRAW"}:
        _require(
            event["meshGeneration"] is not None,
            f"{event['stage'].lower()}-missing-mesh-generation",
        )
    if event["stage"] == "FIRST_VALID_DRAW":
        _require(event["frameId"] is not None, "first-valid-draw-missing-frame")
    return (event["domain"], *(key[field] for field in KEY_FIELDS))


def _nearest_rank(values: list[int], quantile: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(quantile * len(ordered)))
    return ordered[rank - 1]


def _metric(values: list[int], unit: str = "nanoseconds") -> dict[str, Any]:
    if not values:
        return {
            "available": False,
            "sampleCount": 0,
            "unit": unit,
            "reason": "no eligible completed samples",
        }
    return {
        "available": True,
        "sampleCount": len(values),
        "unit": unit,
        "p50": _nearest_rank(values, 0.50),
        "p95": _nearest_rank(values, 0.95),
        "p99": _nearest_rank(values, 0.99),
        "maximum": max(values),
    }


def _event_time(stage_map: dict[str, dict[str, Any]], stage: str) -> int | None:
    event = stage_map.get(stage)
    return None if event is None else int(event["monotonicNanos"])


def _check_order(
    stage_map: dict[str, dict[str, Any]], earlier: str, later: str
) -> None:
    left = _event_time(stage_map, earlier)
    right = _event_time(stage_map, later)
    if left is not None and right is not None:
        _require(right >= left, f"timing-inversion:{earlier}->{later}")


def evaluate(data: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
    try:
        _validate_root(data)
    except EvidenceError as exc:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "state": "rejected-invalid-evidence",
            "errors": [str(exc)],
            "acceptanceEligible": False,
            "metrics": {},
        }

    if data["trialStatus"] != "passed" or not data["complete"]:
        errors.append("failed-or-incomplete-trial")
    if data["droppedEvents"] != 0:
        errors.append("dropped-events")
    if not data["events"]:
        errors.append("empty-sample")

    chains: dict[tuple[Any, ...], dict[str, dict[str, Any]]] = defaultdict(dict)
    sequence_seen: set[int] = set()
    logical_publications: dict[tuple[Any, ...], tuple[Any, ...]] = {}

    for raw in data["events"]:
        try:
            chain_key = _validate_event(raw)
        except EvidenceError as exc:
            errors.append(str(exc))
            continue
        sequence = int(raw["sequence"])
        if sequence in sequence_seen:
            errors.append("duplicate-sequence")
        sequence_seen.add(sequence)
        stage = str(raw["stage"])
        if stage in chains[chain_key]:
            errors.append(f"duplicate-stage:{stage}")
        else:
            chains[chain_key][stage] = raw
        if stage == "PUBLISHED":
            key = raw["key"]
            logical = (
                raw["domain"],
                key["sectionId"],
                key["geometryRevision"],
                key["lightingRevision"],
                key["materialGeneration"],
                raw["meshGeneration"],
            )
            logical_publications[logical] = chain_key

    latencies: list[int] = []
    queue_waits: list[int] = []
    build_times: list[int] = []
    upload_waits: list[int] = []
    cancelled_count = 0
    incomplete_required_draws = 0
    first_draw_count = 0

    for stage_map in chains.values():
        try:
            for pair in (
                ("DATA_READY", "QUEUED"),
                ("QUEUED", "BUILD_START"),
                ("BUILD_START", "BUILD_END"),
                ("BUILD_END", "UPLOAD_QUEUED"),
                ("UPLOAD_QUEUED", "GPU_ENCODED"),
                ("GPU_ENCODED", "GPU_DEPENDENCY_READY"),
                ("PUBLISHED", "FIRST_VALID_DRAW"),
                ("GPU_ENCODED", "GPU_COMPLETED"),
            ):
                _check_order(stage_map, *pair)

            if "PUBLISHED" in stage_map:
                published = stage_map["PUBLISHED"]
                if "GPU_DEPENDENCY_READY" in stage_map:
                    _check_order(stage_map, "GPU_DEPENDENCY_READY", "PUBLISHED")
                elif "BUILD_END" in stage_map:
                    _check_order(stage_map, "BUILD_END", "PUBLISHED")
                else:
                    raise EvidenceError("published-without-ready-predecessor")
                if published["drawExpected"] and "FIRST_VALID_DRAW" not in stage_map:
                    incomplete_required_draws += 1
                    raise EvidenceError("missing-first-valid-draw")

            if "FIRST_VALID_DRAW" in stage_map:
                first = stage_map["FIRST_VALID_DRAW"]
                first_draw_count += 1
                if "PUBLISHED" not in stage_map:
                    key = first["key"]
                    logical = (
                        first["domain"],
                        key["sectionId"],
                        key["geometryRevision"],
                        key["lightingRevision"],
                        key["materialGeneration"],
                        first["meshGeneration"],
                    )
                    if logical in logical_publications:
                        raise EvidenceError("cross-world-contamination")
                    raise EvidenceError("first-valid-draw-without-publication")
                published = stage_map["PUBLISHED"]
                if first["meshGeneration"] != published["meshGeneration"]:
                    raise EvidenceError("first-valid-draw-generation-mismatch")
                if published["drawExpected"]:
                    ready = stage_map.get("DATA_READY")
                    if ready is None:
                        raise EvidenceError("draw-expected-chain-missing-data-ready")
                    latencies.append(
                        int(first["monotonicNanos"])
                        - int(ready["monotonicNanos"])
                    )

            if "CANCELLED" in stage_map:
                cancelled_count += 1
                if "PUBLISHED" in stage_map or "FIRST_VALID_DRAW" in stage_map:
                    raise EvidenceError("cancelled-work-published-or-drawn")
                if "GPU_ENCODED" in stage_map:
                    _check_order(stage_map, "GPU_ENCODED", "CANCELLED")

            if "QUEUED" in stage_map and "BUILD_START" in stage_map:
                queue_waits.append(
                    int(stage_map["BUILD_START"]["monotonicNanos"])
                    - int(stage_map["QUEUED"]["monotonicNanos"])
                )
            if "BUILD_START" in stage_map and "BUILD_END" in stage_map:
                build_times.append(
                    int(stage_map["BUILD_END"]["monotonicNanos"])
                    - int(stage_map["BUILD_START"]["monotonicNanos"])
                )
            if "UPLOAD_QUEUED" in stage_map and "GPU_DEPENDENCY_READY" in stage_map:
                upload_waits.append(
                    int(stage_map["GPU_DEPENDENCY_READY"]["monotonicNanos"])
                    - int(stage_map["UPLOAD_QUEUED"]["monotonicNanos"])
                )
        except EvidenceError as exc:
            errors.append(str(exc))

    metrics = {
        "firstValidDrawLatencyNanos": _metric(latencies),
        "queueWaitNanos": _metric(queue_waits),
        "buildExecutionNanos": _metric(build_times),
        "uploadDependencyWaitNanos": _metric(upload_waits),
        "firstValidDrawCount": {
            "available": True, "sampleCount": 1, "unit": "count",
            "value": first_draw_count,
        },
        "cancelledWorkCount": {
            "available": True, "sampleCount": 1, "unit": "count",
            "value": cancelled_count,
        },
        "incompleteRequiredDrawCount": {
            "available": True, "sampleCount": 1, "unit": "count",
            "value": incomplete_required_draws,
        },
    }

    if errors:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "state": "rejected-invalid-evidence",
            "errors": sorted(set(errors)),
            "acceptanceEligible": False,
            "metrics": metrics,
        }
    if not latencies:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "state": "inconclusive-no-completed-required-draw",
            "errors": [],
            "acceptanceEligible": False,
            "metrics": metrics,
        }
    return {
        "schemaVersion": SCHEMA_VERSION,
        "state": "passed",
        "errors": [],
        "acceptanceEligible": True,
        "metrics": metrics,
    }


def check(path: Path) -> dict[str, Any]:
    try:
        return evaluate(_load(path))
    except EvidenceError as exc:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "state": "rejected-invalid-evidence",
            "errors": [str(exc)],
            "acceptanceEligible": False,
            "metrics": {},
        }


def self_test() -> None:
    _validate_schema_contract()
    cases: dict[str, tuple[str, str | None]] = {
        "valid.json": ("passed", None),
        "empty.json": ("rejected-invalid-evidence", "empty-sample"),
        "missing-first-draw.json": (
            "rejected-invalid-evidence", "missing-first-valid-draw"
        ),
        "wrong-generation.json": (
            "rejected-invalid-evidence", "first-valid-draw-generation-mismatch"
        ),
        "timing-inversion.json": (
            "rejected-invalid-evidence",
            "timing-inversion:BUILD_START->BUILD_END",
        ),
        "cancelled.json": ("passed", None),
        "failed-trial.json": (
            "rejected-invalid-evidence", "failed-or-incomplete-trial"
        ),
        "duplicate-frame.json": (
            "rejected-invalid-evidence", "duplicate-stage:FIRST_VALID_DRAW"
        ),
        "zero-denominator.json": (
            "inconclusive-no-completed-required-draw", None
        ),
        "outlier.json": ("passed", None),
        "cross-world.json": (
            "rejected-invalid-evidence", "cross-world-contamination"
        ),
    }
    for name, (expected_state, expected_error) in cases.items():
        result = check(FIXTURE_ROOT / name)
        assert result["state"] == expected_state, (name, result)
        if expected_error is not None:
            assert expected_error in result["errors"], (name, result)
    cancelled = check(FIXTURE_ROOT / "cancelled.json")
    assert cancelled["metrics"]["cancelledWorkCount"]["value"] == 1
    outlier = check(FIXTURE_ROOT / "outlier.json")
    assert outlier["metrics"]["firstValidDrawLatencyNanos"]["p95"] == 10_000_000
    zero = check(FIXTURE_ROOT / "zero-denominator.json")
    assert not zero["metrics"]["firstValidDrawLatencyNanos"]["available"]
    print("check_terrain_work_evidence self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.evidence is None:
        parser.error("evidence is required unless --self-test is used")
    result = check(args.evidence)
    payload = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")
    return 0 if result["state"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
