package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior checks for the bounded, opt-in RenderEncoderV3 argument scratch. */
final class MetalNativeBridgeArgumentScratchTest {
    @Test
    void zeroAttachmentsPreserveNullArrayPointers() {
        MetalNativeBridge.RenderEncoderArgumentScratch scratch =
                new MetalNativeBridge.RenderEncoderArgumentScratch();
        assertTrue(scratch.tryAcquire());
        try {
            scratch.copy(new MemorySegment[0], new int[0], new int[0], new float[0]);
            assertEquals(0L, scratch.textureArray(0).address());
            assertEquals(0L, scratch.loadArray(0).address());
            assertEquals(0L, scratch.storeArray(0).address());
            assertEquals(0L, scratch.clearColorArray(0).address());
        } finally {
            scratch.release();
        }
    }

    @Test
    void eightAttachmentsCopyAllDescriptorValuesIntoStableStorage() {
        MetalNativeBridge.RenderEncoderArgumentScratch scratch =
                new MetalNativeBridge.RenderEncoderArgumentScratch();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment[] textures = new MemorySegment[8];
            int[] loads = new int[8];
            int[] stores = new int[8];
            float[] clears = new float[32];
            for (int index = 0; index < textures.length; index++) {
                textures[index] = arena.allocate(1, 1);
                loads[index] = index + 1;
                stores[index] = 20 + index;
            }
            for (int index = 0; index < clears.length; index++) {
                clears[index] = index + 0.5F;
            }

            assertTrue(scratch.tryAcquire());
            try {
                scratch.copy(textures, loads, stores, clears);
                assertEquals(textures[7].address(),
                        scratch.textureArray(8).getAtIndex(ValueLayout.ADDRESS, 7).address());
                assertEquals(8, scratch.loadArray(8).getAtIndex(ValueLayout.JAVA_INT, 7));
                assertEquals(27, scratch.storeArray(8).getAtIndex(ValueLayout.JAVA_INT, 7));
                assertEquals(31.5F, scratch.clearColorArray(8).getAtIndex(ValueLayout.JAVA_FLOAT, 31));
            } finally {
                scratch.release();
            }
        }
    }

    @Test
    void sameThreadSequentialCallsReuseTheBoundedSegments() {
        MetalNativeBridge.RenderEncoderArgumentScratch scratch =
                new MetalNativeBridge.RenderEncoderArgumentScratch();
        assertTrue(scratch.tryAcquire());
        MemorySegment first;
        try {
            first = scratch.textureArray(1);
        } finally {
            scratch.release();
        }
        assertTrue(scratch.tryAcquire());
        try {
            assertSame(first, scratch.textureArray(1));
        } finally {
            scratch.release();
        }
    }

    @Test
    void nullUtf8AndOverlongLabelsKeepOriginalFallbackSemantics() {
        MetalNativeBridge.RenderEncoderArgumentScratch scratch =
                new MetalNativeBridge.RenderEncoderArgumentScratch();
        assertTrue(scratch.tryAcquire());
        try {
            assertEquals(0L, scratch.label(null).address());
            MemorySegment utf8 = scratch.label("雪");
            assertEquals((byte) 0xE9, utf8.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals((byte) 0x9B, utf8.get(ValueLayout.JAVA_BYTE, 1));
            assertEquals((byte) 0xAA, utf8.get(ValueLayout.JAVA_BYTE, 2));
            assertEquals((byte) 0, utf8.get(ValueLayout.JAVA_BYTE, 3));
            assertNull(scratch.label("x".repeat(MetalNativeBridge.RenderEncoderArgumentScratch.MAX_LABEL_BYTES)));
        } finally {
            scratch.release();
        }
    }

    @Test
    void reentrantLeaseCannotOverwriteBorrowedArgumentsAndExceptionCanReleaseIt() {
        MetalNativeBridge.RenderEncoderArgumentScratch scratch =
                new MetalNativeBridge.RenderEncoderArgumentScratch();
        assertTrue(scratch.tryAcquire());
        assertTrue(scratch.inUse());
        assertFalse(scratch.tryAcquire());
        try {
            scratch.copy(new MemorySegment[] {MemorySegment.NULL}, new int[0], new int[] {1}, new float[4]);
        } catch (ArrayIndexOutOfBoundsException expected) {
            // The production V3 path releases this lease from its finally block.
        } finally {
            scratch.release();
        }
        assertFalse(scratch.inUse());
        assertTrue(scratch.tryAcquire());
        scratch.release();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void threadLocalScratchIsolatedBetweenRenderThreads() throws InterruptedException {
        MetalNativeBridge.RenderEncoderArgumentScratch caller =
                MetalNativeBridge.RENDER_ENCODER_ARGUMENT_SCRATCH.get();
        AtomicReference<MetalNativeBridge.RenderEncoderArgumentScratch> other = new AtomicReference<>();
        Thread thread = new Thread(() -> other.set(MetalNativeBridge.RENDER_ENCODER_ARGUMENT_SCRATCH.get()));
        thread.start();
        thread.join();
        assertNotSame(caller, other.get());
    }
}
