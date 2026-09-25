package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalHazardGraphTest {
    @Test
    void independentAdjacentNodesMayMerge() {
        IrisMetalHazardGraph graph = IrisMetalHazardGraph.builder()
                .add("compute/a", List.of(new IrisMetalHazardGraph.ResourceUse(
                        "bufferA", IrisMetalHazardGraph.Access.STORAGE_WRITE)), false)
                .add("compute/b", List.of(new IrisMetalHazardGraph.ResourceUse(
                        "bufferB", IrisMetalHazardGraph.Access.STORAGE_WRITE)), false)
                .build();

        assertTrue(graph.mayMergeAdjacent(0, 1));
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void rawDependencyPreventsMerge() {
        IrisMetalHazardGraph graph = IrisMetalHazardGraph.builder()
                .add("compute/a", List.of(new IrisMetalHazardGraph.ResourceUse(
                        "shared", IrisMetalHazardGraph.Access.STORAGE_WRITE)), false)
                .add("compute/b", List.of(new IrisMetalHazardGraph.ResourceUse(
                        "shared", IrisMetalHazardGraph.Access.SAMPLED_READ)), false)
                .build();

        assertFalse(graph.mayMergeAdjacent(0, 1));
        assertEquals(1, graph.edges().size());
        assertTrue(graph.edges().getFirst().kinds().contains(IrisMetalHazardGraph.DependencyKind.RAW));
    }

    @Test
    void explicitBarrierPreventsOtherwiseIndependentMerge() {
        IrisMetalHazardGraph graph = IrisMetalHazardGraph.builder()
                .add("compute/a", List.of(), true)
                .add("compute/b", List.of(), false)
                .build();

        assertFalse(graph.mayMergeAdjacent(0, 1));
        assertTrue(graph.edges().getFirst().kinds().contains(
                IrisMetalHazardGraph.DependencyKind.EXPLICIT_BARRIER));
    }
}
