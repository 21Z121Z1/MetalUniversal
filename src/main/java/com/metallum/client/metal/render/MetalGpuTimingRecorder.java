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
    private static final boolean GPU_PASS_TIMING_ENABLED =
            Boolean.getBoolean("metallum.validation.gpuPassTiming");
    private static final boolean CPU_PASS_TIMING_ENABLED = GPU_PASS_TIMING_ENABLED
            || Boolean.getBoolean("metallum.validation.cpuPassTiming");
    // Formal acceptance samples at least 120 seconds. Retain enough completed
    // command-buffer timings for the whole window instead of silently keeping
    // only the last ~2,048 frames.
    private static final int CAPACITY = 16_384;
    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static final List<CpuPassSample> CPU_PASS_SAMPLES = new ArrayList<>();
    private static long renderEncoderFactoryCalls;
    private static long renderEncoderCacheHits;
    private static long latestGpuNanos;

    private MetalGpuTimingRecorder() {
    }

    static boolean passTimingEnabled() {
        return CPU_PASS_TIMING_ENABLED || GPU_PASS_TIMING_ENABLED;
    }

    static boolean cpuPassTimingEnabled() {
        return CPU_PASS_TIMING_ENABLED;
    }

    static void record(final long submitIndex, final double start, final double end) {
        // Keep the disabled production path outside the monitor. This method is
        // reached once per completed frame, and the previous synchronized
        // declaration acquired the class monitor even when timing was disabled.
        if (!ENABLED || !(start > 0.0) || !(end > start)
                || !Double.isFinite(start) || !Double.isFinite(end)) {
            return;
        }
        synchronized (MetalGpuTimingRecorder.class) {
            SAMPLES.add(new Sample(submitIndex, start, end));
            latestGpuNanos = Math.max(1L, Math.round((end - start) * 1_000_000_000.0));
            if (SAMPLES.size() > CAPACITY) {
                SAMPLES.subList(0, SAMPLES.size() - CAPACITY).clear();
            }
        }
    }

    public static synchronized void reset() {
        SAMPLES.clear();
        CPU_PASS_SAMPLES.clear();
        renderEncoderFactoryCalls = 0L;
        renderEncoderCacheHits = 0L;
        latestGpuNanos = 0L;
        if (GPU_PASS_TIMING_ENABLED) {
            MetalNativeBridge.metallum_gpu_encoder_timing_reset();
        }
    }

    public static synchronized List<Sample> snapshot() {
        return List.copyOf(SAMPLES);
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

    static void recordCpuPass(
            final long submitIndex,
            final String label,
            final long startNanos,
            final long endNanos
    ) {
        // renderEncoder()/finishTiming() are on the render thread. Do not enter
        // a synchronized method for every pass when the validation lane is off.
        if (!CPU_PASS_TIMING_ENABLED || endNanos <= startNanos) {
            return;
        }
        synchronized (MetalGpuTimingRecorder.class) {
            CPU_PASS_SAMPLES.add(new CpuPassSample(
                    submitIndex,
                    label,
                    (endNanos - startNanos) / 1_000_000.0
            ));
            if (CPU_PASS_SAMPLES.size() > CAPACITY * 16) {
                CPU_PASS_SAMPLES.subList(0, CPU_PASS_SAMPLES.size() - CAPACITY * 16).clear();
            }
        }
    }

    static void recordRenderEncoderLookup(final boolean cacheHit) {
        // This call occurs for every renderEncoder() lookup. The disabled path
        // must be a plain predictable branch, not an uncontended monitor enter.
        if (!CPU_PASS_TIMING_ENABLED) {
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
        if (!GPU_PASS_TIMING_ENABLED) {
            return List.of();
        }
        int count = MetalNativeBridge.metallum_gpu_encoder_timing_count();
        List<GpuEncoderSample> samples = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double milliseconds = MetalNativeBridge.metallum_gpu_encoder_timing_milliseconds(index);
            int kind = MetalNativeBridge.metallum_gpu_encoder_timing_kind(index);
            String label = MetalNativeBridge.metallum_gpu_encoder_timing_label(index);
            if (milliseconds > 0.0 && Double.isFinite(milliseconds)) {
                samples.add(new GpuEncoderSample(label, kind == 1 ? "blit" : "render", milliseconds));
            }
        }
        return List.copyOf(samples);
    }

    public record Sample(long submitIndex, double gpuStartTime, double gpuEndTime) {
        public double milliseconds() {
            return (gpuEndTime - gpuStartTime) * 1_000.0;
        }
    }

    public record CpuPassSample(long submitIndex, String label, double milliseconds) {
    }

    public record GpuEncoderSample(String label, String kind, double milliseconds) {
    }

    public record RenderEncoderLookupStats(long factoryCalls, long cacheHits) {
    }
}
