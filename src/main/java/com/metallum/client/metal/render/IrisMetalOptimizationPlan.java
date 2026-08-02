package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-owned optimization plan. All switches are opt-in and default to
 * false so the local agent can enable one transformation at a time.
 */
final class IrisMetalOptimizationPlan {
    static final boolean ENABLE_PASS_FUSION = Boolean.getBoolean("metallum.iris.experimental.passFusion");
    static final boolean ENABLE_LOAD_STORE = Boolean.getBoolean("metallum.iris.experimental.loadStoreLiveness");
    static final boolean ENABLE_COMPUTE_GROUPING = Boolean.getBoolean("metallum.iris.experimental.computeGrouping");
    static final boolean ENABLE_RESOURCE_PRUNING = Boolean.getBoolean("metallum.iris.experimental.resourcePruning");
    static final boolean ENABLE_FINAL_COLOR_FUSION = Boolean.getBoolean("metallum.iris.experimental.finalColorFusion");
    static final boolean ENABLE_ARGUMENT_TABLES = Boolean.getBoolean("metallum.iris.experimental.argumentTables");
    static final boolean ENABLE_ICB = Boolean.getBoolean("metallum.iris.experimental.icb");

    enum LoadAction { DONT_CARE, LOAD, CLEAR }
    enum StoreAction { DONT_CARE, STORE }

    record AttachmentPolicy(String resource, LoadAction load, StoreAction store) {
        AttachmentPolicy {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
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

    private final IrisMetalHazardGraph hazards;
    private final List<MergeGroup> renderMergeGroups;
    private final List<MergeGroup> computeMergeGroups;
    private final Map<Integer, List<AttachmentPolicy>> attachmentPolicies;
    private final ResourceLiveness resourceLiveness;
    private final Map<String, ArgumentLayout> argumentLayouts;
    private final List<IndirectBatch> indirectBatches;

    private IrisMetalOptimizationPlan(
            final IrisMetalHazardGraph hazards,
            final List<MergeGroup> renderMergeGroups,
            final List<MergeGroup> computeMergeGroups,
            final Map<Integer, List<AttachmentPolicy>> attachmentPolicies,
            final ResourceLiveness resourceLiveness,
            final Map<String, ArgumentLayout> argumentLayouts,
            final List<IndirectBatch> indirectBatches
    ) {
        this.hazards = hazards;
        this.renderMergeGroups = List.copyOf(renderMergeGroups);
        this.computeMergeGroups = List.copyOf(computeMergeGroups);
        Map<Integer, List<AttachmentPolicy>> frozen = new LinkedHashMap<>();
        attachmentPolicies.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        this.attachmentPolicies = Map.copyOf(frozen);
        this.resourceLiveness = resourceLiveness;
        this.argumentLayouts = Map.copyOf(argumentLayouts);
        this.indirectBatches = List.copyOf(indirectBatches);
    }

    static Builder builder(final IrisMetalHazardGraph hazards) {
        return new Builder(hazards);
    }

    IrisMetalHazardGraph hazards() { return hazards; }
    List<MergeGroup> renderMergeGroups() { return renderMergeGroups; }
    List<MergeGroup> computeMergeGroups() { return computeMergeGroups; }
    Map<Integer, List<AttachmentPolicy>> attachmentPolicies() { return attachmentPolicies; }
    ResourceLiveness resourceLiveness() { return resourceLiveness; }
    Map<String, ArgumentLayout> argumentLayouts() { return argumentLayouts; }
    List<IndirectBatch> indirectBatches() { return indirectBatches; }

    static final class Builder {
        private final IrisMetalHazardGraph hazards;
        private final List<MergeGroup> renderMergeGroups = new ArrayList<>();
        private final List<MergeGroup> computeMergeGroups = new ArrayList<>();
        private final Map<Integer, List<AttachmentPolicy>> attachmentPolicies = new LinkedHashMap<>();
        private final Set<String> allResources = new LinkedHashSet<>();
        private final Set<String> liveResources = new LinkedHashSet<>();
        private final Set<String> persistentResources = new LinkedHashSet<>();
        private final Map<String, ArgumentLayout> argumentLayouts = new LinkedHashMap<>();
        private final List<IndirectBatch> indirectBatches = new ArrayList<>();

        Builder(final IrisMetalHazardGraph hazards) {
            this.hazards = Objects.requireNonNull(hazards, "hazards");
            for (IrisMetalHazardGraph.Node node : hazards.nodes()) {
                for (IrisMetalHazardGraph.ResourceUse use : node.uses()) {
                    allResources.add(use.resource());
                    liveResources.add(use.resource());
                }
            }
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
                if (hazards.mayMergeAdjacent(i - 1, i)) {
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
                    hazards,
                    renderMergeGroups,
                    computeMergeGroups,
                    attachmentPolicies,
                    liveness,
                    argumentLayouts,
                    indirectBatches
            );
        }
    }
}
