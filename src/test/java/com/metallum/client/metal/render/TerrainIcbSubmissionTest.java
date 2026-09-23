package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class TerrainIcbSubmissionTest {
    @Test void preEncodeRejectionAllowsTheOneFallback() {
        assertFalse(TerrainIcbOwner.submitDraw(() -> false));
        assertTrue(TerrainIcbOwner.submitDraw(() -> true));
    }

    @Test void exceptionAfterNativeSideEffectMustNotBecomeAFallbackReceipt() {
        AtomicInteger draws = new AtomicInteger();
        assertThrows(TerrainIcbOwner.SubmissionUncertain.class, () -> TerrainIcbOwner.submitDraw(() -> {
            draws.incrementAndGet();
            throw new IllegalStateException("crossing failed after encode");
        }));
        assertEquals(1, draws.get());
    }
}
