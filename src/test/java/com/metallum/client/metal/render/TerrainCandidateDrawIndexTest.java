package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TerrainCandidateDrawIndexTest {
    private static final TerrainCandidateSnapshot.CameraPosition CAMERA =
            new TerrainCandidateSnapshot.CameraPosition(0.0, 0.0, 0.0);
    private static final TerrainCandidateSnapshot.VisibilityTransform IDENTITY =
            new TerrainCandidateSnapshot.VisibilityTransform(
                    1, 0, 0, 0,
                    0, 1, 0, 0,
                    0, 0, 1, 0,
                    0, 0, 0, 1
            );

    @Test
    void exactIndexPreservesMultipleSourceDrawsPerCandidate() {
        Object vertex = new Object();
        Object index = new Object();
        var command0 = new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 3, 0);
        var command1 = new IrisMetalIndirectCommandStream.IndexedDraw(18, 1, 12, 7, 0);
        var candidate = candidate(4, vertex, index, List.of(
                candidateDraw(0, 4, command0, 0x3, 101L, vertex, index),
                candidateDraw(1, 4, command1, 0xc, 102L, vertex, index)
        ));
        var lookup = TerrainCandidateDrawIndex.build(snapshot(List.of(candidate)));

        assertEquals(0, lookup.uniqueCandidateIndex(metadata(0, 4, command0, 0x3, 101L, vertex, index)));
        assertEquals(0, lookup.uniqueCandidateIndex(metadata(1, 4, command1, 0xc, 102L, vertex, index)));
        assertEquals(1, lookup.candidateCount());
        assertEquals(2, lookup.indexedDrawCount());
    }

    @Test
    void exactDrawKeyDisambiguatesOtherwiseIdenticalCandidates() {
        Object vertex = new Object();
        Object index = new Object();
        var command0 = new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 3, 0);
        var command1 = new IrisMetalIndirectCommandStream.IndexedDraw(24, 1, 12, 5, 0);
        var candidate0 = candidate(8, vertex, index, List.of(
                candidateDraw(0, 8, command0, 0x1, 201L, vertex, index)
        ));
        var candidate1 = candidate(8, vertex, index, List.of(
                candidateDraw(0, 8, command1, 0x2, 202L, vertex, index)
        ));
        var lookup = TerrainCandidateDrawIndex.build(snapshot(List.of(candidate0, candidate1)));

        assertEquals(0, lookup.uniqueCandidateIndex(metadata(0, 8, command0, 0x1, 201L, vertex, index)));
        assertEquals(1, lookup.uniqueCandidateIndex(metadata(0, 8, command1, 0x2, 202L, vertex, index)));
    }

    @Test
    void duplicateExactCandidatesFailClosedAsAmbiguous() {
        Object vertex = new Object();
        Object index = new Object();
        var command = new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 3, 0);
        var draw = candidateDraw(0, 12, command, 0x3, 301L, vertex, index);
        var lookup = TerrainCandidateDrawIndex.build(snapshot(List.of(
                candidate(12, vertex, index, List.of(draw)),
                candidate(12, vertex, index, List.of(draw))
        )));

        assertThrows(IllegalArgumentException.class,
                () -> lookup.uniqueCandidateIndex(metadata(0, 12, command, 0x3, 301L, vertex, index)));
    }

    @Test
    void emptyCandidateDrawListRetainsLegacyWildcardSemantics() {
        Object vertex = new Object();
        Object index = new Object();
        var command = new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 0, 0, 0);
        var lookup = TerrainCandidateDrawIndex.build(snapshot(List.of(
                candidate(16, vertex, index, List.of())
        )));

        assertEquals(0, lookup.uniqueCandidateIndex(metadata(0, 16, command, 0x1, 401L, vertex, index)));
        assertEquals(0, lookup.indexedDrawCount());
    }

    @Test
    void allocationEqualityCannotSubstituteForProducerObjectIdentity() {
        Object candidateVertex = new String("same-value");
        Object submittedVertex = new String("same-value");
        Object index = new Object();
        var command = new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 0, 0, 0);
        var lookup = TerrainCandidateDrawIndex.build(snapshot(List.of(
                candidate(20, candidateVertex, index, List.of())
        )));

        assertThrows(IllegalArgumentException.class,
                () -> lookup.uniqueCandidateIndex(metadata(
                        0, 20, command, 0x1, 501L, submittedVertex, index)));
    }

    @Test
    void largeUniqueCorpusBuildsOnceAndResolvesLastDraw() {
        final int count = 4096;
        Object vertex = new Object();
        Object index = new Object();
        List<TerrainCandidateSnapshot.Candidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            var command = new IrisMetalIndirectCommandStream.IndexedDraw(6 + i, 1, i, i, 0);
            candidates.add(candidate(i, vertex, index, List.of(
                    candidateDraw(0, i, command, 0x1, 10_000L + i, vertex, index)
            )));
        }
        var lookup = TerrainCandidateDrawIndex.build(snapshot(candidates));
        int last = count - 1;
        var lastCommand = new IrisMetalIndirectCommandStream.IndexedDraw(6 + last, 1, last, last, 0);

        assertEquals(last, lookup.uniqueCandidateIndex(
                metadata(last, last, lastCommand, 0x1, 10_000L + last, vertex, index)));
        assertEquals(count, lookup.candidateCount());
        assertEquals(count, lookup.indexedDrawCount());
    }

    private static TerrainCandidateSnapshot snapshot(final List<TerrainCandidateSnapshot.Candidate> candidates) {
        return new TerrainCandidateSnapshot(CAMERA, IDENTITY, candidates);
    }

    private static TerrainCandidateSnapshot.Candidate candidate(
            final int sectionId,
            final Object vertex,
            final Object index,
            final List<TerrainCandidateSnapshot.IndexedDrawRecord> draws
    ) {
        return new TerrainCandidateSnapshot.Candidate(
                candidateSection(sectionId),
                new TerrainCandidateSnapshot.Aabb(sectionId, 0, 0, sectionId + 1, 1, 1),
                TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                false,
                candidateAllocation(vertex),
                candidateAllocation(index),
                draws
        );
    }

    private static TerrainCandidateSnapshot.IndexedDrawRecord candidateDraw(
            final int ordinal,
            final int sectionId,
            final IrisMetalIndirectCommandStream.IndexedDraw command,
            final int facingMask,
            final long dataPointer,
            final Object vertex,
            final Object index
    ) {
        return new TerrainCandidateSnapshot.IndexedDrawRecord(
                ordinal,
                candidateSection(sectionId),
                TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                command,
                facingMask,
                dataPointer,
                candidateAllocation(vertex),
                candidateAllocation(index)
        );
    }

    private static TerrainDrawMetadata metadata(
            final int ordinal,
            final int sectionId,
            final IrisMetalIndirectCommandStream.IndexedDraw command,
            final int facingMask,
            final long dataPointer,
            final Object vertex,
            final Object index
    ) {
        return new TerrainDrawMetadata(
                ordinal,
                command,
                new TerrainDrawMetadata.SectionIdentity(sectionId, 0, 0, sectionId, sectionId, 0, 0),
                new TerrainDrawMetadata.ContentGeneration(
                        submittedAllocation(vertex), submittedAllocation(index), dataPointer, 0L, command.baseVertex()
                ),
                new TerrainDrawMetadata.Aabb(sectionId, 0, 0, sectionId + 1, 1, 1),
                facingMask,
                false,
                false
        );
    }

    private static TerrainCandidateSnapshot.SectionIdentity candidateSection(final int sectionId) {
        return new TerrainCandidateSnapshot.SectionIdentity(sectionId, 0, 0, sectionId, sectionId, 0, 0);
    }

    private static TerrainCandidateSnapshot.AllocationIdentity candidateAllocation(final Object allocation) {
        return new TerrainCandidateSnapshot.AllocationIdentity(allocation, 0L, 1024L, 7L);
    }

    private static TerrainDrawMetadata.AllocationStamp submittedAllocation(final Object allocation) {
        return new TerrainDrawMetadata.AllocationStamp(allocation, 0L, 1024L, 7L);
    }
}
