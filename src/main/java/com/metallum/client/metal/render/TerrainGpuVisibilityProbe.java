package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Decision-only Metal visibility probe. It consumes the mesh-ready candidate
 * snapshot, publishes a bitset after GPU completion, and never participates in
 * draw submission or ICB masking. Sodium's CPU-produced draw list remains the
 * sole submission authority in this milestone.
 */
public final class TerrainGpuVisibilityProbe {
    public static final boolean ENABLED = TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED;

    private static final Object LOCK = new Object();
    private static final ThreadLocal<Boolean> TERRAIN_DRAW_SCOPE = new ThreadLocal<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final int MAX_PENDING_PROBES = 8;
    private static long lastAttemptedEpoch = -1L;
    private static long lastPublishedEpoch = -1L;
    private static long candidateCount;
    private static long lastVisibleCount;
    private static long lastUncertainCount;
    private static long attemptedCount;
    private static long dispatchCount;
    private static long producedCount;
    private static long fallbackCount;
    private static long falseNegativeOracleCount;
    private static long lastCompletedEpoch = -1L;

    private TerrainGpuVisibilityProbe() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void beginTerrainDrawScope() {
        if (ENABLED) {
            TERRAIN_DRAW_SCOPE.set(Boolean.TRUE);
        }
    }

    public static void endTerrainDrawScope() {
        if (ENABLED) {
            TERRAIN_DRAW_SCOPE.remove();
        }
    }

    static boolean inTerrainDrawScope() {
        return ENABLED && Boolean.TRUE.equals(TERRAIN_DRAW_SCOPE.get());
    }

    /**
     * Drops world-owned pending results. The native owner retains all buffers
     * until the encoded command completes; releasing the Java handle here only
     * prevents a stale world result from being published into the next world.
     */
    static void reset() {
        synchronized (LOCK) {
            for (Pending pending : PENDING) {
                releaseQuietly(pending.probe());
            }
            PENDING.clear();
            lastAttemptedEpoch = -1L;
            lastPublishedEpoch = -1L;
            candidateCount = 0L;
            lastVisibleCount = 0L;
            lastUncertainCount = 0L;
            attemptedCount = 0L;
            dispatchCount = 0L;
            producedCount = 0L;
            fallbackCount = 0L;
            falseNegativeOracleCount = 0L;
            lastCompletedEpoch = -1L;
        }
    }

    /**
     * Ends/reopens the active terrain encoder only when a new bounded snapshot
     * can be submitted. Returning true tells the caller to restore its normal
     * draw state on the reopened encoder.
     */
    static boolean beforeTerrainDraw(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder
    ) {
        if (!ENABLED) {
            return false;
        }
        synchronized (LOCK) {
            pollCompletedLocked();
            TerrainCandidateSnapshot snapshot = TerrainCandidateRegistry.latestSnapshot();
            if (snapshot == null || snapshot.epoch() <= lastAttemptedEpoch) {
                return false;
            }
            final int count = snapshot.candidates().size();
            candidateCount = count;

            // These are non-mutating rejection paths. Marking the epoch avoids
            // repeating the same failed attempt for every layer draw, while a
            // missing current encoder below deliberately remains retryable.
            if (count == 0 || count > TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES
                    || !device.metal4Available() || !device.metal4MainRenderer()
                    || !MetalNativeBridge.terrainVisibilityProbeAvailable()
                    || PENDING.size() >= MAX_PENDING_PROBES) {
                lastAttemptedEpoch = snapshot.epoch();
                attemptedCount++;
                fallbackCount++;
                return false;
            }

            // drawIndexedIndirect is allowed to be the first operation in a
            // pass. MetalCommandEncoder cannot retain a bridge that does not
            // exist, so the caller must ensure renderEncoder() was requested
            // before entering this method.
            MemorySegment retainedEncoder = commandEncoder.endEncoderForTerrainGpuAuthoring();
            if (MetalNativeBridge.isNullHandle(retainedEncoder)) {
                return false;
            }
            // Only now is this epoch genuinely attempted: a compatible native
            // bridge exists and its encoder has been ended for the transition.
            lastAttemptedEpoch = snapshot.epoch();
            attemptedCount++;
            try {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment packedCandidates;
                    MemorySegment packedMatrix;
                    Oracle oracle;
                    try {
                        packedCandidates = snapshot.packGpuVisibilityCandidates(arena);
                        packedMatrix = snapshot.packGpuVisibilityMatrix(arena);
                        oracle = oracleForPackedSnapshot(packedCandidates, packedMatrix, count);
                    } catch (RuntimeException invalidInput) {
                        fallbackCount++;
                        return true;
                    }
                    MemorySegment probe = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityProbe(
                            retainedEncoder,
                            device.metalDeviceHandle(),
                            packedCandidates,
                            packedMatrix,
                            count,
                            snapshot.epoch()
                    );
                    if (MetalNativeBridge.isNullHandle(probe)) {
                        fallbackCount++;
                        return true;
                    }
                    PENDING.add(new Pending(
                            probe,
                            snapshot.epoch(),
                            count,
                            TerrainCandidateSnapshot.gpuVisibilityWordCount(count),
                            oracle.expectedWords(),
                            oracle.visibleCount(),
                            oracle.uncertainCount()
                    ));
                    dispatchCount++;
                    return true;
                }
            } catch (RuntimeException failure) {
                fallbackCount++;
                return true;
            } finally {
                MetalNativeBridge.metallum_release_object(retainedEncoder);
            }
        }
    }

    static TerrainCandidateRegistry.VisibilityResult latestResult() {
        return TerrainCandidateRegistry.latestVisibilityResult();
    }

    public static Telemetry telemetry() {
        synchronized (LOCK) {
            return new Telemetry(
                    ENABLED,
                    candidateCount,
                    lastVisibleCount,
                    lastUncertainCount,
                    attemptedCount,
                    dispatchCount,
                    producedCount,
                    fallbackCount,
                    falseNegativeOracleCount,
                    lastCompletedEpoch
            );
        }
    }

    private static void pollCompletedLocked() {
        for (Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            boolean remove = false;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment epoch = arena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment visible = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment uncertain = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment wordCount = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment bitset = arena.allocate(
                        Math.max(1L, (long) pending.wordCount() * Integer.BYTES), 4
                );
                int status = MetalNativeBridge.terrainVisibilityProbePoll(
                        pending.probe(),
                        epoch,
                        visible,
                        uncertain,
                        wordCount,
                        bitset,
                        pending.wordCount()
                );
                if (status == 0) {
                    continue;
                }
                remove = true;
                if (status > 0) {
                    long completedEpoch = epoch.get(ValueLayout.JAVA_LONG, 0);
                    int completedWordCount = wordCount.get(ValueLayout.JAVA_INT, 0);
                    int completedVisible = visible.get(ValueLayout.JAVA_INT, 0);
                    int completedUncertain = uncertain.get(ValueLayout.JAVA_INT, 0);
                    int[] actualWords = new int[pending.wordCount()];
                    for (int index = 0; index < actualWords.length; index++) {
                        actualWords[index] = bitset.get(
                                ValueLayout.JAVA_INT, (long) index * Integer.BYTES
                        );
                    }
                    TerrainCandidateSnapshot current = TerrainCandidateRegistry.latestSnapshot();
                    boolean currentEpoch = current != null
                            && current.epoch() == completedEpoch
                            && current.candidates().size() == pending.candidateCount();
                    boolean falseNegative = missingExpectedBits(
                            pending.expectedWords(), actualWords
                    );
                    if (falseNegative) {
                        falseNegativeOracleCount++;
                    }
                    CompletionDisposition disposition = classifyCompletion(
                            pending,
                            completedEpoch,
                            completedWordCount,
                            completedVisible,
                            completedUncertain,
                            actualWords,
                            lastPublishedEpoch
                    );
                    if (completedEpoch >= lastCompletedEpoch) {
                        lastCompletedEpoch = completedEpoch;
                        lastVisibleCount = Integer.toUnsignedLong(completedVisible);
                        lastUncertainCount = Integer.toUnsignedLong(completedUncertain);
                    }
                    if (disposition == CompletionDisposition.PUBLISH) {
                        TerrainCandidateRegistry.publishVisibilityResult(
                                new TerrainCandidateRegistry.VisibilityResult(
                                        completedEpoch,
                                        pending.candidateCount(),
                                        completedVisible,
                                        completedUncertain,
                                        actualWords,
                                        false
                                )
                        );
                        lastPublishedEpoch = completedEpoch;
                        producedCount++;
                    } else if (disposition == CompletionDisposition.FALLBACK) {
                        // A stale/malformed/mismatching result never becomes a
                        // culling decision. If it still belongs to the current
                        // snapshot, publish an explicit all-visible fallback so
                        // a future consumer cannot accidentally interpret an
                        // old or partial bitset as authoritative.
                        fallbackCount++;
                        if (currentEpoch && completedEpoch > lastPublishedEpoch) {
                            TerrainCandidateRegistry.publishVisibilityResult(
                                    new TerrainCandidateRegistry.VisibilityResult(
                                            completedEpoch,
                                            pending.candidateCount(),
                                            pending.candidateCount(),
                                            pending.candidateCount(),
                                            allVisibleWords(pending.candidateCount()),
                                            true
                                    )
                            );
                            lastPublishedEpoch = completedEpoch;
                        }
                    }
                } else {
                    fallbackCount++;
                }
            } catch (RuntimeException failure) {
                fallbackCount++;
                remove = true;
            } finally {
                if (remove) {
                    releaseQuietly(pending.probe());
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Pure completion policy used by the poller and focused cross-frame tests.
     * A validated result is diagnostic data for its own epoch even when the
     * registry has already captured a newer frame; only publication order is
     * monotonic, so a late older result cannot replace a newer one.
     */
    static CompletionDisposition classifyCompletion(
            final Pending pending,
            final long completedEpoch,
            final int completedWordCount,
            final int completedVisible,
            final int completedUncertain,
            final int[] actualWords,
            final long lastPublishedEpoch
    ) {
        boolean countersMatch = Integer.toUnsignedLong(completedVisible)
                == pending.expectedVisibleCount()
                && Integer.toUnsignedLong(completedUncertain)
                == pending.expectedUncertainCount();
        boolean valid = completedEpoch == pending.epoch()
                && completedWordCount == pending.wordCount()
                && !missingExpectedBits(pending.expectedWords(), actualWords)
                && countersMatch;
        if (!valid) {
            return CompletionDisposition.FALLBACK;
        }
        return completedEpoch > lastPublishedEpoch
                ? CompletionDisposition.PUBLISH
                : CompletionDisposition.IGNORE;
    }

    enum CompletionDisposition {
        PUBLISH,
        FALLBACK,
        IGNORE
    }

    private static Oracle oracleForPackedSnapshot(
            final MemorySegment packedCandidates,
            final MemorySegment packedMatrix,
            final int count
    ) {
        if (packedCandidates.byteSize() != TerrainCandidateSnapshot.gpuVisibilityCandidateBytes(count)
                || packedMatrix.byteSize() != TerrainCandidateSnapshot.GPU_VISIBILITY_MATRIX_BYTES) {
            throw new IllegalArgumentException("Terrain visibility packed ABI size mismatch");
        }
        TerrainCandidateSnapshot.VisibilityTransform transform = new TerrainCandidateSnapshot.VisibilityTransform(
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 0),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 4),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 8),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 12),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 16),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 20),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 24),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 28),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 32),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 36),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 40),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 44),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 48),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 52),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 56),
                packedMatrix.get(ValueLayout.JAVA_FLOAT, 60)
        );
        int[] expectedWords = new int[TerrainCandidateSnapshot.gpuVisibilityWordCount(count)];
        int visibleCount = 0;
        int uncertainCount = 0;
        for (int index = 0; index < count; index++) {
            long offset = (long) index * TerrainCandidateSnapshot.GPU_VISIBILITY_CANDIDATE_STRIDE_BYTES;
            TerrainCandidateSnapshot.VisibilityDecision decision = TerrainCandidateSnapshot.referenceDecision(
                    transform,
                    packedCandidates.get(ValueLayout.JAVA_FLOAT, offset),
                    packedCandidates.get(ValueLayout.JAVA_FLOAT, offset + 4),
                    packedCandidates.get(ValueLayout.JAVA_FLOAT, offset + 8),
                    packedCandidates.get(ValueLayout.JAVA_FLOAT, offset + 12),
                    packedCandidates.get(ValueLayout.JAVA_FLOAT, offset + 16),
                    packedCandidates.get(ValueLayout.JAVA_FLOAT, offset + 20)
            );
            if (decision.visible()) {
                expectedWords[index >>> 5] |= 1 << (index & 31);
                visibleCount++;
            }
            if (decision.uncertain()) {
                uncertainCount++;
            }
        }
        return new Oracle(expectedWords, visibleCount, uncertainCount);
    }

    private static boolean missingExpectedBits(final int[] expected, final int[] actual) {
        if (expected.length != actual.length) {
            return true;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((actual[index] & expected[index]) != expected[index]) {
                return true;
            }
        }
        return false;
    }

    private static int[] allVisibleWords(final int count) {
        int[] words = new int[TerrainCandidateSnapshot.gpuVisibilityWordCount(count)];
        Arrays.fill(words, -1);
        int remainder = count & 31;
        if (remainder != 0 && words.length > 0) {
            words[words.length - 1] = -1 >>> (32 - remainder);
        }
        return words;
    }

    private static void releaseQuietly(final MemorySegment probe) {
        try {
            MetalNativeBridge.metallum_release_object(probe);
        } catch (RuntimeException ignored) {
            // Reset/close is already a conservative fallback; never mask it
            // with a stale native release failure.
        }
    }

    public record Telemetry(
            boolean enabled,
            long candidateCount,
            long visibleCount,
            long uncertainCount,
            long attemptedCount,
            long dispatchCount,
            long producedCount,
            long fallbackCount,
            long falseNegativeOracleCount,
            long lastCompletedEpoch
    ) {
    }

    private record Oracle(
            int[] expectedWords,
            int visibleCount,
            int uncertainCount
    ) {
        private Oracle {
            expectedWords = expectedWords.clone();
        }

        @Override
        public int[] expectedWords() {
            return expectedWords.clone();
        }
    }

    record Pending(
            MemorySegment probe,
            long epoch,
            int candidateCount,
            int wordCount,
            int[] expectedWords,
            int expectedVisibleCount,
            int expectedUncertainCount
    ) {
        Pending {
            expectedWords = expectedWords.clone();
        }

        @Override
        public int[] expectedWords() {
            return expectedWords.clone();
        }
    }
}
