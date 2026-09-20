package com.metallum.client.validation;

import com.metallum.client.metal.render.NativeProcessMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded, explicitly sampled RSS observations of completed measurement frames. */
final class ProcessMemoryMeasurement {
    static final int MAX_SAMPLES = 65_536;
    static final String SOURCE = "mach_task_info(TASK_VM_INFO.resident_size)";
    private final int capacity;
    private final Supplier<NativeProcessMemory.Sample> probe;
    private final LongSupplier clock;
    private final List<Sample> samples = new ArrayList<>();
    private boolean active;
    private boolean finished;
    private boolean framePending;
    private long windowId;
    private long firstFrame;
    private long nextFrame;
    private long endFrame;
    private long origin;
    private long endOffset;
    private long dropped;
    private long failed;
    private long invalid;
    private ProbeWarmup probeWarmup;

    ProcessMemoryMeasurement(int capacity, Supplier<NativeProcessMemory.Sample> probe, LongSupplier clock) {
        if (capacity < 1 || capacity > MAX_SAMPLES) throw new IllegalArgumentException("Invalid memory sample capacity");
        this.capacity = capacity;
        this.probe = probe;
        this.clock = clock;
    }

    void begin(long window, long frame) {
        if (active || window <= 0 || frame < 0) throw new IllegalStateException("Invalid memory window begin");
        samples.clear();
        dropped = failed = invalid = 0;
        windowId = window;
        firstFrame = nextFrame = frame;
        endFrame = endOffset = 0;
        framePending = finished = false;
        // Resolve/JIT the diagnostic bridge before the caller reanchors frame timing.
        // This observation is explicit warmup evidence, never a window RSS sample.
        long warmupStart = clock.getAsLong();
        NativeProcessMemory.Sample warmupSample = query();
        probeWarmup = new ProbeWarmup(clock.getAsLong() - warmupStart, warmupSample);
        origin = clock.getAsLong();
        active = true;
    }

    void beforeFrame(long window, long frame) {
        if (!active) return;
        if (window != windowId || framePending || frame != nextFrame) invalid++;
        capture(window, frame, "frame-begin");
        framePending = true;
    }

    void afterFrame(long window, long frame) {
        if (!active) return;
        if (window != windowId || !framePending || frame != nextFrame || frame == Long.MAX_VALUE) invalid++;
        capture(window, frame, "frame-end");
        framePending = false;
        nextFrame = frame + 1;
    }

    Snapshot finish(long window, long exclusiveEndFrame) {
        if (!active || framePending || window != windowId || exclusiveEndFrame != nextFrame
                || exclusiveEndFrame <= firstFrame || exclusiveEndFrame < 0) invalid++;
        if (active) capture(window, exclusiveEndFrame, "window-drain");
        endFrame = exclusiveEndFrame;
        endOffset = clock.getAsLong() - origin;
        active = false;
        finished = true;
        return snapshot();
    }

    private void capture(long window, long frame, String phase) {
        if (samples.size() >= capacity) {
            dropped++;
            return;
        }
        long begin = clock.getAsLong() - origin;
        NativeProcessMemory.Sample value = query();
        long end = clock.getAsLong() - origin;
        if (!value.successful()) failed++;
        if (begin < 0 || end < begin || (!samples.isEmpty() && begin < samples.getLast().endOffsetNanos())) invalid++;
        samples.add(new Sample(samples.size(), window, frame, phase, begin, end,
                value.kernelStatus(), value.returnedWordCount(), value.residentBytes(),
                value.physicalFootprintBytes(), value.lifetimeResidentPeakBytes()));
    }

    private NativeProcessMemory.Sample query() {
        try {
            var value = probe.get();
            if (value == null) throw new IllegalStateException("Missing memory sample");
            return value;
        } catch (RuntimeException failure) {
            // A failed diagnostic probe must make the measured metric unavailable, not change rendering.
            return new NativeProcessMemory.Sample(-4, 0, 0, 0, 0);
        }
    }

    private Snapshot snapshot() {
        long peak = 0, footprintPeak = 0, totalProbe = 0, maxProbe = 0;
        try {
            for (var sample : samples) {
                peak = Math.max(peak, sample.residentBytes());
                footprintPeak = Math.max(footprintPeak, sample.physicalFootprintBytes());
                long duration = Math.subtractExact(sample.endOffsetNanos(), sample.beginOffsetNanos());
                totalProbe = Math.addExact(totalProbe, duration);
                maxProbe = Math.max(maxProbe, duration);
            }
        } catch (ArithmeticException overflow) {
            invalid++;
        }
        long frameCount = endFrame - firstFrame;
        boolean complete = finished && !active && !framePending && frameCount > 0
                && frameCount <= (MAX_SAMPLES - 1) / 2 && samples.size() == frameCount * 2 + 1
                && dropped == 0 && failed == 0 && invalid == 0 && endOffset >= 0
                && samples.getLast().endOffsetNanos() <= endOffset;
        return new Snapshot(1, SOURCE, "current-process", "frame-boundaries-and-final-drain",
                "sampled-maximum", windowId, firstFrame, endFrame, capacity, samples.size(), dropped,
                failed, invalid, complete, complete ? "complete-sampled-process-rss" : "incomplete-process-memory-samples",
                peak, footprintPeak, samples.isEmpty() ? 0 : samples.getLast().lifetimeResidentPeakBytes(),
                totalProbe, maxProbe, endOffset, probeWarmup, samples);
    }

    record ProbeWarmup(long durationNanos, NativeProcessMemory.Sample sample) { }

    record Sample(long sequence, long windowId, long frameId, String phase,
                  long beginOffsetNanos, long endOffsetNanos, long kernelStatus, long returnedWordCount,
                  long residentBytes, long physicalFootprintBytes, long lifetimeResidentPeakBytes) { }

    record Snapshot(int schemaVersion, String source, String scope, String samplingPolicy,
                    String peakKind, long windowId, long firstFrame, long endFrameExclusive,
                    int capacitySamples, long sampleCount, long droppedSamples, long failedSamples,
                    long invalidEvents, boolean complete, String status, long peakResidentBytes,
                    long peakPhysicalFootprintBytes, long lifetimeResidentPeakBytesLast,
                    long totalProbeNanos, long maxProbeNanos, long endOffsetNanos,
                    ProbeWarmup probeWarmup, List<Sample> samples) {
        Snapshot { samples = List.copyOf(samples); }
    }
}
