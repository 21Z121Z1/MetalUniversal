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
 * transient. Everything else remains persistent. The structured admission
 * result is generation-aware and carries a stable rejection reason so an
 * allocator can fail closed without guessing from a semantic resource name.</p>
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
        return admitMemoryless(load, store, passIndex, lifetime, graphFullyResolved).admitted()
                ? IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT
                : IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT;
    }

    /**
     * Returns the complete, fail-closed contract a future memoryless allocator
     * must consume.  This is intentionally separate from allocation and is
     * not a feature toggle: no native path calls it to change storage mode yet.
     */
    static MemorylessAdmission admitMemoryless(
            final IrisMetalOptimizationPlan.LoadAction load,
            final IrisMetalOptimizationPlan.StoreAction store,
            final int passIndex,
            final IrisMetalOptimizationPlan.AttachmentLifetime lifetime,
            final boolean graphFullyResolved
    ) {
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(lifetime, "lifetime");
        if (!graphFullyResolved) {
            return rejected(lifetime, "graph-unresolved");
        }
        if (passIndex < 0) {
            return rejected(lifetime, "pass-index-invalid");
        }
        if (load == IrisMetalOptimizationPlan.LoadAction.LOAD) {
            return rejected(lifetime, "load-requires-previous-content");
        }
        if (store != IrisMetalOptimizationPlan.StoreAction.DONT_CARE) {
            return rejected(lifetime, "store-requires-persistence");
        }
        if (lifetime.firstUse() != passIndex) {
            return rejected(lifetime, "first-use-not-this-pass");
        }
        if (lifetime.lastWrite() != passIndex) {
            return rejected(lifetime, "last-write-not-this-pass");
        }
        if (lifetime.lastUse() != passIndex) {
            return rejected(lifetime, "last-use-after-this-pass");
        }
        if (lifetime.nextUse() != -1) {
            return rejected(lifetime, "future-use-present");
        }
        if (!"NONE".equals(lifetime.nextUseAccess())) {
            return rejected(lifetime, "future-access-not-none");
        }
        return new MemorylessAdmission(
                lifetime.allocationKey(),
                lifetime.allocationId(),
                lifetime.allocationGeneration(),
                Decision.ADMITTED,
                "pass-local-lifetime"
        );
    }

    static boolean memorylessEligible(
            final IrisMetalOptimizationPlan.LifetimeClassification classification
    ) {
        return classification == IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT;
    }

    private static MemorylessAdmission rejected(
            final IrisMetalOptimizationPlan.AttachmentLifetime lifetime,
            final String reason
    ) {
        return new MemorylessAdmission(
                lifetime.allocationKey(),
                lifetime.allocationId(),
                lifetime.allocationGeneration(),
                Decision.REJECTED,
                reason
        );
    }

    enum Decision { ADMITTED, REJECTED }

    record MemorylessAdmission(
            String allocationKey,
            long allocationId,
            long allocationGeneration,
            Decision decision,
            String reason
    ) {
        MemorylessAdmission {
            Objects.requireNonNull(allocationKey, "allocationKey");
            if (allocationKey.isBlank()) {
                throw new IllegalArgumentException("allocationKey must not be blank");
            }
            if (allocationId <= 0L || allocationGeneration <= 0L) {
                throw new IllegalArgumentException("Memoryless admission identity must be positive");
            }
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Admission reason must not be blank");
            }
        }

        boolean admitted() {
            return decision == Decision.ADMITTED;
        }
    }
}
