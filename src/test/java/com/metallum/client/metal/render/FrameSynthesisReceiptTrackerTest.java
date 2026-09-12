package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.NOT_PRESENT;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.REACTIVE_ONLY;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.REAL_MOTION;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.UNSUPPORTED;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.MODDED_RENDERERS;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.TRANSPARENCY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FrameSynthesisReceiptTrackerTest {
    @Test
    void activityUnknownAndAbsentRemainDistinct() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp stamp =
                new FrameSynthesisContract.FrameStamp(11L, 4L);
        tracker.beginFrame(stamp);
        tracker.observe(TRANSPARENCY, 3);
        tracker.observeUnsupported(MODDED_RENDERERS, 2, "diagnostic-only custom renderer");

        FrameSynthesisContract.ProducerCoverageSet coverage =
                tracker.finalizeFrame(stamp).coverage();
        assertReceipt(coverage, TRANSPARENCY, REACTIVE_ONLY, 3);
        assertReceipt(coverage, MODDED_RENDERERS, UNSUPPORTED, 2);
        assertReceipt(coverage, BLOCK_ENTITIES, NOT_PRESENT, 0);
    }

    @Test
    void exactCandidateMustBeEncodedForRealMotion() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp stamp =
                new FrameSynthesisContract.FrameStamp(12L, 4L);
        tracker.beginFrame(stamp);
        tracker.observe(BLOCK_ENTITIES, 1);
        tracker.markExactCandidate(BLOCK_ENTITIES);
        assertReceipt(
                tracker.finalizeFrame(stamp).coverage(),
                BLOCK_ENTITIES,
                REACTIVE_ONLY,
                1
        );

        tracker.discardFrame();
        tracker.beginFrame(new FrameSynthesisContract.FrameStamp(13L, 4L));
        tracker.observe(BLOCK_ENTITIES, 1);
        tracker.markExactCandidate(BLOCK_ENTITIES);
        tracker.recordMotionEncoded(BLOCK_ENTITIES);
        assertReceipt(
                tracker.finalizeFrame(new FrameSynthesisContract.FrameStamp(13L, 4L)).coverage(),
                BLOCK_ENTITIES,
                REAL_MOTION,
                1
        );
    }

    @Test
    void candidateEventCannotBeMisclassifiedAsAbsent() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp stamp =
                new FrameSynthesisContract.FrameStamp(21L, 4L);
        tracker.beginFrame(stamp);
        tracker.markExactCandidate(BLOCK_ENTITIES, 401L, 1L);

        assertReceipt(
                tracker.finalizeFrame(stamp).coverage(),
                BLOCK_ENTITIES,
                REACTIVE_ONLY,
                1
        );
    }

    @Test
    void reactiveWeatherPreventsExactParticleBatchFromOverclaimingCoverage() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp stamp =
                new FrameSynthesisContract.FrameStamp(20L, 4L);
        tracker.beginFrame(stamp);
        tracker.observe(PARTICLES_WEATHER, 4);
        tracker.markExactCandidate(PARTICLES_WEATHER, 301L, -1L);
        tracker.recordMotionEncoded(PARTICLES_WEATHER, 301L, -1L);
        tracker.observeReactive(PARTICLES_WEATHER, 2);

        assertReceipt(
                tracker.finalizeFrame(stamp).coverage(),
                PARTICLES_WEATHER,
                REACTIVE_ONLY,
                6
        );
    }

    @Test
    void oneOwnerCannotSatisfyAnotherOwnersCandidate() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp stamp =
                new FrameSynthesisContract.FrameStamp(19L, 4L);
        tracker.beginFrame(stamp);
        tracker.observe(BLOCK_ENTITIES, 2);
        tracker.markExactCandidate(BLOCK_ENTITIES, 101L, 1L);
        tracker.markExactCandidate(BLOCK_ENTITIES, 102L, 1L);
        tracker.recordMotionEncoded(BLOCK_ENTITIES, 101L, 1L);
        tracker.recordMotionEncoded(BLOCK_ENTITIES, 101L, 1L);
        assertReceipt(
                tracker.finalizeFrame(stamp).coverage(),
                BLOCK_ENTITIES,
                REACTIVE_ONLY,
                2
        );
    }

    @Test
    void finalizationIsBoundToStampAndHistoryDiscontinuity() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp stamp =
                new FrameSynthesisContract.FrameStamp(14L, 8L);
        tracker.beginFrame(stamp);
        assertThrows(
                IllegalStateException.class,
                () -> tracker.finalizeFrame(new FrameSynthesisContract.FrameStamp(14L, 9L))
        );
        tracker.invalidateForHistoryDiscontinuity();
        assertThrows(IllegalStateException.class, () -> tracker.finalizeFrame(stamp));
        tracker.discardFrame();

        FrameSynthesisContract.FrameStamp next =
                new FrameSynthesisContract.FrameStamp(15L, 9L);
        tracker.beginFrame(next);
        FrameSynthesisReceiptTracker.Finalized finalized = tracker.finalizeFrame(next);
        assertEquals(next, finalized.stamp());
        assertEquals(finalized, tracker.finalizeFrame(next));
        tracker.commitSubmittedFrame();
        assertThrows(
                IllegalStateException.class,
                () -> tracker.observe(TRANSPARENCY, 1)
        );
        tracker.beginFrame(new FrameSynthesisContract.FrameStamp(16L, 9L));
    }

    @Test
    void discardedFrameDoesNotCarryObservationsForward() {
        FrameSynthesisReceiptTracker tracker = new FrameSynthesisReceiptTracker();
        FrameSynthesisContract.FrameStamp first =
                new FrameSynthesisContract.FrameStamp(17L, 2L);
        tracker.beginFrame(first);
        tracker.observe(TRANSPARENCY, 4);
        tracker.discardFrame();

        FrameSynthesisContract.FrameStamp second =
                new FrameSynthesisContract.FrameStamp(18L, 2L);
        tracker.beginFrame(second);
        assertReceipt(tracker.finalizeFrame(second).coverage(), TRANSPARENCY, NOT_PRESENT, 0);
    }

    private static void assertReceipt(
            final FrameSynthesisContract.ProducerCoverageSet coverage,
            final FrameSynthesisContract.ProducerDomain domain,
            final FrameSynthesisContract.ProducerCoverage expectedCoverage,
            final int expectedSamples
    ) {
        FrameSynthesisContract.ProducerReceipt receipt = coverage.receipts().stream()
                .filter(candidate -> candidate.domain() == domain)
                .findFirst()
                .orElseThrow();
        assertEquals(expectedCoverage, receipt.coverage());
        assertEquals(expectedSamples, receipt.samples());
    }
}
