package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unified, generation-aware resource decision produced from the physical
 * attachment receipt.
 *
 * <p>This is the single hand-off between semantic liveness and allocation:
 * every entry records its identity, lifetime, load/store contract and the
 * exact admission reason.  Native allocation code may consume only entries
 * marked {@code MEMORYLESS}; all other entries remain dedicated/private.</p>
 */
final class IrisMetalTransientResourcePlan {
    record Entry(
            String resourceKey,
            long allocationId,
            long allocationGeneration,
            int firstUse,
            int lastUse,
            IrisMetalOptimizationPlan.LoadAction load,
            IrisMetalOptimizationPlan.StoreAction store,
            String allocationMode,
            String decision,
            String reason
    ) {
        Entry {
            Objects.requireNonNull(resourceKey, "resourceKey");
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(allocationMode, "allocationMode");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
            if (resourceKey.isBlank() || allocationId <= 0L || allocationGeneration <= 0L
                    || firstUse < 0 || lastUse < firstUse) {
                throw new IllegalArgumentException("Invalid transient resource entry");
            }
        }
    }

    record Plan(
            int chainGeneration,
            long targetEpoch,
            String status,
            List<Entry> entries,
            List<String> rejectionReasons
    ) {
        Plan {
            if (chainGeneration < 0 || targetEpoch < 0L) {
                throw new IllegalArgumentException("Invalid transient plan identity");
            }
            Objects.requireNonNull(status, "status");
            entries = List.copyOf(entries);
            rejectionReasons = List.copyOf(rejectionReasons);
        }

        long memorylessCount() {
            return entries.stream().filter(entry -> "MEMORYLESS".equals(entry.allocationMode())).count();
        }

        boolean executable() {
            return "RESOLVED".equals(status) && rejectionReasons.isEmpty();
        }
    }

    private IrisMetalTransientResourcePlan() {
    }

    static Plan compile(final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        List<Entry> entries = new ArrayList<>();
        List<String> reasons = new ArrayList<>(receipt.unresolvedConsumers());
        if (!"RESOLVED_CONSERVATIVE".equals(receipt.status()) || !reasons.isEmpty()) {
            return new Plan(
                    receipt.chainGeneration(),
                    receipt.targetEpoch(),
                    "UNRESOLVED",
                    entries,
                    reasons.isEmpty() ? List.of("attachment-receipt-not-fully-resolved") : reasons
            );
        }

        for (IrisMetalOptimizationPlan.ResolvedAttachment attachment : receipt.attachments()) {
            IrisMetalOptimizationPlan.AttachmentLifetime lifetime = attachment.lifetime();
            if (attachment.resolution() != IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER
                    || lifetime == null) {
                reasons.add("unresolved-attachment:" + attachment.logicalResource());
                continue;
            }
            IrisMetalTransientAttachmentClassifier.MemorylessAdmission admission =
                    IrisMetalTransientAttachmentClassifier.admitMemoryless(
                            attachment.load(),
                            attachment.store(),
                            attachment.passIndex(),
                            lifetime,
                            true
                    );
            boolean admitted = admission.admitted();
            String mode = admitted && IrisMetalOptimizationPlan.ENABLE_MEMORYLESS_ATTACHMENTS
                    ? "MEMORYLESS"
                    : "DEDICATED";
            String decision = admitted ? "ADMITTED" : "REJECTED";
            String reason = admitted && !IrisMetalOptimizationPlan.ENABLE_MEMORYLESS_ATTACHMENTS
                    ? "feature-disabled"
                    : admission.reason();
            entries.add(new Entry(
                    attachment.logicalResource() + "/" + attachment.physicalSide()
                            + "/mip/" + attachment.mipLevel(),
                    lifetime.allocationId(),
                    lifetime.allocationGeneration(),
                    lifetime.firstUse(),
                    lifetime.lastUse(),
                    attachment.load(),
                    attachment.store(),
                    mode,
                    decision,
                    reason
            ));
        }
        return new Plan(
                receipt.chainGeneration(),
                receipt.targetEpoch(),
                reasons.isEmpty() ? "RESOLVED" : "UNRESOLVED",
                entries,
                reasons
        );
    }
}
