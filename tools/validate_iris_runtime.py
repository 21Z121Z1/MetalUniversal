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
        if record.get("type") != "event" or "frame" not in record:
            continue
        key = (record.get("generation"), record.get("frame"))
        result.setdefault(key, []).append(record.get("event"))
    return result


def control_events(records):
    return {record.get("event") for record in records if record.get("type") == "event"}


def check_pass_order(records, required_events, failures):
    events_by_frame = frame_events(records)
    required = set(required_events)
    candidate = None
    for key, events in events_by_frame.items():
        if required.issubset(events):
            candidate = (key, events)
            break
    if not require(candidate is not None, "no frame contains the complete Iris pass chain", failures):
        return None

    _, events = candidate
    positions = {event: events.index(event) for event in set(events)}
    constraints = [
        ("setup", "begin"),
        ("shadow.render.begin", "shadow.render.end"),
        ("shadow.render.end", "shadow.composite"),
        ("shadow.composite", "depthtex2.capture"),
        ("depthtex2.capture", "depthtex1.capture"),
        ("depthtex1.capture", "deferred"),
        ("deferred", "depthtex0.capture"),
        ("depthtex0.capture", "composite"),
        ("composite", "final"),
    ]
    for before, after in constraints:
        require(
            before in positions and after in positions and positions[before] < positions[after],
            f"pass order violation: {before} must precede {after}",
            failures,
        )
    return candidate[0]


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

    pass_order_frame = None
    runtime_results = {}
    for entry in runtime_entries:
        entry_id = entry["id"]
        if entry_id == "runtime.pass-order-and-contribution":
            pass_order_frame = check_pass_order(
                runtime_records, entry.get("receiptEvents", []), failures
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
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    try:
        return validate(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
