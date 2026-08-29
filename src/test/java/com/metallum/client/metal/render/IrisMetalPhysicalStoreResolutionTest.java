package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class IrisMetalPhysicalStoreResolutionTest {
    private static IrisMetalOptimizationPlan.AttachmentLifetime lifetime(
            final int firstUse,
            final int lastWrite,
            final int lastUse,
            final int nextUse,
            final String nextUseAccess
    ) {
        return new IrisMetalOptimizationPlan.AttachmentLifetime(
                "allocation/17/generation/2/mip/0",
                17L,
                2L,
                0,
                firstUse,
                lastWrite,
                lastUse,
                nextUse,
                nextUseAccess
        );
    }

    @Test
    void deadResolvedNonPersistentOutputDropsStore() {
        assertEquals(
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                IrisMetalTransientAttachmentClassifier.resolvePhysicalStore(
                        IrisMetalOptimizationPlan.StoreAction.STORE,
                        "colortex4",
                        3,
                        lifetime(3, 3, 3, -1, "NONE"),
                        Set.of("colortex0"),
                        true
                )
        );
    }

    @Test
    void persistenceOrAnyFutureUseKeepsStore() {
        IrisMetalOptimizationPlan.AttachmentLifetime dead = lifetime(3, 3, 3, -1, "NONE");
        assertEquals(
                IrisMetalOptimizationPlan.StoreAction.STORE,
                IrisMetalTransientAttachmentClassifier.resolvePhysicalStore(
                        IrisMetalOptimizationPlan.StoreAction.STORE,
                        "colortex4",
                        3,
                        dead,
                        Set.of("colortex4"),
                        true
                )
        );
        assertEquals(
                IrisMetalOptimizationPlan.StoreAction.STORE,
                IrisMetalTransientAttachmentClassifier.resolvePhysicalStore(
                        IrisMetalOptimizationPlan.StoreAction.STORE,
                        "colortex4",
                        3,
                        lifetime(3, 3, 4, 4, "SAMPLED_READ"),
                        Set.of(),
                        true
                )
        );
        assertEquals(
                IrisMetalOptimizationPlan.StoreAction.STORE,
                IrisMetalTransientAttachmentClassifier.resolvePhysicalStore(
                        IrisMetalOptimizationPlan.StoreAction.STORE,
                        "colortex4",
                        3,
                        dead,
                        Set.of(),
                        false
                )
        );
    }
}
