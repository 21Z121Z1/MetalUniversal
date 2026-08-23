package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLIndexType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
    void immutableCommandPackingPreservesMixedOrderAndSignedBaseVertex() {
        Object pipeline = new Object();
        TerrainSceneSnapshot snapshot = TerrainSceneSnapshot.capture(
                state(
                        pipeline,
                        new MetalAllocationIdentity(501L, 1L),
                        slots(TerrainSceneSnapshot.ResourceSlice.of(
                                new Object(), new MetalAllocationIdentity(502L, 1L),
                                0L, 128L, 32, false
                        ))
                ),
                commandBuffer(),
                List.of(
                        new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, -4, 0),
                        new IrisMetalIndirectCommandStream.IndexedDraw(18, 2, 12, 7, 1)
                )
        );
        try (Arena arena = Arena.ofConfined()) {
            java.nio.IntBuffer packed = snapshot.packIndexedCommands(arena)
                    .asByteBuffer()
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            int[] values = new int[10];
            packed.get(values);
            assertArrayEquals(
                    new int[] {12, 1, 0, -4, 0, 18, 2, 12, 7, 1},
                    values
            );
        }
    }

    @Test
    void icbContentReuseRequiresExactCommandValues() {
        Object pipeline = new Object();
        Object indexBuffer = new Object();
        MetalAllocationIdentity indexIdentity = new MetalAllocationIdentity(601L, 1L);
        TerrainSceneSnapshot.ResourceSlice indexSlice = TerrainSceneSnapshot.ResourceSlice.of(
                indexBuffer, indexIdentity, 0L, 512L, 0, false
        );
        TerrainSceneSnapshot.ResourceSlice vertexSlice = TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(602L, 1L), 0L, 128L, 32, false
        );
        TerrainSceneSnapshot.StateView state = new TerrainSceneSnapshot.StateView(
                pipeline, 4L, 5L, 6L, indexSlice, MTLIndexType.UInt32, slots(vertexSlice)
        );
        List<IrisMetalIndirectCommandStream.IndexedDraw> commands = List.of(
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 0)
        );
        TerrainSceneSnapshot first = TerrainSceneSnapshot.capture(state, commandBuffer(), commands);
        TerrainSceneSnapshot same = TerrainSceneSnapshot.capture(state, commandBuffer(), commands);
        TerrainSceneSnapshot changed = TerrainSceneSnapshot.capture(
                state,
                commandBuffer(),
                List.of(new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 1))
        );

        assertTrue(first.sameIcbContent(same));
        assertFalse(first.sameIcbContent(changed));

        TerrainSceneSnapshot.StateView dynamicState = new TerrainSceneSnapshot.StateView(
                pipeline,
                4L,
                99L,
                100L,
                indexSlice,
                MTLIndexType.UInt32,
                slots(TerrainSceneSnapshot.ResourceSlice.of(
                        new Object(), new MetalAllocationIdentity(603L, 1L),
                        32L, 256L, 32, false
                ))
        );
        TerrainSceneSnapshot dynamic = TerrainSceneSnapshot.capture(
                dynamicState, commandBuffer(), commands
        );
        TerrainSceneSnapshot.IcbContent key = first.icbContent();
        assertTrue(first.sameIcbContent(key));
        assertTrue(dynamic.sameIcbContent(key),
                "minimal ICB key must ignore transient snapshot state");
        assertTrue(first.sameIcbContent(dynamic),
                "inherited dynamic vertex/binding/source state must not rebuild the ICB");

        TerrainSceneSnapshot.StateView changedIndexGeneration = new TerrainSceneSnapshot.StateView(
                pipeline,
                4L,
                5L,
                6L,
                TerrainSceneSnapshot.ResourceSlice.of(
                        indexBuffer, new MetalAllocationIdentity(601L, 2L), 0L, 512L, 0, false
                ),
                MTLIndexType.UInt32,
                slots(vertexSlice)
        );
        TerrainSceneSnapshot.StateView changedPipelineGeneration = new TerrainSceneSnapshot.StateView(
                pipeline, 5L, 5L, 6L, indexSlice, MTLIndexType.UInt32, slots(vertexSlice)
        );
        TerrainSceneSnapshot.StateView changedIndexType = new TerrainSceneSnapshot.StateView(
                pipeline, 4L, 5L, 6L, indexSlice, MTLIndexType.UInt16, slots(vertexSlice)
        );
        assertFalse(first.sameIcbContent(TerrainSceneSnapshot.capture(
                        changedIndexGeneration, commandBuffer(), commands
                )), "index allocation generation must rebuild the ICB");
        assertFalse(first.sameIcbContent(TerrainSceneSnapshot.capture(
                        changedPipelineGeneration, commandBuffer(), commands
                )), "pipeline generation must rebuild the ICB");
        assertFalse(first.sameIcbContent(TerrainSceneSnapshot.capture(
                        changedIndexType, commandBuffer(), commands
                )), "index type must rebuild the ICB");
    }

    @Test
    void defaultFeatureIsOff() {
        assertFalse(TerrainSceneSnapshot.ENABLED,
                "focused host tests must not silently enable the experimental path");
        assertFalse(TerrainSceneSnapshot.ICB_ENABLED,
                "focused host tests must not silently enable native terrain ICB");
    }

    @Test
    void terrainIcbOptInRoutesAllRequiredMetal4Switches() throws ReflectiveOperationException {
        Assumptions.assumeTrue(TerrainSceneSnapshot.ICB_ENABLED);
        assertTrue(staticBoolean("METAL4_REQUESTED"));
        assertTrue(staticBoolean("METAL4_COMPILER"));
        assertTrue(staticBoolean("METAL4_MAIN_RENDERER"));
    }

    private static boolean staticBoolean(final String name) throws ReflectiveOperationException {
        Field field = MetalDevice.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(null);
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
