package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure contract tests for producer-side candidate indexed draw materialization. */
final class TerrainCandidateDrawMaterializerContractTest {
    @Test
    void localMaterializationPublishesEveryNonEmptyFacingWithSodiumOffsets() {
        Object vertexObject = new Object();
        Object indexObject = new Object();
        List<TerrainCandidateSnapshot.IndexedDrawRecord> draws = materialize(
                TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                true,
                0x1000L,
                100L,
                50L,
                new long[]{4, 0, 8, 0, 4, 0, 0},
                new int[]{0, 1, 2, 3, 4, 5, 6},
                allocation(vertexObject, 50L, 128L, 1L),
                allocation(indexObject, 100L, 128L, 1L)
        );

        assertEquals(3, draws.size());
        assertEquals(new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 100, 50, 0),
                draws.get(0).arguments());
        assertEquals(new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 106, 54, 0),
                draws.get(1).arguments());
        assertEquals(new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 118, 62, 0),
                draws.get(2).arguments());
        assertEquals(List.of(0, 1, 2), draws.stream().map(TerrainCandidateSnapshot.IndexedDrawRecord::ordinal).toList());
        assertEquals(List.of(1, 4, 16), draws.stream()
                .map(TerrainCandidateSnapshot.IndexedDrawRecord::facingMask).toList());
    }

    @Test
    void sharedMaterializationMergesGeometryAcrossEmptyFacesAndFlushesAtSentinel() {
        List<TerrainCandidateSnapshot.IndexedDrawRecord> draws = materialize(
                TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT,
                false,
                0x2000L,
                100L,
                200L,
                new long[]{4, 0, 4, 4, 4, 0, 4},
                new int[]{0, 0, 0, 0, 1, 0, 0},
                allocation(new Object(), 200L, 128L, 2L),
                allocation(new Object(), 100L, 128L, 2L)
        );

        assertEquals(1, draws.size());
        assertEquals(new IrisMetalIndirectCommandStream.IndexedDraw(30, 1, 100, 200, 0),
                draws.get(0).arguments());
        assertEquals((1 << 0) | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 6), draws.get(0).facingMask());
    }

    @Test
    void opaqueAndTranslucentRecordsRetainDistinctIndexIdentity() {
        Object opaqueIndex = new Object();
        Object translucentIndex = new Object();
        TerrainCandidateSnapshot.IndexedDrawRecord opaque = materialize(
                TerrainCandidateSnapshot.TerrainPass.OPAQUE, false, 0x3000L, 0L, 0L,
                new long[]{4, 0, 0, 0, 0, 0, 4}, new int[]{0, 0, 0, 0, 0, 0, 6},
                allocation(new Object(), 0L, 64L, 3L), allocation(opaqueIndex, 0L, 64L, 3L)
        ).get(0);
        TerrainCandidateSnapshot.IndexedDrawRecord translucent = materialize(
                TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT, false, 0x3001L, 0L, 0L,
                new long[]{4, 0, 0, 0, 0, 0, 4}, new int[]{0, 0, 0, 0, 0, 0, 6},
                allocation(new Object(), 0L, 64L, 3L), allocation(translucentIndex, 0L, 64L, 3L)
        ).get(0);

        assertSame(opaqueIndex, opaque.indexAllocation().allocation());
        assertSame(translucentIndex, translucent.indexAllocation().allocation());
        assertNotEquals(opaque.indexAllocation(), translucent.indexAllocation());
        assertEquals(TerrainCandidateSnapshot.TerrainPass.OPAQUE, opaque.pass());
        assertEquals(TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT, translucent.pass());
    }

    @Test
    void baseVertexPreservesSodiumSignedDowncastAndRejectsOverflow() {
        long signedOffset = 0xffff_fff0L;
        TerrainCandidateSnapshot.IndexedDrawRecord draw = materialize(
                TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, 0x4000L, 0L, signedOffset,
                new long[]{4, 0, 0, 0, 0, 0, 0}, new int[]{0, 1, 2, 3, 4, 5, 6},
                allocation(new Object(), signedOffset, 64L, 4L), allocation(new Object(), 0L, 64L, 4L)
        ).get(0);
        assertEquals(-16, draw.arguments().baseVertex());

        assertTrue(TerrainCandidateDrawMaterializer.materialize(
                section(), TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, 0x4001L,
                0L, 0xffff_fffeL, new long[]{4, 0, 0, 0, 0, 0, 0},
                new int[]{0, 1, 2, 3, 4, 5, 6},
                allocation(new Object(), 0xffff_fffeL, 64L, 4L), allocation(new Object(), 0L, 64L, 4L)
        ) == null);
        assertTrue(TerrainCandidateDrawMaterializer.materialize(
                section(), TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, 0x4002L,
                0x8000_0000L, 0L, new long[]{4, 0, 0, 0, 0, 0, 0},
                new int[]{0, 1, 2, 3, 4, 5, 6},
                allocation(new Object(), 0L, 64L, 4L), allocation(new Object(), 0x8000_0000L, 64L, 4L)
        ) == null);
    }

    @Test
    void pointerAndAllocationGenerationArePartOfRecordContent() {
        Object vertex = new Object();
        Object index = new Object();
        TerrainCandidateSnapshot.Candidate original = candidate(
                materialize(TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, 0x5000L, 0L, 0L,
                        new long[]{4, 0, 0, 0, 0, 0, 0}, new int[]{0, 1, 2, 3, 4, 5, 6},
                        allocation(vertex, 0L, 64L, 1L), allocation(index, 0L, 64L, 1L)),
                allocation(vertex, 0L, 64L, 1L), allocation(index, 0L, 64L, 1L)
        );
        TerrainCandidateSnapshot.Candidate changedGeneration = candidate(
                materialize(TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, 0x5000L, 0L, 0L,
                        new long[]{4, 0, 0, 0, 0, 0, 0}, new int[]{0, 1, 2, 3, 4, 5, 6},
                        allocation(vertex, 0L, 64L, 2L), allocation(index, 0L, 64L, 2L)),
                allocation(vertex, 0L, 64L, 2L), allocation(index, 0L, 64L, 2L)
        );
        TerrainCandidateSnapshot.Candidate changedPointer = candidate(
                materialize(TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, 0x5001L, 0L, 0L,
                        new long[]{4, 0, 0, 0, 0, 0, 0}, new int[]{0, 1, 2, 3, 4, 5, 6},
                        allocation(vertex, 0L, 64L, 1L), allocation(index, 0L, 64L, 1L)),
                allocation(vertex, 0L, 64L, 1L), allocation(index, 0L, 64L, 1L)
        );

        assertFalse(original.equals(changedGeneration));
        assertFalse(original.equals(changedPointer));
        assertEquals(0x5000L, original.draws().get(0).dataPointer());
        assertEquals(2L, changedGeneration.draws().get(0).vertexAllocation().generation());
    }

    private static List<TerrainCandidateSnapshot.IndexedDrawRecord> materialize(
            final TerrainCandidateSnapshot.TerrainPass pass,
            final boolean localIndex,
            final long dataPointer,
            final long baseElement,
            final long baseVertex,
            final long[] vertexCounts,
            final int[] facings,
            final TerrainCandidateSnapshot.AllocationIdentity vertex,
            final TerrainCandidateSnapshot.AllocationIdentity index
    ) {
        List<TerrainCandidateSnapshot.IndexedDrawRecord> result =
                TerrainCandidateDrawMaterializer.materialize(
                        section(), pass, localIndex, dataPointer,
                        baseElement, baseVertex, vertexCounts, facings, vertex, index
                );
        assertNotEquals(null, result);
        return result;
    }

    private static TerrainCandidateSnapshot.SectionIdentity section() {
        return new TerrainCandidateSnapshot.SectionIdentity(1, 2, 3, 17, 17, 33, 49);
    }

    private static TerrainCandidateSnapshot.Candidate candidate(
            final List<TerrainCandidateSnapshot.IndexedDrawRecord> draws,
            final TerrainCandidateSnapshot.AllocationIdentity vertex,
            final TerrainCandidateSnapshot.AllocationIdentity index
    ) {
        return new TerrainCandidateSnapshot.Candidate(
                section(), new TerrainCandidateSnapshot.Aabb(0, 32, 48, 16, 48, 64),
                TerrainCandidateSnapshot.TerrainPass.OPAQUE, true, vertex, index, draws
        );
    }

    private static TerrainCandidateSnapshot.AllocationIdentity allocation(
            final Object allocation,
            final long offset,
            final long length,
            final long generation
    ) {
        return new TerrainCandidateSnapshot.AllocationIdentity(allocation, offset, length, generation);
    }
}
