package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalShadowTargetOwnershipTest {
    @Test
    void onlyAnActiveIrisShadowPassBypassesTheMainFrameGraphTarget() {
        assertTrue(IrisMetalPipelineOverrides.shouldBypassTerrainTargetEvaluation(true, true));
        assertFalse(IrisMetalPipelineOverrides.shouldBypassTerrainTargetEvaluation(true, false));
        assertFalse(IrisMetalPipelineOverrides.shouldBypassTerrainTargetEvaluation(false, true));
    }
}
