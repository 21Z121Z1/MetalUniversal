package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeResourceAllocationsTest {
    @Test void decodesFixedAbiWithoutCallingAllocationBytesResidency() {
        var result = NativeResourceAllocations.decode(
                new long[]{1, 1, 65536, 0, 0, 2, 4096, 2},
                new long[]{1, 0, 4096, 0, 0, 0, 0, 0, 2, 1, 0, 3, 1, 0, 0, 0});
        assertEquals("module-created-live-metal-resources", result.scope());
        assertEquals(4096, result.totalAllocatedBytes());
        assertTrue(result.rows().get(1).memoryless());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
    }

    @Test void rejectsTruncatedWrongSchemaAndBooleanAbi() {
        assertThrows(IllegalArgumentException.class, () -> NativeResourceAllocations.decode(
                new long[]{1, 1, 65536, 0, 0, 1, 4096, 1}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeResourceAllocations.decode(
                new long[]{2, 1, 65536, 0, 0, 0, 0, 0}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeResourceAllocations.decode(
                new long[]{1, 1, 65536, 0, 0, 1, 4096, 1},
                new long[]{1, 0, 4096, 0, 2, 0, 0, 0}));
    }
}
