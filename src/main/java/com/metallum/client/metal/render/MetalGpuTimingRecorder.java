package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.util.ArrayList;
import java.util.List;

/** Diagnostic capture of completed main-queue Metal command buffers. */
public final class MetalGpuTimingRecorder {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.validation.gpuTiming")
            || Boolean.getBoolean("metallum.metalfx.debug")
            || Boolean.getBoolean("metallum.opt.terrainAdaptiveScheduling")
            || Boolean.getBoolean("metallum.opt.terrainSchedulingTelemetry");
    private static final boolean PASS_TIMING_ENABLED =
            Boolean.getBoolean("metallum.validation.gpuPassTiming");
    // Ordinary diagnostics stay bounded at the existing capacity. A formal window
    // commonly submits two buffers per frame: 120s * 120fps * 2 exceeds 16,384.
    // Match its explicit count ledger capacity; overflow still fails identity checks.
    private static final int CAPACITY = 16_384;
    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static final List<Sample> MEASUREMENT_SAMPLES = new ArrayList<>();
    private static final List<CpuPassSample> CPU_PASS_SAMPLES = new ArrayList<>();
    private static long renderEncoderFactoryCalls;
    private static long renderEncoderCacheHits;
    private static long latestGpuNanos;
    private static volatile long measurementWindowId;
    private static volatile long measurementFrameId = -1L;

    private MetalGpuTimingRecorder() {
    }

    static boolean passTimingEnabled() {
        return PASS_TIMING_ENABLED;
    }

    static void record(final long submitIndex, final double start, final double end) {
        record(submitIndex, 0L, -1L, start, end);
    }

    /**
     * Records a completed native command buffer with the identity captured when it was
     * encoded. The identity is deliberately passed by the submit owner; completion may happen
     * after the Java-side measurement context has moved to another frame.
     */
    static void record(
            final long submitIndex,
            final long windowId,
            final long frameId,
            final double start,
            final double end
    ) {
        // Keep the disabled production path outside the monitor. This method is
        // reached once per completed frame, and the previous synchronized
        // declaration acquired the class monitor even when timing was disabled.
        if (!ENABLED || !(start > 0.0) || !(end > start)
                || !Double.isFinite(start) || !Double.isFinite(end)
                || windowId < 0L || frameId < -1L) {
            return;
        }
        synchronized (MetalGpuTimingRecorder.class) {
            List<Sample> samples = windowId > 0L ? MEASUREMENT_SAMPLES : SAMPLES;
            samples.add(new Sample(submitIndex, windowId, frameId, start, end));
            latestGpuNanos = Math.max(1L, Math.round((end - start) * 1_000_000_000.0));
            int capacity = windowId > 0L ? NativeEncoderCounts.MAX_ROWS : CAPACITY;
            if (samples.size() > capacity) {
                samples.subList(0, samples.size() - capacity).clear();
            }
        }
    }

    public static synchronized void reset() {
        SAMPLES.clear();
        MEASUREMENT_SAMPLES.clear();
        CPU_PASS_SAMPLES.clear();
        renderEncoderFactoryCalls = 0L;
        renderEncoderCacheHits = 0L;
        latestGpuNanos = 0L;
        measurementWindowId = 0L;
        measurementFrameId = -1L;
        if (ENABLED) MetalNativeBridge.metallum_encoder_counts_reset(0);
        if (PASS_TIMING_ENABLED) {
            MetalNativeBridge.metallum_gpu_encoder_timing_reset();
            MetalNativeBridge.metallum_set_gpu_encoder_timing_context(0L, -1L);
        }
    }

    /** Begins a fresh positive-id measurement window and clears prior Java-side timing samples. */
    public static synchronized void beginMeasurementWindow(final long windowId) {
        requireWindowId(windowId);
        SAMPLES.clear();
        MEASUREMENT_SAMPLES.clear();
        CPU_PASS_SAMPLES.clear();
        renderEncoderFactoryCalls = 0L;
        renderEncoderCacheHits = 0L;
        latestGpuNanos = 0L;
        measurementWindowId = windowId;
        measurementFrameId = -1L;
        if (ENABLED) MetalNativeBridge.metallum_encoder_counts_reset(NativeEncoderCounts.MAX_ROWS);
        if (PASS_TIMING_ENABLED) {
            MetalNativeBridge.metallum_gpu_encoder_timing_reset();
            MetalNativeBridge.metallum_set_gpu_encoder_timing_context(windowId, -1L);
        }
    }

    /** Enters one frame in the currently active measurement window. */
    public static synchronized void beginMeasurementFrame(final long windowId, final long frameId) {
        requireWindowId(windowId);
        requireFrameId(frameId);
        if (measurementWindowId != windowId) {
            throw new IllegalStateException(
                    "measurement frame window does not match active window: " + windowId
                            + " != " + measurementWindowId
            );
        }
        measurementFrameId = frameId;
        if (PASS_TIMING_ENABLED) {
            MetalNativeBridge.metallum_set_gpu_encoder_timing_context(windowId, frameId);
        }
    }

    /** Leaves the active measurement context; subsequent legacy samples use window zero/frame -1. */
    public static synchronized void endMeasurementWindow() {
        measurementWindowId = 0L;
        measurementFrameId = -1L;
        if (PASS_TIMING_ENABLED) {
            MetalNativeBridge.metallum_set_gpu_encoder_timing_context(0L, -1L);
        }
    }

    public static long measurementWindowId() {
        return measurementWindowId;
    }

    public static long measurementFrameId() {
        return measurementFrameId;
    }

    static void bindEncoderCountIdentity(java.lang.foreign.MemorySegment commandBuffer,
                                         long windowId, long frameId, long submitIndex) {
        if (ENABLED && windowId > 0 && frameId >= 0) {
            MetalNativeBridge.metallum_encoder_counts_bind(commandBuffer, windowId, frameId, submitIndex);
        }
    }

    public static NativeEncoderCounts.Snapshot encoderCountSnapshot() {
        if (!ENABLED) {
            return NativeEncoderCounts.decode(new long[]{1, 0, 0, 0, 0, 0, 0, 0}, new long[0]);
        }
        return MetalNativeBridge.metallum_encoder_counts_snapshot();
    }

    /**
     * Drains submitted work outside a measurement interval. The encoder implementation owns the
     * exact in-flight wait and returns the next submit index, so this helper cannot accidentally
     * turn a completion callback into a measurement sample.
     */
    public static long drainSubmittedWorkForMeasurement() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            throw new IllegalStateException("No live Metal device is available for measurement drain");
        }
        return device.commandEncoder().drainValidationGpuWork();
    }

    public static synchronized List<Sample> snapshot() {
        return java.util.stream.Stream.concat(SAMPLES.stream(), MEASUREMENT_SAMPLES.stream())
                .sorted(java.util.Comparator.comparingLong(Sample::submitIndex)).toList();
    }

    /** Returns only Java-side command-buffer samples captured for one measurement window. */
    public static synchronized List<Sample> snapshot(final long windowId) {
        requireWindowId(windowId);
        return MEASUREMENT_SAMPLES.stream()
                .filter(sample -> sample.windowId() == windowId)
                .toList();
    }

    /** Latest completed GPU service duration, or zero when timing is disabled/unavailable. */
    public static long latestGpuNanos() {
        if (!ENABLED) {
            return 0L;
        }
        synchronized (MetalGpuTimingRecorder.class) {
            return latestGpuNanos;
        }
    }

    static void recordCpuPass(final String label, final long startNanos, final long endNanos) {
        // renderEncoder()/finishTiming() are on the render thread. Do not enter
        // a synchronized method for every pass when the validation lane is off.
        if (!PASS_TIMING_ENABLED || endNanos <= startNanos) {
            return;
        }
        synchronized (MetalGpuTimingRecorder.class) {
            CPU_PASS_SAMPLES.add(new CpuPassSample(label, (endNanos - startNanos) / 1_000_000.0));
            if (CPU_PASS_SAMPLES.size() > CAPACITY * 16) {
                CPU_PASS_SAMPLES.subList(0, CPU_PASS_SAMPLES.size() - CAPACITY * 16).clear();
            }
        }
    }

    static void recordRenderEncoderLookup(final boolean cacheHit) {
        // This call occurs for every renderEncoder() lookup. The disabled path
        // must be a plain predictable branch, not an uncontended monitor enter.
        if (!PASS_TIMING_ENABLED) {
            return;
        }
        synchronized (MetalGpuTimingRecorder.class) {
            if (cacheHit) {
                renderEncoderCacheHits++;
            } else {
                renderEncoderFactoryCalls++;
            }
        }
    }

    public static synchronized RenderEncoderLookupStats renderEncoderLookupStats() {
        return new RenderEncoderLookupStats(renderEncoderFactoryCalls, renderEncoderCacheHits);
    }

    public static synchronized List<CpuPassSample> cpuPassSnapshot() {
        return List.copyOf(CPU_PASS_SAMPLES);
    }

    public static List<GpuEncoderSample> gpuEncoderSnapshot() {
        return gpuEncoderSnapshotInternal(null);
    }

    /** Returns only native encoder samples captured for one measurement window. */
    public static List<GpuEncoderSample> gpuEncoderSnapshot(final long windowId) {
        requireWindowId(windowId);
        return gpuEncoderSnapshotInternal(windowId);
    }

    private static List<GpuEncoderSample> gpuEncoderSnapshotInternal(final Long windowId) {
        if (!PASS_TIMING_ENABLED) {
            return List.of();
        }
        int count = MetalNativeBridge.metallum_gpu_encoder_timing_count();
        List<GpuEncoderSample> samples = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double milliseconds = MetalNativeBridge.metallum_gpu_encoder_timing_milliseconds(index);
            int kind = MetalNativeBridge.metallum_gpu_encoder_timing_kind(index);
            long sampleWindowId = MetalNativeBridge.metallum_gpu_encoder_timing_measurement_window_id(index);
            long sampleFrameId = MetalNativeBridge.metallum_gpu_encoder_timing_frame_id(index);
            String label = MetalNativeBridge.metallum_gpu_encoder_timing_label(index);
            if ((windowId == null || sampleWindowId == windowId)
                    && milliseconds > 0.0 && Double.isFinite(milliseconds)) {
                samples.add(new GpuEncoderSample(
                        sampleWindowId,
                        sampleFrameId,
                        label,
                        switch (kind) { case 0 -> "render"; case 1 -> "blit"; case 2 -> "compute"; default -> "unknown"; },
                        milliseconds
                ));
            }
        }
        return List.copyOf(samples);
    }

    public record Sample(
            long submitIndex,
            long windowId,
            long frameId,
            double gpuStartTime,
            double gpuEndTime
    ) {
        public Sample(final long submitIndex, final double gpuStartTime, final double gpuEndTime) {
            this(submitIndex, 0L, -1L, gpuStartTime, gpuEndTime);
        }

        public long measurementWindowId() {
            return windowId;
        }

        public double milliseconds() {
            return (gpuEndTime - gpuStartTime) * 1_000.0;
        }
    }

    public record CpuPassSample(String label, double milliseconds) {
    }

    public record GpuEncoderSample(
            long windowId,
            long frameId,
            String label,
            String kind,
            double milliseconds
    ) {
        public GpuEncoderSample(
                final String label,
                final String kind,
                final double milliseconds
        ) {
            this(0L, -1L, label, kind, milliseconds);
        }

        public long measurementWindowId() {
            return windowId;
        }
    }

    public record RenderEncoderLookupStats(long factoryCalls, long cacheHits) {
    }

    private static void requireWindowId(final long windowId) {
        if (windowId <= 0L) {
            throw new IllegalArgumentException("measurement window id must be positive: " + windowId);
        }
    }

    private static void requireFrameId(final long frameId) {
        if (frameId < 0L) {
            throw new IllegalArgumentException("measurement frame id must be non-negative: " + frameId);
        }
    }
}
