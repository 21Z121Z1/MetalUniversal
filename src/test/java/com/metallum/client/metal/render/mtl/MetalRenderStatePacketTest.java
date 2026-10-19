package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MetalRenderStatePacketTest {
    private static final MemorySegment ENCODER = MemorySegment.ofAddress(0x1000L);

    @Test
    void singleStateProductionDrawPreservesTheDirectSetterRoute() {
        try (MetalRenderStatePacket packet = new MetalRenderStatePacket(8)) {
            packet.appendScissor(ENCODER, 11, 13, 32, 48);
            assertFalse(packet.tryDraw(ENCODER, 1, 3, 0, MemorySegment.NULL, 0, 0, 3, 1, 0));
            assertFalse(packet.hasNativeStateBatch());
            assertEquals(1, packet.entryCount(), "the ordinary flush must still consume this state before drawing");
            assertTrue(packet.active());
        }
    }

    @Test
    void fusedDirectAndIndexedDrawsCarryExactArgumentsAndConsumeStateOnce() {
        try (MetalRenderStatePacket packet = new MetalRenderStatePacket(8)) {
            for (int kind : new int[]{2, 1, 2}) {
                // The v2 ABI accepts one entry; production dispatch applies its
                // stricter native-packet threshold before calling this layer.
                packet.appendScissor(ENCODER, 11, 13, 32, 48);
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                MemorySegment indices = kind == 2 ? MemorySegment.ofAddress(0x5678) : MemorySegment.NULL;
                int first = kind == 2 ? -7 : 9;
                int indexType = kind == 2 ? 1 : 0;
                long indexOffset = kind == 2 ? 12 : 0;
                MetalRenderStatePacket.DrawSubmitter oracle = (enc, state, bytes, draw) -> {
                    calls.incrementAndGet();
                    assertEquals(ENCODER, enc);
                    assertEquals(64, draw.byteSize());
                    assertEquals(64, draw.get(ValueLayout.JAVA_INT, 0));
                    assertEquals(kind, draw.get(ValueLayout.JAVA_INT, 4));
                    assertEquals(3, draw.get(ValueLayout.JAVA_INT, 8));
                    assertEquals(indexType, draw.get(ValueLayout.JAVA_INT, 12));
                    assertEquals(indices.address(), draw.get(ValueLayout.JAVA_LONG, 16));
                    assertEquals(indexOffset, draw.get(ValueLayout.JAVA_LONG, 24));
                    assertEquals(first, draw.get(ValueLayout.JAVA_INT, 32));
                    assertEquals(6, draw.get(ValueLayout.JAVA_INT, 36));
                    assertEquals(5, draw.get(ValueLayout.JAVA_INT, 40));
                    assertEquals(17, draw.get(ValueLayout.JAVA_INT, 44));
                    assertEquals(0, draw.get(ValueLayout.JAVA_LONG, 48));
                    assertEquals(0, draw.get(ValueLayout.JAVA_LONG, 56));
                    assertEquals(64, bytes); // 16-byte header + one 48-byte state record.
                    assertEquals(1, state.get(ValueLayout.JAVA_INT, 12));
                    assertEquals(11, state.get(ValueLayout.JAVA_LONG, 24));
                    return 1;
                };
                assertTrue(packet.tryDraw(ENCODER, kind, 3, indexType, indices, indexOffset,
                        first, 6, 5, 17, oracle));
                assertEquals(0, packet.entryCount());
                assertFalse(packet.tryDraw(ENCODER, kind, 3, indexType, indices, indexOffset,
                        first, 6, 5, 17, oracle));
                assertEquals(1, calls.get(), "an empty packet must use the ordinary draw without a second fused call");
            }
        }
    }

    @Test
    void rejectedFusionKeepsEveryStateEntryForLegacyReplayAndDisablesFurtherFusion() {
        for (int status : new int[]{0, -1, -2, -100}) {
            try (MetalRenderStatePacket packet = new MetalRenderStatePacket(8)) {
                packet.appendPipeline(ENCODER, MemorySegment.ofAddress(0x2000));
                packet.appendScissor(ENCODER, 0, 0, 128, 64);
                assertFalse(packet.tryDraw(ENCODER, 1, 3, 0, MemorySegment.NULL, 0,
                        0, 3, 1, 0, (enc, state, bytes, draw) -> status));
                assertEquals(2, packet.entryCount());
                assertEquals(0x2000, packet.storageForTest().get(ValueLayout.JAVA_LONG, 32));
                assertTrue(packet.active(), "state-only batching remains valid after a fused-draw rejection");
                packet.appendCullMode(ENCODER, 0);
                assertFalse(packet.tryDraw(ENCODER, 1, 3, 0, MemorySegment.NULL, 0,
                        0, 3, 1, 0, (enc, state, bytes, draw) -> { throw new AssertionError("fusion retried"); }));
                assertEquals(3, packet.entryCount());
            }
        }
    }

    @Test
    void uncertainDrawCannotBeRetriedByCatchingTheException() {
        try (MetalRenderStatePacket packet = new MetalRenderStatePacket(8)) {
            packet.appendCullMode(ENCODER, 0);
            RuntimeException failure = new IllegalStateException("uncertain FFM outcome");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> packet.tryDraw(ENCODER, 1, 3, 0, MemorySegment.NULL, 0,
                            0, 3, 1, 0, (enc, state, bytes, draw) -> { throw failure; })));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> packet.flush(ENCODER)));
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> packet.appendCullMode(ENCODER, 1)));
        }
    }

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
    void successfulConfinedAllocationIsCountedWhenActivationTelemetryIsEnabled() {
        MetalRenderStatePacketTelemetry.Snapshot before = MetalRenderStatePacketTelemetry.snapshot();
        try (MetalRenderStatePacket ignored = new MetalRenderStatePacket(8)) {
            if (MetalRenderStatePacketTelemetry.reuseActivationTelemetryEnabled()) {
                MetalRenderStatePacketTelemetry.Snapshot after = MetalRenderStatePacketTelemetry.snapshot();
                assertEquals(before.packetStorageAllocations() + 1, after.packetStorageAllocations());
            }
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
    void closedPacketRejectsFurtherWrites() {
        MetalRenderStatePacket packet = new MetalRenderStatePacket(2);
        packet.close();
        assertThrows(
                IllegalStateException.class,
                () -> packet.appendPipeline(ENCODER, MemorySegment.ofAddress(0x2000L))
        );
    }

    @Test
    void reusedScratchHasExclusiveLeaseAndResetsThePacket() {
        MetalRenderStatePacketTelemetry.Snapshot before = MetalRenderStatePacketTelemetry.snapshot();
        MetalRenderStatePacket first = MetalRenderStatePacket.acquireReusable();
        long address = first.storageForTest().address();
        first.appendPipeline(ENCODER, MemorySegment.ofAddress(0x2000L));
        first.close();
        try (MetalRenderStatePacket next = MetalRenderStatePacket.acquireReusable();
             MetalRenderStatePacket concurrent = MetalRenderStatePacket.acquireReusable()) {
            assertEquals(address, next.storageForTest().address());
            assertNotEquals(address, concurrent.storageForTest().address());
            assertEquals(0, next.entryCount());
            assertEquals(MetalRenderStatePacket.HEADER_SIZE,
                    next.storageForTest().get(ValueLayout.JAVA_INT, 8L));
            assertEquals(0, next.storageForTest().get(ValueLayout.JAVA_INT, 12L));
            assertThrows(IllegalStateException.class,
                    () -> first.appendPipeline(ENCODER, MemorySegment.ofAddress(0x3000L)));
            assertTrue(next.appendPipeline(ENCODER, MemorySegment.ofAddress(0x4000L)));
            assertEquals(0, concurrent.entryCount());
        }
        if (MetalRenderStatePacketTelemetry.reuseActivationTelemetryEnabled()) {
            MetalRenderStatePacketTelemetry.Snapshot after = MetalRenderStatePacketTelemetry.snapshot();
            assertTrue(after.packetStorageReuseHits() > before.packetStorageReuseHits());
            assertTrue(after.packetStorageAllocations() >= before.packetStorageAllocations());
        }
    }
}
