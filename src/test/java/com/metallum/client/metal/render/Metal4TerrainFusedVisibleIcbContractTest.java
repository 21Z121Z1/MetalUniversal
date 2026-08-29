package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4TerrainFusedVisibleIcbContractTest {
    @Test
    void fusedLaneIsOptInConservativeAndSingleDispatch() throws Exception {
        String candidate = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainCandidateSnapshot.java"
        ));
        String probe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"
        ));
        String pass = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        String owner = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainIcbOwner.java"
        ));
        String bridge = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
        ));
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(candidate.contains("metallum.opt.terrainFusedVisibleIcb"));
        assertTrue(candidate.contains("FUSED_VISIBLE_GPU_ICB_ENABLED = VISIBLE_GPU_ICB_ENABLED"));
        assertTrue(probe.contains(
                "TerrainCandidateSnapshot.FUSED_VISIBLE_GPU_ICB_ENABLED && !ORACLE_ENABLED"
        ));
        assertTrue(probe.contains("persistentSceneForFused("));
        assertTrue(owner.contains("encodeFusedVisibleGpu("));
        assertTrue(bridge.contains("terrainFusedVisibleGpuIcbAvailable()"));
        assertTrue(bridge.contains("metallum_MTLDevice_createTerrainFusedVisibleGpuIndexedIcb"));

        int fused = pass.indexOf("owner.encodeFusedVisibleGpu(");
        int twoStage = pass.indexOf("TerrainGpuVisibilityProbe.ownerForEpoch(", fused);
        assertTrue(fused >= 0);
        assertTrue(twoStage > fused, "fused lane must be attempted before the two-stage fallback");

        int nativeEntry = nativeSource.indexOf(
                "public func metallum_MTLDevice_createTerrainFusedVisibleGpuIndexedIcb("
        );
        int nextEntry = nativeSource.indexOf(
                "/// Executes one already encoded terrain ICB", nativeEntry
        );
        assertTrue(nativeEntry >= 0 && nextEntry > nativeEntry);
        String fusedNative = nativeSource.substring(nativeEntry, nextEntry);
        assertTrue(fusedNative.contains("functionName: \"metallum_terrain_gpu_encode_fused_visible\""));
        assertTrue(fusedNative.contains("variant: 2"));
        assertTrue(fusedNative.contains("computeEncoder.resetCommands(buffer: commandBuffer"));
        assertTrue(fusedNative.contains("visibilityOwner: scene"));
        assertTrue(fusedNative.contains("afterStages: .dispatch"));
        assertTrue(fusedNative.contains("beforeQueueStages: [.vertex, .fragment]"));
        assertFalse(fusedNative.contains("visibilityBuffer"));
        assertFalse(fusedNative.contains("afterQueueStages: .dispatch"));

        int kernel = nativeSource.indexOf("kernel void metallum_terrain_gpu_encode_fused_visible(");
        int visibilitySource = nativeSource.indexOf("/// Decision-only terrain visibility", kernel);
        assertTrue(kernel >= 0 && visibilitySource > kernel);
        String fusedKernel = nativeSource.substring(kernel, visibilitySource);
        assertTrue(fusedKernel.contains("candidateBySourceOrdinal[drawIndex]"));
        assertTrue(fusedKernel.contains("int3 blockDelta = candidate.sectionBlock.xyz - frame->cameraBlock.xyz"));
        assertTrue(fusedKernel.contains("if (!visible && !uncertain)"));
        assertTrue(fusedKernel.contains("render_command command(container->commandBuffer, drawIndex)"));
        assertFalse(fusedKernel.contains("visibilityWords"));
    }
}
