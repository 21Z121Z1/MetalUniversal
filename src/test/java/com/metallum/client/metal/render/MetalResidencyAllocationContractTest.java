package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalResidencyAllocationContractTest {
    @Test
    void residencyAuthorityTracksMetalAllocationsIncludingPipelineStates() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains("private func residencyAdd(_ allocation: any MTLAllocation)"));
        assertTrue(source.contains("private func residencyRemove(_ allocation: any MTLAllocation)"));
        assertTrue(source.contains("private func residencyTrackCreated(_ object: AnyObject?)"));
        assertTrue(source.contains("object as? any MTLAllocation"));
        assertTrue(source.contains("as? any MTLAllocation"));
        assertTrue(source.contains("residencyTrackCreated(state)\n                return retainedPointer(state)"));
        assertTrue(source.contains("let state = try device.makeComputePipelineState(function: function)\n            residencyTrackCreated(state)"));

        // Memoryless textures have tile-only storage and must remain excluded.
        assertTrue(source.contains("texture.storageMode == .memoryless"));
        assertFalse(source.contains("private func residencyAdd(_ resource: MTLResource)"));
    }
}
