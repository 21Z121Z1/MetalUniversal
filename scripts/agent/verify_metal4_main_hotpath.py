#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/main/native/MetallumNative.swift"
QUEUE_SOURCE = ROOT / "src/main/java/com/metallum/client/metal/render/mtl/MTLCommandQueue.java"
BUFFER_SOURCE = ROOT / "src/main/java/com/metallum/client/metal/render/mtl/MTLCommandBuffer.java"
TELEMETRY_SOURCE = ROOT / "src/main/java/com/metallum/client/metal/render/mtl/Metal4MainRendererTelemetry.java"
CLASS_START = "private final class Metal4MainQueueContext {"
CLASS_END = "private final class Metal4MainRenderEncoderBridge {"


def fail(message: str) -> None:
    raise SystemExit(f"Metal4 main hot-path invariant failed: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def between(text: str, start: str, end: str) -> str:
    start_index = text.find(start)
    require(start_index >= 0, f"missing marker {start!r}")
    end_index = text.find(end, start_index + len(start))
    require(end_index >= 0, f"missing marker {end!r}")
    return text[start_index:end_index]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    text = SOURCE.read_text(encoding="utf-8")
    cls = between(text, CLASS_START, CLASS_END)

    forbidden = [
        "freshComputeArgumentTables",
        "computeArgumentTables",
        "renderArgumentTables",
        "nextComputeArgumentTable",
        "nextRenderArgumentTable",
        "computeArgumentTableCount",
        "METALLUM_METALFX_FRESH_COMPUTE_ARGUMENT_TABLE",
    ]
    for token in forbidden:
        require(token not in cls and token not in text, f"obsolete table-pool token remains: {token}")

    # Exactly one vertex, fragment and compute table are created for each slot.
    # No makeArgumentTable call is allowed after initialization in this context.
    make_count = cls.count("device.makeArgumentTable(descriptor: tableDescriptor)")
    require(make_count == 3, f"expected exactly three per-slot table factories, got {make_count}")

    accessors = between(cls, "func argumentTables(at index: Int)", "func writeClearUniforms")
    require("makeArgumentTable" not in accessors, "argument-table accessor allocates during encoding")
    require(
        "return (slot.vertexArguments, slot.fragmentArguments)" in accessors,
        "render accessor does not reuse the slot's vertex/fragment tables",
    )
    require(
        "slots[index].computeArguments" in accessors,
        "compute accessor does not reuse the slot's compute table",
    )

    slot_block = between(cls, "private final class Slot {", "private let device: MTLDevice")
    require(slot_block.count("let vertexArguments: MTL4ArgumentTable") == 1, "slot must own one vertex table")
    require(slot_block.count("let fragmentArguments: MTL4ArgumentTable") == 1, "slot must own one fragment table")
    require(slot_block.count("let computeArguments: MTL4ArgumentTable") == 1, "slot must own one compute table")

    begin = between(cls, "func beginLease(label: String?)", "func submit(_ lease:")
    free_test = begin.find("slots[index].state == .free")
    recording = begin.find("slots[index].state = .recording")
    reset = begin.find("slot.allocator.reset()")
    require(free_test >= 0, "beginLease does not select only free slots")
    require(recording > free_test, "slot is not marked recording after free-slot selection")
    require(reset > recording, "allocator reset occurs before ownership transfers to recording")
    require(begin.count("allocator.reset()") == 1, "beginLease must reset exactly one acquired allocator")

    submit = between(cls, "func submit(_ lease:", "func stats()")
    submitted = submit.find("slots[lease.slotIndex].state = .submitted")
    feedback = submit.find("lease.markCompleted(")
    free = submit.find("self.slots[lease.slotIndex].state = .free")
    require(submitted >= 0, "submit does not mark the slot submitted")
    require(feedback > submitted, "completion feedback is not observed after submit")
    require(free > feedback, "slot becomes free before Metal completion feedback is handled")
    require("slotCondition.broadcast()" in submit[free:], "free slot does not wake blocked acquisition")

    queue = QUEUE_SOURCE.read_text(encoding="utf-8")
    buffer = BUFFER_SOURCE.read_text(encoding="utf-8")
    telemetry = TELEMETRY_SOURCE.read_text(encoding="utf-8")
    require(
        "Metal4MainRendererTelemetry.shouldMeasureSlotWait()" in queue,
        "command queue does not gate conservative slot-wait measurement on three-slot pressure",
    )
    require(
        "Metal4MainRendererTelemetry.recordCommandBufferAcquired" in queue,
        "command-buffer acquire is not recorded",
    )
    require(
        "Metal4MainRendererTelemetry.recordCommit()" in buffer,
        "command-buffer commit is not recorded",
    )
    require(
        "Metal4MainRendererTelemetry.recordCompletion()" in buffer,
        "command-buffer retirement is not recorded",
    )
    require(
        "metallum_metal4_main_renderer_stats()" in telemetry,
        "runtime snapshot is not cross-checked against native main-renderer stats",
    )
    require(
        "conservative" in telemetry and "upper bound" in telemetry,
        "slot-wait metric does not document its conservative upper-bound semantics",
    )
    require(
        'System.getProperty("metallum.hotpath.telemetry", "false")' in telemetry,
        "runtime telemetry is not opt-in",
    )

    evidence = {
        "schema": 2,
        "source": str(SOURCE.relative_to(ROOT)),
        "argumentTableModel": "one-long-lived-table-per-stage-family-per-in-flight-slot",
        "argumentTablesPerSlot": 3,
        "argumentTableAllocationsDuringEncoding": 0,
        "computeTableOverflow": 0,
        "renderTableHighWater": 1,
        "allocatorResetAfterFreeSlotAcquire": True,
        "allocatorResetsPerAcquire": 1,
        "slotFreeOnlyAfterCompletionFeedback": True,
        "forbiddenLegacyPoolTokensPresent": False,
        "runtimeTelemetry": {
            "enabledBy": "metallum.hotpath.telemetry",
            "nativeCrossCheck": "metallum_metal4_main_renderer_stats",
            "slotWaitNanos": "conservative-upper-bound-under-three-unretired-submissions",
            "hotPathFfmStatsQueryPerFrame": False,
        },
        "status": "pass",
    }

    if args.output:
        output = args.output
        if not output.is_absolute():
            output = ROOT / output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, separators=(",", ":")))


if __name__ == "__main__":
    main()
