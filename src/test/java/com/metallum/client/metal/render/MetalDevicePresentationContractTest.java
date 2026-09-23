package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalDevicePresentationContractTest {
    @Test
    void nullMetalLayerIsOffscreen() {
        assertFalse(MetalDevice.hasPresentationLayer(MemorySegment.NULL));
    }

    @Test
    void nonNullMetalLayerIsPresentationBacked() {
        assertTrue(MetalDevice.hasPresentationLayer(MemorySegment.ofAddress(1L)));
    }
}
