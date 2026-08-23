package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLIndexType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainSceneSnapshotTest {
    @Test
    void mixedCommandsKeepOrderAndUnusedVertexSlotsAreEmpty() {
        Object pipeline = new Object();
        MetalAllocationIdentity index = new MetalAllocationIdentity(101L, 7L);
        MetalAllocationIdentity vertex = new MetalAllocationIdentity(102L, 3L);
        List<TerrainSceneSnapshot.ResourceSlice> slots = slots(
                TerrainSceneSnapshot.ResourceSlice.of(new Object(), vertex, 16L, 256L, 32, false)
        );
        TerrainSceneSnapshot.ResourceSlice commandBuffer = commandBuffer();
        TerrainSceneSnapshot.StateView state = state(pipeline, index, slots);
        List<IrisMetalIndirectCommandStream.IndexedDraw> commands = List.of(
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 4, 0),
                new IrisMetalIndirectCommandStream.IndexedDraw(18, 2, 12, -3, 1)
        );

        // A negative base vertex is valid Vulkan/Sodium input and must remain
        // untouched; only byte offsets/count fields are validated by the
        // stream record itself.
        TerrainSceneSnapshot snapshot = TerrainSceneSnapshot.capture(state, commandBuffer, commands);

        assertTrue(snapshot.matches(state, commandBuffer, 2));
        assertEquals(0, snapshot.draws().get(0).ordinal());
        assertEquals(1, snapshot.draws().get(1).ordinal());
        assertEquals(commands, snapshot.draws().stream().map(TerrainSceneSnapshot.Draw::arguments).toList());
        assertTrue(snapshot.sceneGeneration() >= 7L);
        assertEquals(TerrainSceneSnapshot.MAX_VERTEX_BUFFERS, slots.size());
    }

    @Test
    void allocationGenerationAndClosedStateFailClosedBeforeSubmit() {
        Object pipeline = new Object();
        MetalAllocationIdentity index = new MetalAllocationIdentity(201L, 1L);
        MetalAllocationIdentity vertex = new MetalAllocationIdentity(202L, 1L);
        List<IrisMetalIndirectCommandStream.IndexedDraw> commands = List.of(
                new IrisMetalIndirectCommandStream.IndexedDraw(6, 1, 0, 0, 0),
                new IrisMetalIndirectCommandStream.IndexedDraw(9, 1, 6, 0, 0)
        );
        TerrainSceneSnapshot.StateView captured = state(
                pipeline, index,
                slots(TerrainSceneSnapshot.ResourceSlice.of(new Object(), vertex, 0L, 128L, 32, false))
        );
        TerrainSceneSnapshot.ResourceSlice commandBuffer = commandBuffer();
        TerrainSceneSnapshot snapshot = TerrainSceneSnapshot.capture(captured, commandBuffer, commands);

        TerrainSceneSnapshot.StateView resized = state(
                pipeline,
                new MetalAllocationIdentity(201L, 2L),
                slots(TerrainSceneSnapshot.ResourceSlice.of(new Object(), vertex, 0L, 128L, 32, false)),
                7L
        );
        TerrainSceneSnapshot.StateView closed = state(
                pipeline, index,
                slots(TerrainSceneSnapshot.ResourceSlice.of(new Object(), vertex, 0L, 128L, 32, true))
        );

        assertFalse(snapshot.matches(resized, commandBuffer, commands.size()));
        assertFalse(snapshot.matches(closed, commandBuffer, commands.size()));
    }

    @Test
    void defaultFeatureIsOff() {
        assertFalse(TerrainSceneSnapshot.ENABLED,
                "focused host tests must not silently enable the experimental path");
    }

    private static TerrainSceneSnapshot.StateView state(
            final Object pipeline,
            final MetalAllocationIdentity index,
            final List<TerrainSceneSnapshot.ResourceSlice> slots
    ) {
        return state(pipeline, index, slots, 6L);
    }

    private static TerrainSceneSnapshot.StateView state(
            final Object pipeline,
            final MetalAllocationIdentity index,
            final List<TerrainSceneSnapshot.ResourceSlice> slots,
            final long sourceGeneration
    ) {
        return new TerrainSceneSnapshot.StateView(
                pipeline,
                4L,
                5L,
                sourceGeneration,
                TerrainSceneSnapshot.ResourceSlice.of(new Object(), index, 0L, 512L, 0, false),
                MTLIndexType.UInt32,
                slots
        );
    }

    private static TerrainSceneSnapshot.ResourceSlice commandBuffer() {
        return TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(401L, 1L), 0L, 40L, 20, false
        );
    }

    private static List<TerrainSceneSnapshot.ResourceSlice> slots(
            final TerrainSceneSnapshot.ResourceSlice active
    ) {
        List<TerrainSceneSnapshot.ResourceSlice> slots = new ArrayList<>();
        slots.add(active);
        while (slots.size() < TerrainSceneSnapshot.MAX_VERTEX_BUFFERS) {
            slots.add(TerrainSceneSnapshot.ResourceSlice.empty());
        }
        return slots;
    }
}
