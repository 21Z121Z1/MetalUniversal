package com.metallum.client.metal.render;

import java.util.Objects;

/**
 * Converts a resolved physical attachment lifetime into a conservative storage
 * eligibility decision for Apple TBDR GPUs.
 *
 * <p>This class deliberately does not allocate a memoryless texture. It is the
 * semantic gate between the lifetime compiler and a future allocation policy:
 * only a fully-resolved attachment that is born, written and dies in one render
 * pass, never needs a load, and never needs a store may be considered pass-local
 * transient. Everything else remains persistent.</p>
 */
final class IrisMetalTransientAttachmentClassifier {
    private IrisMetalTransientAttachmentClassifier() {
    }

    static IrisMetalOptimizationPlan.LifetimeClassification classify(
            final IrisMetalOptimizationPlan.LoadAction load,
            final IrisMetalOptimizationPlan.StoreAction store,
            final int passIndex,
            final IrisMetalOptimizationPlan.AttachmentLifetime lifetime,
            final boolean graphFullyResolved
    ) {
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(lifetime, "lifetime");
        if (!graphFullyResolved
                || passIndex < 0
                || load == IrisMetalOptimizationPlan.LoadAction.LOAD
                || store != IrisMetalOptimizationPlan.StoreAction.DONT_CARE
                || lifetime.firstUse() != passIndex
                || lifetime.lastWrite() != passIndex
                || lifetime.nextUse() != -1
                || !"NONE".equals(lifetime.nextUseAccess())) {
            return IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT;
        }
        return IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT;
    }

    static boolean memorylessEligible(
            final IrisMetalOptimizationPlan.LifetimeClassification classification
    ) {
        return classification == IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT;
    }
}
