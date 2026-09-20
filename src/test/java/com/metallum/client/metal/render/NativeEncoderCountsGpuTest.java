package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Metal encoder-count coverage without enabling timestamp sampling. */
final class NativeEncoderCountsGpuTest {
    private static final int WIDTH = 1;
    private static final int HEIGHT = 1;
    private static final int PIXEL_BYTES = 4;
    private static final int READBACK_ROW_BYTES = 256;
    private static final long WINDOW = 0x4e4154495645434cL;
    private static final boolean METAL4 = Boolean.parseBoolean(
            System.getProperty("metallum.test.encoderCountsMetal4", "false")
    );

    private MemorySegment device;
    private MemorySegment queue;
    private MemorySegment texture;
    private MemorySegment readback;

    @BeforeEach
    void createStandaloneMetalFixture() {
        assertFalse(Boolean.getBoolean("metallum.validation.gpuTiming"),
                "encoder-count coverage must prove independence from timestamp timing");
        assertFalse(Boolean.getBoolean("metallum.validation.gpuPassTiming"),
                "encoder-count coverage must not use pass timestamp sampling");

        device = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(device),
                "MTLCreateSystemDefaultDevice returned null: real GPU is required");
        queue = MetalNativeBridge.MTLDevice_makeCommandQueue(device);
        assertFalse(MetalNativeBridge.isNullHandle(queue),
                "MTLDevice.makeCommandQueue returned null: real GPU queue is required");

        if (METAL4) {
            assertEquals(1, MetalNativeBridge.metallum_metal4_supported(device),
                    "Metal 4 task requires a Metal 4 device/SDK");
            assertEquals(1, MetalNativeBridge.metallum_residency_set_enable(device, queue),
                    "Metal 4 main renderer requires explicit residency");
            // The null layer keeps this fixture off the presentation path while
            // still exercising the same reusable main-queue lease context.
            assertEquals(1, MetalNativeBridge.metallum_metal4_main_renderer_enable(
                    device, MemorySegment.NULL
            ), "Metal 4 main renderer could not be enabled");
        }

        long usage = MTLTextureUsage.RenderTarget.value | MTLTextureUsage.ShaderRead.value;
        texture = MetalNativeBridge.metallum_create_texture_2d(
                device,
                MTLPixelFormat.RGBA8Unorm,
                WIDTH,
                HEIGHT,
                1,
                1,
                0,
                usage,
                MTLStorageMode.Private,
                "encoder-counts-source"
        );
        assertFalse(MetalNativeBridge.isNullHandle(texture),
                "real render attachment creation failed");
        readback = MetalNativeBridge.metallum_create_buffer(
                device,
                READBACK_ROW_BYTES,
                MTLResourceOptions.of(MTLStorageMode.Shared, MTLHazardTrackingMode.Default)
        );
        assertFalse(MetalNativeBridge.isNullHandle(readback),
                "real shared readback buffer creation failed");
    }

    @AfterEach
    void closeStandaloneMetalFixture() {
        try {
            // Do not let a subsequent fixture's native allocations be observed
            // as part of a failed test's measurement window.
            MetalNativeBridge.metallum_encoder_counts_reset(0);
        } finally {
            if (!MetalNativeBridge.isNullHandle(readback)) {
                MetalNativeBridge.metallum_release_object(readback);
                readback = MemorySegment.NULL;
            }
            if (!MetalNativeBridge.isNullHandle(texture)) {
                MetalNativeBridge.metallum_release_object(texture);
                texture = MemorySegment.NULL;
            }
            if (!MetalNativeBridge.isNullHandle(queue)) {
                MetalNativeBridge.metallum_release_object(queue);
                queue = MemorySegment.NULL;
            }
            if (!MetalNativeBridge.isNullHandle(device)) {
                MetalNativeBridge.metallum_release_object(device);
                device = MemorySegment.NULL;
            }
        }
    }

    @Test
    void countsRenderBlitAndComputeWithoutTimestampSampling() {
        MetalNativeBridge.metallum_encoder_counts_reset(NativeEncoderCounts.MAX_ROWS);
        int submitCount = METAL4 ? 6 : 2;
        for (int submit = 0; submit < submitCount; submit++) {
            assertPixelAfterThreeRealEncoders(submit, submit + 10L);
        }

        NativeEncoderCounts.Snapshot snapshot = MetalNativeBridge.metallum_encoder_counts_snapshot();
        assertTrue(snapshot.enabled(), snapshot.toString());
        assertEquals(0L, snapshot.droppedRows(), snapshot.toString());
        assertEquals(0L, snapshot.invalidEvents(), snapshot.toString());
        assertEquals(submitCount, snapshot.rowCount(), snapshot.toString());
        assertEquals(0L, snapshot.activeCommandBuffers(), snapshot.toString());
        assertEquals(0L, snapshot.activeEncoders(), snapshot.toString());
        Set<Long> submitIds = new HashSet<>();
        for (NativeEncoderCounts.Sample row : snapshot.rows()) {
            assertEquals(WINDOW, row.windowId(), row.toString());
            assertEquals(row.submitIndex() + 10L, row.frameId(), row.toString());
            submitIds.add(row.submitIndex());
            assertEquals(row.created(), row.ended(), row.toString());
            assertEquals(3L, row.attempted(), row.toString());
            assertEquals(3L, row.created(), row.toString());
            assertEquals(1L, row.renderCreated(), row.toString());
            assertEquals(METAL4 ? 0L : 1L, row.blitCreated(), row.toString());
            assertEquals(METAL4 ? 2L : 1L, row.computeCreated(), row.toString());
            assertEquals(METAL4 ? 4L : 3L, row.backend(), row.toString());
        }
        assertEquals(submitCount, submitIds.size(), snapshot.toString());

        if (METAL4) {
            long[] stats = MetalNativeBridge.metallum_metal4_main_renderer_stats();
            assertEquals(1L, stats[0], "main renderer must be engaged");
            assertTrue(stats[1] >= submitCount, "main renderer begin count: " + java.util.Arrays.toString(stats));
            assertTrue(stats[2] >= submitCount, "main renderer submit count: " + java.util.Arrays.toString(stats));
            assertTrue(stats[3] >= submitCount - 3, "main renderer lease reuse: " + java.util.Arrays.toString(stats));
        }
    }

    @Test
    void duplicateBindingFailsClosedAndDoesNotChangeReadback() {
        MetalNativeBridge.metallum_encoder_counts_reset(NativeEncoderCounts.MAX_ROWS);
        MemorySegment commandBuffer = newCommandBuffer(77L);
        try {
            bind(commandBuffer, 77L, 123L, 0L);
            assertThrows(IllegalStateException.class,
                    () -> bind(commandBuffer, 77L, 999L, 0L),
                    "a command buffer/lease identity must not be rebound");
            encodeRenderBlit(commandBuffer);
            commitAndWait(commandBuffer);
            assertRedPixel(readbackBytes(), "duplicate bind changed GPU execution/readback");
        } finally {
            release(commandBuffer);
        }

        NativeEncoderCounts.Snapshot snapshot = MetalNativeBridge.metallum_encoder_counts_snapshot();
        assertEquals(1L, snapshot.rowCount(), snapshot.toString());
        assertEquals(0L, snapshot.activeCommandBuffers(), snapshot.toString());
        assertEquals(0L, snapshot.activeEncoders(), snapshot.toString());
        assertTrue(snapshot.invalidEvents() > 0L, snapshot.toString());
        assertEquals(3L, snapshot.rows().getFirst().created(), snapshot.toString());
        assertEquals(3L, snapshot.rows().getFirst().ended(), snapshot.toString());
    }

    @Test
    void duplicateWindowSubmitAcrossPhysicalBuffersKeepsOriginalRow() {
        MetalNativeBridge.metallum_encoder_counts_reset(NativeEncoderCounts.MAX_ROWS);
        MemorySegment first = newCommandBuffer(88L);
        try {
            bind(first, WINDOW, 300L, 9L);
            encodeRenderBlit(first);
            commitAndWait(first);
            assertRedPixel(readbackBytes(), "first physical command buffer did not complete");
        } finally {
            release(first);
        }

        MemorySegment duplicate = newCommandBuffer(89L);
        try {
            assertThrows(IllegalStateException.class,
                    () -> bind(duplicate, WINDOW, 301L, 9L),
                    "same window/submit identity must reject a different frame");
            // Submit real work on the rejected lease to verify accounting failure
            // preserves rendering and returns the Metal 4 slot before teardown.
            encodeRenderBlit(duplicate);
            commitAndWait(duplicate);
            assertRedPixel(readbackBytes(), "rejected duplicate changed GPU execution/readback");
        } finally {
            release(duplicate);
        }

        NativeEncoderCounts.Snapshot snapshot = MetalNativeBridge.metallum_encoder_counts_snapshot();
        assertEquals(1L, snapshot.rowCount(), snapshot.toString());
        assertEquals(0L, snapshot.activeCommandBuffers(), snapshot.toString());
        assertEquals(0L, snapshot.activeEncoders(), snapshot.toString());
        assertTrue(snapshot.invalidEvents() > 0L, snapshot.toString());
        NativeEncoderCounts.Sample original = snapshot.rows().getFirst();
        assertEquals(WINDOW, original.windowId(), original.toString());
        assertEquals(300L, original.frameId(), original.toString());
        assertEquals(9L, original.submitIndex(), original.toString());
    }

    @Test
    void capacityOneReportsDroppedOrInvalidSecondIdentity() {
        MetalNativeBridge.metallum_encoder_counts_reset(1);
        assertPixelAfterThreeRealEncoders(0L, 200L);
        assertPixelAfterThreeRealEncoders(1L, 201L);

        NativeEncoderCounts.Snapshot snapshot = MetalNativeBridge.metallum_encoder_counts_snapshot();
        assertEquals(1L, snapshot.capacityRows(), snapshot.toString());
        assertEquals(1L, snapshot.rowCount(), snapshot.toString());
        assertEquals(0L, snapshot.activeCommandBuffers(), snapshot.toString());
        assertEquals(0L, snapshot.activeEncoders(), snapshot.toString());
        assertTrue(snapshot.droppedRows() > 0L || snapshot.invalidEvents() > 0L,
                "capacity-one overflow must be explicit: " + snapshot);
    }

    private void assertPixelAfterThreeRealEncoders(final long submitIndex, final long frameId) {
        MemorySegment commandBuffer = newCommandBuffer(frameId);
        try {
            bind(commandBuffer, WINDOW, frameId, submitIndex);
            encodeRenderBlit(commandBuffer);
            commitAndWait(commandBuffer);
            assertRedPixel(readbackBytes(), "real render/blit readback did not complete");
        } finally {
            release(commandBuffer);
        }
    }

    private MemorySegment newCommandBuffer(final long labelId) {
        MemorySegment commandBuffer = MetalNativeBridge.MTLCommandQueue_makeCommandBuffer(
                queue, "encoder-counts-" + labelId
        );
        assertFalse(MetalNativeBridge.isNullHandle(commandBuffer),
                "real command-buffer creation failed");
        return commandBuffer;
    }

    private void bind(
            final MemorySegment commandBuffer,
            final long windowId,
            final long frameId,
            final long submitIndex
    ) {
        MetalNativeBridge.metallum_encoder_counts_bind(
                commandBuffer, windowId, frameId, submitIndex
        );
    }

    private void encodeRenderBlit(final MemorySegment commandBuffer) {
        MemorySegment render = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(
                commandBuffer,
                texture,
                MemorySegment.NULL,
                WIDTH,
                HEIGHT,
                1,
                1.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                0.0
        );
        assertFalse(MetalNativeBridge.isNullHandle(render), "real render encoder creation failed");
        endAndRelease(render);

        if (!METAL4) {
            MemorySegment compute = MetalNativeBridge.MTLCommandBuffer_makeComputeCommandEncoder(commandBuffer);
            assertFalse(MetalNativeBridge.isNullHandle(compute), "real compute encoder creation failed");
            endAndRelease(compute);
        }

        MemorySegment blit = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(
                commandBuffer, "encoder-counts-readback"
        );
        assertFalse(MetalNativeBridge.isNullHandle(blit), "real blit encoder creation failed");
        MetalNativeBridge.MTLBlitCommandEncoder_copyFromTextureToBuffer(
                blit,
                texture,
                readback,
                0L,
                0L,
                0L,
                0L,
                0L,
                WIDTH,
                HEIGHT,
                READBACK_ROW_BYTES,
                READBACK_ROW_BYTES
        );
        endAndRelease(blit);

        if (METAL4) {
            // The MTL4 bridge exposes this physical compute encoder through
            // the blit factory. The generic compute factory only accepts an
            // MTL3 command-buffer pointer and must not receive an MTL4 lease.
            MemorySegment compute = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(
                    commandBuffer, "encoder-counts-compute"
            );
            assertFalse(MetalNativeBridge.isNullHandle(compute),
                    "real Metal 4 compute encoder creation failed");
            endAndRelease(compute);
        }
    }

    private void commitAndWait(final MemorySegment commandBuffer) {
        MetalNativeBridge.MTLCommandBuffer_commit(commandBuffer);
        assertEquals(0, MetalNativeBridge.MTLCommandBuffer_waitUntilCompleted(commandBuffer, 10_000L),
                "real GPU command buffer did not complete");
        assertEquals(1, MetalNativeBridge.MTLCommandBuffer_completedSuccessfully(commandBuffer),
                "real GPU command buffer completed with an error");
    }

    private byte[] readbackBytes() {
        MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(readback);
        assertFalse(MetalNativeBridge.isNullHandle(contents), "shared readback contents is null");
        ByteBuffer bytes = MetalNativeBridge.nativeByteBufferView(contents, PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        byte[] result = new byte[PIXEL_BYTES];
        bytes.get(0, result);
        return result;
    }

    private static void assertRedPixel(final byte[] bytes, final String message) {
        assertNotNull(bytes, message);
        assertEquals(4, bytes.length, message);
        assertEquals(255, Byte.toUnsignedInt(bytes[0]), message);
        assertEquals(0, Byte.toUnsignedInt(bytes[1]), message);
        assertEquals(0, Byte.toUnsignedInt(bytes[2]), message);
        assertEquals(255, Byte.toUnsignedInt(bytes[3]), message);
    }

    private static void endAndRelease(final MemorySegment encoder) {
        try {
            MetalNativeBridge.MTLCommandEncoder_endEncoding(encoder);
        } finally {
            MetalNativeBridge.metallum_release_object(encoder);
        }
    }

    private static void release(final MemorySegment object) {
        if (!MetalNativeBridge.isNullHandle(object)) {
            MetalNativeBridge.metallum_release_object(object);
        }
    }
}
