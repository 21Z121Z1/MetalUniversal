package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLIndexType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class TerrainVisibleDrawPlanTest {
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
    void sourceOrdinalStaysIcbSlotWhileCandidateOrdinalIsOnlyVisibilityLookup() {
        Object vertex0 = new Object();
        Object index0 = new Object();
        Object vertex1 = new Object();
        Object index1 = new Object();
        var commandA = new IrisMetalIndirectCommandStream.IndexedDraw(18, 1, 12, 7, 0);
        var commandB = new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 3, 0);
        var metadataA = metadata(0, 9, commandA, 0xc, 902L, vertex1, index1);
        var metadataB = metadata(1, 4, commandB, 0x3, 401L, vertex0, index0);
        var candidates = candidateSnapshot(77L, List.of(
                candidate(4, vertex0, index0, List.of(
                        candidateDraw(0, 4, commandB, 0x3, 401L, vertex0, index0)
                )),
                candidate(9, vertex1, index1, List.of(
                        candidateDraw(0, 9, commandA, 0xc, 902L, vertex1, index1)
                ))
        ));
        TerrainSceneSnapshot source = sourceSnapshot(
                List.of(commandA, commandB), List.of(metadataA, metadataB)
        );

        TerrainVisibleDrawPlan plan = TerrainVisibleDrawPlan.tryBuild(source, candidates);

        assertNotNull(plan);
        assertEquals(77L, plan.candidateEpoch());
        assertEquals(2, plan.candidateCount());
        assertEquals(2, plan.drawCount());
        assertEquals(1, plan.candidateIndex(0));
        assertEquals(0, plan.candidateIndex(1));
        try (Arena arena = Arena.ofConfined()) {
            var packed = plan.packCandidateIndices(arena);
            assertEquals(1, packed.get(ValueLayout.JAVA_INT, 0));
            assertEquals(0, packed.get(ValueLayout.JAVA_INT, Integer.BYTES));
        }
    }

    @Test
    void multipleSourceDrawsMayMapToOneCandidateWithoutChangingSourceOrder() {
        Object vertex = new Object();
        Object index = new Object();
        var command0 = new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 3, 0);
        var command1 = new IrisMetalIndirectCommandStream.IndexedDraw(18, 1, 12, 7, 0);
        var candidate = candidate(4, vertex, index, List.of(
                candidateDraw(0, 4, command0, 0x3, 101L, vertex, index),
                candidateDraw(1, 4, command1, 0xc, 102L, vertex, index)
        ));
        var source = sourceSnapshot(
                List.of(command0, command1),
                List.of(
                        metadata(0, 4, command0, 0x3, 101L, vertex, index),
                        metadata(1, 4, command1, 0xc, 102L, vertex, index)
                )
        );

        TerrainVisibleDrawPlan plan = TerrainVisibleDrawPlan.tryBuild(
                source, candidateSnapshot(12L, List.of(candidate))
        );

        assertNotNull(plan);
        assertEquals(0, plan.candidateIndex(0));
        assertEquals(0, plan.candidateIndex(1));
    }

    @Test
    void missingMetadataFailsClosed() {
        Object vertex = new Object();
        Object index = new Object();
        var command = new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 0, 0, 0);
        TerrainSceneSnapshot source = sourceSnapshot(List.of(command), null);
        var candidates = candidateSnapshot(3L, List.of(
                candidate(3, vertex, index, List.of(
                        candidateDraw(0, 3, command, 0x1, 301L, vertex, index)
                ))
        ));

        assertNull(TerrainVisibleDrawPlan.tryBuild(source, candidates));
    }

    @Test
    void wildcardCandidateIsNotDrawAuthoritative() {
        Object vertex = new Object();
        Object index = new Object();
        var command = new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 0, 0, 0);
        var source = sourceSnapshot(
                List.of(command),
                List.of(metadata(0, 6, command, 0x1, 601L, vertex, index))
        );
        var candidates = candidateSnapshot(6L, List.of(
                candidate(6, vertex, index, List.of())
        ));

        assertNull(TerrainVisibleDrawPlan.tryBuild(source, candidates));
    }

    @Test
    void ambiguousCandidateIdentityFailsClosed() {
        Object vertex = new Object();
        Object index = new Object();
        var command = new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 3, 0);
        var draw = candidateDraw(0, 8, command, 0x3, 801L, vertex, index);
        var source = sourceSnapshot(
                List.of(command),
                List.of(metadata(0, 8, command, 0x3, 801L, vertex, index))
        );
        var candidates = candidateSnapshot(8L, List.of(
                candidate(8, vertex, index, List.of(draw)),
                candidate(8, vertex, index, List.of(draw))
        ));

        assertNull(TerrainVisibleDrawPlan.tryBuild(source, candidates));
    }

    private static TerrainSceneSnapshot sourceSnapshot(
            final List<IrisMetalIndirectCommandStream.IndexedDraw> commands,
            final List<TerrainDrawMetadata> metadata
    ) {
        Object pipeline = new Object();
        TerrainSceneSnapshot.StateView state = new TerrainSceneSnapshot.StateView(
                pipeline,
                4L,
                5L,
                6L,
                TerrainSceneSnapshot.ResourceSlice.of(
                        new Object(), new MetalAllocationIdentity(501L, 1L), 0L, 4096L, 0, false
                ),
                MTLIndexType.UInt32,
                vertexSlots()
        );
        TerrainSceneSnapshot.ResourceSlice commandBuffer = TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(502L, 1L),
                0L, (long) commands.size() * 20L, 20, false
        );
        return TerrainSceneSnapshot.capture(state, commandBuffer, commands, metadata);
    }

    private static List<TerrainSceneSnapshot.ResourceSlice> vertexSlots() {
        List<TerrainSceneSnapshot.ResourceSlice> slots = new ArrayList<>();
        slots.add(TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(503L, 1L), 0L, 4096L, 32, false
        ));
        while (slots.size() < TerrainSceneSnapshot.MAX_VERTEX_BUFFERS) {
            slots.add(TerrainSceneSnapshot.ResourceSlice.empty());
        }
        return slots;
    }

    private static TerrainCandidateSnapshot candidateSnapshot(
            final long epoch,
            final List<TerrainCandidateSnapshot.Candidate> candidates
    ) {
        return new TerrainCandidateSnapshot(epoch, CAMERA, IDENTITY, candidates);
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
                        submittedAllocation(vertex), submittedAllocation(index),
                        dataPointer, 0L, command.baseVertex()
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
