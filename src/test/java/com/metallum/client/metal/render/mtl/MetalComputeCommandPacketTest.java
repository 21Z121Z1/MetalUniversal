package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalComputeCommandPacketTest {
    @Test
    void writesBindingAndDispatchInOrder() {
        try (MetalComputeCommandPacket packet = new MetalComputeCommandPacket(4)) {
            assertTrue(packet.appendBuffer(
                    MemorySegment.NULL,
                    MemorySegment.ofAddress(0x2000),
                    256,
                    3
            ));
            assertTrue(packet.appendDispatch(
                    MemorySegment.NULL,
                    12,
                    7,
                    1,
                    8,
                    8,
                    1
            ));

            MemorySegment storage = packet.storageForTest();
            assertEquals(MetalComputeCommandPacket.MAGIC, storage.get(ValueLayout.JAVA_INT, 0));
            assertEquals(MetalComputeCommandPacket.VERSION, storage.get(ValueLayout.JAVA_INT, 4));
            assertEquals(MetalComputeCommandPacket.ENTRY_SIZE, storage.get(ValueLayout.JAVA_INT, 16));
            assertEquals(2, packet.operationCount());

            long first = MetalComputeCommandPacket.HEADER_SIZE;
            assertEquals(
                    MetalComputeCommandPacket.OP_BUFFER,
                    storage.get(ValueLayout.JAVA_INT, first)
            );
            assertEquals(0x2000, storage.get(ValueLayout.JAVA_LONG, first + 8));
            assertEquals(256, storage.get(ValueLayout.JAVA_LONG, first + 16));
            assertEquals(3, storage.get(ValueLayout.JAVA_LONG, first + 24));

            long second = first + MetalComputeCommandPacket.ENTRY_SIZE;
            assertEquals(
                    MetalComputeCommandPacket.OP_DISPATCH,
                    storage.get(ValueLayout.JAVA_INT, second)
            );
            assertEquals(12, storage.get(ValueLayout.JAVA_LONG, second + 8));
            assertEquals(7, storage.get(ValueLayout.JAVA_LONG, second + 16));
            assertEquals(1, storage.get(ValueLayout.JAVA_LONG, second + 24));
            assertEquals(8, storage.get(ValueLayout.JAVA_LONG, second + 32));
            assertEquals(8, storage.get(ValueLayout.JAVA_LONG, second + 40));
            assertEquals(1, storage.get(ValueLayout.JAVA_LONG, second + 48));
        }
    }

    @Test
    void writesIndirectDispatchArguments() {
        try (MetalComputeCommandPacket packet = new MetalComputeCommandPacket(2)) {
            assertTrue(packet.appendDispatchIndirect(
                    MemorySegment.NULL,
                    MemorySegment.ofAddress(0x3000),
                    64,
                    16,
                    4,
                    1
            ));
            long base = MetalComputeCommandPacket.HEADER_SIZE;
            MemorySegment storage = packet.storageForTest();
            assertEquals(
                    MetalComputeCommandPacket.OP_DISPATCH_INDIRECT,
                    storage.get(ValueLayout.JAVA_INT, base)
            );
            assertEquals(0x3000, storage.get(ValueLayout.JAVA_LONG, base + 8));
            assertEquals(64, storage.get(ValueLayout.JAVA_LONG, base + 16));
            assertEquals(16, storage.get(ValueLayout.JAVA_LONG, base + 24));
            assertEquals(4, storage.get(ValueLayout.JAVA_LONG, base + 32));
            assertEquals(1, storage.get(ValueLayout.JAVA_LONG, base + 40));
        }
    }

    @Test
    void closedPacketRejectsFurtherWrites() {
        MetalComputeCommandPacket packet = new MetalComputeCommandPacket(1);
        packet.close();
        assertThrows(
                IllegalStateException.class,
                () -> packet.appendTexture(MemorySegment.NULL, MemorySegment.NULL, 0)
        );
    }
}
