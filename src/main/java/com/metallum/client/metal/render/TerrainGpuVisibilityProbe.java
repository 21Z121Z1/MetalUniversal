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
 * snapshot, publishes a bitset and a stable compacted candidate-index list
 * after GPU completion, and never participates in draw submission or ICB
 * masking. Sodium's CPU-produced draw list remains the sole submission
 * authority in this milestone.
 */
public final class TerrainGpuVisibilityProbe {
    public static final boolean ENABLED = TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED
            || TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED;
    private static final boolean ORACLE_ENABLED =
            TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED;

    private static final Object LOCK = new Object();
    private static final ThreadLocal<Boolean> TERRAIN_DRAW_SCOPE = new ThreadLocal<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final int MAX_PENDING_PROBES = 8;
    private static final long MAX_PENDING_BYTES = 128L * 1024L * 1024L;
    private static long pendingBytes;
    /** Java owns one retain on the latest native generation-owned static scene. */
    private static MemorySegment persistentSceneOwner = MemorySegment.NULL;
    private static long persistentSceneGeneration = -1L;
    private static int persistentSceneCandidateCount;
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
    private static long compactedCount;
    private static long compactionDispatchCount;
    private static long compactionProducedCount;
    private static long compactionFallbackCount;
    private static long compactionMismatchOracleCount;
    private static long lastCompletedEpoch = -1L;

    private TerrainGpuVisibilityProbe() {
    }

    public static boolean enabled() {
        return ENABLED && (ORACLE_ENABLED || TerrainIcbRuntimeAdmission.gpuIcbAdmitted());
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

    /** True while Sodium is issuing the bounded terrain indirect submission. */
    public static boolean inTerrainDrawScope() {
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
            if (!MetalNativeBridge.isNullHandle(persistentSceneOwner)) {
                releaseQuietly(persistentSceneOwner);
            }
            persistentSceneOwner = MemorySegment.NULL;
            persistentSceneGeneration = -1L;
            persistentSceneCandidateCount = 0;
            pendingBytes = 0L;
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
            compactedCount = 0L;
            compactionDispatchCount = 0L;
            compactionProducedCount = 0L;
            compactionFallbackCount = 0L;
            compactionMismatchOracleCount = 0L;
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
        // The fused shipping lane performs visibility inside ICB authoring and
        // therefore must not create an intermediate bitset/probe encoder. Keep
        // the explicit diagnostic oracle untouched so it can still falsify the
        // fused decision on hardware when both switches are enabled.
        if (!enabled() || (TerrainCandidateSnapshot.FUSED_VISIBLE_GPU_ICB_ENABLED && !ORACLE_ENABLED)) {
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
            final long allocationBytes;
            try {
                allocationBytes = pendingAllocationBytes(count, ORACLE_ENABLED);
            } catch (RuntimeException overflow) {
                lastAttemptedEpoch = snapshot.epoch();
                attemptedCount++;
                fallbackCount++;
                if (ORACLE_ENABLED) { compactionFallbackCount++; }
                return false;
            }

            // These are non-mutating rejection paths. Marking the epoch avoids
            // repeating the same failed attempt for every layer draw, while a
            // missing current encoder below deliberately remains retryable.
            if (count == 0 || count > TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES
                    || !device.metal4Available() || !device.metal4MainRenderer()
                    || !MetalNativeBridge.terrainVisibilityProbeAvailable()
                    || PENDING.size() >= MAX_PENDING_PROBES
                    || pendingBytes > MAX_PENDING_BYTES - allocationBytes) {
                lastAttemptedEpoch = snapshot.epoch();
                attemptedCount++;
                fallbackCount++;
                if (ORACLE_ENABLED) { compactionFallbackCount++; }
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
                    Oracle oracle = null;
                    MemorySegment probe;
                    try {
                        if (!ORACLE_ENABLED && MetalNativeBridge.terrainPersistentVisibilitySceneAvailable()) {
                            if (!ensurePersistentSceneLocked(snapshot, device, arena)) {
                                fallbackCount++;
                                return true;
                            }
                            MemorySegment packedFrame = snapshot.packGpuVisibilitySceneFrame(arena);
                            probe = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilitySceneProbe(
                                    retainedEncoder,
                                    device.metalDeviceHandle(),
                                    persistentSceneOwner,
                                    packedFrame,
                                    snapshot.sceneGeneration(),
                                    snapshot.epoch()
                            );
                        } else {
                            MemorySegment packedCandidates = snapshot.packGpuVisibilityCandidates(arena);
                            MemorySegment packedMatrix = snapshot.packGpuVisibilityMatrix(arena);
                            if (ORACLE_ENABLED) {
                                oracle = oracleForPackedSnapshot(packedCandidates, packedMatrix, count);
                            }
                            probe = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityProbe(
                                    retainedEncoder,
                                    device.metalDeviceHandle(),
                                    packedCandidates,
                                    packedMatrix,
                                    count,
                                    snapshot.epoch()
                            );
                        }
                    } catch (RuntimeException invalidInput) {
                        fallbackCount++;
                        if (ORACLE_ENABLED) { compactionFallbackCount++; }
                        return true;
                    }
                    if (MetalNativeBridge.isNullHandle(probe)) {
                        fallbackCount++;
                        if (ORACLE_ENABLED) { compactionFallbackCount++; }
                        return true;
                    }
                    PENDING.add(oracle != null
                            ? new Pending(
                            probe,
                            snapshot.epoch(),
                            count,
                            TerrainCandidateSnapshot.gpuVisibilityWordCount(count),
                            oracle.expectedWords(),
                            oracle.visibleCount(),
                            oracle.uncertainCount(),
                            oracle.compactedIndices(),
                            true
                    )
                            : Pending.withoutOracle(
                            probe, snapshot.epoch(), count,
                            TerrainCandidateSnapshot.gpuVisibilityWordCount(count)
                    ));
                    pendingBytes += allocationBytes;
                    dispatchCount++;
                    if (ORACLE_ENABLED) { compactionDispatchCount++; }
                    return true;
                }
            } catch (RuntimeException failure) {
                fallbackCount++;
                if (ORACLE_ENABLED) { compactionFallbackCount++; }
                return true;
            } finally {
                MetalNativeBridge.metallum_release_object(retainedEncoder);
            }
        }
    }

    /**
     * Returns the unique in-flight native probe owner for this exact producer
     * epoch. This never polls or copies GPU results to the CPU. The lookup is
     * synchronized with reset/release, then the typed native retain is made
     * while {@link #LOCK} is held; the returned temporary handle is owned by
     * the caller and must be released exactly once after native ICB authoring.
     */
    static MemorySegment ownerForEpoch(final long epoch, final int expectedCandidateCount) {
        if (!ENABLED || epoch < 0L || expectedCandidateCount <= 0) {
            return MemorySegment.NULL;
        }
        synchronized (LOCK) {
            MemorySegment found = MemorySegment.NULL;
            for (Pending pending : PENDING) {
                if (pending.epoch() != epoch
                        || pending.candidateCount() != expectedCandidateCount) {
                    continue;
                }
                if (!MetalNativeBridge.isNullHandle(found)) {
                    return MemorySegment.NULL;
                }
                found = pending.probe();
            }
            return MetalNativeBridge.terrainVisibilityProbeRetain(found);
        }
    }

    /**
     * Returns an owned temporary handle to the exact generation-owned scene
     * for fused ICB authoring. The typed retain is made while {@link #LOCK} is
     * held, so reset cannot release the scene between lookup and retain. The
     * caller must release this temporary exactly once after native authoring;
     * the native ICB owner takes its own strong reference before then.
     */
    static MemorySegment persistentSceneForFused(
            final TerrainCandidateSnapshot snapshot,
            final MetalDevice device
    ) {
        if (!TerrainCandidateSnapshot.FUSED_VISIBLE_GPU_ICB_ENABLED
                || snapshot == null || device == null
                || !MetalNativeBridge.terrainFusedVisibleGpuIcbAvailable()) {
            return MemorySegment.NULL;
        }
        synchronized (LOCK) {
            try (Arena arena = Arena.ofConfined()) {
                if (!ensurePersistentSceneLocked(snapshot, device, arena)) {
                    return MemorySegment.NULL;
                }
                if (persistentSceneGeneration != snapshot.sceneGeneration()
                        || persistentSceneCandidateCount != snapshot.candidates().size()) {
                    return MemorySegment.NULL;
                }
                return MetalNativeBridge.terrainVisibilitySceneRetain(persistentSceneOwner);
            } catch (RuntimeException failure) {
                return MemorySegment.NULL;
            }
        }
    }

    private static boolean ensurePersistentSceneLocked(
            final TerrainCandidateSnapshot snapshot,
            final MetalDevice device,
            final Arena arena
    ) {
        int count = snapshot.candidates().size();
        if (!MetalNativeBridge.isNullHandle(persistentSceneOwner)
                && persistentSceneGeneration == snapshot.sceneGeneration()
                && persistentSceneCandidateCount == count) {
            return true;
        }
        MemorySegment packedScene = snapshot.packGpuVisibilitySceneCandidates(arena);
        MemorySegment replacement = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityScene(
                device.metalDeviceHandle(),
                packedScene,
                count,
                snapshot.sceneGeneration()
        );
        if (MetalNativeBridge.isNullHandle(replacement)) {
            return false;
        }
        MemorySegment previous = persistentSceneOwner;
        persistentSceneOwner = replacement;
        persistentSceneGeneration = snapshot.sceneGeneration();
        persistentSceneCandidateCount = count;
        if (!MetalNativeBridge.isNullHandle(previous)) {
            releaseQuietly(previous);
        }
        return true;
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
                    compactedCount,
                    compactionDispatchCount,
                    compactionProducedCount,
                    compactionFallbackCount,
                    compactionMismatchOracleCount,
                    lastCompletedEpoch
            );
        }
    }

    private static void pollCompletedLocked() {
        for (Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            // The visible-ICB lane needs this owner only until native ICB
            // authoring retains it. After GPU completion, query one status word
            // and release the Java retain: do not allocate an Arena or copy the
            // visibility bitset/compacted list back to the CPU.
            if (!pending.oracleEnabled()) {
                int status = MetalNativeBridge.terrainVisibilityProbeStatus(pending.probe());
                if (status == 0) {
                    continue;
                }
                if (status < 0) {
                    fallbackCount++;
                }
                lastCompletedEpoch = Math.max(lastCompletedEpoch, pending.epoch());
                pendingBytes = Math.max(0L, pendingBytes - pendingAllocationBytes(pending.candidateCount(), pending.oracleEnabled()));
                releaseQuietly(pending.probe());
                iterator.remove();
                continue;
            }
            boolean remove = false;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment epoch = arena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment visible = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment uncertain = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment wordCount = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment compacted = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment bitset = arena.allocate(
                        Math.max(1L, (long) pending.wordCount() * Integer.BYTES), 4
                );
                MemorySegment compactedIndices = arena.allocate(
                        Math.max(1L, (long) pending.candidateCount() * Integer.BYTES), 4
                );
                int status = MetalNativeBridge.terrainVisibilityProbePoll(
                        pending.probe(),
                        epoch,
                        visible,
                        uncertain,
                        wordCount,
                        bitset,
                        pending.wordCount(),
                        compacted,
                        compactedIndices,
                        pending.candidateCount()
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
                    int completedCompacted = compacted.get(ValueLayout.JAVA_INT, 0);
                    int[] actualWords = new int[pending.wordCount()];
                    for (int index = 0; index < actualWords.length; index++) {
                        actualWords[index] = bitset.get(
                                ValueLayout.JAVA_INT, (long) index * Integer.BYTES
                        );
                    }
                    int[] actualCompacted = new int[completedCompacted < 0
                            || completedCompacted > pending.candidateCount() ? 0 : completedCompacted];
                    for (int index = 0; index < actualCompacted.length; index++) {
                        actualCompacted[index] = compactedIndices.get(
                                ValueLayout.JAVA_INT, (long) index * Integer.BYTES
                        );
                    }
                    boolean falseNegative = pending.oracleEnabled() && missingExpectedBits(
                            pending.expectedWords(), actualWords
                    );
                    if (falseNegative) {
                        falseNegativeOracleCount++;
                    }
                    boolean compactionMismatch = pending.oracleEnabled() && !compactionMatches(
                            pending.expectedCompactedIndices(), completedCompacted, actualCompacted
                    );
                    if (compactionMismatch) {
                        compactionMismatchOracleCount++;
                    }
                    CompletionDisposition disposition = classifyCompletion(
                            pending,
                            completedEpoch,
                            completedWordCount,
                            completedVisible,
                            completedUncertain,
                            actualWords,
                            completedCompacted,
                            actualCompacted,
                            lastPublishedEpoch
                    );
                    if (completedEpoch >= lastCompletedEpoch) {
                        lastCompletedEpoch = completedEpoch;
                        lastVisibleCount = Integer.toUnsignedLong(completedVisible);
                        lastUncertainCount = Integer.toUnsignedLong(completedUncertain);
                        compactedCount = Integer.toUnsignedLong(completedCompacted);
                    }
                    if (disposition == CompletionDisposition.PUBLISH) {
                        TerrainCandidateRegistry.publishVisibilityResult(
                                new TerrainCandidateRegistry.VisibilityResult(
                                        completedEpoch,
                                        pending.candidateCount(),
                                        completedVisible,
                                        completedUncertain,
                                        actualWords,
                                        completedCompacted,
                                        actualCompacted,
                                        false
                                )
                        );
                        lastPublishedEpoch = completedEpoch;
                        producedCount++;
                        if (ORACLE_ENABLED) { compactionProducedCount++; }
                    } else if (disposition == CompletionDisposition.FALLBACK) {
                        // A stale/malformed/mismatching result never becomes a
                        // culling decision. If it still belongs to the current
                        // snapshot, publish an explicit all-visible fallback so
                        // a future consumer cannot accidentally interpret an
                        // old or partial bitset as authoritative.
                        fallbackCount++;
                        if (ORACLE_ENABLED) { compactionFallbackCount++; }
                        publishCurrentEpochFallbackLocked(pending);
                    }
                } else {
                    fallbackCount++;
                    if (ORACLE_ENABLED) { compactionFallbackCount++; }
                    publishCurrentEpochFallbackLocked(pending);
                }
            } catch (RuntimeException failure) {
                fallbackCount++;
                if (ORACLE_ENABLED) { compactionFallbackCount++; }
                publishCurrentEpochFallbackLocked(pending);
                remove = true;
            } finally {
                if (remove) {
                    pendingBytes = Math.max(0L, pendingBytes - pendingAllocationBytes(pending.candidateCount(), pending.oracleEnabled()));
                    releaseQuietly(pending.probe());
                    iterator.remove();
                }
            }
        }
    }

    private static void publishCurrentEpochFallbackLocked(final Pending pending) {
        TerrainCandidateSnapshot current = TerrainCandidateRegistry.latestSnapshot();
        if (current == null || !shouldPublishCurrentEpochFallback(
                pending,
                current.epoch(),
                current.candidates().size(),
                lastPublishedEpoch
        )) {
            return;
        }
        TerrainCandidateRegistry.publishVisibilityResult(
                new TerrainCandidateRegistry.VisibilityResult(
                        pending.epoch(),
                        pending.candidateCount(),
                        pending.candidateCount(),
                        pending.candidateCount(),
                        allVisibleWords(pending.candidateCount()),
                        pending.candidateCount(),
                        allVisibleIndices(pending.candidateCount()),
                        true
                )
        );
        lastPublishedEpoch = pending.epoch();
    }

    static boolean shouldPublishCurrentEpochFallback(
            final Pending pending,
            final long currentEpoch,
            final int currentCandidateCount,
            final long lastPublishedEpoch
    ) {
        return pending != null
                && currentEpoch == pending.epoch()
                && currentCandidateCount == pending.candidateCount()
                && pending.epoch() > lastPublishedEpoch;
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
            final int completedCompacted,
            final int[] actualCompacted,
            final long lastPublishedEpoch
    ) {
        boolean valid = completedEpoch == pending.epoch()
                && completedWordCount == pending.wordCount()
                && completedCompacted >= 0
                && completedCompacted <= pending.candidateCount()
                && actualCompacted != null
                && actualCompacted.length == completedCompacted
                && Integer.toUnsignedLong(completedVisible) <= pending.candidateCount()
                && Integer.toUnsignedLong(completedUncertain) <= pending.candidateCount();
        if (valid && pending.oracleEnabled()) {
            boolean countersMatch = Integer.toUnsignedLong(completedVisible)
                    == pending.expectedVisibleCount()
                    && Integer.toUnsignedLong(completedUncertain)
                    == pending.expectedUncertainCount();
            valid = !missingExpectedBits(pending.expectedWords(), actualWords)
                    && countersMatch
                    && compactionMatches(
                    pending.expectedCompactedIndices(), completedCompacted, actualCompacted
            );
        }
        if (!valid) {
            return CompletionDisposition.FALLBACK;
        }
        return completedEpoch > lastPublishedEpoch
                ? CompletionDisposition.PUBLISH
                : CompletionDisposition.IGNORE;
    }

    /** Compatibility overload retained for the bitset-only contract tests. */
    static CompletionDisposition classifyCompletion(
            final Pending pending,
            final long completedEpoch,
            final int completedWordCount,
            final int completedVisible,
            final int completedUncertain,
            final int[] actualWords,
            final long lastPublishedEpoch
    ) {
        int[] compacted = compactIndicesForWords(pending.candidateCount(), actualWords);
        return classifyCompletion(
                pending,
                completedEpoch,
                completedWordCount,
                completedVisible,
                completedUncertain,
                actualWords,
                compacted.length,
                compacted,
                lastPublishedEpoch
        );
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
        int[] compactedIndices = compactIndicesForWords(count, expectedWords);
        return new Oracle(expectedWords, visibleCount, uncertainCount, compactedIndices);
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

    static boolean compactionMatches(
            final int[] expected,
            final int actualCount,
            final int[] actual
    ) {
        if (expected == null || actual == null || actualCount < 0
                || actualCount != actual.length || expected.length != actualCount) {
            return false;
        }
        int previous = -1;
        for (int index = 0; index < actual.length; index++) {
            int candidate = actual[index];
            if (candidate <= previous || candidate != expected[index]) {
                return false;
            }
            previous = candidate;
        }
        return true;
    }

    /** Deterministic CPU oracle for the GPU's stable bitset-to-index scatter. */
    static int[] compactIndicesForWords(final int candidateCount, final int[] words) {
        if (candidateCount < 0
                || candidateCount > TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES
                || words == null
                || words.length != TerrainCandidateSnapshot.gpuVisibilityWordCount(candidateCount)) {
            throw new IllegalArgumentException("Invalid terrain visibility bitset");
        }
        if ((candidateCount & 31) != 0 && words.length > 0) {
            int tailMask = -1 >>> (32 - (candidateCount & 31));
            if ((words[words.length - 1] & ~tailMask) != 0) {
                throw new IllegalArgumentException("Terrain visibility bitset has non-zero tail bits");
            }
        }
        int visible = 0;
        for (int index = 0; index < candidateCount; index++) {
            if ((words[index >>> 5] & (1 << (index & 31))) != 0) {
                visible++;
            }
        }
        int[] compacted = new int[visible];
        int output = 0;
        for (int index = 0; index < candidateCount; index++) {
            if ((words[index >>> 5] & (1 << (index & 31))) != 0) {
                compacted[output++] = index;
            }
        }
        return compacted;
    }

    private static long pendingAllocationBytes(final int candidateCount) {
        return pendingAllocationBytes(candidateCount, true);
    }

    private static long pendingAllocationBytes(final int candidateCount, final boolean compact) {
        int words = TerrainCandidateSnapshot.gpuVisibilityWordCount(candidateCount);
        int blocks = (candidateCount + 255) >>> 8;
        int groups = (blocks + 255) >>> 8;
        long bytes = TerrainCandidateSnapshot.gpuVisibilityCandidateBytes(candidateCount);
        bytes = Math.addExact(bytes, TerrainCandidateSnapshot.GPU_VISIBILITY_MATRIX_BYTES);
        bytes = Math.addExact(bytes, Math.multiplyExact((long) words, Integer.BYTES));
        bytes = Math.addExact(bytes, 2L * Integer.BYTES);
        if (compact) {
            bytes = Math.addExact(bytes, Math.multiplyExact((long) candidateCount, Integer.BYTES));
            bytes = Math.addExact(bytes, Math.multiplyExact((long) blocks, 2L * Integer.BYTES));
            bytes = Math.addExact(bytes, Math.multiplyExact((long) groups, 2L * Integer.BYTES));
            bytes = Math.addExact(bytes, Math.multiplyExact((long) candidateCount, Integer.BYTES));
        } else {
            // Six compaction-only scratch/output buffers remain as one-word
            // placeholders so the stable argument-table layout stays unchanged.
            bytes = Math.addExact(bytes, 6L * Integer.BYTES);
        }
        bytes = Math.addExact(bytes, Integer.BYTES);
        return Math.addExact(bytes, 3L * Integer.BYTES);
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

    private static int[] allVisibleIndices(final int count) {
        int[] indices = new int[count];
        for (int index = 0; index < count; index++) {
            indices[index] = index;
        }
        return indices;
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
            long compactedCount,
            long compactionDispatchCount,
            long compactionProducedCount,
            long compactionFallbackCount,
            long compactionMismatchOracleCount,
            long lastCompletedEpoch
    ) {
        public Telemetry(
                final boolean enabled,
                final long candidateCount,
                final long visibleCount,
                final long uncertainCount,
                final long attemptedCount,
                final long dispatchCount,
                final long producedCount,
                final long fallbackCount,
                final long falseNegativeOracleCount,
                final long lastCompletedEpoch
        ) {
            this(
                    enabled,
                    candidateCount,
                    visibleCount,
                    uncertainCount,
                    attemptedCount,
                    dispatchCount,
                    producedCount,
                    fallbackCount,
                    falseNegativeOracleCount,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    lastCompletedEpoch
            );
        }
    }

    private record Oracle(
            int[] expectedWords,
            int visibleCount,
            int uncertainCount,
            int[] compactedIndices
    ) {
        private Oracle {
            expectedWords = expectedWords.clone();
            compactedIndices = compactedIndices.clone();
        }

        @Override
        public int[] expectedWords() {
            return expectedWords.clone();
        }

        @Override
        public int[] compactedIndices() {
            return compactedIndices.clone();
        }
    }

    record Pending(
            MemorySegment probe,
            long epoch,
            int candidateCount,
            int wordCount,
            int[] expectedWords,
            int expectedVisibleCount,
            int expectedUncertainCount,
            int[] expectedCompactedIndices,
            boolean oracleEnabled
    ) {
        Pending {
            expectedWords = expectedWords.clone();
            expectedCompactedIndices = expectedCompactedIndices.clone();
        }

        Pending(
                final MemorySegment probe,
                final long epoch,
                final int candidateCount,
                final int wordCount,
                final int[] expectedWords,
                final int expectedVisibleCount,
                final int expectedUncertainCount,
                final int[] expectedCompactedIndices
        ) {
            this(probe, epoch, candidateCount, wordCount, expectedWords,
                    expectedVisibleCount, expectedUncertainCount, expectedCompactedIndices, true);
        }

        Pending(
                final MemorySegment probe,
                final long epoch,
                final int candidateCount,
                final int wordCount,
                final int[] expectedWords,
                final int expectedVisibleCount,
                final int expectedUncertainCount
        ) {
            this(
                    probe,
                    epoch,
                    candidateCount,
                    wordCount,
                    expectedWords,
                    expectedVisibleCount,
                    expectedUncertainCount,
                    compactIndicesForWords(candidateCount, expectedWords),
                    true
            );
        }

        static Pending withoutOracle(
                final MemorySegment probe,
                final long epoch,
                final int candidateCount,
                final int wordCount
        ) {
            return new Pending(
                    probe, epoch, candidateCount, wordCount,
                    new int[0], -1, -1, new int[0], false
            );
        }

        @Override
        public int[] expectedWords() {
            return expectedWords.clone();
        }

        @Override
        public int[] expectedCompactedIndices() {
            return expectedCompactedIndices.clone();
        }
    }
}
