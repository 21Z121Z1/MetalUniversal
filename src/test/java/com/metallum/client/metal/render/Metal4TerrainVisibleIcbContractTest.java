package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4TerrainVisibleIcbContractTest {
    @Test
    void visibleIcbKeepsSourceOrdinalsAndCrossEncoderQueueDependency() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int kernel = source.indexOf("kernel void metallum_terrain_gpu_encode_visible(");
        int nativeEntry = source.indexOf("metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb");
        assertTrue(kernel > 0 && nativeEntry > kernel);
        String visible = source.substring(kernel, source.indexOf("/// Executes one already encoded terrain ICB", nativeEntry));
        assertTrue(visible.contains("candidateBySourceOrdinal[drawIndex]"));
        assertTrue(visible.contains("render_command command(container->commandBuffer, drawIndex)"));
        assertTrue(visible.contains("resetCommands(buffer: commandBuffer, range: 0..<commandCount)"));
        assertTrue(visible.contains("afterQueueStages: .dispatch"));
        assertTrue(visible.contains("beforeStages: .dispatch"));
        assertTrue(visible.contains("visibilityOwner.leaseIdentity == ObjectIdentifier(bridge.lease)"));
        assertTrue(visible.contains("visibilityOwner.epoch == expectedEpoch"));
    }

    @Test
    void visibilityOwnerStoresValueOnlyLeaseIdentity() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int owner = source.indexOf("private final class TerrainGpuVisibilityProbeOwner");
        int nativeEntry = source.indexOf("metallum_MTLDevice_createTerrainGpuVisibilityProbe", owner);
        assertTrue(owner > 0 && nativeEntry > owner);
        String ownerSource = source.substring(owner, nativeEntry);
        assertTrue(ownerSource.contains("let leaseIdentity: ObjectIdentifier"));
        assertTrue(ownerSource.contains("leaseIdentity: ObjectIdentifier"));
        assertTrue(ownerSource.contains("self.leaseIdentity = leaseIdentity"));
        assertTrue(!ownerSource.contains("let lease: Metal4MainCommandBufferLease"));
    }

    @Test
    void visibleFeatureRetainsNativeCapabilityGate() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/metallum/client/metal/render/MetalDevice.java"));
        assertTrue(source.contains("TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibilityProbeAvailable()"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibleGpuIcbAvailable()"));
        assertTrue(source.contains("EXPLICIT_GPU_VISIBILITY_PROBE_METAL4 || VISIBLE_GPU_ICB_METAL4"));
    }
}
