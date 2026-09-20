package com.metallum.client.validation;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import com.metallum.client.metal.render.NativeEncoderCounts;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

/** Validates encoder lifecycle counts against the actual measured submission identities. */
final class EncoderMeasurementWindow {
    private EncoderMeasurementWindow() { }

    static Summary summarize(NativeEncoderCounts.Snapshot ledger,
            List<MetalGpuTimingRecorder.Sample> submissions, long windowId,
            long firstFrame, long endFrame, long firstSubmit, long endSubmit, boolean supportedScope) {
        if (!supportedScope) return incomplete("opaque or separate-queue rendering is outside ledger scope");
        if (ledger.schemaVersion() != 1 || !ledger.enabled()
                || !NativeEncoderCounts.SCOPE.equals(ledger.scope())
                || ledger.capacityRows() < 1 || ledger.capacityRows() > NativeEncoderCounts.MAX_ROWS
                || ledger.rowCount() != ledger.rows().size() || ledger.rowCount() > ledger.capacityRows()
                || ledger.droppedRows() != 0 || ledger.invalidEvents() != 0
                || ledger.activeCommandBuffers() != 0 || ledger.activeEncoders() != 0) {
            return incomplete("disabled, truncated, invalid or unfinished native encoder ledger");
        }
        if (!GpuMeasurementWindow.summarize(submissions, windowId, firstFrame, endFrame,
                firstSubmit, endSubmit).complete() || ledger.rowCount() != endSubmit - firstSubmit) {
            return incomplete("encoder ledger does not cover the measured GPU submissions");
        }
        var gpuFrames = new HashMap<Long, Long>();
        for (var sample : submissions) gpuFrames.put(sample.submitIndex(), sample.frameId());
        var frames = new TreeMap<Long, Long>();
        long expectedSubmit = firstSubmit;
        long render = 0, blit = 0, compute = 0;
        try {
            for (var row : ledger.rows().stream().sorted(
                    Comparator.comparingLong(NativeEncoderCounts.Sample::submitIndex)).toList()) {
                if (row.windowId() != windowId || row.submitIndex() != expectedSubmit++
                        || row.frameId() < firstFrame || row.frameId() >= endFrame
                        || !Long.valueOf(row.frameId()).equals(gpuFrames.get(row.submitIndex()))
                        || (row.backend() != 3 && row.backend() != 4)
                        || row.attempted() < 0 || row.created() < 0 || row.ended() < 0
                        || row.renderCreated() < 0 || row.blitCreated() < 0 || row.computeCreated() < 0
                        || row.createFailures() != 0 || row.unsupportedEncodes() != 0 || row.invalidEvents() != 0
                        || row.attempted() != row.created() || row.created() != row.ended()
                        || row.created() != Math.addExact(Math.addExact(row.renderCreated(), row.blitCreated()), row.computeCreated())) {
                    return incomplete("encoder submission identity, kind or lifecycle mismatch");
                }
                frames.merge(row.frameId(), row.created(), Math::addExact);
                render = Math.addExact(render, row.renderCreated());
                blit = Math.addExact(blit, row.blitCreated());
                compute = Math.addExact(compute, row.computeCreated());
            }
            Math.addExact(Math.addExact(render, blit), compute);
        } catch (ArithmeticException overflow) {
            return incomplete("encoder count overflow");
        }
        if (frames.size() != endFrame - firstFrame) return incomplete("missing encoder frame");
        var orderedCounts = frames.values().stream().sorted().toList();
        double p50 = orderedCounts.get((orderedCounts.size() - 1) / 2);
        return new Summary(true, "complete-main-queue-native-encoders", List.copyOf(frames.values()),
                render, blit, compute, p50);
    }

    private static Summary incomplete(String reason) {
        return new Summary(false, reason, List.of(), 0, 0, 0, 0.0);
    }

    record Summary(boolean complete, String status, List<Long> frameCounts,
                   long renderTotal, long blitTotal, long computeTotal, double p50PerFrame) { }
}
