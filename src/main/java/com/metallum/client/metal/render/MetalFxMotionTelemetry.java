package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional, low-overhead evidence for the MetalFX motion and frame-generation
 * path.
 *
 * <p>This is deliberately separate from the render-contract trace. The trace
 * records semantic render identity; this class records whether the motion
 * producer and frame-generation lifecycle reached each Java-observable stage.
 * Enable it with {@code metallum.metalfx.motionTelemetry=true} or the existing
 * {@code metallum.hotpath.telemetry=true} switch.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalFxMotionTelemetry {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("metallum.metalfx.motionTelemetry", "false"))
                    || Boolean.parseBoolean(System.getProperty("metallum.hotpath.telemetry", "false"));
    private static final Accumulator GLOBAL = new Accumulator(ENABLED);

    private MetalFxMotionTelemetry() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Starts the per-frame deduplication window without inventing a frame ID. */
    static void beginFrame() {
        GLOBAL.beginFrame();
    }

    static void recordSourceFrame(
            final long frameId,
            final boolean eligible,
            final int rejectedReasons,
            final String exactCoverageFailureReason
    ) {
        GLOBAL.recordSourceFrame(frameId, eligible, rejectedReasons, exactCoverageFailureReason);
    }

    static void recordRequested(final long frameId) {
        GLOBAL.recordRequested(frameId);
    }

    static void recordEncoded(final long frameId) {
        GLOBAL.recordEncoded(frameId);
    }

    static void recordSubmitted(final long frameId) {
        GLOBAL.recordSubmitted(frameId);
    }

    static void recordCompleted(final long frameId, final boolean success) {
        GLOBAL.recordCompleted(frameId, success);
    }

    static void recordHistoryCapture(
            final long vertexCount,
            final long cpuReadBytes,
            final long cpuCopyBytes,
            final long cpuNanos
    ) {
        GLOBAL.recordHistoryCapture(vertexCount, cpuReadBytes, cpuCopyBytes, cpuNanos);
    }

    static void recordGpuUpload(final long bytes) {
        GLOBAL.recordGpuUpload(bytes);
    }

    static void recordMotionReplayDraw() {
        GLOBAL.recordMotionReplayDraw();
    }

    static void recordCpuMotionNanos(final long nanos) {
        GLOBAL.recordCpuMotionNanos(nanos);
    }

    /**
     * Returns a stable snapshot suitable for structured validation output.
     * Completed means the command buffer containing the encode completed
     * successfully. Presented is intentionally unavailable here: only the
     * native CAMetalDisplayLink presenter can observe a non-zero
     * {@code presentedTime}.
     */
    public static Snapshot snapshot() {
        return GLOBAL.snapshot();
    }

    public static void reset() {
        GLOBAL.reset();
    }

    static Accumulator accumulatorForTests(final boolean enabled) {
        return new Accumulator(enabled);
    }

    static final class Accumulator {
        private static final String EXACT_COVERAGE_INCOMPLETE = "exactCoverageIncomplete";
        private static final String UNAVAILABLE_GPU_MOTION =
                "no reliable motion-only GPU timestamp source";
        private static final String UNAVAILABLE_PRESENTED =
                "native MetalFX presenter timeline required; Java observes no scanout callback";

        private final boolean enabled;
        private final Map<String, Long> rejectionByReason = new LinkedHashMap<>();
        private long frameOrdinal;
        private long lastSourceFrameOrdinal = Long.MIN_VALUE;
        private long firstFrameId = -1L;
        private long lastFrameId = -1L;
        private long frameCount;
        private final LongAdder sourceFrames = new LongAdder();
        private final LongAdder eligibleSourceFrames = new LongAdder();
        private final LongAdder rejectedSourceFrames = new LongAdder();
        private final LongAdder requested = new LongAdder();
        private final LongAdder encoded = new LongAdder();
        private final LongAdder submitted = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder historicalVertexCount = new LongAdder();
        private final LongAdder cpuReadBytes = new LongAdder();
        private final LongAdder cpuCopyBytes = new LongAdder();
        private final LongAdder gpuUploadBytes = new LongAdder();
        private final LongAdder motionReplayDraws = new LongAdder();
        private final LongAdder cpuMotionNanos = new LongAdder();

        Accumulator(final boolean enabled) {
            this.enabled = enabled;
        }

        void beginFrame() {
            if (enabled) {
                frameOrdinal++;
            }
        }

        void recordSourceFrame(
                final long frameId,
                final boolean eligible,
                final int rejectedReasons,
                final String exactCoverageFailureReason
        ) {
            if (!enabled || lastSourceFrameOrdinal == frameOrdinal) {
                return;
            }
            lastSourceFrameOrdinal = frameOrdinal;
            if (firstFrameId < 0L) {
                firstFrameId = frameId;
            }
            lastFrameId = frameId;
            frameCount++;
            sourceFrames.increment();
            if (eligible) {
                eligibleSourceFrames.increment();
            } else {
                rejectedSourceFrames.increment();
                recordRejectionBits(rejectedReasons);
                if (exactCoverageFailureReason != null && !exactCoverageFailureReason.isBlank()) {
                    increment("exactCoverage." + normalizedReason(exactCoverageFailureReason));
                } else if (rejectedReasons == 0) {
                    increment(EXACT_COVERAGE_INCOMPLETE);
                }
            }
        }

        void recordRequested(final long ignoredFrameId) {
            if (enabled) {
                requested.increment();
            }
        }

        void recordEncoded(final long ignoredFrameId) {
            if (enabled) {
                encoded.increment();
            }
        }

        void recordSubmitted(final long ignoredFrameId) {
            if (enabled) {
                submitted.increment();
            }
        }

        void recordCompleted(final long ignoredFrameId, final boolean success) {
            if (enabled && success) {
                completed.increment();
            }
        }

        void recordHistoryCapture(
                final long vertexCount,
                final long readBytes,
                final long copyBytes,
                final long nanos
        ) {
            if (!enabled) {
                return;
            }
            historicalVertexCount.add(Math.max(0L, vertexCount));
            cpuReadBytes.add(Math.max(0L, readBytes));
            cpuCopyBytes.add(Math.max(0L, copyBytes));
            recordCpuMotionNanos(nanos);
        }

        void recordGpuUpload(final long bytes) {
            if (enabled) {
                gpuUploadBytes.add(Math.max(0L, bytes));
            }
        }

        void recordMotionReplayDraw() {
            if (enabled) {
                motionReplayDraws.increment();
            }
        }

        void recordCpuMotionNanos(final long nanos) {
            if (enabled) {
                cpuMotionNanos.add(Math.max(0L, nanos));
            }
        }

        Snapshot snapshot() {
            if (!enabled) {
                return Snapshot.disabled();
            }
            long capturedVertices = historicalVertexCount.sum();
            return new Snapshot(
                    true,
                    new SamplingWindow(firstFrameId, lastFrameId, frameCount),
                    Collections.unmodifiableMap(new LinkedHashMap<>(rejectionByReason)),
                    sourceFrames.sum(),
                    eligibleSourceFrames.sum(),
                    rejectedSourceFrames.sum(),
                    new StageCount(requested.sum(), true, "Java frame-generation input admission"),
                    new StageCount(encoded.sum(), true, "native frame-generation encode returned success"),
                    new StageCount(submitted.sum(), true, "Java command-buffer commit containing frame-generation encode"),
                    new StageCount(completed.sum(), true, "Java command-buffer completion with successful status"),
                    new StageCount(0L, false, UNAVAILABLE_PRESENTED),
                    capturedVertices,
                    cpuReadBytes.sum(),
                    cpuCopyBytes.sum(),
                    gpuUploadBytes.sum(),
                    motionReplayDraws.sum(),
                    new Cost(
                            cpuMotionNanos.sum(),
                            capturedVertices > 0L,
                            "CPU exact position-history capture and motion preparation"
                    ),
                    new Cost(0L, false, UNAVAILABLE_GPU_MOTION)
            );
        }

        void reset() {
            rejectionByReason.clear();
            frameOrdinal = 0L;
            lastSourceFrameOrdinal = Long.MIN_VALUE;
            firstFrameId = -1L;
            lastFrameId = -1L;
            frameCount = 0L;
            sourceFrames.reset();
            eligibleSourceFrames.reset();
            rejectedSourceFrames.reset();
            requested.reset();
            encoded.reset();
            submitted.reset();
            completed.reset();
            historicalVertexCount.reset();
            cpuReadBytes.reset();
            cpuCopyBytes.reset();
            gpuUploadBytes.reset();
            motionReplayDraws.reset();
            cpuMotionNanos.reset();
        }

        private void recordRejectionBits(final int bits) {
            incrementIfSet(bits, MetalFxMotionEligibility.NON_RIGID_ENTITY, "nonRigidEntity");
            incrementIfSet(bits, MetalFxMotionEligibility.UNKNOWN_ENTITY, "unknownEntity");
            incrementIfSet(bits, MetalFxMotionEligibility.FIRST_PERSON, "firstPerson");
            incrementIfSet(bits, MetalFxMotionEligibility.PARTICLE, "particle");
            incrementIfSet(bits, MetalFxMotionEligibility.MOVING_BLOCK, "movingBlock");
            incrementIfSet(bits, MetalFxMotionEligibility.DISPLAY_ENTITY, "displayEntity");
            incrementIfSet(bits, MetalFxMotionEligibility.MISSING_HISTORY, "missingHistory");
            incrementIfSet(bits, MetalFxMotionEligibility.SHARED_AUXILIARY, "sharedAuxiliary");
        }

        private void incrementIfSet(final int bits, final int bit, final String name) {
            if ((bits & bit) != 0) {
                increment(name);
            }
        }

        private void increment(final String name) {
            rejectionByReason.merge(name, 1L, Long::sum);
        }

        private String normalizedReason(final String reason) {
            StringBuilder normalized = new StringBuilder(Math.min(reason.length(), 64));
            for (int index = 0; index < reason.length() && normalized.length() < 64; index++) {
                char character = reason.charAt(index);
                if (Character.isLetterOrDigit(character)) {
                    normalized.append(character);
                } else if (normalized.length() == 0 || normalized.charAt(normalized.length() - 1) != '_') {
                    normalized.append('_');
                }
            }
            while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '_') {
                normalized.setLength(normalized.length() - 1);
            }
            return normalized.isEmpty() ? "unknown" : normalized.toString();
        }
    }

    public record SamplingWindow(long firstFrameId, long lastFrameId, long frameCount) {
    }

    public record StageCount(long count, boolean available, String source) {
    }

    public record Cost(long nanoseconds, boolean available, String source) {
    }

    public record Snapshot(
            boolean enabled,
            SamplingWindow samplingWindow,
            Map<String, Long> rejectionByReason,
            long sourceFrames,
            long eligibleSourceFrames,
            long rejectedSourceFrames,
            StageCount requested,
            StageCount encoded,
            StageCount submitted,
            StageCount completed,
            StageCount presented,
            long historicalVertexCount,
            long cpuReadBytes,
            long cpuCopyBytes,
            long gpuUploadBytes,
            long motionReplayDraws,
            Cost cpuMotionCost,
            Cost gpuMotionCost
    ) {
        static Snapshot disabled() {
            return new Snapshot(
                    false,
                    new SamplingWindow(-1L, -1L, 0L),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    new StageCount(0L, false, "telemetry disabled"),
                    new StageCount(0L, false, "telemetry disabled"),
                    new StageCount(0L, false, "telemetry disabled"),
                    new StageCount(0L, false, "telemetry disabled"),
                    new StageCount(0L, false, "telemetry disabled"),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    new Cost(0L, false, "telemetry disabled"),
                    new Cost(0L, false, "telemetry disabled")
            );
        }
    }
}
