package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalComputeArgumentContractTest {
    @Test
    void bindingIndicesMatchTheSharedNativeTableContract() {
        for (int limit : new int[]{31, 128, 16}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MetalComputePass.validateBindingIndex(-1, limit, "test"));
            assertDoesNotThrow(() -> MetalComputePass.validateBindingIndex(0, limit, "test"));
            assertDoesNotThrow(() -> MetalComputePass.validateBindingIndex(limit - 1, limit, "test"));
            assertThrows(IllegalArgumentException.class,
                    () -> MetalComputePass.validateBindingIndex(limit, limit, "test"));
        }
    }

    @Test
    void boundBufferAddressMustBeInsideTheAllocation() {
        assertDoesNotThrow(() -> MetalComputePass.validateBufferOffset(0L, 16L));
        assertDoesNotThrow(() -> MetalComputePass.validateBufferOffset(15L, 16L));
        for (long offset : new long[]{-1L, 16L, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MetalComputePass.validateBufferOffset(offset, 16L));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MetalComputePass.validateBufferOffset(0L, 0L));
    }

    @Test
    void indirectLayoutIsThreeAlignedUint32ValuesWithoutOverflow() {
        assertDoesNotThrow(() -> MetalComputePass.validateIndirectRange(0L, 12L));
        assertDoesNotThrow(() -> MetalComputePass.validateIndirectRange(16L, 28L));
        for (long offset : new long[]{-4L, 1L, 20L, 28L, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MetalComputePass.validateIndirectRange(offset, 28L));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MetalComputePass.validateIndirectRange(0L, 11L));
        assertDoesNotThrow(() -> MetalComputePass.validateIndirectRange(Long.MAX_VALUE - 15L, Long.MAX_VALUE));
    }
}
