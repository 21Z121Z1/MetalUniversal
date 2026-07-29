package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFrameGenerationFallbackStateTest {
    @Test
    void onlyTheExactUncommittedSuccessorCanConsumeTheFallback() {
        MetalCommandEncoder.QueuedFrameGenerationFallbackState state =
                new MetalCommandEncoder.QueuedFrameGenerationFallbackState();
        FrameStamp queuedStamp = new FrameStamp(12L, 4L);
        MetalCommandEncoder.QueuedFrameGenerationFallback fallback = fallback(queuedStamp, 0x1000L);

        state.queue(fallback);
        assertTrue(state.hasPending());
        assertNull(state.takeExact(new FrameStamp(11L, 4L)));
        assertNull(state.takeExact(new FrameStamp(12L, 5L)));
        assertTrue(state.hasPending(), "a different frame or epoch must not consume the current fallback");
        assertFalse(fallback.wasEncodedAsFallback());

        assertSame(fallback, state.takeExact(queuedStamp));
        assertTrue(fallback.wasEncodedAsFallback());
        assertFalse(state.hasPending());
        assertNull(state.takeExact(queuedStamp), "the exact fallback is one-shot");
    }

    @Test
    void commandBufferBoundaryClearsWithoutMarkingAReplacement() {
        MetalCommandEncoder.QueuedFrameGenerationFallbackState state =
                new MetalCommandEncoder.QueuedFrameGenerationFallbackState();
        MetalCommandEncoder.QueuedFrameGenerationFallback first = fallback(new FrameStamp(1L, 1L), 0x2000L);
        MetalCommandEncoder.QueuedFrameGenerationFallback second = fallback(new FrameStamp(2L, 1L), 0x3000L);

        state.queue(first);
        assertThrows(IllegalStateException.class, () -> state.queue(second));
        state.clear();
        assertFalse(first.wasEncodedAsFallback(),
                "normal commit/close clears state without claiming FrameGen was replaced");
        assertFalse(state.hasPending());

        state.queue(second);
        assertSame(second, state.takeExact(second.stamp()));
    }

    private static MetalCommandEncoder.QueuedFrameGenerationFallback fallback(
            final FrameStamp stamp,
            final long baseAddress
    ) {
        return new MetalCommandEncoder.QueuedFrameGenerationFallback(
                stamp,
                MemorySegment.ofAddress(baseAddress),
                MemorySegment.ofAddress(baseAddress + 0x100L),
                MemorySegment.ofAddress(baseAddress + 0x200L)
        );
    }
}
