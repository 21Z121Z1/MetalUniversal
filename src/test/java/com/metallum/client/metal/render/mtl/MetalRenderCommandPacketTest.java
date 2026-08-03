package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalRenderCommandPacketTest {
    @Test
    void writesOrderedStateAndDrawOperations() {
        try (MetalRenderCommandPacket packet = new MetalRenderCommandPacket(4)) {
            assertTrue(packet.appendScissor(MemorySegment.NULL, 3, 5, 640, 360));
            assertTrue(packet.appendDrawPrimitives(
                    MemorySegment.NULL,
                    3,
                    7,
                    18,
                    2,
                    4
            ));

            MemorySegment storage = packet.storageForTest();
            assertEquals(MetalRenderCommandPacket.MAGIC, storage.get(ValueLayout.JAVA_INT, 0));
            assertEquals(MetalRenderCommandPacket.VERSION, storage.get(ValueLayout.JAVA_INT, 4));
            assertEquals(MetalRenderCommandPacket.ENTRY_SIZE, storage.get(ValueLayout.JAVA_INT, 16));
            assertEquals(2, packet.operationCount());

            long first = MetalRenderCommandPacket.HEADER_SIZE;
            assertEquals(
                    MetalRenderCommandPacket.OP_SCISSOR,
                    storage.get(ValueLayout.JAVA_INT, first)
            );
            assertEquals(3, storage.get(ValueLayout.JAVA_LONG, first + 8));
            assertEquals(5, storage.get(ValueLayout.JAVA_LONG, first + 16));
            assertEquals(640, storage.get(ValueLayout.JAVA_LONG, first + 24));
            assertEquals(360, storage.get(ValueLayout.JAVA_LONG, first + 32));

            long second = first + MetalRenderCommandPacket.ENTRY_SIZE;
            assertEquals(
                    MetalRenderCommandPacket.OP_DRAW_PRIMITIVES,
                    storage.get(ValueLayout.JAVA_INT, second)
            );
            assertEquals(3, storage.get(ValueLayout.JAVA_LONG, second + 8));
            assertEquals(7, storage.get(ValueLayout.JAVA_LONG, second + 16));
            assertEquals(18, storage.get(ValueLayout.JAVA_LONG, second + 24));
            assertEquals(2, storage.get(ValueLayout.JAVA_LONG, second + 32));
            assertEquals(4, storage.get(ValueLayout.JAVA_LONG, second + 40));
        }
    }

    @Test
    void packsSignedBaseVertexAndBaseInstanceWithoutAllocation() {
        try (MetalRenderCommandPacket packet = new MetalRenderCommandPacket(2)) {
            assertTrue(packet.appendDrawIndexed(
                    MemorySegment.NULL,
                    3,
                    36,
                    0,
                    MemorySegment.ofAddress(0x1000),
                    128,
                    1,
                    -17,
                    9
            ));
            long base = MetalRenderCommandPacket.HEADER_SIZE;
            long packed = packet.storageForTest().get(
                    ValueLayout.JAVA_LONG,
                    base + 56
            );
            assertEquals(-17, (int) (packed >> 32));
            assertEquals(9, (int) packed);
        }
    }

    @Test
    void closedPacketRejectsFurtherWrites() {
        MetalRenderCommandPacket packet = new MetalRenderCommandPacket(1);
        packet.close();
        assertThrows(
                IllegalStateException.class,
                () -> packet.appendCullMode(MemorySegment.NULL, 0)
        );
    }
}
