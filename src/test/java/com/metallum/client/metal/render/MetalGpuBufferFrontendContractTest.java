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

    @Test
    void borrowedUsageFacadesShareTheAllocationRatherThanInventingAliasIndependence() {
        MetalAllocationIdentity backing = new MetalAllocationIdentity(201L, 7L);
        MetalGpuBuffer vertex = new MetalGpuBuffer(null, GpuBuffer.USAGE_VERTEX, 64L,
                MemorySegment.ofAddress(1L), backing);
        MetalGpuBuffer indirect = new MetalGpuBuffer(null, GpuBuffer.USAGE_INDIRECT_PARAMETERS, 64L,
                MemorySegment.ofAddress(1L), backing);
        assertNotSame(vertex, indirect);
        assertEquals(vertex.nativeHandle(), indirect.nativeHandle());
        assertEquals(backing, vertex.allocationIdentity());
        assertEquals(backing, indirect.allocationIdentity());
        assertNotEquals(vertex.usage(), indirect.usage());
        var window = new IrisMetalComputeGroupingRuntime.IndependenceWindow();
        window.append(new IrisMetalComputeGroupingRuntime.AccessSet(java.util.Set.of(),
                java.util.Set.of(vertex.allocationIdentity())));
        assertFalse(window.admits(new IrisMetalComputeGroupingRuntime.AccessSet(
                java.util.Set.of(indirect.allocationIdentity()), java.util.Set.of())),
                "an indirect read through another facade still follows the physical allocation's writer");
    }

}
