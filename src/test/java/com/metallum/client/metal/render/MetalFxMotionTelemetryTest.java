package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxMotionTelemetryTest {
    @Test
    void sourceFramesAreDeduplicatedPerRenderFrameAndStagesStaySeparate() {
        MetalFxMotionTelemetry.Accumulator telemetry =
                MetalFxMotionTelemetry.accumulatorForTests(true);

        telemetry.beginFrame();
        telemetry.recordSourceFrame(41L, true, 0, null);
        telemetry.recordSourceFrame(41L, false, MetalFxMotionEligibility.PARTICLE, null);
        telemetry.recordRequested(41L);
        telemetry.recordEncoded(41L);
        telemetry.recordSubmitted(41L);
        telemetry.recordCompleted(41L, true);

        telemetry.beginFrame();
        telemetry.recordSourceFrame(
                42L,
                false,
                MetalFxMotionEligibility.PARTICLE | MetalFxMotionEligibility.MISSING_HISTORY,
                null
        );

        MetalFxMotionTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertEquals(41L, snapshot.samplingWindow().firstFrameId());
        assertEquals(42L, snapshot.samplingWindow().lastFrameId());
        assertEquals(2L, snapshot.samplingWindow().frameCount());
        assertEquals(2L, snapshot.sourceFrames());
        assertEquals(1L, snapshot.eligibleSourceFrames());
        assertEquals(1L, snapshot.rejectedSourceFrames());
        assertEquals(1L, snapshot.rejectionByReason().get("particle"));
        assertEquals(1L, snapshot.rejectionByReason().get("missingHistory"));
        assertEquals(1L, snapshot.requested().count());
        assertEquals(1L, snapshot.encoded().count());
        assertEquals(1L, snapshot.submitted().count());
        assertEquals(1L, snapshot.completed().count());
        assertTrue(snapshot.requested().available());
        assertTrue(snapshot.completed().available());
        assertFalse(snapshot.presented().available());
    }

    @Test
    void byteAndCostCountersAreAggregatedWithoutEnablingGpuClaims() {
        MetalFxMotionTelemetry.Accumulator telemetry =
                MetalFxMotionTelemetry.accumulatorForTests(true);
        telemetry.recordHistoryCapture(12L, 144L, 144L, 80L);
        telemetry.recordHistoryCapture(3L, 36L, 36L, 20L);
        telemetry.recordGpuUpload(512L);
        telemetry.recordMotionReplayDraw();

        MetalFxMotionTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertEquals(15L, snapshot.historicalVertexCount());
        assertEquals(180L, snapshot.cpuReadBytes());
        assertEquals(180L, snapshot.cpuCopyBytes());
        assertEquals(512L, snapshot.gpuUploadBytes());
        assertEquals(1L, snapshot.motionReplayDraws());
        assertEquals(100L, snapshot.cpuMotionCost().nanoseconds());
        assertTrue(snapshot.cpuMotionCost().available());
        assertFalse(snapshot.gpuMotionCost().available());
    }

    @Test
    void exactCoverageReasonsAreBoundedAndStructured() {
        MetalFxMotionTelemetry.Accumulator telemetry =
                MetalFxMotionTelemetry.accumulatorForTests(true);
        telemetry.beginFrame();
        telemetry.recordSourceFrame(9L, false, 0, "unsupported-exact-pipeline:core/entity?unsafe");

        MetalFxMotionTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertEquals(1L, snapshot.rejectionByReason().get("exactCoverage.unsupported_exact_pipeline_core_entity_unsafe"));
    }

    @Test
    void disabledTelemetryProducesUnavailableZeroSnapshot() {
        MetalFxMotionTelemetry.Accumulator telemetry =
                MetalFxMotionTelemetry.accumulatorForTests(false);
        telemetry.beginFrame();
        telemetry.recordSourceFrame(7L, true, 0, null);

        MetalFxMotionTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertFalse(snapshot.enabled());
        assertEquals(0L, snapshot.sourceFrames());
        assertFalse(snapshot.requested().available());
        assertFalse(snapshot.cpuMotionCost().available());
    }
}
