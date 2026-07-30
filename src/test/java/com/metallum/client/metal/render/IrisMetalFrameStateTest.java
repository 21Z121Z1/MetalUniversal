package com.metallum.client.metal.render;

import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalFrameStateTest {
    @Test
    void overrideRequiresAnActiveWorldMainTargetAndMatchingDrawTarget() {
        IrisMetalFrameState state = new IrisMetalFrameState();

        assertFalse(state.shouldOverrideShaders(true));
        state.beginWorldRendering();
        assertTrue(state.shouldOverrideShaders(true));
        assertFalse(state.shouldOverrideShaders(false));
        state.setMainBound(false);
        assertFalse(state.shouldOverrideShaders(true));
        state.setMainBound(true);
        state.endWorldRendering();
        assertFalse(state.shouldOverrideShaders(true));
    }

    @Test
    void phaseRemovalAndTemporaryOverrideMatchIrisBoundaries() {
        IrisMetalFrameState state = new IrisMetalFrameState();

        assertSame(WorldRenderingPhase.NONE, state.phase());
        state.setPhase(WorldRenderingPhase.TERRAIN_SOLID);
        assertSame(WorldRenderingPhase.TERRAIN_SOLID, state.phase());
        state.setOverridePhase(WorldRenderingPhase.ENTITIES);
        assertSame(WorldRenderingPhase.ENTITIES, state.phase());
        state.setOverridePhase(null);
        assertSame(WorldRenderingPhase.TERRAIN_SOLID, state.phase());
        state.setPhase(WorldRenderingPhase.NONE);
        assertSame(WorldRenderingPhase.NONE, state.phase());
    }
}
