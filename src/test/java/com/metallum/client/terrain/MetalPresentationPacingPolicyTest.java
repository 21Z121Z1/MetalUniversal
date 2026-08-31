package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalPresentationPacingPolicyTest {
    @Test
    void featureIsOptInAndKeepsQueueDepthBounded() {
        PresentationPacingSnapshot snapshot = PresentationPacingSnapshot.capture(
                4L, 120, 8_000_000L, 7_000_000L,
                8_333_333L, 100_000L, 3L
        );
        MetalPresentationPacingPolicy.Decision disabled =
                MetalPresentationPacingPolicy.decide(snapshot, 3, false);
        assertFalse(disabled.enabled());
        assertEquals("feature-disabled", disabled.reason());

        MetalPresentationPacingPolicy.Decision enabled =
                MetalPresentationPacingPolicy.decide(snapshot, 3, true);
        assertTrue(enabled.enabled());
        assertEquals(120, enabled.refreshRateHz());
        assertEquals(8_333_333L, enabled.targetIntervalNanos());
        assertEquals(3, enabled.framesInFlight());
    }

    @Test
    void unavailableDisplayOrInvalidQueueFailsClosed() {
        PresentationPacingSnapshot unavailable = PresentationPacingSnapshot.capture(
                1L, -1, -1L, -1L
        );
        MetalPresentationPacingPolicy.Decision noDisplay =
                MetalPresentationPacingPolicy.decide(unavailable, 2, true);
        assertFalse(noDisplay.enabled());
        assertEquals("refresh-rate-unavailable", noDisplay.reason());

        PresentationPacingSnapshot valid = PresentationPacingSnapshot.capture(1L, 60, -1L, -1L);
        MetalPresentationPacingPolicy.Decision badQueue =
                MetalPresentationPacingPolicy.decide(valid, 4, true);
        assertFalse(badQueue.enabled());
        assertEquals("invalid-queue-depth", badQueue.reason());
        assertEquals(3, badQueue.framesInFlight());
    }
}
