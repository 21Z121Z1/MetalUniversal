package com.metallum.client.metal.framegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A validated execution plan.
 *
 * <p>Alias slots are backend allocation identities, never native pointers. A
 * backend consumes this by allocating one texture per entry of
 * {@link #slotDescriptors()}, then executing {@link #passes()} in order and
 * honouring {@link #barriers()}.</p>
 */
public record CompiledFrameGraph(
        List<FramePass> passes,
        Map<SemanticResource, ResourceDescriptor> resources,
        Map<SemanticResource, Integer> aliasSlots,
        List<ResourceDescriptor> slotDescriptors,
        List<Barrier> barriers,
        Set<SemanticResource> unusedResources
) {
    public CompiledFrameGraph {
        passes = List.copyOf(passes);
        resources = Map.copyOf(resources);
        aliasSlots = Map.copyOf(aliasSlots);
        slotDescriptors = List.copyOf(slotDescriptors);
        barriers = List.copyOf(barriers);
        unusedResources = Set.copyOf(unusedResources);
    }

    /** Number of textures the backend must allocate for this plan. */
    public int slotCount() {
        return slotDescriptors.size();
    }

    /**
     * The slot a resource was assigned.
     *
     * @throws FrameGraphException if the resource is not part of the plan, which
     *         means the caller is about to bind something the compiler never
     *         allocated
     */
    public int slotOf(final SemanticResource resource) {
        Integer slot = aliasSlots.get(Objects.requireNonNull(resource, "resource"));
        if (slot == null) {
            throw new FrameGraphException("Resource " + resource + " has no allocation slot in this plan");
        }
        return slot;
    }

    /** Semantic resources sharing {@code slot}, in slot-assignment order. */
    public List<SemanticResource> resourcesInSlot(final int slot) {
        List<SemanticResource> sharing = new ArrayList<>();
        for (FramePass pass : passes) {
            for (SemanticResource resource : pass.resources().keySet()) {
                Integer assigned = aliasSlots.get(resource);
                if (assigned != null && assigned == slot && !sharing.contains(resource)) {
                    sharing.add(resource);
                }
            }
        }
        return List.copyOf(sharing);
    }

    /** Human-readable plan, for logs and for the acceptance artifacts. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("passes=").append(passes.size())
                .append(" slots=").append(slotCount())
                .append(" barriers=").append(barriers.size())
                .append('\n');
        for (FramePass pass : passes) {
            text.append("  ").append(pass.phase()).append(' ').append(pass.name());
            pass.resources().forEach((resource, access) ->
                    text.append(' ').append(access).append('(').append(resource)
                            .append("@s").append(aliasSlots.get(resource)).append(')'));
            text.append('\n');
        }
        for (Barrier barrier : barriers) {
            text.append("  barrier ").append(barrier.hazard()).append(' ')
                    .append(barrier.afterPass()).append(" -> ").append(barrier.beforePass())
                    .append(" on ").append(barrier.resource()).append('\n');
        }
        if (!unusedResources.isEmpty()) {
            text.append("  unused ").append(unusedResources).append('\n');
        }
        return text.toString();
    }

    /**
     * A synchronisation edge the backend must insert between two passes. The
     * compiler derives these from declared access, so a pass author cannot
     * forget one.
     */
    public record Barrier(String afterPass, String beforePass, SemanticResource resource, Hazard hazard) {
        public Barrier {
            Objects.requireNonNull(afterPass, "afterPass");
            Objects.requireNonNull(beforePass, "beforePass");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(hazard, "hazard");
        }
    }

    public enum Hazard {
        READ_AFTER_WRITE,
        WRITE_AFTER_READ,
        WRITE_AFTER_WRITE
    }
}
