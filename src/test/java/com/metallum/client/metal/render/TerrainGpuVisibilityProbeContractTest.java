package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainGpuVisibilityProbeContractTest {
    @Test
    void defaultFeatureIsOff() {
        assertFalse(TerrainGpuVisibilityProbe.enabled(),
                "the value-only terrain visibility probe must require explicit opt-in");
    }

    @Test
    void invalidOrUnavailableNativeProbeInputsFallbackBeforeDispatch() {
        assertTrue(MetalNativeBridge.isNullHandle(
                MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityProbe(
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, 0, 0L
                )
        ));
        assertEquals(0, MetalNativeBridge.terrainVisibilityProbePoll(
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, -1,
                MemorySegment.NULL, MemorySegment.NULL, -1
        ));
    }

    @Test
    void packsCameraRelativeAabbAtFixed32ByteStrideAndColumnMajorMatrix() {
        TerrainCandidateSnapshot snapshot = snapshot(
                new TerrainCandidateSnapshot.CameraPosition(100.0, -2.0, 50.0),
                new TerrainCandidateSnapshot.VisibilityTransform(
                        1, 2, 3, 4,
                        5, 6, 7, 8,
                        9, 10, 11, 12,
                        13, 14, 15, 16
                ),
                new TerrainCandidateSnapshot.Aabb(100.0, -2.0, 50.0, 116.0, 14.0, 66.0)
        );

        try (Arena arena = Arena.ofConfined()) {
            var candidates = snapshot.packGpuVisibilityCandidates(arena);
            assertEquals(TerrainCandidateSnapshot.GPU_VISIBILITY_CANDIDATE_STRIDE_BYTES, candidates.byteSize());
            assertEquals(0.0F, candidates.get(ValueLayout.JAVA_FLOAT, 0));
            assertEquals(16.0F, candidates.get(ValueLayout.JAVA_FLOAT, 12));
            assertEquals(16.0F, candidates.get(ValueLayout.JAVA_FLOAT, 24));

            var matrix = snapshot.packGpuVisibilityMatrix(arena);
            assertEquals(64L, matrix.byteSize());
            assertEquals(1.0F, matrix.get(ValueLayout.JAVA_FLOAT, 0));
            assertEquals(2.0F, matrix.get(ValueLayout.JAVA_FLOAT, 4));
            assertEquals(5.0F, matrix.get(ValueLayout.JAVA_FLOAT, 16));
            assertEquals(16.0F, matrix.get(ValueLayout.JAVA_FLOAT, 60));
        }
    }

    @Test
    void narrowsAabbBoundsOutwardAndKeepsBitsetCapacityBounded() {
        double exactMin = 0.1d;
        double exactMax = 0.2d;
        TerrainCandidateSnapshot snapshot = snapshot(
                new TerrainCandidateSnapshot.CameraPosition(0.0, 0.0, 0.0),
                identity(),
                new TerrainCandidateSnapshot.Aabb(exactMin, exactMin, exactMin,
                        exactMax, exactMax, exactMax)
        );

        try (Arena arena = Arena.ofConfined()) {
            var packed = snapshot.packGpuVisibilityCandidates(arena);
            float packedMin = packed.get(ValueLayout.JAVA_FLOAT, 0);
            float packedMax = packed.get(ValueLayout.JAVA_FLOAT, 12);
            assertTrue((double) packedMin <= exactMin);
            assertTrue((double) packedMax >= exactMax);
        }

        assertEquals(0, TerrainCandidateSnapshot.gpuVisibilityWordCount(0));
        assertEquals(1, TerrainCandidateSnapshot.gpuVisibilityWordCount(1));
        assertEquals(1, TerrainCandidateSnapshot.gpuVisibilityWordCount(32));
        assertEquals(2, TerrainCandidateSnapshot.gpuVisibilityWordCount(33));
        assertThrows(IllegalArgumentException.class, () ->
                TerrainCandidateSnapshot.gpuVisibilityWordCount(
                        TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES + 1
                ));
        assertThrows(IllegalArgumentException.class, () ->
                TerrainCandidateSnapshot.gpuVisibilityCandidateBytes(
                        TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES + 1
                ));
        assertArrayEquals(new int[0], TerrainGpuVisibilityProbe.compactIndicesForWords(
                TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES,
                new int[TerrainCandidateSnapshot.gpuVisibilityWordCount(
                        TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES
                )]
        ));
        assertThrows(IllegalArgumentException.class, () ->
                TerrainGpuVisibilityProbe.compactIndicesForWords(33, new int[]{0, 2}));
    }

    @Test
    void cameraRelativeOverflowAndNonFiniteMatrixFailClosedBeforeDispatch() {
        TerrainCandidateSnapshot overflow = snapshot(
                new TerrainCandidateSnapshot.CameraPosition(-Double.MAX_VALUE, 0.0, 0.0),
                identity(),
                new TerrainCandidateSnapshot.Aabb(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        );
        assertThrows(IllegalArgumentException.class, () -> {
            try (Arena arena = Arena.ofConfined()) {
                overflow.packGpuVisibilityCandidates(arena);
            }
        });

        TerrainCandidateSnapshot nonFiniteMatrix = snapshot(
                new TerrainCandidateSnapshot.CameraPosition(0.0, 0.0, 0.0),
                new TerrainCandidateSnapshot.VisibilityTransform(
                        Float.NaN, 0, 0, 0,
                        0, 1, 0, 0,
                        0, 0, 1, 0,
                        0, 0, 0, 1
                ),
                new TerrainCandidateSnapshot.Aabb(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        );
        assertThrows(IllegalArgumentException.class, () -> {
            try (Arena arena = Arena.ofConfined()) {
                nonFiniteMatrix.packGpuVisibilityMatrix(arena);
            }
        });
    }

    @Test
    void cpuReferenceUsesConservativeSixPlaneAndWContracts() {
        TerrainCandidateSnapshot.VisibilityTransform identity = identity();
        var emptyBoundary = TerrainCandidateSnapshot.referenceDecision(
                identity, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F
        );
        assertTrue(emptyBoundary.visible(), "a degenerate boundary AABB must not be culled");
        assertFalse(emptyBoundary.uncertain(), "finite boundary AABB should be certain");
        assertTrue(TerrainCandidateSnapshot.referenceDecision(identity, -0.5F, -0.5F, -0.5F,
                0.5F, 0.5F, 0.5F).visible());
        assertTrue(!TerrainCandidateSnapshot.referenceDecision(identity, -3.0F, -0.1F, -0.1F,
                -2.0F, 0.1F, 0.1F).visible());
        assertTrue(!TerrainCandidateSnapshot.referenceDecision(identity, -0.1F, -0.1F, -3.0F,
                0.1F, 0.1F, -2.0F).visible());
        assertTrue(!TerrainCandidateSnapshot.referenceDecision(identity, -0.1F, -0.1F, 2.0F,
                0.1F, 0.1F, 3.0F).visible());

        TerrainCandidateSnapshot.VisibilityTransform negativeW = new TerrainCandidateSnapshot.VisibilityTransform(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, -1
        );
        var uncertainW = TerrainCandidateSnapshot.referenceDecision(
                negativeW, -0.1F, -0.1F, -0.1F, 0.1F, 0.1F, 0.1F
        );
        assertTrue(uncertainW.visible());
        assertTrue(uncertainW.uncertain());

        var uncertainNaN = TerrainCandidateSnapshot.referenceDecision(
                identity, Float.NaN, 0, 0, 1, 1, 1
        );
        assertTrue(uncertainNaN.visible());
        assertTrue(uncertainNaN.uncertain());
    }

    @Test
    void visibilityResultRequiresExactCapacityAndCanRepresentAllVisibleFallback() {
        var fallback = new TerrainCandidateRegistry.VisibilityResult(
                7L, 33, 33, 33, new int[]{-1, 1}, true
        );
        assertTrue(fallback.fallback());
        assertEquals(2, fallback.visibilityWords().length);
        assertThrows(IllegalArgumentException.class, () ->
                new TerrainCandidateRegistry.VisibilityResult(7L, 33, 1, 0, new int[]{-1})
        );
        assertThrows(IllegalArgumentException.class, () ->
                new TerrainCandidateRegistry.VisibilityResult(
                        7L, 33, 2, 0, new int[]{1, 1}, 2, new int[]{0, 31}, false
                )
        );
    }

    @Test
    void snapshotEpochIsStableAndValueOnly() {
        TerrainCandidateSnapshot snapshot = new TerrainCandidateSnapshot(
                42L,
                new TerrainCandidateSnapshot.CameraPosition(1.0, 2.0, 3.0),
                identity(),
                List.of()
        );
        assertEquals(42L, snapshot.epoch());
        assertEquals(0, snapshot.candidates().size());
    }

    @Test
    void completedEpochPublishesAcrossFrameAdvanceAndLateResultCannotOverwriteNewer() {
        // Dispatching epoch N and capturing N+1 before the GPU completes N is
        // the normal command-buffer cadence. Completion policy deliberately
        // does not require the registry's latest snapshot to still be N: the
        // result is value-only diagnostic data stamped with its own epoch.
        var pendingN = pending(10L);
        assertEquals(
                TerrainGpuVisibilityProbe.CompletionDisposition.PUBLISH,
                TerrainGpuVisibilityProbe.classifyCompletion(
                        pendingN, 10L, 1, 1, 0, new int[]{1}, -1L
                )
        );

        // Once N+1 has published, a valid but late N completion is ignored and
        // cannot move the monotonic publication watermark backwards.
        var pendingN1 = pending(11L);
        assertEquals(
                TerrainGpuVisibilityProbe.CompletionDisposition.PUBLISH,
                TerrainGpuVisibilityProbe.classifyCompletion(
                        pendingN1, 11L, 1, 1, 0, new int[]{1}, 10L
                )
        );
        assertEquals(
                TerrainGpuVisibilityProbe.CompletionDisposition.IGNORE,
                TerrainGpuVisibilityProbe.classifyCompletion(
                        pendingN, 10L, 1, 1, 0, new int[]{1}, 11L
                )
        );
    }

    @Test
    void cpuCompactionOracleIsStableAcrossBlockAndTailBoundaries() {
        int[] counts = {1, 31, 32, 33, 255, 256, 257, 8191, 8192, 8193};
        for (int candidateCount : counts) {
            int[] allZero = new int[TerrainCandidateSnapshot.gpuVisibilityWordCount(candidateCount)];
            assertArrayEquals(new int[0], TerrainGpuVisibilityProbe.compactIndicesForWords(
                    candidateCount, allZero));

            int[] allOne = new int[allZero.length];
            java.util.Arrays.fill(allOne, -1);
            if ((candidateCount & 31) != 0) {
                allOne[allOne.length - 1] = -1 >>> (32 - (candidateCount & 31));
            }
            int[] expectedAll = new int[candidateCount];
            for (int index = 0; index < candidateCount; index++) {
                expectedAll[index] = index;
            }
            assertArrayEquals(expectedAll, TerrainGpuVisibilityProbe.compactIndicesForWords(
                    candidateCount, allOne));

            int[] sparse = new int[allZero.length];
            for (int index : new int[]{0, Math.min(31, candidateCount - 1),
                    Math.min(32, candidateCount - 1), candidateCount - 1}) {
                sparse[index >>> 5] |= 1 << (index & 31);
            }
            int[] compacted = TerrainGpuVisibilityProbe.compactIndicesForWords(candidateCount, sparse);
            int[] expectedSparse = referenceIndices(candidateCount, sparse);
            assertArrayEquals(expectedSparse, compacted);
            for (int candidate : compacted) {
                assertTrue((sparse[candidate >>> 5] & (1 << (candidate & 31))) != 0);
            }
        }

        Random random = new Random(0x4d4554414cL);
        for (int trial = 0; trial < 64; trial++) {
            int candidateCount = 1 + random.nextInt(8193);
            int[] words = new int[TerrainCandidateSnapshot.gpuVisibilityWordCount(candidateCount)];
            for (int index = 0; index < words.length; index++) {
                words[index] = random.nextInt();
            }
            if ((candidateCount & 31) != 0) {
                words[words.length - 1] &= -1 >>> (32 - (candidateCount & 31));
            }
            int[] compacted = TerrainGpuVisibilityProbe.compactIndicesForWords(candidateCount, words);
            assertArrayEquals(referenceIndices(candidateCount, words), compacted);
            assertArrayEquals(compacted, TerrainGpuVisibilityProbe.compactIndicesForWords(
                    candidateCount, words));
        }
    }

    @Test
    void compactedMismatchFailsOpenInsteadOfPublishing() {
        TerrainGpuVisibilityProbe.Pending pending = new TerrainGpuVisibilityProbe.Pending(
                MemorySegment.NULL, 3L, 33, 2, new int[]{1, 1}, 2, 0,
                new int[]{0, 32}
        );
        assertEquals(
                TerrainGpuVisibilityProbe.CompletionDisposition.FALLBACK,
                TerrainGpuVisibilityProbe.classifyCompletion(
                        pending, 3L, 2, 2, 0, new int[]{1, 1}, 2,
                        new int[]{0, 31}, -1L
                )
        );
    }

    @Test
    void failedCompletionFallbackRequiresCurrentMatchingEpochAndNeverRewinds() {
        TerrainGpuVisibilityProbe.Pending pending = new TerrainGpuVisibilityProbe.Pending(
                MemorySegment.NULL, 3L, 33, 2, new int[]{1, 1}, 2, 0,
                new int[]{0, 32}
        );
        assertTrue(TerrainGpuVisibilityProbe.shouldPublishCurrentEpochFallback(
                pending, 3L, 33, 2L));
        assertFalse(TerrainGpuVisibilityProbe.shouldPublishCurrentEpochFallback(
                pending, 3L, 32, 2L));
        assertFalse(TerrainGpuVisibilityProbe.shouldPublishCurrentEpochFallback(
                pending, 2L, 33, 2L));
        assertFalse(TerrainGpuVisibilityProbe.shouldPublishCurrentEpochFallback(
                pending, 3L, 33, 3L));
    }

    private static TerrainGpuVisibilityProbe.Pending pending(long epoch) {
        return new TerrainGpuVisibilityProbe.Pending(
                MemorySegment.NULL,
                epoch,
                1,
                1,
                new int[]{1},
                1,
                0
        );
    }

    private static int[] referenceIndices(final int candidateCount, final int[] words) {
        int count = 0;
        for (int candidate = 0; candidate < candidateCount; candidate++) {
            if ((words[candidate >>> 5] & (1 << (candidate & 31))) != 0) {
                count++;
            }
        }
        int[] indices = new int[count];
        int output = 0;
        for (int candidate = 0; candidate < candidateCount; candidate++) {
            if ((words[candidate >>> 5] & (1 << (candidate & 31))) != 0) {
                indices[output++] = candidate;
            }
        }
        return indices;
    }

    private static TerrainCandidateSnapshot snapshot(
            TerrainCandidateSnapshot.CameraPosition camera,
            TerrainCandidateSnapshot.VisibilityTransform transform,
            TerrainCandidateSnapshot.Aabb aabb
    ) {
        TerrainCandidateSnapshot.AllocationIdentity vertex =
                new TerrainCandidateSnapshot.AllocationIdentity(new Object(), 0, 16, 1);
        TerrainCandidateSnapshot.AllocationIdentity index =
                new TerrainCandidateSnapshot.AllocationIdentity(new Object(), 0, 16, 1);
        TerrainCandidateSnapshot.SectionIdentity section =
                new TerrainCandidateSnapshot.SectionIdentity(0, 0, 0, 0, 0, 0, 0);
        return new TerrainCandidateSnapshot(
                1L,
                camera,
                transform,
                List.of(new TerrainCandidateSnapshot.Candidate(
                        section, aabb, TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                        false, vertex, index
                ))
        );
    }

    private static TerrainCandidateSnapshot.VisibilityTransform identity() {
        return new TerrainCandidateSnapshot.VisibilityTransform(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        );
    }
}
