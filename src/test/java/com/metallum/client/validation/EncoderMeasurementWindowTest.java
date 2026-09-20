package com.metallum.client.validation;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import com.metallum.client.metal.render.NativeEncoderCounts;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EncoderMeasurementWindowTest {
    private static final List<MetalGpuTimingRecorder.Sample> GPU = List.of(
            new MetalGpuTimingRecorder.Sample(10, 1, 40, 1, 1.001),
            new MetalGpuTimingRecorder.Sample(11, 1, 40, 1, 1.001),
            new MetalGpuTimingRecorder.Sample(12, 1, 41, 1, 1.001));

    private static NativeEncoderCounts.Sample row(long submit, long frame, long backend,
                                                 long render, long blit, long compute) {
        long total = render + blit + compute;
        return new NativeEncoderCounts.Sample(1, frame, submit, backend,
                total, total, total, render, blit, compute, 0, 0, 0);
    }

    private static NativeEncoderCounts.Snapshot ledger(List<NativeEncoderCounts.Sample> rows) {
        return new NativeEncoderCounts.Snapshot(1, true, 16, 0, 0, 0, 0,
                rows.size(), NativeEncoderCounts.SCOPE, rows);
    }

    private static EncoderMeasurementWindow.Summary summarize(NativeEncoderCounts.Snapshot snapshot) {
        return EncoderMeasurementWindow.summarize(snapshot, GPU, 1, 40, 42, 10, 13, true);
    }

    @Test
    void countsPhysicalEncodersAcrossMultipleSubmissionsAndBothBackendsWithoutTimestamps() {
        var result = summarize(ledger(List.of(
                row(12, 41, 4, 6, 0, 4), row(10, 40, 3, 1, 1, 1), row(11, 40, 4, 1, 0, 1))));
        assertTrue(result.complete());
        assertEquals(List.of(5L, 10L), result.frameCounts());
        assertEquals(5, result.p50PerFrame(), "nearest-rank median must not become the mean 7.5");
        assertEquals(8, result.renderTotal());
        assertEquals(1, result.blitTotal());
        assertEquals(6, result.computeTotal());
    }

    @Test
    void rejectsMatchingCountsWithWrongOrDuplicateSubmissionIdentity() {
        assertFalse(summarize(ledger(List.of(row(10, 40, 3, 1, 0, 0),
                row(10, 40, 3, 1, 0, 0), row(12, 41, 4, 1, 0, 0)))).complete());
        assertFalse(summarize(ledger(List.of(row(10, 40, 3, 1, 0, 0),
                row(11, 41, 3, 1, 0, 0), row(12, 41, 4, 1, 0, 0)))).complete());
    }

    @Test
    void rejectsUnendedOrOpaqueEncodersAndTruncatedLedger() {
        var first = row(10, 40, 3, 1, 0, 0);
        var second = row(11, 40, 3, 0, 1, 0);
        var unfinished = new NativeEncoderCounts.Sample(1, 41, 12, 4, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        assertFalse(summarize(ledger(List.of(first, second, unfinished))).complete());
        var opaque = new NativeEncoderCounts.Sample(1, 41, 12, 4, 1, 1, 1, 1, 0, 0, 0, 1, 0);
        assertFalse(summarize(ledger(List.of(first, second, opaque))).complete());
        var full = ledger(List.of(first, second, row(12, 41, 4, 1, 0, 0)));
        var overflow = new NativeEncoderCounts.Snapshot(1, true, 16, 1, 0, 0, 0, 3,
                NativeEncoderCounts.SCOPE, full.rows());
        assertFalse(summarize(overflow).complete());
        assertFalse(EncoderMeasurementWindow.summarize(full, GPU, 1, 40, 42, 10, 13, false).complete());
    }

    @Test
    void validEmptyEncoderSubmissionDoesNotDisappearFromCoverage() {
        var result = summarize(ledger(List.of(row(10, 40, 3, 0, 0, 0),
                row(11, 40, 3, 1, 0, 0), row(12, 41, 4, 0, 0, 0))));
        assertTrue(result.complete());
        assertEquals(List.of(1L, 0L), result.frameCounts());
    }

    @Test
    void decodesFixedWidthAbiWithoutGuessingMissingFields() {
        var decoded = NativeEncoderCounts.decode(new long[]{1, 1, 16, 0, 0, 0, 0, 1},
                new long[]{9, 42, 88, 4, 6, 6, 6, 1, 2, 3, 0, 0, 0});
        assertEquals(9, decoded.rows().getFirst().windowId());
        assertEquals(42, decoded.rows().getFirst().frameId());
        assertEquals(88, decoded.rows().getFirst().submitIndex());
        assertEquals(4, decoded.rows().getFirst().backend());
        assertEquals(3, decoded.rows().getFirst().computeCreated());
        assertThrows(IllegalArgumentException.class, () -> NativeEncoderCounts.decode(
                new long[]{1, 1, 16, 0, 0, 0, 0, 1}, new long[12]));
        assertThrows(IllegalArgumentException.class, () -> NativeEncoderCounts.decode(
                new long[]{1, 2, 16, 0, 0, 0, 0, 0}, new long[0]));
    }
}
