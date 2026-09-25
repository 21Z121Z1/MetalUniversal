package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxRuntimeSettingsTest {
    private static final MetalFxConfig.RuntimeSettings BASE =
            new MetalFxConfig.RuntimeSettings(
                    MetalFxConfig.Mode.TEMPORAL,
                    0.5F,
                    true,
                    false,
                    false
            );

    @Test
    void hudIsPersistedForNextStartupWithoutRebuildingCurrentMetalFx() {
        var hudEnabled = new MetalFxConfig.RuntimeSettings(
                BASE.mode(),
                BASE.scale(),
                BASE.transparencyReactiveMask(),
                BASE.frameGeneration(),
                true
        );

        assertFalse(hudEnabled.requiresRenderRefreshComparedTo(BASE));
        assertFalse(hudEnabled.requiresShaderRefreshComparedTo(BASE));
    }

    @Test
    void scaleAndModeChangesInvalidateShaderPipelines() {
        var newScale = new MetalFxConfig.RuntimeSettings(
                BASE.mode(), 0.67F, true, false, false
        );
        var newMode = new MetalFxConfig.RuntimeSettings(
                MetalFxConfig.Mode.SPATIAL, BASE.scale(), true, false, false
        );

        assertTrue(newScale.requiresRenderRefreshComparedTo(BASE));
        assertTrue(newScale.requiresShaderRefreshComparedTo(BASE));
        assertTrue(newMode.requiresRenderRefreshComparedTo(BASE));
        assertTrue(newMode.requiresShaderRefreshComparedTo(BASE));
    }

    @Test
    void reactiveMaskAndFrameGenerationRebuildMetalFxButKeepMaterialPsos() {
        var reactiveDisabled = new MetalFxConfig.RuntimeSettings(
                BASE.mode(), BASE.scale(), false, false, false
        );
        var frameGenerationEnabled = new MetalFxConfig.RuntimeSettings(
                BASE.mode(), BASE.scale(), true, true, false
        );

        assertTrue(reactiveDisabled.requiresRenderRefreshComparedTo(BASE));
        assertFalse(reactiveDisabled.requiresShaderRefreshComparedTo(BASE));
        assertTrue(frameGenerationEnabled.requiresRenderRefreshComparedTo(BASE));
        assertFalse(frameGenerationEnabled.requiresShaderRefreshComparedTo(BASE));
    }
}
