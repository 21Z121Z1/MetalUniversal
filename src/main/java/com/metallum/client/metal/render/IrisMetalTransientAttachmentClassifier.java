package com.metallum.client.metal.render;

import java.util.Objects;
import java.util.Set;

/**
 * Converts a resolved physical attachment lifetime into a conservative storage
 * eligibility decision for Apple TBDR GPUs.
 *
 * <p>This class deliberately does not allocate a memoryless texture. It is the
 * semantic gate between the lifetime compiler and the physical allocation /
 * store policy: only fully-resolved dead outputs may drop their store, and only
 * an attachment born, written and dead in one render pass may subsequently be
 * considered pass-local transient. Everything else remains persistent.</p>
 */
final class IrisMetalTransientAttachmentClassifier {
    private IrisMetalTransientAttachmentClassifier() {
    }

    /**
     * Resolves the physical store action from authoritative allocation liveness.
     * Logical persistence always wins. A declared STORE can become DONT_CARE
     * only when the current pass is provably the allocation's final write and
     * final use and no later consumer exists in a completely resolved graph.
     */
    static IrisMetalOptimizationPlan.StoreAction resolvePhysicalStore(
            final IrisMetalOptimizationPlan.StoreAction declaredStore,
            final String logicalResource,
            final int passIndex,
            final IrisMetalOptimizationPlan.AttachmentLifetime lifetime,
            final Set<String> persistentResources,
            final boolean graphFullyResolved
    ) {
        Objects.requireNonNull(declaredStore, "declaredStore");
        Objects.requireNonNull(logicalResource, "logicalResource");
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(persistentResources, "persistentResources");
        if (declaredStore != IrisMetalOptimizationPlan.StoreAction.STORE
                || !graphFullyResolved
                || passIndex < 0
                || persistentResources.contains(logicalResource)
                || lifetime.lastWrite() != passIndex
                || lifetime.lastUse() != passIndex
                || lifetime.nextUse() != -1
                || !"NONE".equals(lifetime.nextUseAccess())) {
            return declaredStore;
        }
        return IrisMetalOptimizationPlan.StoreAction.DONT_CARE;
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
                || lifetime.lastUse() != passIndex
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
