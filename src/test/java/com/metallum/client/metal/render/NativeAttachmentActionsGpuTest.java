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

/** Real descriptor/final-store facts, checked against completed GPU readback. */
final class NativeAttachmentActionsGpuTest {
    private static final int WIDTH = 1;
    private static final int HEIGHT = 1;
    private static final int PIXEL_BYTES = 4;
    private static final int READBACK_ROW_BYTES = 256;
    private static final long WINDOW = 0x4e4154495645434cL;
    private static final boolean METAL4 = Boolean.parseBoolean(
            System.getProperty("metallum.test.attachmentActionsMetal4", "false")
    );

    private MemorySegment device;
    private MemorySegment queue;
    private MemorySegment texture;
    private MemorySegment readback;
    private MemorySegment fence;
    private boolean fenceSignaled;

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

        fence = MetalNativeBridge.metallum_create_fence(device);
        assertFalse(MetalNativeBridge.isNullHandle(fence));

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
            MetalNativeBridge.metallum_attachment_actions_reset(0);
            MetalNativeBridge.metallum_encoder_counts_reset(0);
        } finally {
            release(fence);
            fence = MemorySegment.NULL;
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
    void recordsFinalColorAndDepthActionsAcrossReusedCommandBuffers() {
        MetalNativeBridge.metallum_encoder_counts_reset(NativeEncoderCounts.MAX_ROWS);
        MetalNativeBridge.metallum_attachment_actions_reset(NativeAttachmentActions.MAX_ROWS);
        MemorySegment depth = MetalNativeBridge.metallum_create_texture_2d(device,
                MTLPixelFormat.Depth32Float, WIDTH, HEIGHT, 1, 1, 0,
                MTLTextureUsage.RenderTarget.value, MTLStorageMode.Private, "attachment-depth");
        assertFalse(MetalNativeBridge.isNullHandle(depth));
        try {
            // Six submissions cross the three-slot Metal 4 lease ring twice.
            for (int submit = 0; submit < 6; submit++) {
                MemorySegment cb = newCommandBuffer(submit);
                try {
                    bind(cb, WINDOW, 10 + submit, submit);
                    for (int pass = 0; pass < 2; pass++) {
                        MemorySegment render = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV3(
                                cb, new MemorySegment[]{texture}, depth, new int[]{2}, new int[]{2},
                                new float[]{1, 0, 0, 1}, 2, 2, 0.5, WIDTH, HEIGHT, "attachment-deferred");
                        assertFalse(MetalNativeBridge.isNullHandle(render));
                        try {
                            MetalNativeBridge.MTLRenderCommandEncoder_setColorStoreAction(render, 0, pass);
                            MetalNativeBridge.MTLRenderCommandEncoder_setDepthStoreAction(render, 1 - pass);
                            endRender(render);
                        } finally { release(render); }
                    }
                    copyReadback(cb);
                    commitAndWait(cb);
                    assertRedPixel(readbackBytes(), "recording final actions changed GPU output");
                } finally { release(cb); }
            }
        } finally { release(depth); }

        var snapshot = MetalNativeBridge.metallum_attachment_actions_snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(0, snapshot.invalidEvents(), snapshot.toString());
        assertEquals(0, snapshot.droppedRows());
        assertEquals(0, snapshot.activeRenderEncoders());
        assertEquals(12, snapshot.createdRenderEncoders());
        assertEquals(36, snapshot.rowCount());
        assertEquals(12, snapshot.rows().stream().filter(r -> r.aspect() == -1).count());
        assertEquals(12, snapshot.rows().stream().map(NativeAttachmentActions.Row::encoderSequence).distinct().count());
        for (var row : snapshot.rows()) {
            assertEquals(WINDOW, row.windowId());
            assertEquals(row.submitIndex() + 10, row.frameId());
            assertEquals(METAL4 ? 4 : 3, row.backend());
            assertEquals(1, row.ended());
            assertEquals(0, row.errorBits(), row.toString());
            assertEquals(0, row.reserved());
            if (row.aspect() == -1) {
                assertEquals(0x101, row.slot(), "descriptor expected color 0 plus depth");
                continue;
            }
            assertEquals(1, row.width());
            assertEquals(1, row.height());
            assertEquals(1, row.sampleCount());
            assertEquals(2, row.textureType());
            assertEquals(2, row.storageMode());
            assertEquals(0, row.level());
            assertEquals(0, row.resolvePixelFormat());
            assertEquals(2, row.loadAction());
            assertEquals(4, row.initialStoreAction(), "native MTLStoreAction.unknown is 4");
            assertTrue(row.finalStoreAction() == 0 || row.finalStoreAction() == 1);
            assertEquals(row.aspect() == 0 ? MTLPixelFormat.RGBA8Unorm.value : MTLPixelFormat.Depth32Float.value,
                    row.pixelFormat());
        }
        assertEquals(6, snapshot.rows().stream().filter(r -> r.aspect() == 0 && r.finalStoreAction() == 0).count());
        assertEquals(6, snapshot.rows().stream().filter(r -> r.aspect() == 0 && r.finalStoreAction() == 1).count());
        assertEquals(6, snapshot.rows().stream().filter(r -> r.aspect() == 1 && r.finalStoreAction() == 0).count());
        assertEquals(6, snapshot.rows().stream().filter(r -> r.aspect() == 1 && r.finalStoreAction() == 1).count());
        var counts = MetalNativeBridge.metallum_encoder_counts_snapshot();
        assertEquals(snapshot.createdRenderEncoders(), counts.rows().stream().mapToLong(NativeEncoderCounts.Sample::renderCreated).sum());
        assertEquals(0, counts.invalidEvents());
    }

    @Test
    void recordsV1V2AndV3FactoriesWhileLoadPreservesPriorClear() {
        MetalNativeBridge.metallum_encoder_counts_reset(64);
        MetalNativeBridge.metallum_attachment_actions_reset(64);
        MemorySegment cb = newCommandBuffer(0);
        try {
            bind(cb, WINDOW, 60, 0);
            MemorySegment first = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(cb, texture,
                    MemorySegment.NULL, WIDTH, HEIGHT, 1, 1, 0, 0, 1, 0, 0);
            assertFalse(MetalNativeBridge.isNullHandle(first));
            endRenderAndRelease(first);
            MemorySegment second = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV2(cb,
                    new MemorySegment[]{texture}, MemorySegment.NULL, WIDTH, HEIGHT,
                    new int[]{0}, new float[]{0, 0, 0, 0}, 0, 0, "attachment-v2-load");
            assertFalse(MetalNativeBridge.isNullHandle(second));
            endRenderAndRelease(second);
            MemorySegment third = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV3(cb,
                    new MemorySegment[]{texture}, MemorySegment.NULL, new int[]{1}, new int[]{1},
                    new float[]{0, 0, 0, 0}, 0, 0, 0, WIDTH, HEIGHT, "attachment-v3-load");
            assertFalse(MetalNativeBridge.isNullHandle(third));
            endRenderAndRelease(third);
            copyReadback(cb);
            commitAndWait(cb);
            assertRedPixel(readbackBytes(), "V2/V3 load failed to preserve the V1 clear");
        } finally { release(cb); }
        var snapshot = MetalNativeBridge.metallum_attachment_actions_snapshot();
        assertEquals(0, snapshot.invalidEvents());
        assertEquals(0, snapshot.droppedRows());
        assertEquals(3, snapshot.createdRenderEncoders());
        assertEquals(6, snapshot.rowCount());
        assertEquals(java.util.List.of(2L, 1L, 1L), snapshot.rows().stream().filter(r -> r.aspect() == 0)
                .map(NativeAttachmentActions.Row::loadAction).toList());
        for (var row : snapshot.rows()) {
            assertEquals(1, row.ended());
            assertEquals(0, row.errorBits());
            if (row.aspect() == 0) {
                assertEquals(1, row.initialStoreAction());
                assertEquals(1, row.finalStoreAction());
            }
        }
    }

    @Test
    void preservesMrtHolesAndCombinedDepthStencilAsSeparateAspects() {
        MetalNativeBridge.metallum_encoder_counts_reset(64);
        MetalNativeBridge.metallum_attachment_actions_reset(64);
        MemorySegment extra = MetalNativeBridge.metallum_create_texture_2d(device, MTLPixelFormat.RGBA8Unorm,
                WIDTH, HEIGHT, 1, 1, 0, MTLTextureUsage.RenderTarget.value, MTLStorageMode.Private, "attachment-slot2");
        MemorySegment depth = MetalNativeBridge.metallum_create_texture_2d(device, MTLPixelFormat.Depth32Float_Stencil8,
                WIDTH, HEIGHT, 1, 1, 0, MTLTextureUsage.RenderTarget.value, MTLStorageMode.Private, "attachment-depth-stencil");
        assertFalse(MetalNativeBridge.isNullHandle(extra));
        assertFalse(MetalNativeBridge.isNullHandle(depth));
        MemorySegment cb = newCommandBuffer(0);
        try {
            bind(cb, WINDOW, 50, 0);
            MemorySegment render = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV3(cb,
                    new MemorySegment[]{texture, MemorySegment.NULL, extra}, depth,
                    new int[]{2, 0, 2}, new int[]{1, 0, 1},
                    new float[]{1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1},
                    2, 0, 0.5, WIDTH, HEIGHT, "attachment-mrt-holes");
            assertFalse(MetalNativeBridge.isNullHandle(render));
            endRenderAndRelease(render);
            copyReadback(cb);
            commitAndWait(cb);
            assertRedPixel(readbackBytes(), "MRT accounting changed clear output");
        } finally { release(cb); release(depth); release(extra); }
        var snapshot = MetalNativeBridge.metallum_attachment_actions_snapshot();
        assertEquals(0, snapshot.invalidEvents(), snapshot.toString());
        assertEquals(0, snapshot.droppedRows());
        assertEquals(5, snapshot.rowCount());
        var marker = snapshot.rows().stream().filter(r -> r.aspect() == -1).findFirst().orElseThrow();
        assertEquals(0x305, marker.slot(), "color 0/2, depth and stencil must all be represented");
        assertEquals(Set.of(0L, 2L), snapshot.rows().stream().filter(r -> r.aspect() == 0)
                .map(NativeAttachmentActions.Row::slot).collect(java.util.stream.Collectors.toSet()));
        for (var row : snapshot.rows()) {
            assertEquals(1, row.ended());
            assertEquals(0, row.errorBits());
            if (row.aspect() == 1 || row.aspect() == 2) {
                assertEquals(MTLPixelFormat.Depth32Float_Stencil8.value, row.pixelFormat());
                assertEquals(0, row.finalStoreAction());
                assertEquals(0, row.slot());
            }
        }
    }

    @Test
    void capturesLiveEncoderThenItsCompletedFinalAction() {
        MetalNativeBridge.metallum_encoder_counts_reset(64);
        MetalNativeBridge.metallum_attachment_actions_reset(64);
        MemorySegment cb = newCommandBuffer(0);
        try {
            bind(cb, WINDOW, 30, 0);
            MemorySegment render = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV3(
                    cb, new MemorySegment[]{texture}, MemorySegment.NULL, new int[]{2}, new int[]{2},
                    new float[]{1, 0, 0, 1}, 0, 0, 0, WIDTH, HEIGHT, "attachment-live");
            assertFalse(MetalNativeBridge.isNullHandle(render));
            try {
                var live = MetalNativeBridge.metallum_attachment_actions_snapshot();
                assertEquals(1, live.activeRenderEncoders());
                assertTrue(live.rows().stream().allMatch(r -> r.ended() == 0));
                var color = live.rows().stream().filter(r -> r.aspect() == 0).findFirst().orElseThrow();
                assertEquals(4, color.initialStoreAction());
                assertEquals(4, color.finalStoreAction());
                MetalNativeBridge.MTLRenderCommandEncoder_setColorStoreAction(render, 0, 1);
                endRender(render);
            } finally { release(render); }
            copyReadback(cb);
            commitAndWait(cb);
            assertRedPixel(readbackBytes(), "live snapshot changed GPU execution");
        } finally { release(cb); }
        var completed = MetalNativeBridge.metallum_attachment_actions_snapshot();
        assertEquals(0, completed.activeRenderEncoders());
        assertEquals(0, completed.invalidEvents());
        assertTrue(completed.rows().stream().allMatch(r -> r.ended() == 1));
        assertEquals(1, completed.rows().stream().filter(r -> r.aspect() == 0).findFirst().orElseThrow().finalStoreAction());
    }

    @Test
    void boundedOverflowDoesNotChangeReadback() {
        MetalNativeBridge.metallum_encoder_counts_reset(64);
        MetalNativeBridge.metallum_attachment_actions_reset(1);
        renderAndCheck(true);
        var snapshot = MetalNativeBridge.metallum_attachment_actions_snapshot();
        assertEquals(1, snapshot.capacityRows());
        assertTrue(snapshot.rowCount() <= 1);
        assertTrue(snapshot.droppedRows() > 0 || snapshot.invalidEvents() > 0, snapshot.toString());
    }

    @Test
    void missingBindingIsVisibleAndPreservesGpuExecution() {
        MetalNativeBridge.metallum_encoder_counts_reset(64);
        MetalNativeBridge.metallum_attachment_actions_reset(64);
        renderAndCheck(false);
        assertTrue(MetalNativeBridge.metallum_attachment_actions_snapshot().invalidEvents() > 0);
    }

    @Test
    void disabledLedgerStaysEmptyWithoutChangingReadback() {
        MetalNativeBridge.metallum_encoder_counts_reset(64);
        MetalNativeBridge.metallum_attachment_actions_reset(0);
        renderAndCheck(true);
        var snapshot = MetalNativeBridge.metallum_attachment_actions_snapshot();
        assertFalse(snapshot.enabled());
        assertEquals(0, snapshot.rowCount());
        assertEquals(0, snapshot.createdRenderEncoders());
        assertEquals(0, snapshot.invalidEvents());
    }

    private void renderAndCheck(boolean bindIdentity) {
        MemorySegment cb = newCommandBuffer(0);
        try {
            if (bindIdentity) bind(cb, WINDOW, 30, 0);
            encodeRenderBlit(cb);
            commitAndWait(cb);
            assertRedPixel(readbackBytes(), "diagnostic failure changed GPU output");
        } finally { release(cb); }
    }

    private void copyReadback(MemorySegment cb) {
        MemorySegment blit = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(cb, "attachment-readback");
        assertFalse(MetalNativeBridge.isNullHandle(blit));
        try {
            waitReadback(blit);
            MetalNativeBridge.MTLBlitCommandEncoder_copyFromTextureToBuffer(blit, texture, readback,
                    0L, 0L, 0L, 0L, 0L, WIDTH, HEIGHT, READBACK_ROW_BYTES, READBACK_ROW_BYTES);
            MetalNativeBridge.MTLCommandEncoder_endEncoding(blit);
        } finally { release(blit); }
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
        endRenderAndRelease(render);

        if (!METAL4) {
            MemorySegment compute = MetalNativeBridge.MTLCommandBuffer_makeComputeCommandEncoder(commandBuffer);
            assertFalse(MetalNativeBridge.isNullHandle(compute), "real compute encoder creation failed");
            endAndRelease(compute);
        }

        MemorySegment blit = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(
                commandBuffer, "encoder-counts-readback"
        );
        assertFalse(MetalNativeBridge.isNullHandle(blit), "real blit encoder creation failed");
        waitReadback(blit);
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

    private void endRender(MemorySegment encoder) {
        // Production texture allocations are untracked. Preserve the same explicit
        // dependency chain here; sequential CPU calls do not order GPU encoders.
        if (!METAL4) {
            if (fenceSignaled) MetalNativeBridge.MTLRenderCommandEncoder_waitForFence(encoder, fence, 3L);
            MetalNativeBridge.MTLRenderCommandEncoder_updateFence(encoder, fence, 3L);
            fenceSignaled = true;
        }
        MetalNativeBridge.MTLCommandEncoder_endEncoding(encoder);
    }

    private void endRenderAndRelease(MemorySegment encoder) {
        try { endRender(encoder); } finally { release(encoder); }
    }

    private void waitReadback(MemorySegment blit) {
        if (!METAL4) MetalNativeBridge.MTLBlitCommandEncoder_waitForFence(blit, fence);
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
