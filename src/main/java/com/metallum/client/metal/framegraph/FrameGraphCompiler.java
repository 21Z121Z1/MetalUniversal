package com.metallum.client.metal.framegraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.metallum.client.metal.framegraph.ResourceDescriptor.Lifetime;

/**
 * Turns declared passes into a validated, deterministic execution plan.
 *
 * <p>Determinism is a hard requirement, not a nicety: the golden-frame A/B
 * comparison needs byte-identical output across runs, so the compiled pass
 * order, barrier list and slot assignment must not depend on hash iteration
 * order. Every collection this class iterates is therefore either explicitly
 * sorted or insertion-ordered.</p>
 */
final class FrameGraphCompiler {
    private FrameGraphCompiler() {
    }

    private static final Comparator<FramePass> CANONICAL =
            Comparator.comparing(FramePass::phase).thenComparingInt(FramePass::declarationOrder);

    static CompiledFrameGraph compile(
            final Map<SemanticResource, ResourceDescriptor> resources,
            final List<FramePass> declarations
    ) {
        if (declarations.isEmpty()) {
            throw new FrameGraphException("A frame graph must declare at least one pass");
        }
        List<FramePass> canonical = declarations.stream().sorted(CANONICAL).toList();
        validateAccess(resources, canonical);

        Map<String, FramePass> byName = new LinkedHashMap<>();
        for (FramePass pass : canonical) {
            byName.put(pass.name(), pass);
        }
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> incoming = new LinkedHashMap<>();
        for (FramePass pass : canonical) {
            outgoing.put(pass.name(), new LinkedHashSet<>());
            incoming.put(pass.name(), 0);
        }

        addDependencyEdges(canonical, byName, outgoing, incoming);
        addPhaseEdges(canonical, outgoing, incoming);
        addHazardEdges(canonical, outgoing, incoming);

        List<FramePass> ordered = topologicalSort(canonical, byName, outgoing, incoming);
        validateInitialisation(resources, ordered);

        List<CompiledFrameGraph.Barrier> barriers = barriers(ordered);
        Allocation allocation = allocate(resources, ordered);
        Set<SemanticResource> unused = EnumSet.noneOf(SemanticResource.class);
        for (SemanticResource declared : resources.keySet()) {
            if (!allocation.slots().containsKey(declared)) {
                unused.add(declared);
            }
        }
        return new CompiledFrameGraph(ordered, resources, allocation.slots(),
                allocation.slotDescriptors(), barriers, unused);
    }

    /**
     * Rejects references the backend could not honour: a resource nobody
     * declared, and a resource used from a pipeline stage its descriptor was
     * never created for.
     */
    private static void validateAccess(
            final Map<SemanticResource, ResourceDescriptor> resources,
            final List<FramePass> passes
    ) {
        for (FramePass pass : passes) {
            for (Map.Entry<SemanticResource, FramePass.Access> usage : pass.resources().entrySet()) {
                SemanticResource semantic = usage.getKey();
                ResourceDescriptor descriptor = resources.get(semantic);
                if (descriptor == null) {
                    throw new FrameGraphException("Pass " + pass.name()
                            + " references undeclared resource " + semantic);
                }
                ResourceDescriptor.PipelineStage stage = pass.phase().executionStage();
                if (!descriptor.allows(stage)) {
                    throw new FrameGraphException("Pass " + pass.name() + " uses " + semantic
                            + " from stage " + stage + ", which that resource does not permit "
                            + descriptor.stages());
                }
                if (usage.getValue().writes() && descriptor.lifetime() == Lifetime.EXTERNAL
                        && stage != ResourceDescriptor.PipelineStage.FRAGMENT
                        && stage != ResourceDescriptor.PipelineStage.BLIT
                        && stage != ResourceDescriptor.PipelineStage.PRESENT) {
                    throw new FrameGraphException("Pass " + pass.name() + " writes externally owned "
                            + semantic + " from stage " + stage);
                }
            }
        }
    }

    /**
     * Rejects a read of a transient resource before anything has written it.
     * Runs on the final order because that is the order the backend executes;
     * a transient slot's contents before its first write are whatever the
     * previous frame's aliased occupant left there.
     */
    private static void validateInitialisation(
            final Map<SemanticResource, ResourceDescriptor> resources,
            final List<FramePass> ordered
    ) {
        Set<SemanticResource> initialised = EnumSet.noneOf(SemanticResource.class);
        resources.forEach((semantic, descriptor) -> {
            if (descriptor.lifetime() != Lifetime.TRANSIENT) {
                initialised.add(semantic);
            }
        });
        for (FramePass pass : ordered) {
            for (Map.Entry<SemanticResource, FramePass.Access> usage : pass.resources().entrySet()) {
                SemanticResource semantic = usage.getKey();
                if (usage.getValue().reads() && !initialised.contains(semantic)) {
                    throw new FrameGraphException("Pass " + pass.name() + " reads transient resource "
                            + semantic + " before its first write");
                }
                if (usage.getValue().writes()) {
                    initialised.add(semantic);
                }
            }
        }
    }

    private static void addDependencyEdges(
            final List<FramePass> passes,
            final Map<String, FramePass> byName,
            final Map<String, Set<String>> outgoing,
            final Map<String, Integer> incoming
    ) {
        for (FramePass pass : passes) {
            for (String dependency : pass.dependsOn()) {
                FramePass target = byName.get(dependency);
                if (target == null) {
                    throw new FrameGraphException("Pass " + pass.name()
                            + " depends on missing pass " + dependency);
                }
                if (target.phase().ordinal() > pass.phase().ordinal()) {
                    throw new FrameGraphException("Pass " + pass.name() + " in phase " + pass.phase()
                            + " depends on " + dependency + " in later phase " + target.phase());
                }
                addEdge(dependency, pass.name(), outgoing, incoming);
            }
        }
    }

    /**
     * Fixes the coarse frame order by chaining consecutive occupied phases.
     *
     * <p>Edges between adjacent phase groups are enough: order across
     * non-adjacent phases follows by transitivity. Connecting every pair of
     * phases instead would produce a quadratic edge set for no additional
     * ordering.</p>
     */
    private static void addPhaseEdges(
            final List<FramePass> canonical,
            final Map<String, Set<String>> outgoing,
            final Map<String, Integer> incoming
    ) {
        Map<FramePass.Phase, List<FramePass>> byPhase = new EnumMap<>(FramePass.Phase.class);
        for (FramePass pass : canonical) {
            byPhase.computeIfAbsent(pass.phase(), ignored -> new ArrayList<>()).add(pass);
        }
        List<List<FramePass>> groups = new ArrayList<>(byPhase.values());
        for (int group = 0; group + 1 < groups.size(); group++) {
            for (FramePass earlier : groups.get(group)) {
                for (FramePass later : groups.get(group + 1)) {
                    addEdge(earlier.name(), later.name(), outgoing, incoming);
                }
            }
        }
    }

    private static void addHazardEdges(
            final List<FramePass> canonical,
            final Map<String, Set<String>> outgoing,
            final Map<String, Integer> incoming
    ) {
        Map<SemanticResource, String> lastWriter = new EnumMap<>(SemanticResource.class);
        Map<SemanticResource, Set<String>> readers = new EnumMap<>(SemanticResource.class);
        for (FramePass pass : canonical) {
            for (Map.Entry<SemanticResource, FramePass.Access> usage : pass.resources().entrySet()) {
                SemanticResource resource = usage.getKey();
                FramePass.Access access = usage.getValue();
                String writer = lastWriter.get(resource);
                if (access.reads() && writer != null) {
                    addEdge(writer, pass.name(), outgoing, incoming);
                }
                if (access.writes()) {
                    if (writer != null) {
                        addEdge(writer, pass.name(), outgoing, incoming);
                    }
                    for (String reader : readers.getOrDefault(resource, Set.of())) {
                        if (!reader.equals(writer)) {
                            addEdge(reader, pass.name(), outgoing, incoming);
                        }
                    }
                    readers.remove(resource);
                    lastWriter.put(resource, pass.name());
                }
                if (access.reads()) {
                    readers.computeIfAbsent(resource, ignored -> new LinkedHashSet<>()).add(pass.name());
                }
            }
        }
    }

    private static void addEdge(
            final String from,
            final String to,
            final Map<String, Set<String>> outgoing,
            final Map<String, Integer> incoming
    ) {
        if (from.equals(to)) {
            return;
        }
        if (outgoing.get(from).add(to)) {
            incoming.compute(to, (ignored, count) -> count + 1);
        }
    }

    private static List<FramePass> topologicalSort(
            final List<FramePass> canonical,
            final Map<String, FramePass> byName,
            final Map<String, Set<String>> outgoing,
            final Map<String, Integer> incoming
    ) {
        PriorityQueue<FramePass> ready = new PriorityQueue<>(CANONICAL);
        for (FramePass pass : canonical) {
            if (incoming.get(pass.name()) == 0) {
                ready.add(pass);
            }
        }
        List<FramePass> result = new ArrayList<>(canonical.size());
        while (!ready.isEmpty()) {
            FramePass pass = ready.remove();
            result.add(pass);
            for (String successor : outgoing.get(pass.name())) {
                if (incoming.compute(successor, (ignored, count) -> count - 1) == 0) {
                    ready.add(byName.get(successor));
                }
            }
        }
        if (result.size() != canonical.size()) {
            List<String> unresolved = incoming.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            throw new FrameGraphException("Frame graph contains a dependency or hazard cycle: " + unresolved);
        }
        return result;
    }

    private static List<CompiledFrameGraph.Barrier> barriers(final List<FramePass> ordered) {
        List<CompiledFrameGraph.Barrier> result = new ArrayList<>();
        Map<SemanticResource, String> lastWriter = new EnumMap<>(SemanticResource.class);
        Map<SemanticResource, Set<String>> readers = new EnumMap<>(SemanticResource.class);
        for (FramePass pass : ordered) {
            for (Map.Entry<SemanticResource, FramePass.Access> usage : pass.resources().entrySet()) {
                SemanticResource resource = usage.getKey();
                FramePass.Access access = usage.getValue();
                String writer = lastWriter.get(resource);
                if (access.reads() && writer != null) {
                    result.add(new CompiledFrameGraph.Barrier(writer, pass.name(), resource,
                            CompiledFrameGraph.Hazard.READ_AFTER_WRITE));
                }
                if (access.writes()) {
                    if (writer != null) {
                        result.add(new CompiledFrameGraph.Barrier(writer, pass.name(), resource,
                                CompiledFrameGraph.Hazard.WRITE_AFTER_WRITE));
                    }
                    for (String reader : readers.getOrDefault(resource, Set.of())) {
                        // A read-modify-write pass is already covered by the
                        // write-after-write edge above; emitting a second
                        // barrier for the same pair would be noise.
                        if (!reader.equals(writer) && !reader.equals(pass.name())) {
                            result.add(new CompiledFrameGraph.Barrier(reader, pass.name(), resource,
                                    CompiledFrameGraph.Hazard.WRITE_AFTER_READ));
                        }
                    }
                    readers.remove(resource);
                    lastWriter.put(resource, pass.name());
                }
                if (access.reads()) {
                    readers.computeIfAbsent(resource, ignored -> new LinkedHashSet<>()).add(pass.name());
                }
            }
        }
        return result;
    }

    /**
     * Assigns each used resource an allocation slot, letting transient resources
     * whose live ranges do not overlap share one.
     *
     * <p>Slots are searched in ascending index order and live ranges are visited
     * in ascending start order with the semantic enum breaking ties, so the
     * assignment is a pure function of the declaration.</p>
     */
    private static Allocation allocate(
            final Map<SemanticResource, ResourceDescriptor> resources,
            final List<FramePass> ordered
    ) {
        Map<SemanticResource, int[]> ranges = new EnumMap<>(SemanticResource.class);
        for (int index = 0; index < ordered.size(); index++) {
            for (SemanticResource resource : ordered.get(index).resources().keySet()) {
                int[] range = ranges.get(resource);
                if (range == null) {
                    ranges.put(resource, new int[] { index, index });
                } else {
                    range[1] = index;
                }
            }
        }

        List<Range> transient_ = new ArrayList<>();
        List<SemanticResource> persistent = new ArrayList<>();
        ranges.forEach((resource, range) -> {
            if (resources.get(resource).lifetime() == Lifetime.TRANSIENT) {
                transient_.add(new Range(resource, range[0], range[1]));
            } else {
                persistent.add(resource);
            }
        });
        transient_.sort(Comparator.comparingInt(Range::first).thenComparing(Range::resource));
        persistent.sort(Comparator.naturalOrder());

        Map<SemanticResource, Integer> slots = new EnumMap<>(SemanticResource.class);
        List<ResourceDescriptor> slotDescriptors = new ArrayList<>();
        List<Range> slotTails = new ArrayList<>();
        for (Range range : transient_) {
            ResourceDescriptor descriptor = resources.get(range.resource());
            int selected = -1;
            for (int slot = 0; slot < slotTails.size(); slot++) {
                Range tail = slotTails.get(slot);
                if (tail.last() < range.first() && resources.get(tail.resource()).aliasCompatible(descriptor)) {
                    selected = slot;
                    break;
                }
            }
            if (selected < 0) {
                selected = slotDescriptors.size();
                slotDescriptors.add(descriptor);
                slotTails.add(range);
            } else {
                slotTails.set(selected, range);
            }
            slots.put(range.resource(), selected);
        }
        for (SemanticResource resource : persistent) {
            slots.put(resource, slotDescriptors.size());
            slotDescriptors.add(resources.get(resource));
            slotTails.add(new Range(resource, 0, Integer.MAX_VALUE));
        }
        return new Allocation(slots, slotDescriptors);
    }

    private record Range(SemanticResource resource, int first, int last) {
    }

    private record Allocation(Map<SemanticResource, Integer> slots, List<ResourceDescriptor> slotDescriptors) {
    }
}
