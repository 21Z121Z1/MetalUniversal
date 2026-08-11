package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalComputeStateShadowTest {
    @Test
    void identicalComputeBindingsAreSuppressed() {
        MetalComputeStateShadow shadow = new MetalComputeStateShadow(16);
        MemorySegment pipeline = MemorySegment.ofAddress(0x1000L);
        MemorySegment buffer = MemorySegment.ofAddress(0x2000L);
        MemorySegment texture = MemorySegment.ofAddress(0x3000L);
        MemorySegment sampler = MemorySegment.ofAddress(0x4000L);

        assertTrue(shadow.setPipeline(pipeline));
        assertFalse(shadow.setPipeline(pipeline));
        assertTrue(shadow.setBuffer(buffer, 32L, 2));
        assertFalse(shadow.setBuffer(buffer, 32L, 2));
        assertTrue(shadow.setTexture(texture, 3));
        assertFalse(shadow.setTexture(texture, 3));
        assertTrue(shadow.setSampler(sampler, 3));
        assertFalse(shadow.setSampler(sampler, 3));
    }

    @Test
    void backingOrOffsetChangeForcesBufferBind() {
        MetalComputeStateShadow shadow = new MetalComputeStateShadow(16);
        MemorySegment first = MemorySegment.ofAddress(0x2000L);
        MemorySegment second = MemorySegment.ofAddress(0x2100L);

        assertTrue(shadow.setBuffer(first, 0L, 1));
        assertTrue(shadow.setBuffer(first, 64L, 1));
        assertTrue(shadow.setBuffer(second, 64L, 1));
        assertFalse(shadow.setBuffer(second, 64L, 1));
    }

    @Test
    void outOfRangeBindingsFailOpen() {
        MetalComputeStateShadow shadow = new MetalComputeStateShadow(8);
        MemorySegment handle = MemorySegment.ofAddress(0x5000L);

        assertTrue(shadow.setBuffer(handle, 0L, 80));
        assertTrue(shadow.setBuffer(handle, 0L, 80));
        assertTrue(shadow.setTexture(handle, 80));
        assertTrue(shadow.setSampler(handle, 80));
    }
}
