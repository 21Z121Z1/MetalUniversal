package com.metallum.client.validation;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class GpuMeasurementWindowTest {
    private static MetalGpuTimingRecorder.Sample sample(long submit, long frame) {
        return new MetalGpuTimingRecorder.Sample(submit, 1, frame, 1.0, 1.001);
    }

    @Test
    void aggregatesMultipleSubmissionsPerFrameByIdentityDespiteCompletionOrder() {
        var result = GpuMeasurementWindow.summarize(
                List.of(sample(12, 41), sample(10, 40), sample(11, 40)), 1, 40, 42, 10, 13);
        assertTrue(result.complete());
        assertEquals(2.0, result.frameMilliseconds().get(0), 1e-9);
        assertEquals(1.0, result.frameMilliseconds().get(1), 1e-9);
    }

    @Test
    void rejectsMissingAndDuplicatedSubmissionsRatherThanMatchingCounts() {
        assertFalse(GpuMeasurementWindow.summarize(
                List.of(sample(10, 40), sample(12, 41)), 1, 40, 42, 10, 13).complete());
        assertFalse(GpuMeasurementWindow.summarize(
                List.of(sample(10, 40), sample(10, 41)), 1, 40, 42, 10, 12).complete());
    }

    @Test
    void rejectsWarmupOrMissingFrameEvenWithEqualSampleCount() {
        assertFalse(GpuMeasurementWindow.summarize(
                List.of(sample(10, 39), sample(11, 41)), 1, 40, 42, 10, 12).complete());
        assertFalse(GpuMeasurementWindow.summarize(
                List.of(sample(10, 40), sample(11, 40)), 1, 40, 42, 10, 12).complete());
        assertFalse(GpuMeasurementWindow.summarize(
                List.of(sample(10, 40), sample(11, 41)), 2, 40, 42, 10, 12).complete());
    }
}
