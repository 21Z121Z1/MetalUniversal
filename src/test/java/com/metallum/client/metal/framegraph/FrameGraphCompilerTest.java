package com.metallum.client.metal.framegraph;

import java.util.List;

import org.junit.jupiter.api.Test;

import static com.metallum.client.metal.framegraph.FramePass.Phase.MOTION_MERGE;
import static com.metallum.client.metal.framegraph.FramePass.Phase.PRESENT;
import static com.metallum.client.metal.framegraph.FramePass.Phase.REACTIVE_MASK;
import static com.metallum.client.metal.framegraph.FramePass.Phase.TEMPORAL_UPSCALE;
import static com.metallum.client.metal.framegraph.FramePass.Phase.TRANSPARENCY;
import static com.metallum.client.metal.framegraph.FramePass.Phase.UI;
import static com.metallum.client.metal.framegraph.FramePass.Phase.WORLD_MRT;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.ColorSpace.DATA;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.ColorSpace.DISPLAY_NATIVE;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.Lifetime.HISTORY;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.Lifetime.TRANSIENT;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.PixelFormat.BGRA8_UNORM;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.PixelFormat.R8_UNORM;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.SizeDomain.NATIVE_DISPLAY;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.SizeDomain.RENDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameGraphCompilerTest {
    private static final ResourceDescriptor R8_COMPUTE =
            ResourceDescriptor.computeTarget(RENDER, R8_UNORM, DATA, TRANSIENT);
    private static final ResourceDescriptor R8_SCALER =
            ResourceDescriptor.scalerInput(RENDER, R8_UNORM, DATA, TRANSIENT);

    private static List<String> names(final CompiledFrameGraph graph) {
        return graph.passes().stream().map(FramePass::name).toList();
    }

    @Test
    void phaseOrderWinsOverDeclarationOrder() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("late", REACTIVE_MASK, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .pass("early", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .compile();
        assertEquals(List.of("early", "late"), names(graph),
                "a pass declared second but belonging to an earlier phase must still execute first");
    }

    @Test
    void declarationOrderBreaksTiesInsideOnePhase() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.DISOCCLUSION, R8_COMPUTE)
                .pass("first", MOTION_MERGE, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("second", MOTION_MERGE, pass -> pass.write(SemanticResource.DISOCCLUSION))
                .compile();
        assertEquals(List.of("first", "second"), names(graph),
                "independent passes in one phase keep declaration order");
    }

    @Test
    void hazardsBecomeBarriers() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("write", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("read", TRANSPARENCY, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .pass("rewrite", MOTION_MERGE, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .compile();

        assertEquals(List.of(
                        new CompiledFrameGraph.Barrier("write", "read", SemanticResource.CUTOUT_COVERAGE,
                                CompiledFrameGraph.Hazard.READ_AFTER_WRITE),
                        new CompiledFrameGraph.Barrier("write", "rewrite", SemanticResource.CUTOUT_COVERAGE,
                                CompiledFrameGraph.Hazard.WRITE_AFTER_WRITE),
                        new CompiledFrameGraph.Barrier("read", "rewrite", SemanticResource.CUTOUT_COVERAGE,
                                CompiledFrameGraph.Hazard.WRITE_AFTER_READ)),
                graph.barriers(),
                "every read-after-write, write-after-write and write-after-read pair must be reported once");
    }

    @Test
    void readModifyWriteDoesNotBarrierAgainstItself() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("write", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("modify", TRANSPARENCY, pass -> pass.readWrite(SemanticResource.CUTOUT_COVERAGE))
                .pass("after", MOTION_MERGE, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .compile();

        assertFalse(graph.barriers().stream().anyMatch(b -> b.afterPass().equals(b.beforePass())),
                "a pass must never be ordered against itself");
        assertEquals(1L,
                graph.barriers().stream()
                        .filter(b -> b.afterPass().equals("modify") && b.beforePass().equals("after"))
                        .count(),
                "the read-modify-write pass must produce exactly one barrier toward the next writer, not both a"
                        + " write-after-write and a redundant write-after-read");
    }

    @Test
    void disjointTransientRangesShareOneSlot() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.DISOCCLUSION, R8_COMPUTE)
                .pass("produce-a", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("consume-a", TRANSPARENCY, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .pass("produce-b", MOTION_MERGE, pass -> pass.write(SemanticResource.DISOCCLUSION))
                .pass("consume-b", REACTIVE_MASK, pass -> pass.read(SemanticResource.DISOCCLUSION))
                .compile();

        assertEquals(graph.slotOf(SemanticResource.CUTOUT_COVERAGE), graph.slotOf(SemanticResource.DISOCCLUSION),
                "two identically described transient resources with disjoint live ranges must share a slot");
        assertEquals(1, graph.slotCount(), "only one texture needs allocating");
        assertEquals(List.of(SemanticResource.CUTOUT_COVERAGE, SemanticResource.DISOCCLUSION),
                graph.resourcesInSlot(0), "both resources must be reported as sharing slot 0");
    }

    @Test
    void overlappingTransientRangesDoNotShareASlot() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.DISOCCLUSION, R8_COMPUTE)
                .pass("produce", WORLD_MRT, pass -> pass
                        .write(SemanticResource.CUTOUT_COVERAGE))
                .pass("both", MOTION_MERGE, pass -> pass
                        .read(SemanticResource.CUTOUT_COVERAGE)
                        .write(SemanticResource.DISOCCLUSION))
                .pass("consume", REACTIVE_MASK, pass -> pass.read(SemanticResource.DISOCCLUSION))
                .compile();

        assertNotEquals(graph.slotOf(SemanticResource.CUTOUT_COVERAGE), graph.slotOf(SemanticResource.DISOCCLUSION),
                "live ranges that touch the same pass must not share a slot");
        assertEquals(2, graph.slotCount(), "two overlapping transients need two textures");
    }

    @Test
    void differingStageSetsBlockAliasing() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.REACTIVE_MASK, R8_SCALER)
                .pass("produce-a", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("consume-a", TRANSPARENCY, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .pass("produce-b", MOTION_MERGE, pass -> pass.write(SemanticResource.REACTIVE_MASK))
                .pass("consume-b", TEMPORAL_UPSCALE, pass -> pass.read(SemanticResource.REACTIVE_MASK))
                .compile();

        assertNotEquals(graph.slotOf(SemanticResource.CUTOUT_COVERAGE), graph.slotOf(SemanticResource.REACTIVE_MASK),
                "the backend derives MTLTextureUsage from the stage set, so a slot allocated for compute-only use"
                        + " cannot host a resource a MetalFX scaler samples, however well the dimensions match");
    }

    @Test
    void differingSizeDomainsBlockAliasing() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.DISOCCLUSION,
                        ResourceDescriptor.computeTarget(NATIVE_DISPLAY, R8_UNORM, DATA, TRANSIENT))
                .pass("produce-a", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("consume-a", TRANSPARENCY, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .pass("produce-b", MOTION_MERGE, pass -> pass.write(SemanticResource.DISOCCLUSION))
                .pass("consume-b", REACTIVE_MASK, pass -> pass.read(SemanticResource.DISOCCLUSION))
                .compile();

        assertNotEquals(graph.slotOf(SemanticResource.CUTOUT_COVERAGE), graph.slotOf(SemanticResource.DISOCCLUSION),
                "a render-resolution target must not alias a display-resolution one");
    }

    @Test
    void historyResourcesAreNeverAliased() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.COMPOSED_COLOR,
                        ResourceDescriptor.scalerOutput(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, HISTORY))
                .resource(SemanticResource.UPSCALED_COLOR,
                        ResourceDescriptor.scalerOutput(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, TRANSIENT))
                .pass("produce", WORLD_MRT, pass -> pass.write(SemanticResource.UPSCALED_COLOR))
                .pass("consume", TRANSPARENCY, pass -> pass.read(SemanticResource.UPSCALED_COLOR))
                // A scaler output carries no compute usage, so the history write
                // has to come from a fragment-stage phase.
                .pass("history", UI, pass -> pass.write(SemanticResource.COMPOSED_COLOR))
                .compile();

        assertNotEquals(graph.slotOf(SemanticResource.UPSCALED_COLOR), graph.slotOf(SemanticResource.COMPOSED_COLOR),
                "a cross-frame history target must keep its own allocation even when a dead transient looks compatible");
    }

    @Test
    void slotAssignmentIsAPureFunctionOfTheDeclaration() {
        CompiledFrameGraph first = aliasingGraph();
        CompiledFrameGraph second = aliasingGraph();
        assertEquals(names(first), names(second), "pass order must be reproducible");
        assertEquals(first.aliasSlots(), second.aliasSlots(), "slot assignment must be reproducible");
        assertEquals(first.barriers(), second.barriers(), "barrier list must be reproducible");
        assertEquals(0, first.slotOf(SemanticResource.CUTOUT_COVERAGE),
                "the first transient live range must take slot 0");
        assertEquals(0, first.slotOf(SemanticResource.DISOCCLUSION),
                "the reusing range must take the lowest free compatible slot, not the next fresh one");
    }

    private static CompiledFrameGraph aliasingGraph() {
        return new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.DISOCCLUSION, R8_COMPUTE)
                .pass("produce-a", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("consume-a", TRANSPARENCY, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .pass("produce-b", MOTION_MERGE, pass -> pass.write(SemanticResource.DISOCCLUSION))
                .pass("consume-b", REACTIVE_MASK, pass -> pass.read(SemanticResource.DISOCCLUSION))
                .compile();
    }

    @Test
    void readingATransientBeforeAnyWriteIsRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("read-first", WORLD_MRT, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE))
                .compile());
        assertTrue(failure.getMessage().contains("before its first write"), failure.getMessage());
    }

    @Test
    void usingAResourceFromADisallowedStageIsRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                // An attachment carries no compute usage.
                .resource(SemanticResource.UI_COLOR,
                        ResourceDescriptor.attachment(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, TRANSIENT))
                .pass("compute-write", MOTION_MERGE, pass -> pass.write(SemanticResource.UI_COLOR))
                .compile());
        assertTrue(failure.getMessage().contains("does not permit"), failure.getMessage());
    }

    @Test
    void referencingAnUndeclaredResourceIsRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .pass("orphan", WORLD_MRT, pass -> pass.write(SemanticResource.SCENE_COLOR))
                .compile());
        assertTrue(failure.getMessage().contains("undeclared resource"), failure.getMessage());
    }

    @Test
    void conflictingDescriptorsForOneResourceAreRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_SCALER));
        assertTrue(failure.getMessage().contains("Conflicting declarations"), failure.getMessage());
    }

    @Test
    void identicalRedeclarationComposes() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("write", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .compile();
        assertEquals(1, graph.slotCount(),
                "two extensions agreeing about a shared resource must not allocate it twice");
    }

    @Test
    void dependencyCyclesAreRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("x", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE).dependsOn("y"))
                .pass("y", WORLD_MRT, pass -> pass.write(SemanticResource.DISOCCLUSION).dependsOn("x"))
                .resource(SemanticResource.DISOCCLUSION, R8_COMPUTE)
                .compile());
        assertTrue(failure.getMessage().contains("cycle"), failure.getMessage());
    }

    @Test
    void dependingOnAMissingPassIsRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("only", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE).dependsOn("ghost"))
                .compile());
        assertTrue(failure.getMessage().contains("missing pass"), failure.getMessage());
    }

    @Test
    void dependingOnALaterPhaseIsRejected() {
        FrameGraphException failure = assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.FINAL_COLOR,
                        ResourceDescriptor.presentTarget(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE))
                .pass("present", PRESENT, pass -> pass.write(SemanticResource.FINAL_COLOR))
                .pass("world", WORLD_MRT, pass -> pass
                        .write(SemanticResource.CUTOUT_COVERAGE)
                        .dependsOn("present"))
                .compile());
        assertTrue(failure.getMessage().contains("later phase"), failure.getMessage());
    }

    @Test
    void duplicatePassNamesAreRejected() {
        assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("same", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .pass("same", TRANSPARENCY, pass -> pass.read(SemanticResource.CUTOUT_COVERAGE)));
    }

    @Test
    void declaringOneResourceTwiceInOnePassIsRejected() {
        assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .pass("both", WORLD_MRT, pass -> pass
                        .write(SemanticResource.CUTOUT_COVERAGE)
                        .read(SemanticResource.CUTOUT_COVERAGE)));
    }

    @Test
    void anEmptyGraphIsRejected() {
        assertThrows(FrameGraphException.class, () -> new FrameGraphBuilder().compile());
    }

    @Test
    void aBuilderCannotBeCompiledTwice() {
        FrameGraphBuilder builder = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE);
        builder.pass("write", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE));
        builder.compile();
        assertThrows(FrameGraphException.class, builder::compile);
    }

    @Test
    void unusedResourcesAreReportedRatherThanAllocated() {
        CompiledFrameGraph graph = new FrameGraphBuilder()
                .resource(SemanticResource.CUTOUT_COVERAGE, R8_COMPUTE)
                .resource(SemanticResource.DISOCCLUSION, R8_COMPUTE)
                .pass("write", WORLD_MRT, pass -> pass.write(SemanticResource.CUTOUT_COVERAGE))
                .compile();

        assertEquals(java.util.Set.of(SemanticResource.DISOCCLUSION), graph.unusedResources(),
                "a resource no pass touches must be reported");
        assertEquals(1, graph.slotCount(), "an untouched resource must not consume an allocation slot");
        assertThrows(FrameGraphException.class, () -> graph.slotOf(SemanticResource.DISOCCLUSION),
                "binding a resource the compiler never allocated must fail loudly");
    }

    @Test
    void aMultisampledMipChainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceDescriptor(
                RENDER, R8_UNORM, DATA, 4, 4, TRANSIENT,
                java.util.EnumSet.of(ResourceDescriptor.PipelineStage.FRAGMENT)));
    }

    @Test
    void aResourceUsableFromNoStageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceDescriptor(
                RENDER, R8_UNORM, DATA, 1, 1, TRANSIENT,
                java.util.EnumSet.noneOf(ResourceDescriptor.PipelineStage.class)));
    }
}
