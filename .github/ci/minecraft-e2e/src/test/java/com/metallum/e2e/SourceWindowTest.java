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
}
