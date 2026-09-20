package com.metallum.client.validation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class FrameMeasurementWindowTest {
    @Test
    void excludesWarmupAndBoundaryDrainAndClosesLastCpuBeforeReport() {
        var window = new FrameMeasurementWindow(10_000_000, 20_000_000, 0, 1);
        assertEquals(FrameMeasurementWindow.Step.WARMUP, window.beforeFrame(0, 1));
        window.afterFrame(0, 9_000_000);
        assertEquals(FrameMeasurementWindow.Step.START, window.beforeFrame(1, 10_000_001));
        window.reanchorStart(20_000_001);
        window.afterFrame(1, 23_000_001);
        assertEquals(FrameMeasurementWindow.Step.MEASURE, window.beforeFrame(2, 30_000_001));
        window.afterFrame(2, 34_000_001);
        assertEquals(FrameMeasurementWindow.Step.COMPLETE, window.beforeFrame(3, 40_000_001));
        window.afterFrame(3, 50_000_001);
        assertEquals(List.of(10.0, 10.0), window.intervals());
        assertEquals(List.of(3.0, 4.0), window.cpuDurations());
        assertEquals(1, window.firstFrame());
        assertEquals(3, window.endFrame());
    }

    @Test
    void frameCountProtocolCollectsExactlyTheRequestedCompleteFrames() {
        var window = new FrameMeasurementWindow(0, 0, 1, 2);
        assertEquals(FrameMeasurementWindow.Step.WARMUP, window.beforeFrame(0, 1));
        assertEquals(FrameMeasurementWindow.Step.START, window.beforeFrame(1, 2));
        window.afterFrame(1, 3);
        assertEquals(FrameMeasurementWindow.Step.MEASURE, window.beforeFrame(2, 4));
        window.afterFrame(2, 5);
        assertEquals(FrameMeasurementWindow.Step.COMPLETE, window.beforeFrame(3, 6));
        assertEquals(2, window.intervals().size());
        assertEquals(2, window.cpuDurations().size());
    }

    @Test
    void rejectsMissingCpuSampleAndSkippedFrameInsteadOfCroppingArrays() {
        var window = new FrameMeasurementWindow(0, 0, 0, 2);
        window.beforeFrame(0, 1);
        assertThrows(IllegalStateException.class, () -> window.beforeFrame(1, 3));
        window.afterFrame(0, 2);
        assertThrows(IllegalStateException.class, () -> window.beforeFrame(2, 3));
    }
}
