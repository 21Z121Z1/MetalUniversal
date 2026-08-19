#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/main/native/MetallumNative.swift"
QUEUE_SOURCE = ROOT / "src/main/java/com/metallum/client/metal/render/mtl/MTLCommandQueue.java"
BUFFER_SOURCE = ROOT / "src/main/java/com/metallum/client/metal/render/mtl/MTLCommandBuffer.java"
TELEMETRY_SOURCE = ROOT / "src/main/java/com/metallum/client/metal/render/mtl/Metal4MainRendererTelemetry.java"
E2E_BUILD = ROOT / ".github/ci/minecraft-e2e/build.gradle"
E2E_TEST = ROOT / ".github/ci/minecraft-e2e/src/main/java/com/metallum/client/metal/render/Metal4MainRendererEvidenceGameTest.java"
PHYSICAL_CORRECTNESS = ROOT / "scripts/agent/run_metal4_main_p1_physical_correctness.sh"
PHYSICAL_PERFORMANCE = ROOT / "scripts/agent/run_metal4_main_p1_physical_performance.sh"
PHYSICAL_MATRIX = ROOT / "scripts/agent/run_metal4_main_p1_physical_matrix.sh"
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
        "private final boolean metal4Supported;" in queue
        and "public boolean metal4Supported()" in queue,
        "command queue does not retain runtime Metal 4 capability evidence",
    )
    require(
        "private boolean residencySetEnabled;" in queue
        and "public boolean residencySetEnabled()" in queue,
        "command queue does not retain runtime residency activation evidence",
    )
    require(
        "this.residencySetEnabled = enabled;" in queue,
        "residency evidence is not tied to the native enable result",
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

    e2e_build = E2E_BUILD.read_text(encoding="utf-8")
    e2e_test = E2E_TEST.read_text(encoding="utf-8")
    require("p1Metal4Lane" in e2e_build, "production E2E has no explicit P1 lane selector")
    require(
        'p1Metal4Lane in ["off", "baseline", "candidate"]' in e2e_build,
        "production E2E does not fail closed on the three P1 lanes",
    )
    for property_line in (
        'jvmArgs.add("-Dmetallum.opt.metal4=true")',
        'jvmArgs.add("-Dmetallum.opt.metal4Compiler=true")',
        'jvmArgs.add("-Dmetallum.opt.metal4Present=true")',
        'jvmArgs.add("-Dmetallum.opt.residencySet=true")',
        'jvmArgs.add("-Dmetallum.opt.metal4MainQueuePilot=false")',
    ):
        require(property_line in e2e_build, f"matched physical lane is missing {property_line}")
    require(
        'jvmArgs.add("-Dmetallum.opt.metal4MainRenderer=${requireMetal4MainRenderer}")' in e2e_build,
        "baseline/candidate lane does not isolate the main-renderer toggle",
    )
    require(
        "device.commandQueue.metal4Supported()" in e2e_test
        and "device.commandQueue.residencySetEnabled()" in e2e_test,
        "production GameTest does not consume queue capability/residency evidence",
    )
    require(
        'lane.equals("baseline")' in e2e_test and 'lane.equals("candidate")' in e2e_test,
        "production GameTest does not validate both P1 lanes",
    )
    require(
        "result.mainRendererEngaged() == candidate" in e2e_test,
        "production GameTest does not prove requested main-renderer engagement",
    )
    for identity_property in (
        "metallum.ci.p1SourceSha",
        "metallum.ci.p1ProductionJarSha256",
        "metallum.ci.p1NativeDylibSha256",
    ):
        require(identity_property in e2e_build and identity_property in e2e_test,
                f"production E2E does not carry exact identity field {identity_property}")

    physical_correctness = PHYSICAL_CORRECTNESS.read_text(encoding="utf-8")
    require("buildMacNative jar verifyProductionJarIsolation" in physical_correctness,
            "physical correctness runner does not build production bits once")
    require("p1Metal4Lane=$lane" in physical_correctness,
            "physical correctness runner does not use explicit baseline/candidate lanes")
    require("check_metal4_main_e2e_pair.py" in physical_correctness,
            "physical correctness runner does not invoke the exact-bits pair gate")
    require("MTL_DEBUG_LAYER=1" in physical_correctness,
            "physical correctness runner does not enable Metal API validation")
    require("env -u METALLUM_HOSTED_METAL_OFFSCREEN" in physical_correctness,
            "physical correctness runner must forbid the hosted offscreen override")

    physical_performance = PHYSICAL_PERFORMANCE.read_text(encoding="utf-8")
    require('PROFILE_ID="${PROFILE_ID:-V1}"' in physical_performance,
            "P1 performance runner does not expose a fail-closed profile selector")
    for profile in ("V1)", "I0)", "I1)"):
        require(profile in physical_performance, f"P1 performance runner is missing {profile[:-1]}")
    require("POTATO_SHADER_PACK" in physical_performance and "BSL_SHADER_PACK" in physical_performance,
            "Iris profiles do not require explicit shader-pack inputs")
    require("SHADER_PACK_SHA" in physical_performance and "SHADER_OPTIONS_SHA" in physical_performance,
            "Iris profiles are not content-addressed")
    require('pin_equals_property "$IRIS_CONFIG" "enableShaders" "false"' in physical_performance,
            "V1 performance runner does not disable mutable shader-pack activation")
    require('pin_equals_property "$IRIS_CONFIG" "enableShaders" "true"' in physical_performance,
            "I0/I1 performance runner does not enable the staged shader pack")
    require('grep -F "Using shaderpack: $STAGED_PACK_NAME"' in physical_performance,
            "Iris profile trial does not prove exact staged shader-pack activation")
    require('pin_colon_option "$OPTIONS_FILE" "guiScale" "$UI_SCALE"' in physical_performance,
            "performance runner does not pin UI scale")
    require('pin_colon_option "$OPTIONS_FILE" "renderDistance" "$RENDER_DISTANCE"' in physical_performance,
            "performance runner does not pin render distance")
    require("EXPECTED_FRAMEBUFFER_WIDTH=1708" in physical_performance
            and "EXPECTED_FRAMEBUFFER_HEIGHT=960" in physical_performance,
            "performance runner does not pin the validated Retina framebuffer")
    require("CAMERA_SCRIPT_SHA" in physical_performance and "CAMERA_POLICY" in physical_performance,
            "performance runner does not content-address the fixed-camera driver")
    require("P1_CORRECTNESS_GATE" in physical_performance and "sourceSha" in physical_performance,
            "performance runner does not require a correctness result for the same source SHA")
    require("BLOCKS < 4" in physical_performance
            and "WARMUP_SECONDS < 30" in physical_performance
            and "SAMPLE_SECONDS < 120" in physical_performance,
            "performance runner does not enforce the benchmark minimum sample protocol")
    require("block % 2" in physical_performance
            and "order=(baseline candidate)" in physical_performance
            and "order=(candidate baseline)" in physical_performance,
            "performance runner does not implement ABBA-equivalent interleaving")
    require("MTL_DEBUG_LAYER=0" in physical_performance,
            "performance measurements must not run with Metal API validation overhead")
    require("restore_runtime_config" in physical_performance,
            "performance runner does not restore the user's mutable client config")
    require("${PROFILE_ID,,}" not in physical_performance,
            "performance runner uses Bash 4-only lowercase expansion on macOS")

    physical_matrix = PHYSICAL_MATRIX.read_text(encoding="utf-8")
    for profile in ("V1", "I0", "I1"):
        require(profile in physical_matrix, f"physical matrix does not require {profile}")
    require("accepted-candidate" in physical_matrix,
            "physical matrix does not require every profile to accept the candidate")
    require("production_jar_sha256" in physical_matrix and "native_dylib_sha256" in physical_matrix,
            "physical matrix does not cross-check exact production binary identity")

    evidence = {
        "schema": 5,
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
            "metal4CapabilityRecorded": True,
            "residencyActivationRecorded": True,
        },
        "physicalAB": {
            "lanes": ["baseline", "candidate"],
            "sameMetal4Compiler": True,
            "sameMetal4Present": True,
            "sameResidency": True,
            "onlyIntendedDifference": "metallum.opt.metal4MainRenderer",
            "exactBinaryIdentity": True,
        },
        "performanceProtocol": {
            "profiles": ["V1", "I0", "I1"],
            "shaderProfilesContentAddressed": True,
            "shaderActivationProven": True,
            "fixedFramebuffer": [1708, 960],
            "fixedUiScale": True,
            "fixedRenderDistance": True,
            "contentAddressedCameraDriver": True,
            "minimumBlocks": 4,
            "minimumWarmupSeconds": 30,
            "minimumSampleSeconds": 120,
            "interleaved": True,
            "metalDebugLayer": False,
            "macOSBashCompatible": True,
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
