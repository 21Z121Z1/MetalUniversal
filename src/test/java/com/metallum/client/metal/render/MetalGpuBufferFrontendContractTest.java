package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

class MetalGpuBufferFrontendContractTest {
    @Test
    void frontendCanValidateWrappedBufferWithoutNativeInitialization() {
        // The wrapped-handle constructor does not allocate or dereference GPU memory.
        // This opaque identity must not be submitted to Metal or released by this test.
        GpuBuffer buffer = new MetalGpuBuffer(null, GpuBuffer.USAGE_VERTEX, 64L,
                MemorySegment.ofAddress(1L));
        BaseGpuBuffer frontendBuffer = (BaseGpuBuffer) buffer;
        assertEquals(64L, frontendBuffer.size());
        assertEquals(GpuBuffer.USAGE_VERTEX, frontendBuffer.usage());
        assertDoesNotThrow(frontendBuffer::checkCanBeUsed);
    }

    @Test
    void frontendRejectsBufferWithoutLiveBacking() {
        GpuBuffer buffer = new MetalGpuBuffer(null, GpuBuffer.USAGE_VERTEX, 64L,
                MemorySegment.NULL);
        BaseGpuBuffer frontendBuffer = (BaseGpuBuffer) buffer;
        assertTrue(frontendBuffer.isClosed());
        assertThrows(IllegalStateException.class, frontendBuffer::checkCanBeUsed);
    }
}
