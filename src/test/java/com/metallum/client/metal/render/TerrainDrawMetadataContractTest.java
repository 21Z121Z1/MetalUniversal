package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLIndexType;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Package-level contract for the producer-side draw/section sidecar. */
final class TerrainDrawMetadataContractTest {
    @Test
    void localMaskTracksEveryPutCallButOnlyVisibleFacesBecomeDraws() {
        int count = ModelQuadFacing.COUNT;
        int mask = (1 << 1) | (1 << (count - 1));

        assertEquals(List.of(1 << 1, 1 << (count - 1)),
                TerrainDrawMetadataGrouping.localVisibleFacingMasks(mask, count));
        assertEquals(0, TerrainDrawMetadataGrouping.localPutFacingMask(mask, 0, count));
        assertEquals(1 << 1, TerrainDrawMetadataGrouping.localPutFacingMask(mask, 1, count));
        assertEquals(1 << (count - 1),
                TerrainDrawMetadataGrouping.localPutFacingMask(mask, count - 1, count));

        List<Integer> finalOrdinals = new ArrayList<>();
        int ordinal = 0;
        for (int putCall = 0; putCall < count; putCall++) {
            if (TerrainDrawMetadataGrouping.localPutFacingMask(mask, putCall, count) != 0) {
                finalOrdinals.add(ordinal++);
            }
        }
        assertEquals(List.of(0, 1), finalOrdinals);
    }

    @Test
    void sharedRunsPreserveVisibleOrderAndMergeAcrossAnEmptyFaceExactlyLikeSodium() {
        int count = ModelQuadFacing.COUNT;
        int[] vertexCounts = new int[count];
        int[] facings = new int[count];
        vertexCounts[0] = 4;
        vertexCounts[1] = 0;
        vertexCounts[2] = 4;
        vertexCounts[3] = 4;
        vertexCounts[4] = 4;
        facings[0] = facings[2] = facings[3] = 0;
        facings[4] = 1;

        assertEquals(List.of((1 << 0) | (1 << 2) | (1 << 3)),
                TerrainDrawMetadataGrouping.sharedFacingGroups(vertexCounts, facings, 1));
    }

    @Test
    void sharedVisibleRunsBecomeSeparateMergedFacingGroups() {
        int count = ModelQuadFacing.COUNT;
        int[] vertexCounts = new int[count];
        int[] facings = new int[count];
        vertexCounts[0] = 4;
        vertexCounts[1] = 4;
        vertexCounts[2] = 4;
        vertexCounts[3] = 4;
        vertexCounts[4] = 4;
        facings[0] = facings[1] = facings[3] = 0;
        facings[2] = 1;
        facings[4] = 1;

        assertEquals(List.of((1 << 0) | (1 << 1), 1 << 3),
                TerrainDrawMetadataGrouping.sharedFacingGroups(vertexCounts, facings, 1));
    }

    @Test
    void oneSectionMultiFacingDrawsKeepExplicitOrdinalAndFacing() {
        Object allocation = new Object();
        TerrainDrawMetadataStore store = new TerrainDrawMetadataStore();
        store.append(metadata(0, draw(12, 0), allocation, 0b0000001, false, true));
        store.append(metadata(1, draw(18, 12), allocation, 0b0000100, false, true));

        List<TerrainDrawMetadata> frozen = store.freeze(List.of(draw(12, 0), draw(18, 12)));

        assertEquals(List.of(0, 1), frozen.stream().map(TerrainDrawMetadata::ordinal).toList());
        assertEquals(0b0000001, frozen.get(0).facingMask());
        assertEquals(0b0000100, frozen.get(1).facingMask());
        assertEquals(frozen.get(0).section(), frozen.get(1).section());
        assertEquals(frozen.get(0).contentGeneration(), frozen.get(1).contentGeneration());
    }

    @Test
    void sharedIndexFacingMergeIsOneDrawWithTheMergedFacingMask() {
        Object allocation = new Object();
        TerrainDrawMetadataStore store = new TerrainDrawMetadataStore();
        store.append(metadata(0, draw(24, 0), allocation, 0b0000011, false, false));
        store.append(metadata(1, draw(6, 24), allocation, 0b0010000, false, false));

        List<TerrainDrawMetadata> frozen = store.freeze(List.of(draw(24, 0), draw(6, 24)));

        assertEquals(0b0000011, frozen.get(0).facingMask(), "shared run must bind both facings");
        assertEquals(0b0010000, frozen.get(1).facingMask());
        assertFalse(frozen.get(0).localIndex());
        assertFalse(frozen.get(1).localIndex());
    }

    @Test
    void opaqueAndTranslucentOrderRemainsTheProducerOrder() {
        Object allocation = new Object();
        TerrainDrawMetadata opaque = metadata(0, draw(12, 0), allocation, 1, false, true);
        TerrainDrawMetadata translucent = metadata(1, draw(6, 12), allocation, 2, true, false);
        TerrainDrawMetadataStore store = new TerrainDrawMetadataStore();
        store.append(opaque);
        store.append(translucent);
        List<TerrainDrawMetadata> frozen = store.freeze(List.of(opaque.arguments(), translucent.arguments()));

        assertEquals(List.of(false, true), frozen.stream().map(TerrainDrawMetadata::translucent).toList());
        assertEquals(List.of(0, 1), frozen.stream().map(TerrainDrawMetadata::ordinal).toList());
        assertSame(opaque, frozen.get(0));
        assertSame(translucent, frozen.get(1));
    }

    @Test
    void contentGenerationAndAabbArePartOfSnapshotContent() {
        Object pipeline = new Object();
        Object allocation = new Object();
        IrisMetalIndirectCommandStream.IndexedDraw command = draw(12, 0);
        TerrainDrawMetadata original = metadata(0, command, allocation, 1, false, true);
        TerrainDrawMetadata changedGeneration = metadata(0, command, allocation, 1, false, true, 16.0, 2L);
        TerrainDrawMetadata changedAabb = metadata(0, command, allocation, 1, false, true, 17.0);
        TerrainSceneSnapshot.StateView capturedState = state(pipeline);
        TerrainSceneSnapshot first = TerrainSceneSnapshot.capture(
                capturedState, commandBuffer(), List.of(command), List.of(original)
        );
        TerrainSceneSnapshot sameContent = TerrainSceneSnapshot.capture(
                capturedState, commandBuffer(), List.of(command),
                List.of(metadata(0, command, allocation, 1, false, true))
        );

        TerrainSceneSnapshot generationChanged = TerrainSceneSnapshot.capture(
                capturedState, commandBuffer(), List.of(command), List.of(changedGeneration)
        );
        TerrainSceneSnapshot aabbChanged = TerrainSceneSnapshot.capture(
                capturedState, commandBuffer(), List.of(command), List.of(changedAabb)
        );

        // Allocation-generation and stable world-AABB changes are both
        // producer-content changes even when the section and command repeat.
        org.junit.jupiter.api.Assertions.assertTrue(first.sameIcbContent(sameContent));
        assertFalse(first.sameIcbContent(generationChanged));
        assertFalse(first.sameIcbContent(aabbChanged));
        assertNotSame(first.draws().get(0).metadata(), generationChanged.draws().get(0).metadata());
        assertNotEquals(original.contentGeneration().vertexAllocation().generation(),
                changedGeneration.contentGeneration().vertexAllocation().generation());
    }

    @Test
    void featureOffDoesNotEnterMetadataCaptureOrSceneSnapshot() {
        assertFalse(TerrainDrawMetadataCapture.enabled());
        assertFalse(TerrainSceneSnapshot.DRAW_METADATA_ENABLED);
        assertFalse(TerrainSceneSnapshot.captureEnabled());
    }

    private static IrisMetalIndirectCommandStream.IndexedDraw draw(final int count, final int firstIndex) {
        return new IrisMetalIndirectCommandStream.IndexedDraw(count, 1, firstIndex, 0, 0);
    }

    private static TerrainDrawMetadata metadata(
            final int ordinal,
            final IrisMetalIndirectCommandStream.IndexedDraw command,
            final Object allocation,
            final int facingMask,
            final boolean translucent,
            final boolean localIndex
    ) {
        return metadata(ordinal, command, allocation, facingMask, translucent, localIndex, 16.0, 0L);
    }

    private static TerrainDrawMetadata metadata(
            final int ordinal,
            final IrisMetalIndirectCommandStream.IndexedDraw command,
            final Object allocation,
            final int facingMask,
            final boolean translucent,
            final boolean localIndex,
            final double maxX
    ) {
        return metadata(ordinal, command, allocation, facingMask, translucent, localIndex, maxX, 0L);
    }

    private static TerrainDrawMetadata metadata(
            final int ordinal,
            final IrisMetalIndirectCommandStream.IndexedDraw command,
            final Object allocation,
            final int facingMask,
            final boolean translucent,
            final boolean localIndex,
            final double maxX,
            final long generation
    ) {
        TerrainDrawMetadata.AllocationStamp allocationStamp =
                new TerrainDrawMetadata.AllocationStamp(allocation, 0L, 128L, generation);
        return new TerrainDrawMetadata(
                ordinal,
                command,
                new TerrainDrawMetadata.SectionIdentity(1, 2, 3, 17, 17, 33, 49),
                new TerrainDrawMetadata.ContentGeneration(
                        allocationStamp, allocationStamp, 0x1000L, 0L, 0L
                ),
                new TerrainDrawMetadata.Aabb(0.0, 32.0, 48.0, maxX, 48.0, 64.0),
                facingMask,
                translucent,
                localIndex
        );
    }

    private static TerrainSceneSnapshot.StateView state(final Object pipeline) {
        List<TerrainSceneSnapshot.ResourceSlice> slots = new ArrayList<>();
        slots.add(TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(800L, 1L), 0L, 128L, 32, false
        ));
        while (slots.size() < TerrainSceneSnapshot.MAX_VERTEX_BUFFERS) {
            slots.add(TerrainSceneSnapshot.ResourceSlice.empty());
        }
        return new TerrainSceneSnapshot.StateView(
                pipeline,
                1L,
                1L,
                1L,
                TerrainSceneSnapshot.ResourceSlice.of(
                        new Object(), new MetalAllocationIdentity(801L, 1L), 0L, 128L, 0, false
                ),
                MTLIndexType.UInt32,
                slots
        );
    }

    private static TerrainSceneSnapshot.ResourceSlice commandBuffer() {
        return TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(802L, 1L), 0L, 20L, 20, false
        );
    }
}
