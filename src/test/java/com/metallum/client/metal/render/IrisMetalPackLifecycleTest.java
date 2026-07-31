package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalPackLifecycleTest {
    @Test
    void loadsOnlyWhenTheMetalPipelineAndShadersAreEnabled() {
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(false, false));
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(false, true));
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, false));
        assertTrue(IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, true));
    }

    @Test
    void consumesOnlyTheLiveGenerationDisabledTransition() {
        IrisMetalPackLifecycle.onSemanticPipelineActivated();
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));

        IrisMetalPackLifecycle.onSemanticPipelineDestroyed();
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, true));
        assertTrue(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
    }
}
