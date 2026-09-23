package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MetalNativeInterfaceTableTest {
    @Test
    void parsesAppendOnlyHeaderAndEntries() {
        try (Arena arena = Arena.ofConfined()) {
            long pointerBytes = ValueLayout.ADDRESS.byteSize();
            long byteCount = MetalNativeInterfaceTable.HEADER_MIN_BYTES + 2L * pointerBytes;
            MemorySegment table = arena.allocate(byteCount, Long.BYTES);
            writeHeader(table, byteCount, 3, 1, 2, 1L << 10);
            table.set(
                    ValueLayout.ADDRESS,
                    MetalNativeInterfaceTable.HEADER_MIN_BYTES,
                    MemorySegment.ofAddress(0x1000L)
            );
            table.set(
                    ValueLayout.ADDRESS,
                    MetalNativeInterfaceTable.HEADER_MIN_BYTES + pointerBytes,
                    MemorySegment.ofAddress(0x2000L)
            );

            MetalNativeInterfaceTable parsed = MetalNativeInterfaceTable.parse(3, 1, table);

            assertEquals(3, parsed.featureId());
            assertEquals(1, parsed.abiVersion());
            assertEquals(1L << 10, parsed.buildCapabilities());
            assertEquals(2, parsed.entryCount());
            assertEquals(0x1000L, parsed.entry(0).address());
            assertEquals(0x2000L, parsed.entry(1).address());
        }
    }

    @Test
    void rejectsFeatureVersionSizeAndNullEntryMismatches() {
        try (Arena arena = Arena.ofConfined()) {
            long pointerBytes = ValueLayout.ADDRESS.byteSize();
            long byteCount = MetalNativeInterfaceTable.HEADER_MIN_BYTES + pointerBytes;
            MemorySegment table = arena.allocate(byteCount, Long.BYTES);
            writeHeader(table, byteCount, 3, 1, 1, 0L);
            table.set(
                    ValueLayout.ADDRESS,
                    MetalNativeInterfaceTable.HEADER_MIN_BYTES,
                    MemorySegment.ofAddress(0x1000L)
            );

            assertNull(MetalNativeInterfaceTable.parse(4, 1, table));
            assertNull(MetalNativeInterfaceTable.parse(3, 2, table));

            table.set(ValueLayout.JAVA_INT, 4L, MetalNativeInterfaceTable.HEADER_MIN_BYTES);
            assertNull(MetalNativeInterfaceTable.parse(3, 1, table));

            writeHeader(table, byteCount, 3, 1, 1, 0L);
            table.set(
                    ValueLayout.ADDRESS,
                    MetalNativeInterfaceTable.HEADER_MIN_BYTES,
                    MemorySegment.NULL
            );
            assertNull(MetalNativeInterfaceTable.parse(3, 1, table));
        }
    }

    private static void writeHeader(
            final MemorySegment table,
            final long byteCount,
            final int featureId,
            final int version,
            final int entryCount,
            final long capabilities
    ) {
        table.set(ValueLayout.JAVA_INT, 0L, MetalNativeInterfaceTable.HEADER_MIN_BYTES);
        table.set(ValueLayout.JAVA_INT, 4L, Math.toIntExact(byteCount));
        table.set(ValueLayout.JAVA_INT, 8L, version);
        table.set(ValueLayout.JAVA_INT, 12L, featureId);
        table.set(ValueLayout.JAVA_INT, 16L, entryCount);
        table.set(ValueLayout.JAVA_INT, 20L, 0);
        table.set(ValueLayout.JAVA_LONG, 24L, capabilities);
    }
}
