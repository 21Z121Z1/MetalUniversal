package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalPlacementHeapContractTest {
    @Test
    void placementHeapUsesExplicitOverlappingOffsetsAndFailsClosed() throws Exception {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String javaSource = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPlacementHeap.java"
        ));
        String targets = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPingPongTargets.java"
        ));
        String recipe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalHeapAliasRecipe.java"
        ));

        assertTrue(nativeSource.contains("heapDescriptor.type = .placement"));
        assertTrue(nativeSource.contains("heapTextureSizeAndAlign(descriptor: descriptor)"));
        assertTrue(nativeSource.contains("offset: slotOffsets[record.slot]"));
        assertTrue(nativeSource.contains("descriptor.hazardTrackingMode = .untracked"));
        assertTrue(javaSource.contains("IrisMetalHeapAliasRuntime.current()"));
        assertTrue(javaSource.contains("mip != 0"));
        assertTrue(javaSource.contains("return null;"));
        assertTrue(targets.contains("placementAllocation = IrisMetalPlacementHeap.tryCreate"));
        assertTrue(targets.contains("heapMain != null ? heapMain"));
        assertTrue(targets.contains("placementAllocation.retireOwner(device)"));
        assertTrue(recipe.contains("placement-heap backing range"));
        assertTrue(recipe.contains("ordering proof"));
    }
}
