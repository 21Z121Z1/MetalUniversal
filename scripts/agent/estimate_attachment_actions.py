#!/usr/bin/env python3
"""Estimate logical attachment action payload, never physical GPU bandwidth.

The producer supplies final native actions, not a precomputed byte counter.
Every frame is retained, including frames with zero render attachments. Any
unsupported descriptor invalidates the entire window instead of dropping work.
"""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any

from verify_native_attachment_facts import validate_report

I64_MAX = (1 << 63) - 1
SOURCE = "native-attachment-actions-v1"
SCOPE = "main-queue-logical-attachment-action-bytes"
DEFINITION = (
    "Logical sample payload for final native attachment actions over the render area: "
    "load + store + resolve destination bytes, summed by completed frame. Clear/dontCare "
    "do not load; discarded stores do not write; memoryless source payload stays tile-local. "
    "Per-aspect component bits exclude allocation padding, compression, caches and tile effects. "
    "This is an action-footprint estimate, not measured GPU/DRAM traffic or allocated memory."
)
BYTE_FIELDS = ("loadBytes", "storeBytes", "resolveBytes", "deferredDiscardBytes", "totalBytes")

# Metal enum values, with logical uncompressed component bits per sample.
# Packed color formats still have a defined total payload; compressed formats
# are deliberately absent. Depth/stencil components are accounted separately.
COLOR_BYTES = {
    **dict.fromkeys((10, 11, 12, 13, 14), 1),
    **dict.fromkeys((20, 22, 23, 24, 25, 30, 31, 32, 33, 34), 2),
    **dict.fromkeys((53, 54, 55, 60, 62, 63, 64, 65, 70, 71, 72, 73, 74,
                     80, 81, 90, 91, 92, 93), 4),
    **dict.fromkeys((103, 104, 105, 110, 112, 113, 114, 115), 8),
    **dict.fromkeys((123, 124, 125), 16),
}
ASPECT_BYTES = {0: COLOR_BYTES, 1: {250: 2, 252: 4, 260: 4},
                2: {253: 1, 260: 1}}


class Unsupported(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise Unsupported(message)


def _product(*values: int) -> int:
    result = 1
    for value in values:
        _require(value >= 0 and (not value or result <= I64_MAX // value),
                 "logical byte product exceeds signed Int64")
        result *= value
    return result


def _add(left: int, right: int) -> int:
    _require(0 <= right <= I64_MAX - left, "logical byte sum exceeds signed Int64")
    return left + right


def _texture(row: dict[str, int], *, resolve: bool, layers: int, layered: bool) -> tuple[int, int, int]:
    def get(name: str) -> int:
        return row[("resolve" + name[0].upper() + name[1:]) if resolve else name]

    width, height = get("width"), get("height")
    kind, samples, array = get("textureType"), get("sampleCount"), get("arrayLength")
    _require(width > 0 and height > 0 and get("depth") == 1,
             "only positive 2D attachment extents are supported")
    _require(kind in (2, 3, 4, 8), "cube/3D/buffer/unknown attachment texture type is not admitted")
    _require(samples in (1, 2, 4, 8), "unsupported sample count")
    _require((kind in (2, 3) and samples == 1) or (kind in (4, 8) and samples > 1),
             "texture type and sample count disagree")
    _require(get("level") == 0,
             "nonzero mip level needs mip-count metadata absent from the v1 native facts")
    _require(get("depthPlane") == 0, "2D attachment depthPlane must be zero")
    _require(get("storageMode") in (0, 1, 2, 3), "unsupported storage mode")
    _require(array > 0, "attachment arrayLength must be positive")
    if kind in (2, 4):
        _require(array == 1 and get("slice") == 0 and layers == 1,
                 "non-array attachment cannot select array layers")
    elif layered:
        _require(get("slice") == 0 and layers <= array,
                 "layered array requires zero base slice and sufficient array length")
    else:
        _require(get("slice") < array, "attachment slice is outside the texture array")
    if resolve:
        _require(samples == 1 and get("storageMode") != 3,
                 "resolve destination must be single-sample and externally backed")
    return width, height, samples


def _encoder(marker: dict[str, int], children: list[dict[str, int]]) -> dict[str, int]:
    result = dict.fromkeys(BYTE_FIELDS, 0)
    if not children:
        return result
    raw_layers = marker["renderTargetArrayLength"]
    # Metal defines zero as non-layered rendering, using the attachment's
    # selected slice. It does not mean the texture's entire arrayLength.
    layers = raw_layers if raw_layers else 1
    layered = raw_layers > 0
    extents = []
    for row in children:
        _require(all(row[key] == marker[key] for key in
                     ("renderTargetWidth", "renderTargetHeight", "renderTargetArrayLength")),
                 "attachment render area differs from its descriptor marker")
        extents.append(_texture(row, resolve=False, layers=layers, layered=layered))
    _require(len({samples for _, _, samples in extents}) == 1,
             "attachments in one descriptor have different sample counts")
    # A zero dimension means unconstrained. Until unequal implicit extents
    # receive independent native coverage, require equal dimensions rather than
    # guessing which attachment constrains load/store operations.
    width, height = marker["renderTargetWidth"], marker["renderTargetHeight"]
    _require(bool(width) == bool(height), "mixed explicit/implicit render dimensions are not admitted")
    if not width:
        _require(len({w for w, _, _ in extents}) == 1, "unequal implicit attachment widths are not admitted")
        width = extents[0][0]
    if not height:
        _require(len({h for _, h, _ in extents}) == 1, "unequal implicit attachment heights are not admitted")
        height = extents[0][1]
    _require(width > 0 and height > 0 and all(width <= w and height <= h for w, h, _ in extents),
             "render target area exceeds an attachment extent")
    for row, (_, _, samples) in zip(children, extents):
        bpp = ASPECT_BYTES[row["aspect"]].get(row["pixelFormat"])
        _require(bpp is not None, "unsupported pixel format for this attachment aspect")
        _require(row["storeActionOptions"] == 0, "custom store options are not admitted")
        load, store = row["loadAction"], row["finalStoreAction"]
        _require(store in (0, 1, 2, 3), "custom sample depth store has no admitted byte model")
        memoryless = row["storageMode"] == 3
        if memoryless:
            _require(load != 1 and store in (0, 2), "memoryless attachment requests an external source load/store")
        payload = _product(width, height, layers, samples, bpp)
        if not memoryless:
            if load == 1:
                result["loadBytes"] = _add(result["loadBytes"], payload)
            if store in (1, 3):
                result["storeBytes"] = _add(result["storeBytes"], payload)
            if row["initialStoreAction"] == 4 and store == 0:
                result["deferredDiscardBytes"] = _add(result["deferredDiscardBytes"], payload)
        if store in (2, 3):
            _require(samples > 1 and row["resolvePixelFormat"] != 0,
                     "resolve store requires a multisample source and resolve texture")
            rw, rh, _ = _texture(row, resolve=True, layers=layers, layered=layered)
            _require(row["resolvePixelFormat"] == row["pixelFormat"],
                     "resolve format reinterpretation is not admitted")
            _require(rw >= width and rh >= height, "resolve destination is smaller than render area")
            allowed_filters = (0, 1, 2) if row["aspect"] == 1 else (0,)
            _require(row["resolveFilter"] in allowed_filters,
                     "resolve filter requires unproved cross-aspect ownership")
            result["resolveBytes"] = _add(result["resolveBytes"], _product(width, height, layers, bpp))
        else:
            _require(all(row[key] == 0 for key in row if key.startswith("resolve")),
                     "unused resolve descriptor is not admitted")
    result["totalBytes"] = _add(_add(result["loadBytes"], result["storeBytes"]), result["resolveBytes"])
    return result


def estimate_attachment_actions(report: dict[str, Any]) -> tuple[dict[str, Any] | None, list[str]]:
    if "nativeAttachmentLedger" not in report:
        return None, []
    _, errors = validate_report(report)
    if errors:
        return None, ["raw attachment facts: " + error for error in errors]
    groups: dict[int, list[dict[str, int]]] = {}
    for row in report["nativeAttachmentLedger"]["rows"]:
        groups.setdefault(row["encoderSequence"], []).append(row)
    # Iteration follows actual bounded submission evidence, never an untrusted
    # reported frame count. Zero-render frames remain explicit zero-byte rows.
    frames = {row["frameId"]: {"frameId": row["frameId"], **dict.fromkeys(BYTE_FIELDS, 0)}
              for row in report["gpuSubmissionSamples"]}
    try:
        for sequence, group in groups.items():
            marker = next(row for row in group if row["aspect"] == -1)
            children = [row for row in group if row["aspect"] != -1]
            try:
                amounts = _encoder(marker, children)
            except Unsupported as failure:
                raise Unsupported(f"encoderSequence {sequence}: {failure}") from failure
            frame = frames[marker["frameId"]]
            for key in BYTE_FIELDS:
                frame[key] = _add(frame[key], amounts[key])
        totals = dict.fromkeys(BYTE_FIELDS, 0)
        for frame in frames.values():
            for key in BYTE_FIELDS:
                totals[key] = _add(totals[key], frame[key])
    except Unsupported as failure:
        return None, [str(failure)]
    ordered = [frames[key] for key in sorted(frames)]
    return {
        "schemaVersion": 1, "source": SOURCE, "scope": SCOPE, "complete": True,
        "definition": DEFINITION, "frames": ordered, "totals": totals,
        "medianBytesPerFrame": statistics.median(frame["totalBytes"] for frame in ordered),
        "physicalBandwidthMeasured": False,
    }, []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result, errors = estimate_attachment_actions(json.loads(args.input.read_text()))
    except (OSError, ValueError, TypeError) as failure:
        result, errors = None, [str(failure)]
    text = json.dumps({"estimate": result, "errors": errors}, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end="")
    return 0 if result is not None and not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
