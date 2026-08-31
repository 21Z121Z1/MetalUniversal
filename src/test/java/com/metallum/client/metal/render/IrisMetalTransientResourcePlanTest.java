package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalTransientResourcePlanTest {
    @Test
    void resolvedReceiptPreservesIdentityAndRecordsFeatureDisabledMode() {
        var lifetime = new IrisMetalOptimizationPlan.AttachmentLifetime(
                "allocation/9/generation/2/mip/0", 9L, 2L, 0,
                4, 4, 4, -1, "NONE"
        );
        var attachment = new IrisMetalOptimizationPlan.ResolvedAttachment(
                "plan/4", "semantic/4", 0, "colortex0",
                9L, 2L, 0, "main",
                IrisMetalOptimizationPlan.LoadAction.CLEAR,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                4,
                IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER,
                IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT,
                lifetime.allocationKey(), lifetime
        );
        var receipt = new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                4, 22L, "targets", "RESOLVED_CONSERVATIVE",
                List.of(attachment), List.of(lifetime), List.of()
        );

        var plan = IrisMetalTransientResourcePlan.compile(receipt);
        assertTrue(plan.executable());
        assertEquals(1, plan.entries().size());
        assertEquals("DEDICATED", plan.entries().getFirst().allocationMode());
        assertEquals("feature-disabled", plan.entries().getFirst().reason());
        assertEquals(0L, plan.memorylessCount());
    }

    @Test
    void unresolvedReceiptNeverProducesAnExecutableAllocationPlan() {
        var receipt = new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                4, 22L, "targets", "UNRESOLVED_CONSERVATIVE",
                List.of(), List.of(), List.of("unknown-consumer")
        );
        var plan = IrisMetalTransientResourcePlan.compile(receipt);
        assertFalse(plan.executable());
        assertEquals("UNRESOLVED", plan.status());
        assertEquals(List.of("unknown-consumer"), plan.rejectionReasons());
    }
}
