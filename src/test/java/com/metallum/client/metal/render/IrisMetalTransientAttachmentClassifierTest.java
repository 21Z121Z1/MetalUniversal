package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalTransientAttachmentClassifierTest {
    private static IrisMetalOptimizationPlan.AttachmentLifetime lifetime(
            final int firstUse,
            final int lastWrite,
            final int lastUse,
            final int nextUse,
            final String nextAccess
    ) {
        return new IrisMetalOptimizationPlan.AttachmentLifetime(
                "allocation/7/generation/3/mip/0",
                7L,
                3L,
                0,
                firstUse,
                lastWrite,
                lastUse,
                nextUse,
                nextAccess
        );
    }

    @Test
    void singlePassDiscardedAttachmentIsMemorylessEligible() {
        var classification = IrisMetalTransientAttachmentClassifier.classify(
                IrisMetalOptimizationPlan.LoadAction.CLEAR,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                4,
                lifetime(4, 4, 4, -1, "NONE"),
                true
        );
        assertEquals(IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT, classification);
        assertTrue(IrisMetalTransientAttachmentClassifier.memorylessEligible(classification));
    }

    @Test
    void loadStoreAndFutureConsumersStayPersistent() {
        assertEquals(
                IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                IrisMetalTransientAttachmentClassifier.classify(
                        IrisMetalOptimizationPlan.LoadAction.LOAD,
                        IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                        2,
                        lifetime(2, 2, 2, -1, "NONE"),
                        true
                )
        );
        assertEquals(
                IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                IrisMetalTransientAttachmentClassifier.classify(
                        IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                        IrisMetalOptimizationPlan.StoreAction.STORE,
                        2,
                        lifetime(2, 2, 2, -1, "NONE"),
                        true
                )
        );
        assertEquals(
                IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                IrisMetalTransientAttachmentClassifier.classify(
                        IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                        IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                        2,
                        lifetime(2, 2, 3, 3, "SAMPLED_READ"),
                        true
                )
        );
    }

    @Test
    void aLaterAccessKeepsTheAttachmentPersistentEvenWithoutNextWrite() {
        var classification = IrisMetalTransientAttachmentClassifier.classify(
                IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                2,
                lifetime(2, 2, 3, -1, "NONE"),
                true
        );
        assertEquals(IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT, classification);
    }

    @Test
    void unresolvedGraphFailsClosed() {
        var classification = IrisMetalTransientAttachmentClassifier.classify(
                IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                1,
                lifetime(1, 1, 1, -1, "NONE"),
                false
        );
        assertEquals(IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT, classification);
        assertFalse(IrisMetalTransientAttachmentClassifier.memorylessEligible(classification));
    }
}
