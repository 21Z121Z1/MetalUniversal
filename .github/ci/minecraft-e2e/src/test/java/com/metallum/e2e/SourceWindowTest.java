package com.metallum.e2e;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceWindowTest {
    @Test void excludesWarmupEndBoundaryAndCrossBoundaryIntervals() {
        var result = SourceWindow.summarize(new long[]{1, 100, 110, 140, 200, 201}, 6, 100, 200);
        assertEquals(3, result.get("count").getAsInt());
        assertEquals(2, result.get("intervalCount").getAsInt());
        assertEquals(0.00001, result.get("intervalP50Ms").getAsDouble());
        assertEquals(0.00003, result.get("intervalP99Ms").getAsDouble());
    }
    @Test void incompleteIntervalAndInvalidClockCannotInventDistribution() {
        assertTrue(SourceWindow.summarize(new long[]{100}, 1, 100, 200).get("intervalP99Ms").isJsonNull());
        assertThrows(IllegalStateException.class, () -> SourceWindow.summarize(new long[]{100, 100}, 2, 100, 200));
        assertThrows(IllegalArgumentException.class, () -> SourceWindow.summarize(new long[0], 0, 100, 100));
    }
    @Test void streamingHistogramHasFixedMemoryAndExplicitQuantileResolution() {
        var collector = new SourceWindow.Accumulator(100, 200_000_000_000L);
        collector.record(99);
        for (int i = 0; i < 10_001; i++) collector.record(100 + i * 16_650_000L);
        collector.record(200_000_000_000L);
        var result = collector.finish();
        assertEquals(10_001, result.get("count").getAsLong());
        assertEquals(16.7, result.get("intervalP99UpperBoundMs").getAsDouble());
        assertEquals(16.7, result.get("intervalP999UpperBoundMs").getAsDouble());
        assertEquals(16_650_000, result.get("worstIntervalNs").getAsLong());
        assertTrue(result.get("complete").getAsBoolean());
    }
    @Test void histogramDoesNotTurnMissingTailOverflowOrInvalidClockIntoPreciseQuantiles() {
        var collector = new SourceWindow.Accumulator(100, 10_000_000_000L);
        collector.record(100); collector.record(100); collector.record(2_000_000_000L);
        var result = collector.finish();
        assertEquals(1, result.get("invalidTimestamps").getAsLong());
        assertEquals(1, result.get("overflowIntervals").getAsLong());
        assertTrue(result.get("intervalP99UpperBoundMs").isJsonNull());
        assertTrue(result.get("intervalP999UpperBoundMs").isJsonNull());
        assertFalse(result.get("complete").getAsBoolean());
    }
    @Test void aClosedHalfOpenWindowNeverAcceptsLateOrOutOfOrderSamples() {
        var collector = new SourceWindow.Accumulator(100, 1_000_000);
        collector.record(100);
        collector.record(200_100);
        collector.record(1_000_000);
        var closed = collector.finish();
        collector.record(400_100);
        collector.record(1_000_001);
        assertEquals(closed, collector.finish());
        closed.addProperty("count", 999);
        assertEquals(2, collector.finish().get("count").getAsInt());
    }
}
