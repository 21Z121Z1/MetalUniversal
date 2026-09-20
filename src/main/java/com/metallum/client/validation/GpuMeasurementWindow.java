package com.metallum.client.validation;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import java.util.List;
import java.util.TreeMap;

/** Validates actual submission/frame membership before calculating frame GPU service time. */
final class GpuMeasurementWindow {
    private GpuMeasurementWindow() { }

    static Summary summarize(List<MetalGpuTimingRecorder.Sample> samples, long windowId,
                             long firstFrame, long endFrame, long firstSubmit, long endSubmit) {
        if (windowId <= 0 || firstFrame < 0 || endFrame <= firstFrame
                || firstSubmit < 0 || endSubmit <= firstSubmit
                || samples.size() != endSubmit - firstSubmit) {
            return new Summary(false, List.of());
        }
        var frames = new TreeMap<Long, Double>();
        long expectedSubmit = firstSubmit;
        for (var sample : samples.stream()
                .sorted(java.util.Comparator.comparingLong(MetalGpuTimingRecorder.Sample::submitIndex)).toList()) {
            if (sample.windowId() != windowId || sample.submitIndex() != expectedSubmit++
                    || sample.frameId() < firstFrame || sample.frameId() >= endFrame
                    || !Double.isFinite(sample.milliseconds()) || sample.milliseconds() <= 0.0) {
                return new Summary(false, List.of());
            }
            frames.merge(sample.frameId(), sample.milliseconds(), Double::sum);
        }
        if (frames.size() != endFrame - firstFrame
                || frames.values().stream().anyMatch(value -> !Double.isFinite(value))) {
            return new Summary(false, List.of());
        }
        return new Summary(true, List.copyOf(frames.values()));
    }

    record Summary(boolean complete, List<Double> frameMilliseconds) { }
}
