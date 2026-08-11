package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalRenderStatePacketTest {
    private static final MemorySegment ENCODER = MemorySegment.ofAddress(0x1000L);

    @Test
    void writesFixedWidthVersionedEntries() {
        try (MetalRenderStatePacket packet = new MetalRenderStatePacket(8)) {
            assertTrue(packet.appendPipeline(ENCODER, MemorySegment.ofAddress(0x2000L)));
            assertTrue(packet.appendTextureAndSampler(
                    ENCODER,
                    MemorySegment.ofAddress(0x3000L),
                    MemorySegment.ofAddress(0x4000L),
                    7L,
                    3
            ));

            MemorySegment storage = packet.storageForTest();
            assertEquals(MetalRenderStatePacket.MAGIC, storage.get(ValueLayout.JAVA_INT, 0L));
            assertEquals(MetalRenderStatePacket.VERSION, storage.get(ValueLayout.JAVA_INT, 4L));
            assertEquals(2, packet.entryCount());

            long first = MetalRenderStatePacket.HEADER_SIZE;
            assertEquals(
                    MetalRenderStatePacket.OP_PIPELINE,
                    storage.get(ValueLayout.JAVA_INT, first)
            );
            assertEquals(0x2000L, storage.get(ValueLayout.JAVA_LONG, first + 16L));

            long second = first + MetalRenderStatePacket.ENTRY_SIZE;
            assertEquals(
                    MetalRenderStatePacket.OP_TEXTURE_AND_SAMPLER,
                    storage.get(ValueLayout.JAVA_INT, second)
            );
            assertEquals(3, storage.get(ValueLayout.JAVA_INT, second + 4L));
            assertEquals(7L, storage.get(ValueLayout.JAVA_LONG, second + 8L));
            assertEquals(0x3000L, storage.get(ValueLayout.JAVA_LONG, second + 16L));
            assertEquals(0x4000L, storage.get(ValueLayout.JAVA_LONG, second + 24L));
        }
    }

    @Test
    void encodesFloatBitsAndScissorWithoutObjects() {
        try (MetalRenderStatePacket packet = new MetalRenderStatePacket(4)) {
            packet.appendDepthBias(ENCODER, 1.25F, -0.5F, 0.125F);
            packet.appendScissor(ENCODER, 11L, 13L, 1920L, 1080L);

            MemorySegment storage = packet.storageForTest();
            long bias = MetalRenderStatePacket.HEADER_SIZE;
            assertEquals(
                    Integer.toUnsignedLong(Float.floatToRawIntBits(1.25F)),
                    storage.get(ValueLayout.JAVA_LONG, bias + 16L)
            );
            assertEquals(
                    Integer.toUnsignedLong(Float.floatToRawIntBits(-0.5F)),
                    storage.get(ValueLayout.JAVA_LONG, bias + 24L)
            );

            long scissor = bias + MetalRenderStatePacket.ENTRY_SIZE;
            assertEquals(11L, storage.get(ValueLayout.JAVA_LONG, scissor + 8L));
            assertEquals(13L, storage.get(ValueLayout.JAVA_LONG, scissor + 16L));
            assertEquals(1920L, storage.get(ValueLayout.JAVA_LONG, scissor + 24L));
            assertEquals(1080L, storage.get(ValueLayout.JAVA_LONG, scissor + 32L));
        }
    }

    @Test
    void preservesNullableMetalBindingsAsZeroAddresses() {
        try (MetalRenderStatePacket packet = new MetalRenderStatePacket(2)) {
            assertTrue(packet.appendDepthStencil(ENCODER, null));
            assertTrue(packet.appendTextureAndSampler(ENCODER, null, null, 2L, 3));

            MemorySegment storage = packet.storageForTest();
            long depthStencil = MetalRenderStatePacket.HEADER_SIZE;
            assertEquals(0L, storage.get(ValueLayout.JAVA_LONG, depthStencil + 16L));

            long textureAndSampler = depthStencil + MetalRenderStatePacket.ENTRY_SIZE;
            assertEquals(0L, storage.get(ValueLayout.JAVA_LONG, textureAndSampler + 16L));
            assertEquals(0L, storage.get(ValueLayout.JAVA_LONG, textureAndSampler + 24L));
        }
    }

    @Test
    void closedPacketRejectsFurtherWrites() {
        MetalRenderStatePacket packet = new MetalRenderStatePacket(2);
        packet.close();
        assertThrows(
                IllegalStateException.class,
                () -> packet.appendPipeline(ENCODER, MemorySegment.ofAddress(0x2000L))
        );
    }
}
