package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java bounds and window-isolation checks for completed GPU samples. */
final class MetalGpuTimingRecorderRetentionTest {
    private static final long WINDOW = 73L;
    private static final int FORMAL_SAMPLES = 28_800;
    private static final int ORDINARY_CAPACITY = 16_384;
    private static final int FORMAL_CAPACITY = NativeEncoderCounts.MAX_ROWS;

    @BeforeEach
    void clearRecorderLists() throws Exception {
        assertTrue(Boolean.getBoolean("metallum.validation.gpuTiming"),
                "retention test requires the enabled timing JVM");
        clearStaticLists();
    }

    @AfterEach
    void restoreRecorderLists() throws Exception {
        clearStaticLists();
    }

    @Test
    void ordinaryRetentionDoesNotTrimAFormalWindow() {
        for (int index = 0; index < FORMAL_SAMPLES; index++) {
            record(WINDOW, 1_000_000L + index, index);
        }
        for (int index = 0; index < FORMAL_SAMPLES; index++) {
            record(0L, index, index);
        }

        List<MetalGpuTimingRecorder.Sample> formal = MetalGpuTimingRecorder.snapshot(WINDOW);
        assertEquals(FORMAL_SAMPLES, formal.size());
        assertEquals(1_000_000L, formal.getFirst().submitIndex());
        assertEquals(1_000_000L + FORMAL_SAMPLES - 1, formal.getLast().submitIndex());

        List<MetalGpuTimingRecorder.Sample> all = MetalGpuTimingRecorder.snapshot();
        assertEquals(FORMAL_SAMPLES + ORDINARY_CAPACITY, all.size());
        assertEquals(ORDINARY_CAPACITY, all.stream()
                .filter(sample -> sample.windowId() == 0L)
                .count());
    }

    @Test
    void formalWindowRetentionIsBoundedAtNativeEncoderCapacity() {
        int total = FORMAL_CAPACITY + 1_024;
        for (int index = 0; index < total; index++) {
            record(WINDOW, index, index);
        }

        List<MetalGpuTimingRecorder.Sample> formal = MetalGpuTimingRecorder.snapshot(WINDOW);
        assertEquals(FORMAL_CAPACITY, formal.size());
        assertEquals(1_024L, formal.getFirst().submitIndex());
        assertEquals(total - 1L, formal.getLast().submitIndex());
    }

    private static void record(final long windowId, final long submitIndex, final long frameId) {
        double start = submitIndex + 1.0;
        MetalGpuTimingRecorder.record(submitIndex, windowId, frameId, start, start + 0.25);
    }

    @SuppressWarnings("unchecked")
    private static void clearStaticLists() throws Exception {
        for (String fieldName : List.of("SAMPLES", "MEASUREMENT_SAMPLES", "CPU_PASS_SAMPLES")) {
            Field field = MetalGpuTimingRecorder.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((List<MetalGpuTimingRecorder.Sample>) field.get(null)).clear();
        }
    }
}
