package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable hazard graph used by the experimental Iris pass scheduler.
 *
 * <p>The graph is deliberately backend-neutral: resources are identified by a
 * generation-stable string and every node declares exact read/write intent.
 * Runtime scheduling may only merge adjacent nodes when this graph proves that
 * no RAW, WAR or WAW dependency is crossed.</p>
 */
final class IrisMetalHazardGraph {
    enum Access {
        SAMPLED_READ,
        ATTACHMENT_READ,
        ATTACHMENT_WRITE,
        STORAGE_READ,
        STORAGE_WRITE,
        BUFFER_READ,
        BUFFER_WRITE,
        COPY_READ,
        COPY_WRITE,
        PRESENT_READ;

        boolean reads() {
            return switch (this) {
                case SAMPLED_READ, ATTACHMENT_READ, STORAGE_READ, BUFFER_READ, COPY_READ, PRESENT_READ -> true;
                default -> false;
            };
        }

        boolean writes() {
            return switch (this) {
                case ATTACHMENT_WRITE, STORAGE_WRITE, BUFFER_WRITE, COPY_WRITE -> true;
                default -> false;
            };
        }
    }

    record ResourceUse(String resource, Access access) {
        ResourceUse {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(access, "access");
            if (resource.isBlank()) {
                throw new IllegalArgumentException("Hazard resource identity must not be blank");
            }
        }
    }

    record Node(int index, String name, List<ResourceUse> uses, boolean explicitBarrierAfter) {
        Node {
            Objects.requireNonNull(name, "name");
            uses = List.copyOf(uses);
        }

        Set<String> reads() {
            Set<String> result = new LinkedHashSet<>();
            for (ResourceUse use : uses) {
                if (use.access().reads()) result.add(use.resource());
            }
            return Set.copyOf(result);
        }

        Set<String> writes() {
            Set<String> result = new LinkedHashSet<>();
            for (ResourceUse use : uses) {
                if (use.access().writes()) result.add(use.resource());
            }
            return Set.copyOf(result);
        }
    }

    enum DependencyKind { RAW, WAR, WAW, EXPLICIT_BARRIER }

    record Edge(int from, int to, Set<DependencyKind> kinds, Set<String> resources) {
        Edge {
            kinds = Set.copyOf(kinds);
            resources = Set.copyOf(resources);
        }
    }

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Map<Integer, List<Edge>> incoming;

    private IrisMetalHazardGraph(final List<Node> nodes, final List<Edge> edges) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        Map<Integer, List<Edge>> byTarget = new HashMap<>();
        for (Edge edge : edges) {
            byTarget.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
        }
        Map<Integer, List<Edge>> frozen = new HashMap<>();
        byTarget.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        this.incoming = Map.copyOf(frozen);
    }

    static Builder builder() {
        return new Builder();
    }

    List<Node> nodes() {
        return nodes;
    }

    List<Edge> edges() {
        return edges;
    }

    List<Edge> incoming(final int nodeIndex) {
        return incoming.getOrDefault(nodeIndex, List.of());
    }

    /** Adjacent nodes may share one encoder only when no dependency or explicit barrier separates them. */
    boolean mayMergeAdjacent(final int first, final int second) {
        if (second != first + 1 || first < 0 || second >= nodes.size()) {
            return false;
        }
        if (nodes.get(first).explicitBarrierAfter()) {
            return false;
        }
        for (Edge edge : incoming(second)) {
            if (edge.from() == first) {
                return false;
            }
        }
        return true;
    }

    static final class Builder {
        private final List<Node> nodes = new ArrayList<>();

        Builder add(final String name, final List<ResourceUse> uses, final boolean explicitBarrierAfter) {
            nodes.add(new Node(nodes.size(), name, uses, explicitBarrierAfter));
            return this;
        }

        IrisMetalHazardGraph build() {
            List<Edge> edges = new ArrayList<>();
            Map<String, Integer> lastWriter = new LinkedHashMap<>();
            Map<String, Set<Integer>> readersSinceWrite = new LinkedHashMap<>();

            for (Node node : nodes) {
                Map<Integer, EnumSet<DependencyKind>> kindsBySource = new LinkedHashMap<>();
                Map<Integer, Set<String>> resourcesBySource = new LinkedHashMap<>();

                for (ResourceUse use : node.uses()) {
                    String resource = use.resource();
                    if (use.access().reads()) {
                        Integer writer = lastWriter.get(resource);
                        if (writer != null) {
                            add(kindsBySource, resourcesBySource, writer, DependencyKind.RAW, resource);
                        }
                        readersSinceWrite.computeIfAbsent(resource, ignored -> new LinkedHashSet<>()).add(node.index());
                    }
                    if (use.access().writes()) {
                        Integer writer = lastWriter.get(resource);
                        if (writer != null) {
                            add(kindsBySource, resourcesBySource, writer, DependencyKind.WAW, resource);
                        }
                        for (Integer reader : readersSinceWrite.getOrDefault(resource, Set.of())) {
                            if (reader != node.index()) {
                                add(kindsBySource, resourcesBySource, reader, DependencyKind.WAR, resource);
                            }
                        }
                        readersSinceWrite.remove(resource);
                        lastWriter.put(resource, node.index());
                    }
                }

                if (node.index() > 0 && nodes.get(node.index() - 1).explicitBarrierAfter()) {
                    add(kindsBySource, resourcesBySource, node.index() - 1,
                            DependencyKind.EXPLICIT_BARRIER, "<explicit-barrier>");
                }

                for (Map.Entry<Integer, EnumSet<DependencyKind>> entry : kindsBySource.entrySet()) {
                    edges.add(new Edge(entry.getKey(), node.index(), entry.getValue(),
                            resourcesBySource.getOrDefault(entry.getKey(), Set.of())));
                }
            }
            return new IrisMetalHazardGraph(nodes, edges);
        }

        private static void add(
                final Map<Integer, EnumSet<DependencyKind>> kinds,
                final Map<Integer, Set<String>> resources,
                final int source,
                final DependencyKind kind,
                final String resource
        ) {
            kinds.computeIfAbsent(source, ignored -> EnumSet.noneOf(DependencyKind.class)).add(kind);
            resources.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(resource);
        }
    }
}
