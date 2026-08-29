package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalHeapAliasRecipeTest {
    @Test
    void reusesOneSlotOnlyAcrossStrictlyDisjointClosedLifetimes() {
        var a = lifetime("allocation/1/generation/1/mip/0", 1L, 0, 1);
        var b = lifetime("allocation/2/generation/1/mip/0", 2L, 2, 4);
        var c = lifetime("allocation/3/generation/1/mip/0", 3L, 1, 3);
        var receipt = receipt(
                List.of(
                        attachment("colortex0", "main", a, 0),
                        attachment("colortex1", "alt", b, 2),
                        attachment("colortex2", "main", c, 1)
                ),
                List.of(a, b, c)
        );
        IrisMetalHeapAliasRecipe.Recipe recipe = IrisMetalHeapAliasRecipe.compile(receipt);
        assertTrue(recipe.executable());
        assertEquals(1, recipe.aliasSlots().size());
        assertEquals(2, recipe.aliasedResourceCount());
        assertEquals(List.of("colortex0/main/mip/0", "colortex1/alt/mip/0"),
                recipe.aliasSlots().getFirst().members().stream()
                        .map(IrisMetalHeapAliasRecipe.Member::resourceKey).toList());
        assertEquals(1, recipe.aliasSlots().getFirst().handoffs().getFirst().afterPass());
        assertEquals(2, recipe.aliasSlots().getFirst().handoffs().getFirst().beforePass());
        assertEquals(List.of("colortex2/main/mip/0"),
                recipe.dedicatedMembers().stream()
                        .map(IrisMetalHeapAliasRecipe.Member::resourceKey).toList());
    }

    @Test
    void touchingClosedIntervalsDoNotAlias() {
        var a = lifetime("allocation/1/generation/1/mip/0", 1L, 0, 2);
        var b = lifetime("allocation/2/generation/1/mip/0", 2L, 2, 4);
        IrisMetalHeapAliasRecipe.Recipe recipe = IrisMetalHeapAliasRecipe.compile(receipt(
                List.of(
                        attachment("colortex0", "main", a, 0),
                        attachment("colortex1", "main", b, 2)
                ),
                List.of(a, b)
        ));
        assertTrue(recipe.executable());
        assertTrue(recipe.aliasSlots().isEmpty());
        assertEquals(2, recipe.dedicatedMembers().size());
    }

    @Test
    void unresolvedReceiptFailsClosed() {
        var receipt = new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                7, 4L, "targets", "UNRESOLVED_CONSERVATIVE",
                List.of(), List.of(), List.of("compute-consumer")
        );
        IrisMetalHeapAliasRecipe.Recipe recipe = IrisMetalHeapAliasRecipe.compile(receipt);
        assertFalse(recipe.executable());
        assertEquals(List.of("attachment-receipt-not-fully-resolved"), recipe.rejectedReasons());
    }

    private static IrisMetalOptimizationPlan.AttachmentLifetime lifetime(
            final String allocationKey, final long allocationId,
            final int firstUse, final int lastUse
    ) {
        return new IrisMetalOptimizationPlan.AttachmentLifetime(
                allocationKey, allocationId, 1L, 0,
                firstUse, firstUse, lastUse, -1, "NONE"
        );
    }

    private static IrisMetalOptimizationPlan.ResolvedAttachment attachment(
            final String logical, final String side,
            final IrisMetalOptimizationPlan.AttachmentLifetime lifetime,
            final int pass
    ) {
        return new IrisMetalOptimizationPlan.ResolvedAttachment(
                "iris/composite/render/" + pass + "/" + logical,
                "semantic/" + logical,
                0, logical,
                lifetime.allocationId(), lifetime.allocationGeneration(),
                lifetime.mipLevel(), side,
                IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                IrisMetalOptimizationPlan.StoreAction.STORE,
                pass,
                IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER,
                IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                lifetime.allocationKey(), lifetime
        );
    }

    private static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt(
            final List<IrisMetalOptimizationPlan.ResolvedAttachment> attachments,
            final List<IrisMetalOptimizationPlan.AttachmentLifetime> lifetimes
    ) {
        return new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                7, 4L, "targets", "RESOLVED_CONSERVATIVE",
                attachments, lifetimes, List.of()
        );
    }
}
