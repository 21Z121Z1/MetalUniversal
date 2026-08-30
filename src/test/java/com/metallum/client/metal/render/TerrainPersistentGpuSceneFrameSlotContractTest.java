package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainPersistentGpuSceneFrameSlotContractTest {
    @Test
    void persistentSceneOwnsOneScratchSetPerMetal4MainQueueSlot() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int ownerStart = source.indexOf("private final class TerrainGpuVisibilitySceneOwner");
        int probeStart = source.indexOf("metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe", ownerStart);
        int legacyStart = source.indexOf("Dispatches the value-only visibility probe", probeStart);
        assertTrue(ownerStart > 0 && probeStart > ownerStart && legacyStart > probeStart);
        String owner = source.substring(ownerStart, probeStart);
        String probe = source.substring(probeStart, legacyStart);
        assertTrue(owner.contains("static let inFlightSlotCount = 3"));
        assertTrue(owner.contains("final class FrameSlot"));
        assertTrue(owner.contains("let arguments: MTL4ArgumentTable"));
        assertTrue(owner.contains("for slotIndex in 0..<Self.inFlightSlotCount"));
        assertTrue(probe.contains("scene.frameSlot(at: bridge.lease.slotIndex)"));
        assertTrue(probe.contains("slot.frameBuffer.contents().copyMemory"));
        assertTrue(probe.contains("computeEncoder.setArgumentTable(slot.arguments)"));
        assertTrue(probe.contains("ownsProbeBuffers: false"));
        assertFalse(probe.contains("device.makeBuffer(length: 96"));
        assertFalse(probe.contains("device.makeArgumentTable(descriptor:"));
    }
}
