package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLIndexType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainSubmissionScopeTest {
    @Test
    void authorizedAndStaleTransactionsAreOneShotWhenFeatureIsEnabled() {
        Assumptions.assumeTrue(TerrainSceneSnapshot.ENABLED);
        Object pipeline = new Object();
        TerrainSceneSnapshot.StateView state = state(pipeline, 11L);
        TerrainSceneSnapshot.StateView stale = state(pipeline, 12L);
        List<IrisMetalIndirectCommandStream.IndexedDraw> commands = List.of(
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 0),
                new IrisMetalIndirectCommandStream.IndexedDraw(18, 1, 12, 1, 0)
        );
        Object commandResource = new Object();
        MetalAllocationIdentity commandAllocation = new MetalAllocationIdentity(303L, 1L);
        TerrainSceneSnapshot.ResourceSlice commandBuffer = commandBuffer(commandResource, commandAllocation, 0L);
        TerrainSceneSnapshot.ResourceSlice differentCommandBuffer =
                commandBuffer(commandResource, commandAllocation, 20L);
        TerrainSceneSnapshot snapshot = TerrainSceneSnapshot.capture(state, commandBuffer, commands);

        try (TerrainSubmissionScope ignored = TerrainSubmissionScope.begin()) {
            TerrainSubmissionScope.publish(snapshot);
            assertTrue(TerrainSubmissionScope.consume(state, commandBuffer, commands.size()));
            assertFalse(TerrainSubmissionScope.consume(state, commandBuffer, commands.size()),
                    "a consumed snapshot must not submit twice");

            TerrainSubmissionScope.publish(snapshot);
            int legacyFallbacks = 0;
            assertFalse(TerrainSubmissionScope.consume(state, differentCommandBuffer, commands.size()),
                    "same command allocation with a changed offset must fail closed");
            legacyFallbacks++;
            assertFalse(TerrainSubmissionScope.consume(stale, commandBuffer, commands.size()),
                    "a stale snapshot must be consumed and cannot replay a second time");
            assertEquals(1, legacyFallbacks,
                    "the stale snapshot leaves exactly one legacy fallback");
        }
    }

    private static TerrainSceneSnapshot.StateView state(
            final Object pipeline,
            final long sourceGeneration
    ) {
        List<TerrainSceneSnapshot.ResourceSlice> vertices = new ArrayList<>();
        vertices.add(TerrainSceneSnapshot.ResourceSlice.of(
                new Object(), new MetalAllocationIdentity(302L, 1L), 0L, 128L, 32, false
        ));
        while (vertices.size() < TerrainSceneSnapshot.MAX_VERTEX_BUFFERS) {
            vertices.add(TerrainSceneSnapshot.ResourceSlice.empty());
        }
        return new TerrainSceneSnapshot.StateView(
                pipeline,
                3L,
                4L,
                sourceGeneration,
                TerrainSceneSnapshot.ResourceSlice.of(
                        new Object(), new MetalAllocationIdentity(301L, 1L), 0L, 256L, 0, false
                ),
                MTLIndexType.UInt16,
                vertices
        );
    }

    private static TerrainSceneSnapshot.ResourceSlice commandBuffer(
            final Object resource,
            final MetalAllocationIdentity allocation,
            final long offset
    ) {
        return TerrainSceneSnapshot.ResourceSlice.of(
                resource, allocation, offset, 40L, 20, false
        );
    }
}
