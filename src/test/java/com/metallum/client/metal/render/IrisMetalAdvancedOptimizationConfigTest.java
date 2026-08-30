package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalAdvancedOptimizationConfigTest {
    @Test
    void stableValueWinsOverLegacyAliasIncludingExplicitFalse() {
        assertTrue(IrisMetalAdvancedOptimizationConfig.resolveAlias("true", "false", false));
        assertFalse(IrisMetalAdvancedOptimizationConfig.resolveAlias("false", "true", true));
    }

    @Test
    void legacyAliasIsUsedOnlyWhenStableValueIsAbsent() {
        assertTrue(IrisMetalAdvancedOptimizationConfig.resolveAlias(null, "true", false));
        assertFalse(IrisMetalAdvancedOptimizationConfig.resolveAlias(null, "false", true));
    }

    @Test
    void missingValuesUseTheDeclaredFallback() {
        assertFalse(IrisMetalAdvancedOptimizationConfig.resolveAlias(null, null, false));
        assertTrue(IrisMetalAdvancedOptimizationConfig.resolveAlias(null, null, true));
    }

    @Test
    void optimizationPlanUsesTheSameResolvedFeatureSnapshot() {
        IrisMetalAdvancedOptimizationConfig.Snapshot config =
                IrisMetalAdvancedOptimizationConfig.snapshot();

        assertEquals(config.renderPassFusion(), IrisMetalOptimizationPlan.ENABLE_PASS_FUSION);
        assertEquals(config.attachmentLiveness(), IrisMetalOptimizationPlan.ENABLE_LOAD_STORE);
        assertEquals(config.computeGrouping(), IrisMetalOptimizationPlan.ENABLE_COMPUTE_GROUPING);
        assertEquals(config.depthLiveness(), IrisMetalOptimizationPlan.ENABLE_RESOURCE_PRUNING);
        assertEquals(config.finalColorFusion(), IrisMetalOptimizationPlan.ENABLE_FINAL_COLOR_FUSION);
        assertEquals(config.argumentTables(), IrisMetalOptimizationPlan.ENABLE_ARGUMENT_TABLES);
        assertEquals(config.indirectSubmission(), IrisMetalOptimizationPlan.ENABLE_ICB);
    }
}
