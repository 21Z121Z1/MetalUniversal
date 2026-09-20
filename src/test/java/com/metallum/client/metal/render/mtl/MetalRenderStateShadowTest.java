package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class MetalRenderStateShadowTest {
    private static final MemorySegment BUFFER_A = MemorySegment.ofAddress(0x1000L);
    private static final MemorySegment BUFFER_B = MemorySegment.ofAddress(0x2000L);
    private static final MemorySegment TEXTURE_A = MemorySegment.ofAddress(0x3000L);
    private static final MemorySegment TEXTURE_B = MemorySegment.ofAddress(0x4000L);
    private static final MemorySegment SAMPLER_A = MemorySegment.ofAddress(0x5000L);

    @Test
    void recycledEncoderStateIsEmptyAndNeverSharedWithAnActiveEncoder() {
        MetalRenderStateShadow first = MetalRenderStateShadow.acquire();
        first.setPipeline(BUFFER_A);
        first.recordBuffer(BUFFER_A, 64, 0, 3);
        first.setTextureAndSampler(TEXTURE_A, SAMPLER_A, 0, 3);
        first.setScissor(0, 0, 100, 100);
        first.recycle();
        MetalRenderStateShadow next = MetalRenderStateShadow.acquire();
        MetalRenderStateShadow concurrent = MetalRenderStateShadow.acquire();
        try {
            if (Boolean.getBoolean("metallum.opt.reuseEncoderState")) assertSame(first, next);
            else assertNotSame(first, next);
            assertNotSame(next, concurrent);
            assertTrue(next.setPipeline(BUFFER_A));
            assertEquals(MetalRenderStateShadow.BufferUpdate.FULL_BIND, next.classifyBuffer(BUFFER_A, 64, 0, 3));
            assertTrue(next.setTextureAndSampler(TEXTURE_A, SAMPLER_A, 0, 3));
            assertTrue(next.setScissor(0, 0, 100, 100));
        } finally {
            next.recycle();
            concurrent.recycle();
        }
    }

    @Test
    void combinedStageBindSeedsIndividualStageState() {
        MetalRenderStateShadow shadow = new MetalRenderStateShadow(16);

        assertEquals(
                MetalRenderStateShadow.BufferUpdate.FULL_BIND,
                shadow.classifyBuffer(BUFFER_A, 32L, 2L, 0b11)
        );
        shadow.recordBuffer(BUFFER_A, 32L, 2L, 0b11);

        assertEquals(
                MetalRenderStateShadow.BufferUpdate.SKIP,
                shadow.classifyBuffer(BUFFER_A, 32L, 2L, 0b01)
        );
        assertEquals(
                MetalRenderStateShadow.BufferUpdate.SKIP,
                shadow.classifyBuffer(BUFFER_A, 32L, 2L, 0b10)
        );
    }

    @Test
    void sameBufferWithNewOffsetUsesOffsetOnlyPath() {
        MetalRenderStateShadow shadow = new MetalRenderStateShadow(16);
        shadow.recordBuffer(BUFFER_A, 0L, 4L, 0b11);

        assertEquals(
                MetalRenderStateShadow.BufferUpdate.OFFSET_ONLY,
                shadow.classifyBuffer(BUFFER_A, 64L, 4L, 0b11)
        );
        shadow.recordBuffer(BUFFER_A, 64L, 4L, 0b11);
        assertEquals(
                MetalRenderStateShadow.BufferUpdate.SKIP,
                shadow.classifyBuffer(BUFFER_A, 64L, 4L, 0b11)
        );
        assertEquals(
                MetalRenderStateShadow.BufferUpdate.FULL_BIND,
                shadow.classifyBuffer(BUFFER_B, 64L, 4L, 0b11)
        );
    }

    @Test
    void textureAndSamplerAreTrackedPerStage() {
        MetalRenderStateShadow shadow = new MetalRenderStateShadow(16);

        assertTrue(shadow.setTextureAndSampler(TEXTURE_A, SAMPLER_A, 1L, 0b11));
        assertFalse(shadow.setTextureAndSampler(TEXTURE_A, SAMPLER_A, 1L, 0b01));
        assertTrue(shadow.setTexture(TEXTURE_B, 1L, 0b01));
        assertFalse(shadow.setTextureAndSampler(TEXTURE_B, SAMPLER_A, 1L, 0b01));
        assertFalse(shadow.setTextureAndSampler(TEXTURE_A, SAMPLER_A, 1L, 0b10));
    }

    @Test
    void clearHelperInvalidationForcesCompleteRebind() {
        MetalRenderStateShadow shadow = new MetalRenderStateShadow(16);

        assertTrue(shadow.setPipeline(MemorySegment.ofAddress(0x6000L)));
        assertFalse(shadow.setPipeline(MemorySegment.ofAddress(0x6000L)));
        shadow.recordBuffer(BUFFER_A, 0L, 0L, 0b01);
        shadow.invalidateAll();

        assertTrue(shadow.setPipeline(MemorySegment.ofAddress(0x6000L)));
        assertEquals(
                MetalRenderStateShadow.BufferUpdate.FULL_BIND,
                shadow.classifyBuffer(BUFFER_A, 0L, 0L, 0b01)
        );
    }

    @Test
    void outOfRangeBindingsFailOpen() {
        MetalRenderStateShadow shadow = new MetalRenderStateShadow(8);

        assertEquals(
                MetalRenderStateShadow.BufferUpdate.FULL_BIND,
                shadow.classifyBuffer(BUFFER_A, 0L, 80L, 0b01)
        );
        assertTrue(shadow.setTexture(TEXTURE_A, 80L, 0b01));
        assertTrue(shadow.setBufferOffset(0L, 80L, 0b01));
    }
}
