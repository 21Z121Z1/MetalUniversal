package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainPersistentGpuSceneNativeContractTest {
    @Test
    void shippingVisibilityReusesGenerationOwnedStaticScene() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String bridge = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"));
        String probe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"));

        assertTrue(nativeSource.contains("final class TerrainGpuVisibilitySceneOwner"));
        assertTrue(nativeSource.contains("metallum_MTLDevice_createTerrainGpuVisibilityScene"));
        assertTrue(nativeSource.contains("metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe"));
        assertTrue(nativeSource.contains("int3 blockDelta = candidate.sectionBlock.xyz - frame->cameraBlock.xyz"));
        assertTrue(nativeSource.contains("sceneOwner: scene"));
        assertTrue(nativeSource.contains("ownsCandidateBuffer: false"));
        assertTrue(bridge.contains("terrainPersistentVisibilitySceneAvailable"));
        assertTrue(probe.contains("persistentSceneGeneration == snapshot.sceneGeneration()"));
        assertTrue(probe.contains("snapshot.packGpuVisibilitySceneFrame(arena)"));
        assertTrue(probe.contains("snapshot.packGpuVisibilitySceneCandidates(arena)"));
    }
}
