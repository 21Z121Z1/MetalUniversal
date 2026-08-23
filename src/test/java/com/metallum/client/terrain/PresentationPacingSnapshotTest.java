package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresentationPacingSnapshotTest {
    @Test
    void refreshRatesProduceRoundedTargetIntervals() {
        assertEquals(16_666_667L, PresentationPacingSnapshot.capture(1L, 60, -1L, -1L)
                .targetPresentInterval().value());
        assertEquals(11_111_111L, PresentationPacingSnapshot.capture(1L, 90, -1L, -1L)
                .targetPresentInterval().value());
        assertEquals(8_333_333L, PresentationPacingSnapshot.capture(1L, 120, -1L, -1L)
                .targetPresentInterval().value());
    }

    @Test
    void measuredAndFallbackTargetValuesRemainDistinct() {
        PresentationPacingSnapshot measured = PresentationPacingSnapshot.capture(3L, 90, 4_000_000L, 5_000_000L);
        assertTrue(measured.targetPresentInterval().available());
        assertTrue(measured.targetPresentInterval().measured());
        assertEquals(PresentationPacingSnapshot.REFRESH_RATE_PROVENANCE,
                measured.targetPresentInterval().provenance());
        assertNull(measured.targetPresentInterval().fallbackReason());

        PresentationPacingSnapshot fallback = PresentationPacingSnapshot.capture(3L, -1, 4_000_000L, 5_000_000L);
        assertTrue(fallback.targetPresentInterval().available());
        assertFalse(fallback.targetPresentInterval().measured());
        assertEquals(TerrainSchedulingController.TARGET_FRAME_NANOS,
                fallback.targetPresentInterval().value());
        assertEquals(PresentationPacingSnapshot.TARGET_FALLBACK_PROVENANCE,
                fallback.targetPresentInterval().provenance());
        assertEquals(PresentationPacingSnapshot.TARGET_FALLBACK_REASON,
                fallback.targetPresentInterval().fallbackReason());
        assertEquals(PresentationPacingSnapshot.TARGET_FALLBACK_REASON, fallback.fallbackReason());
    }

    @Test
    void missingNativeFieldsAreUnavailableWithReasons() {
        PresentationPacingSnapshot snapshot = PresentationPacingSnapshot.capture(4L, 60, -1L, -1L);
        assertFalse(snapshot.measuredPresentInterval().available());
        assertFalse(snapshot.measuredPresentInterval().measured());
        assertEquals(PresentationPacingSnapshot.PRESENT_INTERVAL_UNAVAILABLE_REASON,
                snapshot.measuredPresentInterval().fallbackReason());
        assertFalse(snapshot.drawableWait().available());
        assertEquals(PresentationPacingSnapshot.DRAWABLE_WAIT_UNAVAILABLE_REASON,
                snapshot.drawableWait().fallbackReason());
        assertFalse(snapshot.framesInFlight().available());
        assertEquals(PresentationPacingSnapshot.FRAMES_IN_FLIGHT_UNAVAILABLE_REASON,
                snapshot.framesInFlight().fallbackReason());
        assertEquals(PresentationPacingSnapshot.UNAVAILABLE_VALUE, snapshot.cpuFrameTime().value());
        assertEquals(PresentationPacingSnapshot.UNAVAILABLE_VALUE, snapshot.gpuFrameTime().value());
    }

    @Test
    void optionalMeasuredSourcesCanBeCarriedWithoutChangingTarget() {
        PresentationPacingSnapshot snapshot = PresentationPacingSnapshot.capture(
                5L, 120, 2_000_000L, 3_000_000L, 8_333_334L, 700_000L, 2L
        );
        assertEquals(8_333_333L, snapshot.targetPresentInterval().value());
        assertTrue(snapshot.measuredPresentInterval().available());
        assertTrue(snapshot.measuredPresentInterval().measured());
        assertEquals(8_333_334L, snapshot.measuredPresentInterval().value());
        assertEquals(700_000L, snapshot.drawableWait().value());
        assertEquals(2L, snapshot.framesInFlight().value());
    }
}
