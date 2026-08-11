#!/usr/bin/env python3
"""Fail-closed validator for physical Iris Metal runtime receipts."""

import argparse
import json
import pathlib
import sys


def read_jsonl(path):
    records = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {error}") from error
    if not records:
        raise ValueError(f"{path}: no receipt records")
    return records


def require(condition, reason, failures):
    if not condition:
        failures.append(reason)
    return condition


def frame_events(records):
    result = {}
    for record in records:
        if record.get("type") not in {"event", "color-space"} or "frame" not in record:
            continue
        key = (record.get("generation"), record.get("frame"))
        result.setdefault(key, []).append(record.get("event"))
    return result


def control_events(records):
    return {record.get("event") for record in records if record.get("type") == "event"}


def records_of_type(records, record_type):
    return [record for record in records if record.get("type") == record_type]


def check_runtime_identity(records, args, failures):
    device_records = records_of_type(records, "device")
    require(device_records, "runtime receipt has no device identity record", failures)
    if not device_records:
        return

    matched = [record for record in device_records if record.get("codeIdentityMatched") is True]
    require(matched, "runtime receipt has no matching code identity", failures)

    native_hashes = {
        record.get("nativeDylibSha256")
        for record in device_records
        if record.get("nativeDylibSha256")
    }
    require(native_hashes, "runtime receipt has no native dylib SHA-256", failures)
    require(
        len(native_hashes) <= 1,
        f"runtime receipt contains multiple native dylib identities: {sorted(native_hashes)}",
        failures,
    )

    def require_expected(field, expected, label):
        if expected is None or expected == "":
            return
        actual = {record.get(field) for record in device_records}
        require(
            actual == {expected},
            f"{label} mismatch: expected {expected}, got {sorted(actual, key=lambda value: str(value))}",
            failures,
        )

    require_expected("sourceCommit", args.expected_source_commit, "source commit")
    require_expected("codeSource", args.expected_code_source, "code source")
    require_expected("codeSourceSha256", args.expected_code_sha256, "code source SHA-256")
    require_expected("nativeDylibSha256", args.expected_native_dylib_sha256, "native dylib SHA-256")
    require_expected("artifactJarSha256", args.expected_artifact_jar_sha256, "artifact JAR SHA-256")
    if args.expected_artifact_jar_sha256:
        require(
            all(record.get("artifactJarIdentityMatched") is True for record in device_records),
            "runtime receipt has no matching artifact JAR identity",
            failures,
        )
    if args.expected_native_dylib_sha256:
        require(
            all(record.get("nativeDylibIdentityMatched") is True for record in device_records),
            "runtime receipt has no matching native dylib identity",
            failures,
        )


def check_color_space_finalization(records, final_frames, failures):
    color_events = records_of_type(records, "color-space")
    require(color_events, "runtime receipt has no color-space finalization record", failures)
    if not color_events:
        return

    events_by_frame = frame_events(records)
    for final_frame in final_frames:
        key = (final_frame.get("generation"), final_frame.get("frame"))
        events = events_by_frame.get(key, [])
        require(
            "color-space.finalized" in events,
            f"final frame {key} has no color-space.finalized event",
            failures,
        )
        if "color-space.finalized" not in events:
            continue
        positions = {event: events.index(event) for event in set(events)}
        require(
            "final" in positions and positions["final"] < positions["color-space.finalized"],
            f"color-space finalization must follow final for frame {key}",
            failures,
        )

    final_frame_keys = {
        (record.get("generation"), record.get("frame")) for record in final_frames
    }
    sampled_color_keys = {
        (record.get("generation"), record.get("frame")) for record in color_events
    }
    require(
        final_frame_keys.issubset(sampled_color_keys),
        "at least one final-frame readback has no matching color-space finalization",
        failures,
    )

    indexed_colors = [
        (index, record)
        for index, record in enumerate(records)
        if record.get("type") == "color-space"
        and record.get("event") == "color-space.finalized"
    ]
    indexed_final_frames = [
        (index, record)
        for index, record in enumerate(records)
        if record.get("type") == "final-frame"
    ]
    for final_index, final_frame in indexed_final_frames:
        key = (final_frame.get("generation"), final_frame.get("frame"))
        matching_colors = [
            color_index
            for color_index, color_event in indexed_colors
            if (color_event.get("generation"), color_event.get("frame")) == key
        ]
        require(
            any(color_index < final_index for color_index in matching_colors),
            f"color-space.finalized must be recorded before final-frame for frame {key}",
            failures,
        )
        final_events = [
            index
            for index, record in enumerate(records)
            if record.get("type") == "event"
            and record.get("event") == "final"
            and (record.get("generation"), record.get("frame")) == key
        ]
        require(
            any(
                final_event < color_index < final_index
                for final_event in final_events
                for color_index in matching_colors
            ),
            f"finalization record order is not final -> color-space -> final-frame for frame {key}",
            failures,
        )


def check_pass_order(records, entry, failures):
    events_by_frame = frame_events(records)
    required = set(entry.get("receiptEvents", []))
    shadow_mode = entry.get("shadowMode")
    if shadow_mode == "scene-or-empty":
        required -= {"shadow.render.begin", "shadow.render.end"}
    candidate = None
    for key, events in events_by_frame.items():
        scene_present = {
            "shadow.render.begin", "shadow.render.end"
        }.issubset(events)
        empty_present = "shadow.render.empty" in events
        shadow_present = (
            scene_present if shadow_mode == "scene-or-empty" else True
        ) or (shadow_mode == "scene-or-empty" and empty_present)
        if required.issubset(events) and shadow_present:
            candidate = (key, events)
            break
    if not require(candidate is not None, "no frame contains the complete Iris pass chain", failures):
        return None

    _, events = candidate
    positions = {event: events.index(event) for event in set(events)}
    constraints = [
        ("setup", "begin"),
        ("shadow.composite", "depthtex2.capture"),
        ("depthtex2.capture", "depthtex1.capture"),
        ("depthtex1.capture", "deferred"),
        ("deferred", "depthtex0.capture"),
        ("depthtex0.capture", "composite"),
        ("composite", "final"),
    ]
    if shadow_mode == "scene-or-empty":
        if {"shadow.render.begin", "shadow.render.end"}.issubset(events):
            constraints[1:1] = [("shadow.render.begin", "shadow.render.end")]
            constraints[2:2] = [("shadow.render.end", "shadow.composite")]
        else:
            constraints[1:1] = [("shadow.render.empty", "shadow.composite")]
    else:
        constraints[1:1] = [("shadow.render.begin", "shadow.render.end")]
        constraints[2:2] = [("shadow.render.end", "shadow.composite")]
    for before, after in constraints:
        require(
            before in positions and after in positions and positions[before] < positions[after],
            f"pass order violation: {before} must precede {after}",
            failures,
        )
    return candidate[0]


def check_shadow_evidence(records, entry, failures):
    mode = entry.get("shadowEvidence")
    if mode != "scene-content-or-empty":
        return None

    events_by_frame = frame_events(records)
    scene_keys = {
        key
        for key, events in events_by_frame.items()
        if {"shadow.render.begin", "shadow.render.end"}.issubset(events)
    }
    empty_keys = {
        key
        for key, events in events_by_frame.items()
        if "shadow.render.empty" in events
    }
    shadow_frames = [record for record in records if record.get("type") == "shadow-frame"]
    shadow_stages = [record for record in records if record.get("type") == "shadow-stage"]

    if not scene_keys:
        require(empty_keys, "no shadow scene or explicit empty shadow route was recorded", failures)
        require(
            not shadow_frames,
            "explicit empty shadow route unexpectedly produced shadow-frame readbacks",
            failures,
        )
        require(
            not shadow_stages,
            "explicit empty shadow route unexpectedly produced shadow-stage readbacks",
            failures,
        )
        return {
            "mode": "empty",
            "sceneFrames": 0,
            "emptyFrames": len(empty_keys),
            "contentFrames": 0,
            "stageFrames": 0,
        }

    require(
        any((record.get("generation"), record.get("frame")) in scene_keys for record in shadow_frames),
        "shadow scene has no generation-owned shadow-frame readback",
        failures,
    )
    content_frames = [
        record
        for record in shadow_frames
        if (record.get("generation"), record.get("frame")) in scene_keys
        and record.get("shadowContentObserved") is True
    ]
    require(content_frames, "shadow scene readbacks never observed non-clear depth after a real draw", failures)
    require(
        any(record.get("shadowDrawObserved") is True for record in content_frames),
        "shadow content readback has no observed draw submission",
        failures,
    )
    stage_requirement = entry.get("shadowStageEvidence")
    if stage_requirement == "required-when-scene":
        stage_content = [
            record
            for record in shadow_stages
            if (record.get("generation"), record.get("frame")) in scene_keys
            and (
                record.get("shadowtex0NonClearSamples", 0) > 0
                or record.get("shadowtex1NonClearSamples", 0) > 0
            )
        ]
        require(shadow_stages, "shadow scene has no stage-level Metal depth readback", failures)
        require(stage_content, "shadow stage readbacks never observed non-clear depth", failures)
    restored_keys = {
        key
        for key, events in events_by_frame.items()
        if "shadow.state.restored" in events
    }
    require(
        all(key in restored_keys for key in scene_keys),
        "shadow scene frame is missing shadow.state.restored",
        failures,
    )
    return {
        "mode": "scene",
        "sceneFrames": len(scene_keys),
        "emptyFrames": len(empty_keys),
        "contentFrames": len(content_frames),
        "stageFrames": len(shadow_stages),
        "stageEvidence": stage_requirement or "not-required",
    }


def validate(args):
    matrix = json.loads(args.matrix.read_text(encoding="utf-8"))
    runtime_records = read_jsonl(args.receipt)
    control_records = read_jsonl(args.control)
    failures = []

    require(
        matrix.get("schema") == "iris-metal-opengl-semantic-matrix-v1",
        "unexpected semantic matrix schema",
        failures,
    )
    entries = matrix.get("entries", [])
    require(
        matrix.get("declaredTotal") == len(entries),
        "semantic matrix declaredTotal does not match entries",
        failures,
    )
    static_verified = sum(1 for entry in entries if entry.get("status") == "verified")
    runtime_entries = [entry for entry in entries if entry.get("status") == "runtime"]
    runtime_events = {record.get("event") for record in runtime_records if record.get("type") == "event"}
    control_event_set = control_events(control_records)
    final_frames = [record for record in runtime_records if record.get("type") == "final-frame"]
    retired_generations = {
        record.get("generation")
        for record in runtime_records
        if record.get("type") == "event" and record.get("event") == "generation-retired"
    }

    check_runtime_identity(runtime_records, args, failures)
    check_color_space_finalization(runtime_records, final_frames, failures)

    pass_order_frame = None
    shadow_evidence = None
    runtime_results = {}
    for entry in runtime_entries:
        entry_id = entry["id"]
        if entry_id == "runtime.pass-order-and-contribution":
            pass_order_frame = check_pass_order(
                runtime_records, entry, failures
            )
            shadow_evidence = check_shadow_evidence(
                runtime_records, entry, failures
            )
            runtime_results[entry_id] = pass_order_frame is not None and not failures
        elif entry_id == "runtime.reload-disable-enable":
            missing_control = set(entry.get("controlEvents", [])) - control_event_set
            missing_receipt = set(entry.get("receiptEvents", [])) - runtime_events
            require(not missing_control, f"missing control events: {sorted(missing_control)}", failures)
            require(not missing_receipt, f"missing runtime events: {sorted(missing_receipt)}", failures)
            require(
                len(retired_generations) >= 2,
                "reload/disable-enable did not retire at least two generations",
                failures,
            )
            runtime_results[entry_id] = not missing_control and not missing_receipt and len(retired_generations) >= 2
        elif entry_id == "runtime.readback-dynamic-exit":
            required_control = set(entry.get("controlEvents", []))
            missing_control = required_control - control_event_set
            require(
                not missing_control,
                f"missing control events: {sorted(missing_control)}",
                failures,
            )
            non_black = [record for record in final_frames if record.get("nonBlackRgbPixels", 0) > 0]
            dynamic = [
                record for record in final_frames
                if record.get("hasPreviousFrame")
                and record.get("changedPixels", 0) > 0
                and record.get("meanAbsoluteByteDelta", 0.0) > 0.0
            ]
            require(
                len(final_frames) >= args.min_final_frames,
                f"only {len(final_frames)} final frames, need {args.min_final_frames}",
                failures,
            )
            require(non_black, "all final readbacks are black", failures)
            require(dynamic, "final readbacks never changed across frames", failures)
            require(args.exit_status == entry.get("requiresExitStatus"),
                    f"client exit status {args.exit_status} is not normal", failures)
            result_records = [record for record in control_records if record.get("type") == "result"]
            require(
                result_records and result_records[-1].get("status") == "passed",
                "control receipt has no passed result",
                failures,
            )
            runtime_results[entry_id] = bool(result_records) and result_records[-1].get("status") == "passed" \
                and bool(non_black) and bool(dynamic) and len(final_frames) >= args.min_final_frames \
                and args.exit_status == entry.get("requiresExitStatus") \
                and not missing_control

    runtime_verified = sum(1 for entry in runtime_entries if runtime_results.get(entry["id"], False))
    total = len(entries)
    verified = static_verified + runtime_verified
    ratio = verified / total if total else 0.0
    require(ratio >= matrix.get("threshold", 1.0),
            f"semantic coverage {verified}/{total} ({ratio:.4f}) below threshold",
            failures)

    output = {
        "schema": "iris-metal-runtime-validation-v1",
        "matrix": matrix.get("schema"),
        "receipt": str(args.receipt),
        "controlReceipt": str(args.control),
        "exitStatus": args.exit_status,
        "finalFrames": len(final_frames),
        "nonBlackFrames": sum(1 for record in final_frames if record.get("nonBlackRgbPixels", 0) > 0),
        "dynamicFrames": sum(1 for record in final_frames if record.get("changedPixels", 0) > 0),
        "retiredGenerations": sorted(value for value in retired_generations if value is not None),
        "passOrderFrame": list(pass_order_frame) if pass_order_frame is not None else None,
        "shadowEvidence": shadow_evidence,
        "semanticMatrix": {
            "verified": verified,
            "total": total,
            "ratio": ratio,
            "threshold": matrix.get("threshold"),
            "staticVerified": static_verified,
            "runtimeVerified": runtime_verified,
        },
        "status": "passed" if not failures else "failed",
        "failures": failures,
    }
    if args.output:
        args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2, sort_keys=True))
    return 0 if not failures else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=pathlib.Path, required=True)
    parser.add_argument("--receipt", type=pathlib.Path, required=True)
    parser.add_argument("--control", type=pathlib.Path, required=True)
    parser.add_argument("--exit-status", type=int, required=True)
    parser.add_argument("--min-final-frames", type=int, default=60)
    parser.add_argument("--expected-source-commit")
    parser.add_argument("--expected-code-source")
    parser.add_argument("--expected-code-sha256")
    parser.add_argument("--expected-native-dylib-sha256")
    parser.add_argument("--expected-artifact-jar-sha256")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    try:
        return validate(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
