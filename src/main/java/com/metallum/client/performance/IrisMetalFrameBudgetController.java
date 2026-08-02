package com.metallum.client.performance;

import java.util.Locale;

/** Deadline-oriented admission controller for non-critical render work. */
public final class IrisMetalFrameBudgetController {
    public static final String ENABLED_PROPERTY = "metallum.opt.frameBudget";
    public static final String MODE_PROPERTY = "metallum.opt.frameBudget.mode";
    public static final String TARGET_HZ_PROPERTY = "metallum.opt.frameBudget.targetHz";
    public static final String SAFETY_MARGIN_PROPERTY = "metallum.opt.frameBudget.safetyMarginNanos";

    private static final double EWMA_ALPHA = 0.20;
    private static final long NEAR_CAMERA_BORROW_NANOS = 500_000L;
    private static final IrisMetalFrameBudgetController RUNTIME = createRuntime();

    private final boolean enabled;
    private final Mode mode;
    private final long framePeriodNanos;
    private final long safetyMarginNanos;
    private long sequence;
    private long frameBeginNanos;
    private long nextPresentationNanos;
    private long predictedCoreNanos;
    private long reservedNanos;
    private long admittedTasks;
    private long deferredTasks;
    private long rejectedValidationTasks;
    private DisplayDeadlineSnapshot deadline = DisplayDeadlineSnapshot.unavailable();

    public IrisMetalFrameBudgetController(
            boolean enabled,
            Mode mode,
            long framePeriodNanos,
            long safetyMarginNanos
    ) {
        if (framePeriodNanos <= 0L || safetyMarginNanos < 0L || safetyMarginNanos >= framePeriodNanos) {
            throw new IllegalArgumentException("invalid frame period or safety margin");
        }
        this.enabled = enabled;
        this.mode = mode == null ? Mode.STABLE : mode;
        this.framePeriodNanos = framePeriodNanos;
        this.safetyMarginNanos = safetyMarginNanos;
        this.predictedCoreNanos = Math.min(8_000_000L, framePeriodNanos);
    }

    public static IrisMetalFrameBudgetController runtime() {
        return RUNTIME;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long estimatedFramePeriodNanos() {
        return framePeriodNanos;
    }

    public synchronized DisplayDeadlineSnapshot beginFrame(long nowNanos) {
        frameBeginNanos = Math.max(0L, nowNanos);
        reservedNanos = 0L;
        sequence++;
        if (!enabled) {
            deadline = DisplayDeadlineSnapshot.unavailable();
            return deadline;
        }
        if (nextPresentationNanos <= 0L) {
            nextPresentationNanos = saturatedAdd(nowNanos, framePeriodNanos);
        }
        while (nextPresentationNanos <= nowNanos) {
            long next = saturatedAdd(nextPresentationNanos, framePeriodNanos);
            if (next == nextPresentationNanos) break;
            nextPresentationNanos = next;
        }
        deadline = new DisplayDeadlineSnapshot(
                sequence,
                nextPresentationNanos,
                Math.max(0L, nextPresentationNanos - safetyMarginNanos),
                framePeriodNanos,
                DisplayDeadlineSnapshot.Source.ESTIMATED_CADENCE
        );
        return deadline;
    }

    /** Accepts only a newer deadline from the actual presentation owner. */
    public synchronized void observeDisplayDeadline(DisplayDeadlineSnapshot observed) {
        if (enabled && observed != null && observed.available()
                && observed.source() == DisplayDeadlineSnapshot.Source.METAL_DISPLAY_LINK
                && observed.sequence() >= deadline.sequence()) {
            deadline = observed;
        }
    }

    public synchronized void endFrame(long nowNanos, long cpuNanos, long gpuNanos) {
        if (!enabled) return;
        long measuredCpu = cpuNanos > 0L ? cpuNanos
                : frameBeginNanos > 0L && nowNanos > frameBeginNanos ? nowNanos - frameBeginNanos : 0L;
        long measured = Math.max(measuredCpu, Math.max(0L, gpuNanos));
        if (measured <= 0L) return;
        measured = clamp(measured, 250_000L, saturatedMultiply(framePeriodNanos, 4L));
        predictedCoreNanos = Math.round(predictedCoreNanos * (1.0 - EWMA_ALPHA) + measured * EWMA_ALPHA);
    }

    public synchronized DisplayDeadlineSnapshot currentDeadline() {
        return deadline;
    }

    public synchronized long predictedCoreRenderNanos() {
        return predictedCoreNanos;
    }

    public synchronized long availableBudgetNanos(long nowNanos) {
        if (!enabled || !deadline.available()) return 0L;
        return Math.max(0L, deadline.nanosUntilCommitDeadline(nowNanos) - predictedCoreNanos - reservedNanos);
    }

    public synchronized long clampBudget(WorkCategory category, long requestedNanos, long nowNanos) {
        long requested = Math.max(0L, requestedNanos);
        if (!enabled || category == WorkCategory.CRITICAL_VISIBLE) return requested;
        if (category == WorkCategory.VALIDATION_ONLY) return 0L;
        long available = availableBudgetNanos(nowNanos);
        if (category == WorkCategory.VISIBLE_NEAR_CAMERA) {
            available = saturatedAdd(available, NEAR_CAMERA_BORROW_NANOS);
        }
        return Math.min(requested, available);
    }

    public synchronized boolean reserve(WorkCategory category, long estimatedNanos, long nowNanos) {
        long estimate = Math.max(1L, estimatedNanos);
        if (!enabled || category == WorkCategory.CRITICAL_VISIBLE) {
            admittedTasks++;
            return true;
        }
        if (category == WorkCategory.VALIDATION_ONLY) {
            rejectedValidationTasks++;
            return false;
        }
        if (clampBudget(category, estimate, nowNanos) < estimate) {
            deferredTasks++;
            return false;
        }
        reservedNanos = saturatedAdd(reservedNanos, estimate);
        admittedTasks++;
        return true;
    }

    public boolean tryExecute(WorkCategory category, long estimatedNanos, Runnable task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        if (!reserve(category, estimatedNanos, System.nanoTime())) return false;
        task.run();
        return true;
    }

    public synchronized Counters counters() {
        return new Counters(admittedTasks, deferredTasks, rejectedValidationTasks, reservedNanos);
    }

    private static IrisMetalFrameBudgetController createRuntime() {
        boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
        Mode mode = Mode.parse(System.getProperty(MODE_PROPERTY, "STABLE"));
        double hz = clamp(parseDouble(System.getProperty(TARGET_HZ_PROPERTY), 60.0), 24.0, 240.0);
        long period = Math.max(1L, Math.round(1_000_000_000.0 / hz));
        long defaultMargin = mode == Mode.LOW_LATENCY ? 350_000L : 750_000L;
        long margin = clamp(parseLong(System.getProperty(SAFETY_MARGIN_PROPERTY), defaultMargin), 0L, period - 1L);
        return new IrisMetalFrameBudgetController(enabled, mode, period, margin);
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value.trim()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            double parsed = value == null ? fallback : Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ignored) { return fallback; }
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + Math.max(0L, right);
    }

    private static long saturatedMultiply(long value, long multiplier) {
        return value > 0L && multiplier > 0L && value > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : Math.max(0L, value) * Math.max(0L, multiplier);
    }

    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    public enum Mode {
        LOW_LATENCY, STABLE;
        static Mode parse(String value) {
            try { return value == null ? STABLE : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return STABLE; }
        }
    }

    public enum WorkCategory {
        CRITICAL_VISIBLE,
        VISIBLE_NEAR_CAMERA,
        BACKGROUND_VISIBLE,
        CACHE_WARMUP,
        MAINTENANCE,
        VALIDATION_ONLY
    }

    public record Counters(long admittedTasks, long deferredTasks, long rejectedValidationTasks, long reservedNanos) {}
}
