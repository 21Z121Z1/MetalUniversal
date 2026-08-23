package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLIndexType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Package-level contract for the producer-side draw/section sidecar. */
final class TerrainDrawMetadataContractTest {
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
        TerrainDrawMetadata changedAllocation = metadata(0, command, new Object(), 1, false, true);
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
                capturedState, commandBuffer(), List.of(command), List.of(changedAllocation)
        );
        TerrainSceneSnapshot aabbChanged = TerrainSceneSnapshot.capture(
                capturedState, commandBuffer(), List.of(command), List.of(changedAabb)
        );

        // A fresh producer object with the same section/allocation/AABB values
        // is reusable; identity changes below are the invalidating cases.
        org.junit.jupiter.api.Assertions.assertTrue(first.sameIcbContent(sameContent));
        assertFalse(first.sameIcbContent(generationChanged));
        assertFalse(first.sameIcbContent(aabbChanged));
        assertNotSame(first.draws().get(0).metadata(), generationChanged.draws().get(0).metadata());
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
        return metadata(ordinal, command, allocation, facingMask, translucent, localIndex, 16.0);
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
        TerrainDrawMetadata.AllocationStamp allocationStamp =
                new TerrainDrawMetadata.AllocationStamp(allocation, 0L, 128L);
        return new TerrainDrawMetadata(
                ordinal,
                command,
                new TerrainDrawMetadata.SectionIdentity(1, 2, 3, 17, 17, 33, 49),
                new TerrainDrawMetadata.ContentGeneration(
                        allocationStamp, allocationStamp, 0x1000L, 0L, 0L
                ),
                new TerrainDrawMetadata.Aabb(0.0, 32.0, 48.0, maxX, 48.0, 64.0),
                new TerrainDrawMetadata.Aabb(-1.0, 2.0, 3.0, maxX - 1.0, 18.0, 19.0),
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
