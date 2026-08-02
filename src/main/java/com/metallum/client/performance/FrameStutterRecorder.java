package com.metallum.client.performance;

import com.metallum.client.terrain.TerrainSchedulingController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Collects bounded per-frame evidence and assigns one primary stutter cause. */
public final class FrameStutterRecorder {
    public static final String ENABLED_PROPERTY = "metallum.validation.frameStutter";
    public static final String REPORT_PROPERTY = "metallum.validation.frameStutterReport";
    private static final int DEFAULT_CAPACITY = 16_384;
    private static final int MEDIAN_WINDOW = 120;
    private static final long FRAME_33_MS = 33_333_333L;
    private static final long FRAME_50_MS = 50_000_000L;
    private static final long FRAME_100_MS = 100_000_000L;
    private static final FrameStutterRecorder RUNTIME = new FrameStutterRecorder(
            Boolean.getBoolean(ENABLED_PROPERTY)
                    || !System.getProperty(REPORT_PROPERTY, "").isBlank(),
            DEFAULT_CAPACITY
    );

    private final boolean enabled;
    private final int capacity;
    private final List<FrameSample> samples = new ArrayList<>();
    private final ArrayDeque<Long> rollingFrameTimes = new ArrayDeque<>();
    private final AtomicLong compileCount = new AtomicLong();
    private final AtomicLong compileNanos = new AtomicLong();
    private final AtomicLong longestCompileNanos = new AtomicLong();
    private final AtomicLong backgroundCompileCount = new AtomicLong();
    private final AtomicLong submitCount = new AtomicLong();
    private final AtomicLong submitNanos = new AtomicLong();
    private final AtomicLong lastSubmitEndNanos = new AtomicLong();
    private FrameStart active;
    private long frameIndex;

    public FrameStutterRecorder(boolean enabled, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.enabled = enabled;
        this.capacity = capacity;
    }

    public static FrameStutterRecorder runtime() {
        return RUNTIME;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void beginFrame(long nowNanos, DisplayDeadlineSnapshot deadline) {
        if (!enabled) return;
        active = new FrameStart(
                Math.max(0L, nowNanos),
                compileCount.get(), compileNanos.get(), backgroundCompileCount.get(),
                submitCount.get(), submitNanos.get(), lastSubmitEndNanos.get(),
                deadline == null ? DisplayDeadlineSnapshot.unavailable() : deadline
        );
    }

    public void recordPipelineCompile(String pipeline, long durationNanos, boolean background) {
        if (!enabled || durationNanos <= 0L) return;
        compileCount.incrementAndGet();
        compileNanos.addAndGet(durationNanos);
        longestCompileNanos.accumulateAndGet(durationNanos, Math::max);
        if (background) backgroundCompileCount.incrementAndGet();
    }

    public void recordCommandSubmit(long startNanos, long endNanos) {
        if (!enabled || endNanos <= startNanos) return;
        submitCount.incrementAndGet();
        submitNanos.addAndGet(endNanos - startNanos);
        lastSubmitEndNanos.set(endNanos);
    }

    public synchronized void endFrame(
            long nowNanos,
            long gpuNanos,
            long gcPauseNanos,
            TerrainSchedulingController.FrameSnapshot terrain
    ) {
        if (!enabled || active == null || nowNanos <= active.beginNanos()) return;
        long frameNanos = nowNanos - active.beginNanos();
        long frameCompileCount = Math.max(0L, compileCount.get() - active.compileCount());
        long frameCompileNanos = Math.max(0L, compileNanos.get() - active.compileNanos());
        long frameBackgroundCompiles = Math.max(0L, backgroundCompileCount.get() - active.backgroundCompileCount());
        long frameSubmitCount = Math.max(0L, submitCount.get() - active.submitCount());
        long frameSubmitNanos = Math.max(0L, submitNanos.get() - active.submitNanos());
        long submitEnd = lastSubmitEndNanos.get();
        long commitTimestamp = submitEnd > active.lastSubmitEndNanos() ? submitEnd : 0L;
        long rollingMedian = percentile(rollingFrameTimes.stream().mapToLong(Long::longValue).toArray(), 0.50);
        boolean rollingSpike = rollingFrameTimes.size() >= 8 && frameNanos > rollingMedian * 2L;
        DisplayDeadlineSnapshot deadline = active.deadline();
        boolean missedDeadline = deadline.available()
                && (commitTimestamp > 0L ? commitTimestamp : nowNanos) > deadline.commitDeadlineNanos();
        long buildNanos = terrain == null ? 0L : terrain.buildSubmitNanos();
        long uploadNanos = terrain == null ? 0L : terrain.uploadNanos();
        int submittedTasks = terrain == null ? 0 : terrain.submittedTasks();
        int uploadResults = terrain == null ? 0 : terrain.uploadResults();
        StutterCause cause = classify(
                frameNanos, Math.max(0L, gpuNanos), frameCompileNanos,
                frameSubmitNanos, Math.max(0L, gcPauseNanos), buildNanos, uploadNanos,
                deadline.framePeriodNanos()
        );
        samples.add(new FrameSample(
                ++frameIndex, active.beginNanos(), nowNanos, frameNanos,
                Math.max(0L, gpuNanos), commitTimestamp,
                frameCompileCount, frameCompileNanos, frameBackgroundCompiles,
                frameSubmitCount, frameSubmitNanos, Math.max(0L, gcPauseNanos),
                buildNanos, uploadNanos, submittedTasks, uploadResults,
                deadline.source(), deadline.targetPresentationNanos(), deadline.commitDeadlineNanos(),
                missedDeadline, rollingSpike, cause
        ));
        if (samples.size() > capacity) samples.subList(0, samples.size() - capacity).clear();
        rollingFrameTimes.addLast(frameNanos);
        while (rollingFrameTimes.size() > MEDIAN_WINDOW) rollingFrameTimes.removeFirst();
        active = null;
    }

    public synchronized void reset() {
        samples.clear();
        rollingFrameTimes.clear();
        active = null;
        frameIndex = 0L;
        compileCount.set(0L);
        compileNanos.set(0L);
        longestCompileNanos.set(0L);
        backgroundCompileCount.set(0L);
        submitCount.set(0L);
        submitNanos.set(0L);
        lastSubmitEndNanos.set(0L);
    }

    public synchronized List<FrameSample> snapshot() {
        return List.copyOf(samples);
    }

    public synchronized Summary summary() {
        long[] times = samples.stream().mapToLong(FrameSample::frameNanos).toArray();
        long missed = samples.stream().filter(FrameSample::missedDeadline).count();
        long rolling = samples.stream().filter(FrameSample::rollingMedianSpike).count();
        long above33 = Arrays.stream(times).filter(value -> value > FRAME_33_MS).count();
        long above50 = Arrays.stream(times).filter(value -> value > FRAME_50_MS).count();
        long above100 = Arrays.stream(times).filter(value -> value > FRAME_100_MS).count();
        int maxConsecutive = 0;
        int current = 0;
        for (FrameSample sample : samples) {
            current = sample.missedDeadline() ? current + 1 : 0;
            maxConsecutive = Math.max(maxConsecutive, current);
        }
        long p99 = percentile(times, 0.99);
        long p999 = percentile(times, 0.999);
        return new Summary(
                samples.size(), percentile(times, 0.50), percentile(times, 0.95), p99, p999,
                fpsFromNanos(p99), fpsFromNanos(p999), rolling, above33, above50, above100,
                missed, maxConsecutive, compileCount.get(), compileNanos.get(),
                longestCompileNanos.get(), backgroundCompileCount.get(), submitCount.get(), submitNanos.get()
        );
    }

    private static StutterCause classify(
            long frame, long gpu, long compile, long submit, long gc, long build, long upload, long period
    ) {
        long meaningful = Math.max(1_000_000L, frame / 5L);
        if (compile >= meaningful) return StutterCause.PIPELINE_COMPILE;
        if (submit >= meaningful) return StutterCause.COMMAND_QUEUE_BACKPRESSURE;
        if (gc >= meaningful) return StutterCause.JAVA_GC;
        if (upload >= meaningful) return StutterCause.CHUNK_UPLOAD;
        if (build >= meaningful) return StutterCause.CHUNK_REBUILD;
        long budget = period > 0L ? period : 16_666_667L;
        if (gpu > budget) return StutterCause.GPU_OVER_BUDGET;
        if (frame > budget) return StutterCause.CPU_RENDER_ENCODE;
        return StutterCause.UNKNOWN;
    }

    static long percentile(long[] values, double quantile) {
        if (values.length == 0) return 0L;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double fpsFromNanos(long nanos) {
        return nanos > 0L ? 1_000_000_000.0 / nanos : 0.0;
    }

    private record FrameStart(
            long beginNanos,
            long compileCount,
            long compileNanos,
            long backgroundCompileCount,
            long submitCount,
            long submitNanos,
            long lastSubmitEndNanos,
            DisplayDeadlineSnapshot deadline
    ) {}

    public enum StutterCause {
        PIPELINE_COMPILE,
        DRAWABLE_WAIT,
        COMMAND_QUEUE_BACKPRESSURE,
        CPU_RENDER_ENCODE,
        GPU_OVER_BUDGET,
        RESOURCE_ALLOCATION,
        CHUNK_UPLOAD,
        CHUNK_REBUILD,
        TRANSLUCENT_SORT,
        JAVA_GC,
        EXPLICIT_SYNC_WAIT,
        UNKNOWN
    }

    public record FrameSample(
            long frameIndex, long frameBeginNanos, long frameEndNanos, long frameNanos,
            long gpuNanos, long commandBufferCommitNanos,
            long pipelineCompileCount, long pipelineCompileNanos, long backgroundPipelineCompileCount,
            long commandSubmitCount, long commandSubmitNanos, long gcPauseNanos,
            long chunkBuildNanos, long chunkUploadNanos, int chunkBuildSubmittedCount, int chunkUploadResultCount,
            DisplayDeadlineSnapshot.Source deadlineSource, long displayTargetNanos, long displayDeadlineNanos,
            boolean missedDeadline, boolean rollingMedianSpike, StutterCause primaryCause
    ) {}

    public record Summary(
            int frames, long frameP50Nanos, long frameP95Nanos, long frameP99Nanos, long frameP999Nanos,
            double onePercentLowFps, double pointOnePercentLowFps,
            long rollingMedianSpikes, long framesAbove33ms, long framesAbove50ms, long framesAbove100ms,
            long missedDeadlines, int maxConsecutiveMissedDeadlines,
            long pipelineCompileCount, long pipelineCompileNanos, long longestPipelineCompileNanos,
            long backgroundPipelineCompileCount, long commandSubmitCount, long commandSubmitNanos
    ) {}
}
