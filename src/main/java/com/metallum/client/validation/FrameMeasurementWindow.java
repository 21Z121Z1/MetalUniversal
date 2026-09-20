package com.metallum.client.validation;

import java.util.ArrayList;
import java.util.List;

/** Pairs each completed render CPU sample with its following frame boundary. */
final class FrameMeasurementWindow {
    enum Step { WARMUP, START, MEASURE, COMPLETE }

    private final long warmupNanos;
    private final long sampleNanos;
    private final int settleFrames;
    private final int measuredFrames;
    private final List<Double> intervals = new ArrayList<>();
    private final List<Double> cpu = new ArrayList<>();
    private boolean anchored;
    private boolean started;
    private boolean complete;
    private boolean cpuPending;
    private long timelineStart;
    private long measurementStart;
    private long frameStart;
    private long currentFrame;
    private long firstFrame;
    private long endFrame;

    FrameMeasurementWindow(long warmupNanos, long sampleNanos, int settleFrames, int measuredFrames) {
        if (warmupNanos < 0 || sampleNanos < 0 || settleFrames < 0 || measuredFrames < 1) {
            throw new IllegalArgumentException("Invalid measurement duration or frame count");
        }
        this.warmupNanos = warmupNanos;
        this.sampleNanos = sampleNanos;
        this.settleFrames = settleFrames;
        this.measuredFrames = measuredFrames;
    }

    Step beforeFrame(long frame, long now) {
        if (complete) return Step.COMPLETE;
        if (!anchored) {
            timelineStart = now;
            anchored = true;
        }
        boolean duration = warmupNanos > 0 || sampleNanos > 0;
        if (!started) {
            if (duration ? now - timelineStart < warmupNanos : frame < settleFrames) {
                return Step.WARMUP;
            }
            started = true;
            firstFrame = frame;
            measurementStart = now;
            startFrame(frame, now);
            return Step.START;
        }
        if (cpuPending || frame != currentFrame + 1 || now <= frameStart) {
            throw new IllegalStateException("Incomplete or non-contiguous measurement frame");
        }
        intervals.add((now - frameStart) / 1_000_000.0);
        if (duration ? now - measurementStart >= sampleNanos : intervals.size() >= measuredFrames) {
            complete = true;
            endFrame = frame;
            return Step.COMPLETE;
        }
        startFrame(frame, now);
        return Step.MEASURE;
    }

    /** Excludes the one-time warmup GPU drain and recorder reset from all samples. */
    void reanchorStart(long now) {
        if (!started || !intervals.isEmpty() || !cpu.isEmpty() || now < frameStart) {
            throw new IllegalStateException("Only the initial measurement boundary may be reanchored");
        }
        measurementStart = now;
        frameStart = now;
    }

    private void startFrame(long frame, long now) {
        currentFrame = frame;
        frameStart = now;
        cpuPending = true;
    }

    void afterFrame(long frame, long now) {
        if (!started || complete) return;
        if (!cpuPending || frame != currentFrame || now <= frameStart) {
            throw new IllegalStateException("Unpaired measurement CPU frame");
        }
        cpu.add((now - frameStart) / 1_000_000.0);
        cpuPending = false;
    }

    List<Double> intervals() { return List.copyOf(intervals); }
    List<Double> cpuDurations() { return List.copyOf(cpu); }
    long firstFrame() { return firstFrame; }
    long endFrame() { return endFrame; }
}
