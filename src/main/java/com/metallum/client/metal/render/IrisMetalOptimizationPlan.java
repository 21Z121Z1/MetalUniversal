package com.metallum.client.metal.render;

import com.metallum.client.validation.contract.PassType;
import com.metallum.client.validation.contract.SemanticPassIdResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-owned optimization plan. All switches are opt-in and default to
 * false so the local agent can enable one transformation at a time.
 */
final class IrisMetalOptimizationPlan {
    private static final IrisMetalAdvancedOptimizationConfig.Snapshot FEATURE_GATES =
            IrisMetalAdvancedOptimizationConfig.snapshot();
    static final boolean ENABLE_PASS_FUSION = FEATURE_GATES.renderPassFusion();
    static final boolean ENABLE_LOAD_STORE = FEATURE_GATES.attachmentLiveness();
    static final boolean ENABLE_COMPUTE_GROUPING = FEATURE_GATES.computeGrouping();
    static final boolean ENABLE_RESOURCE_PRUNING = FEATURE_GATES.depthLiveness();
    static final boolean ENABLE_FINAL_COLOR_FUSION = FEATURE_GATES.finalColorFusion();
    static final boolean ENABLE_ARGUMENT_TABLES = FEATURE_GATES.argumentTables();
    static final boolean ENABLE_ICB = FEATURE_GATES.indirectSubmission();
    static final boolean ENABLE_MEMORYLESS_ATTACHMENTS = FEATURE_GATES.memorylessAttachments();
    static final boolean ENABLE_HEAP_ALIASING = FEATURE_GATES.heapAliasing();

    enum LoadAction { DONT_CARE, LOAD, CLEAR }
    enum StoreAction { DONT_CARE, STORE }

    /** Creation-time plan binding remains physical-identity free. */
    enum BindingStatus { UNBOUND_DIAGNOSTIC_ONLY }

    private static String requireName(final String value, final String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    enum AttachmentResolution { RESOLVED_RASTER, UNRESOLVED_CONSERVATIVE }

    enum LifetimeClassification { CONSERVATIVE_PERSISTENT, PASS_LOCAL_TRANSIENT }

    /**
     * Immutable liveness for one concrete allocation/subresource. The compiler
     * derives {@code lastUse} from the maximum pass access, independently of
     * the last write, so a later read cannot be mistaken for attachment death.
     */
    record AttachmentLifetime(
            String allocationKey,
            long allocationId,
            long allocationGeneration,
            int mipLevel,
            int firstUse,
            int lastWrite,
            int lastUse,
            int nextUse,
            String nextUseAccess
    ) {
        AttachmentLifetime {
            requireName(allocationKey, "allocationKey");
            if (allocationId <= 0L || allocationGeneration <= 0L) {
                throw new IllegalArgumentException("Attachment allocation identity must be positive");
            }
            if (mipLevel < 0 || firstUse < 0 || lastWrite < -1
                    || lastUse < firstUse || lastUse < lastWrite || nextUse < -1) {
                throw new IllegalArgumentException("Invalid attachment lifetime range");
            }
            requireName(nextUseAccess, "nextUseAccess");
        }
    }

    /** One raster candidate bound to the physical side used by its pass. */
    record ResolvedAttachment(
            String planPassKey,
            String semanticPassId,
            int slot,
            String logicalResource,
            long allocationId,
            long allocationGeneration,
            int mipLevel,
            String physicalSide,
            LoadAction load,
            StoreAction store,
            int passIndex,
            AttachmentResolution resolution,
            LifetimeClassification classification,
            String allocationKey,
            AttachmentLifetime lifetime
    ) {
        ResolvedAttachment {
            requireName(planPassKey, "planPassKey");
            requireName(semanticPassId, "semanticPassId");
            requireName(logicalResource, "logicalResource");
            requireName(physicalSide, "physicalSide");
            requireName(allocationKey, "allocationKey");
            if (slot < 0 || passIndex < 0 || mipLevel < 0) {
                throw new IllegalArgumentException("Invalid attachment receipt position");
            }
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(classification, "classification");
            if (resolution == AttachmentResolution.RESOLVED_RASTER) {
                if (allocationId <= 0L || allocationGeneration <= 0L) {
                    throw new IllegalArgumentException("Resolved attachment identity must be positive");
                }
                Objects.requireNonNull(lifetime, "lifetime");
            } else if (lifetime != null || allocationId != 0L || allocationGeneration != 0L) {
                throw new IllegalArgumentException("Unresolved attachment must not carry a physical identity");
            }
        }
    }

    /** Generation-scoped, diagnostic-only physical attachment compiler output. */
    record AttachmentLifetimeReceipt(
            int chainGeneration,
            long targetEpoch,
            String targetSignature,
            String status,
            List<ResolvedAttachment> attachments,
            List<AttachmentLifetime> lifetimes,
            List<String> unresolvedConsumers
    ) {
        AttachmentLifetimeReceipt {
            if (chainGeneration < 0) {
                throw new IllegalArgumentException("Chain generation must be non-negative");
            }
            if (targetEpoch < 0L) {
                throw new IllegalArgumentException("Target epoch must be non-negative");
            }
            requireName(targetSignature, "targetSignature");
            requireName(status, "status");
            attachments = List.copyOf(attachments);
            lifetimes = List.copyOf(lifetimes);
            Objects.requireNonNull(unresolvedConsumers, "unresolvedConsumers");
            unresolvedConsumers = unresolvedConsumers.stream().sorted().toList();
        }
    }

    record AttachmentPolicy(String resource, LoadAction load, StoreAction store) {
        AttachmentPolicy {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
        }
    }

    /**
     * One immutable logical pass entry in the diagnostic plan receipt. This
     * is a view over the existing hazard descriptors, not another graph.
     */
    record PlanPass(
            String planPassKey,
            String semanticPassId,
            PassType type,
            String stage,
            int ordinal,
            List<IrisMetalHazardGraph.ResourceUse> logicalUses,
            List<AttachmentPolicy> attachmentCandidates,
            String attachmentCompatibilityKey,
            BindingStatus bindingStatus
    ) {
        PlanPass {
            requireName(planPassKey, "planPassKey");
            requireName(semanticPassId, "semanticPassId");
            Objects.requireNonNull(type, "type");
            requireName(stage, "stage");
            if (ordinal < 0) {
                throw new IllegalArgumentException("Plan pass ordinal must be non-negative");
            }
            logicalUses = sortedUses(logicalUses);
            attachmentCandidates = List.copyOf(attachmentCandidates);
            attachmentCompatibilityKey = attachmentCompatibilityKey == null ? "" : attachmentCompatibilityKey;
            Objects.requireNonNull(bindingStatus, "bindingStatus");
        }

        private static List<IrisMetalHazardGraph.ResourceUse> sortedUses(
                final List<IrisMetalHazardGraph.ResourceUse> uses
        ) {
            Objects.requireNonNull(uses, "logicalUses");
            List<IrisMetalHazardGraph.ResourceUse> frozen = new ArrayList<>(uses);
            frozen.sort(Comparator
                    .comparing(IrisMetalHazardGraph.ResourceUse::resource)
                    .thenComparing(use -> use.access().name()));
            return List.copyOf(frozen);
        }

        private static String requireName(final String value, final String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }

    record MergeGroup(int firstNode, int lastNode, List<String> names) {
        MergeGroup {
            names = List.copyOf(names);
            if (firstNode < 0 || lastNode < firstNode) {
                throw new IllegalArgumentException("Invalid merge group " + firstNode + ".." + lastNode);
            }
        }
    }

    record ResourceLiveness(
            Set<String> liveResources,
            Set<String> persistentResources,
            Set<String> deadResources,
            boolean depthtex1Required,
            boolean depthtex2Required
    ) {
        ResourceLiveness {
            liveResources = Set.copyOf(liveResources);
            persistentResources = Set.copyOf(persistentResources);
            deadResources = Set.copyOf(deadResources);
        }
    }

    record ArgumentSlot(String name, Kind kind, int index, boolean writable) {
        enum Kind { BUFFER, TEXTURE, SAMPLER }

        ArgumentSlot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            if (index < 0) throw new IllegalArgumentException("Argument index must be non-negative");
        }
    }

    record ArgumentLayout(List<ArgumentSlot> slots, long stableHash) {
        ArgumentLayout {
            slots = List.copyOf(slots);
        }

        static ArgumentLayout of(final List<ArgumentSlot> slots) {
            long hash = 0xcbf29ce484222325L;
            for (ArgumentSlot slot : slots) {
                hash ^= slot.name().hashCode();
                hash *= 0x100000001b3L;
                hash ^= slot.kind().ordinal();
                hash *= 0x100000001b3L;
                hash ^= slot.index();
                hash *= 0x100000001b3L;
                hash ^= slot.writable() ? 1 : 0;
                hash *= 0x100000001b3L;
            }
            return new ArgumentLayout(slots, hash);
        }
    }

    record IndirectBatch(
            String pipelineKey,
            String attachmentKey,
            int firstDraw,
            int drawCount,
            boolean indexed
    ) {
        IndirectBatch {
            Objects.requireNonNull(pipelineKey, "pipelineKey");
            Objects.requireNonNull(attachmentKey, "attachmentKey");
            if (firstDraw < 0 || drawCount <= 0) {
                throw new IllegalArgumentException("Invalid indirect draw range");
            }
        }
    }

    private final int chainGeneration;
    private final IrisMetalHazardGraph hazards;
    private final List<PlanPass> passReceipt;
    private final List<MergeGroup> renderMergeGroups;
    private final List<MergeGroup> computeMergeGroups;
    private final Map<Integer, List<AttachmentPolicy>> attachmentPolicies;
    private final ResourceLiveness resourceLiveness;
    private final Map<String, ArgumentLayout> argumentLayouts;
    private final List<IndirectBatch> indirectBatches;
    private final AttachmentLifetimeReceipt attachmentLifetimeReceipt;

    private IrisMetalOptimizationPlan(
            final int chainGeneration,
            final IrisMetalHazardGraph hazards,
            final List<PlanPass> passReceipt,
            final List<MergeGroup> renderMergeGroups,
            final List<MergeGroup> computeMergeGroups,
            final Map<Integer, List<AttachmentPolicy>> attachmentPolicies,
            final ResourceLiveness resourceLiveness,
            final Map<String, ArgumentLayout> argumentLayouts,
            final List<IndirectBatch> indirectBatches,
            final AttachmentLifetimeReceipt attachmentLifetimeReceipt
    ) {
        if (chainGeneration < 0) {
            throw new IllegalArgumentException("Chain generation must be non-negative");
        }
        this.chainGeneration = chainGeneration;
        this.hazards = hazards;
        this.passReceipt = freezePassReceipt(passReceipt);
        this.renderMergeGroups = List.copyOf(renderMergeGroups);
        this.computeMergeGroups = List.copyOf(computeMergeGroups);
        Map<Integer, List<AttachmentPolicy>> frozen = new LinkedHashMap<>();
        attachmentPolicies.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        this.attachmentPolicies = Map.copyOf(frozen);
        this.resourceLiveness = resourceLiveness;
        this.argumentLayouts = Map.copyOf(argumentLayouts);
        this.indirectBatches = List.copyOf(indirectBatches);
        this.attachmentLifetimeReceipt = attachmentLifetimeReceipt;
    }

    static Builder builder(final int chainGeneration, final IrisMetalHazardGraph hazards) {
        return new Builder(chainGeneration, hazards);
    }

    static Builder builder(final IrisMetalHazardGraph hazards) {
        return builder(0, hazards);
    }

    int chainGeneration() { return chainGeneration; }
    IrisMetalHazardGraph hazards() { return hazards; }
    List<PlanPass> passReceipt() { return passReceipt; }
    List<MergeGroup> renderMergeGroups() { return renderMergeGroups; }
    List<MergeGroup> computeMergeGroups() { return computeMergeGroups; }
    Map<Integer, List<AttachmentPolicy>> attachmentPolicies() { return attachmentPolicies; }
    ResourceLiveness resourceLiveness() { return resourceLiveness; }
    Map<String, ArgumentLayout> argumentLayouts() { return argumentLayouts; }
    List<IndirectBatch> indirectBatches() { return indirectBatches; }
    AttachmentLifetimeReceipt attachmentLifetimeReceipt() { return attachmentLifetimeReceipt; }

    static IrisMetalOptimizationPlan withAttachmentLifetimeReceipt(
            final IrisMetalOptimizationPlan source,
            final AttachmentLifetimeReceipt receipt
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(receipt, "receipt");
        if (source.chainGeneration != receipt.chainGeneration()) {
            throw new IllegalArgumentException("Attachment receipt generation does not match plan");
        }
        return new IrisMetalOptimizationPlan(
                source.chainGeneration,
                source.hazards,
                source.passReceipt,
                source.renderMergeGroups,
                source.computeMergeGroups,
                source.attachmentPolicies,
                source.resourceLiveness,
                source.argumentLayouts,
                source.indirectBatches,
                receipt
        );
    }

    static final class Builder {
        private final int chainGeneration;
        private final IrisMetalHazardGraph hazards;
        private final List<PlanPass> passReceipt = new ArrayList<>();
        private final List<MergeGroup> renderMergeGroups = new ArrayList<>();
        private final List<MergeGroup> computeMergeGroups = new ArrayList<>();
        private final Map<Integer, List<AttachmentPolicy>> attachmentPolicies = new LinkedHashMap<>();
        private final Set<String> allResources = new LinkedHashSet<>();
        private final Set<String> liveResources = new LinkedHashSet<>();
        private final Set<String> persistentResources = new LinkedHashSet<>();
        private final Map<String, ArgumentLayout> argumentLayouts = new LinkedHashMap<>();
        private final List<IndirectBatch> indirectBatches = new ArrayList<>();

        Builder(final int chainGeneration, final IrisMetalHazardGraph hazards) {
            if (chainGeneration < 0) {
                throw new IllegalArgumentException("Chain generation must be non-negative");
            }
            this.chainGeneration = chainGeneration;
            this.hazards = Objects.requireNonNull(hazards, "hazards");
            for (IrisMetalHazardGraph.Node node : hazards.nodes()) {
                for (IrisMetalHazardGraph.ResourceUse use : node.uses()) {
                    allResources.add(use.resource());
                    liveResources.add(use.resource());
                }
            }
        }

        Builder passReceipt(final PlanPass pass) {
            passReceipt.add(Objects.requireNonNull(pass, "pass"));
            return this;
        }

        Builder markPersistent(final String resource) {
            liveResources.add(resource);
            persistentResources.add(resource);
            allResources.add(resource);
            return this;
        }

        Builder markDead(final String resource) {
            if (!persistentResources.contains(resource)) {
                liveResources.remove(resource);
            }
            allResources.add(resource);
            return this;
        }

        Builder attachmentPolicy(final int node, final List<AttachmentPolicy> policies) {
            attachmentPolicies.put(node, List.copyOf(policies));
            return this;
        }

        Builder argumentLayout(final String programKey, final ArgumentLayout layout) {
            argumentLayouts.put(programKey, layout);
            return this;
        }

        Builder indirectBatch(final IndirectBatch batch) {
            indirectBatches.add(batch);
            return this;
        }

        Builder deriveAdjacentMergeGroups() {
            deriveGroups(renderMergeGroups, "render/");
            deriveGroups(computeMergeGroups, "compute/");
            return this;
        }

        private void deriveGroups(final List<MergeGroup> output, final String prefix) {
            int start = -1;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < hazards.nodes().size(); i++) {
                IrisMetalHazardGraph.Node node = hazards.nodes().get(i);
                boolean matching = node.name().startsWith(prefix);
                if (!matching) {
                    flush(output, start, i - 1, names);
                    start = -1;
                    names = new ArrayList<>();
                    continue;
                }
                if (start < 0) {
                    start = i;
                    names.add(node.name());
                    continue;
                }
                if (mayMergeAdjacent(i - 1, i, prefix)) {
                    names.add(node.name());
                } else {
                    flush(output, start, i - 1, names);
                    start = i;
                    names = new ArrayList<>();
                    names.add(node.name());
                }
            }
            flush(output, start, hazards.nodes().size() - 1, names);
        }

        private boolean mayMergeAdjacent(final int first, final int second, final String prefix) {
            if (!hazards.mayMergeAdjacent(first, second)
                    || first < 0
                    || second >= passReceipt.size()) {
                return false;
            }
            PlanPass previous = passReceipt.get(first);
            PlanPass current = passReceipt.get(second);
            PassType expected = "render/".equals(prefix) ? PassType.RENDER : PassType.COMPUTE;
            if (previous.type() != expected || current.type() != expected
                    || !previous.stage().equals(current.stage())) {
                return false;
            }
            return expected != PassType.RENDER
                    || previous.attachmentCompatibilityKey().equals(current.attachmentCompatibilityKey());
        }

        private static void flush(
                final List<MergeGroup> output,
                final int start,
                final int end,
                final List<String> names
        ) {
            if (start >= 0 && end > start && names.size() > 1) {
                output.add(new MergeGroup(start, end, names));
            }
        }

        IrisMetalOptimizationPlan build() {
            Set<String> dead = new LinkedHashSet<>(allResources);
            dead.removeAll(liveResources);
            ResourceLiveness liveness = new ResourceLiveness(
                    liveResources,
                    persistentResources,
                    dead,
                    liveResources.contains("depthtex1"),
                    liveResources.contains("depthtex2")
            );
            return new IrisMetalOptimizationPlan(
                    chainGeneration,
                    hazards,
                    passReceipt,
                    renderMergeGroups,
                    computeMergeGroups,
                    attachmentPolicies,
                    liveness,
                    argumentLayouts,
                    indirectBatches,
                    null
            );
        }
    }

    static PlanPass passReceipt(
            final String stage,
            final PassType type,
            final int ordinal,
            final String rawName,
            final List<IrisMetalHazardGraph.ResourceUse> uses,
            final List<AttachmentPolicy> attachments,
            final String attachmentCompatibilityKey
    ) {
        String planPassKey = stablePlanPassKey(stage, type, ordinal, rawName);
        return new PlanPass(
                planPassKey,
                SemanticPassIdResolver.resolve(planPassKey, type),
                type,
                normalizeToken(stage),
                ordinal,
                uses,
                attachments,
                attachmentCompatibilityKey,
                BindingStatus.UNBOUND_DIAGNOSTIC_ONLY
        );
    }

    static String stablePlanPassKey(
            final String stage,
            final PassType type,
            final int ordinal,
            final String rawName
    ) {
        Objects.requireNonNull(type, "type");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Plan pass ordinal must be non-negative");
        }
        return "iris/"
                + normalizeToken(stage)
                + "/"
                + normalizeToken(type.name())
                + "/"
                + ordinal
                + "/"
                + normalizeToken(rawName);
    }

    private static String normalizeToken(final String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^[-.]|[-.]$)", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static List<PlanPass> freezePassReceipt(final List<PlanPass> passes) {
        Objects.requireNonNull(passes, "passReceipt");
        Map<String, PlanPass> byKey = new LinkedHashMap<>();
        for (PlanPass pass : passes) {
            PlanPass previous = byKey.put(pass.planPassKey(), pass);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate plan pass key: " + pass.planPassKey());
            }
        }
        return List.copyOf(byKey.values());
    }
}
