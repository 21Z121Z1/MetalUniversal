package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Java/native residency ownership contract. The companion Swift
 * fixture performs the device-level add/commit/remove check; these assertions
 * prevent a future ABI or cache refactor from silently dropping one of the
 * allocation classes or its release path.
 */
final class MetalResidencyAllocationContractTest {
    @Test
    void residencyAuthorityUsesMtlAllocationAndKeepsMemorylessExcluded() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains("private func residencyAdd(_ allocation: any MTLAllocation)"));
        assertTrue(source.contains("private func residencyRemove(_ allocation: any MTLAllocation)"));
        assertTrue(source.contains("private func residencyTrackCreated(_ object: AnyObject?)"));
        assertTrue(source.contains("object as? any MTLAllocation"));
        assertTrue(source.contains("texture.storageMode == .memoryless"));
        assertFalse(source.contains("private func residencyAdd(_ resource: MTLResource)"));
    }

    @Test
    void publicRenderAndComputeFactoriesTrackBeforeReturningRetainedHandles() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains(
                "let state = try device.makeComputePipelineState(function: function)\n"
                        + "            residencyTrackCreated(state)\n"
                        + "            return retainedPointer(state)"
        ));
        assertTrue(source.contains(
                "residencyTrackCreated(state)\n"
                        + "                return retainedPointer(state)"
        ), "the MTL4 compiler branch must track its render PSO");
        assertTrue(source.contains(
                "residencyTrackCreated(state)\n"
                        + "        return retainedPointer(state)"
        ), "the MTL3 render fallback must track its render PSO");
        assertTrue(source.contains(
                "residencyTrackReleased(obj)\n"
                        + "        Unmanaged<AnyObject>.fromOpaque(obj).release()"
        ), "raw release must remove residency before dropping the owner");
    }

    @Test
    void internalMainQueuePsoCachesAndMetalFxShutdownHaveSymmetricTracking() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains("residencyTrackCreated(visibilityState)"));
        assertTrue(source.contains("residencyTrackCreated(blockScanState)"));
        assertTrue(source.contains("residencyTrackCreated(pipeline)\n            return pipeline"));
        assertTrue(source.contains("residencyTrackCreated(state)\n            return TerrainGpuComputePipeline"));
        assertTrue(source.contains("residencyTrackCreated(camera)"));
        assertTrue(source.contains("residencyTrackCreated(clear)"));
        assertTrue(source.contains("NativeState.copyPipelines[key] = pipeline"));
        assertTrue(source.contains(
                "This cache is consumed by both the ordinary MTLCommandQueue and the\n"
                        + "    // Metal 4 main queue. The latter has no implicit PSO residency."
        ));
        assertTrue(source.contains("residencyTrackReleased(NativeState.motionPipeline)"));
        assertTrue(source.contains("for pipeline in NativeState.copyPipelines.values"));
        assertTrue(source.contains("NativeState.copyPipelines.removeAll()"));
    }

    @Test
    void residencyIsDeviceScopedAndExposesLifetimeCountersForNativeFixture() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains("residencyDeviceStorage"));
        assertTrue(source.contains("residency set already belongs to another Metal device"));
        assertTrue(source.contains("residencyTrackedAllocations.insert(identity).inserted"));
        assertTrue(source.contains("residencyTrackedAllocations.remove(identity)"));
        assertTrue(source.contains("@_cdecl(\"metallum_residency_set_lifetime_stats\")"));
        assertTrue(source.contains("residencyCreatedCount"));
        assertTrue(source.contains("residencyReleasedCount"));
    }

    @Test
    void nativeFixtureExercisesBothPsoKindsAndExactRawReleaseSymmetry() throws IOException {
        String fixture = Files.readString(Path.of("src/test/native/Metal4PipelinePathTest.swift"));

        assertTrue(fixture.contains("createShippingPipelineHandle"));
        assertTrue(fixture.contains("createShippingComputePipelineHandle"));
        assertTrue(fixture.contains("residencyComputeShaderSource"));
        assertTrue(fixture.contains("metallum_residency_set_lifetime_stats"));
        assertTrue(fixture.contains("metallum_release_object(metal4RenderPointer)"));
        assertTrue(fixture.contains("metallum_release_object(metal3RenderPointer)"));
        assertTrue(fixture.contains("metallum_release_object(computePointer)"));
        assertTrue(fixture.contains("afterCreate == baseline + 5"));
        assertTrue(fixture.contains("lifetimeAfterDrain.released == lifetimeBefore.released + 5"));
        assertTrue(fixture.contains("memoryless texture excluded"));
    }

    @Test
    void privateMetal4PresenterUsesItsOwnQueueResidencySetForBorrowedPsos() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains("private var residentPipelines: [MTLRenderPipelineState]"));
        assertTrue(source.contains("for pipeline in residentPipelines"));
        assertTrue(source.contains("queue.addResidencySet(residencySet)"));
        assertTrue(source.contains("pipelines: [\n                       copyPipeline"));
        assertTrue(source.contains("pipelines: [\n                newCopyPipeline"));
    }
}
