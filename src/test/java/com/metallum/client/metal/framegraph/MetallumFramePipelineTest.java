package com.metallum.client.metal.framegraph;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetallumFramePipelineTest {
    private static List<String> names(final CompiledFrameGraph graph) {
        return graph.passes().stream().map(FramePass::name).toList();
    }

    @Test
    void frameGenerationCompilesToTheRealPassOrder() {
        CompiledFrameGraph graph = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.frameGeneration(), List.of());

        assertEquals(List.of(
                        "world-mrt",
                        "transparency",
                        "motion-camera",
                        "motion-merge",
                        "reactive-mask",
                        "temporal-upscale",
                        "ui",
                        "ui-composition",
                        "frame-interpolation",
                        "present"),
                names(graph),
                "the compiled order must match the order the backend actually encodes");
        assertEquals(Set.of(), graph.unusedResources(),
                "the full configuration must use every resource it declares");
    }

    @Test
    void cameraMotionIsOrderedBeforeTheMerge() {
        CompiledFrameGraph graph = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.frameGeneration(), List.of());

        assertTrue(graph.barriers().contains(new CompiledFrameGraph.Barrier(
                        "motion-camera", "motion-merge", SemanticResource.CAMERA_MOTION,
                        CompiledFrameGraph.Hazard.READ_AFTER_WRITE)),
                "metallum_motion_merge_v2 reads what metallum_motion_camera_v2 wrote, so the compiler owes us"
                        + " that barrier without anyone declaring it");
    }

    @Test
    void theInterpolatorOutputReusesTheScalerOutputSlot() {
        CompiledFrameGraph graph = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.frameGeneration(), List.of());

        assertEquals(graph.slotOf(SemanticResource.UPSCALED_COLOR), graph.slotOf(SemanticResource.INTERPOLATED_COLOR),
                "the scaler output is dead once UI composition has consumed it, so the interpolator output can"
                        + " share its texture: one display-resolution BGRA8 target saved per frame");
        assertEquals(13, graph.slotCount(),
                "14 declared resources minus the one aliased pair is 13 textures; update this only together with"
                        + " a deliberate change to the pipeline declaration");
    }

    @Test
    void vanillaDeclaresNoMetalFxWork() {
        CompiledFrameGraph graph = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.vanilla(), List.of());

        assertEquals(List.of("world-mrt", "transparency", "ui", "ui-composition", "present"), names(graph),
                "with every MetalFX stage off the graph must contain no motion, reactive or scaler pass");
        assertFalse(graph.aliasSlots().containsKey(SemanticResource.MERGED_MOTION),
                "a disabled stage must not cost an allocation slot");
        assertFalse(graph.aliasSlots().containsKey(SemanticResource.REACTIVE_MASK),
                "a disabled stage must not cost an allocation slot");
        assertEquals(Set.of(), graph.unusedResources(),
                "the vanilla configuration must not declare anything it does not use");
    }

    @Test
    void sceneTargetsLiveInTheDisplayDomainWhenNotUpscaling() {
        CompiledFrameGraph upscaled = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.fullTemporal(), List.of());
        CompiledFrameGraph plain = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.vanilla(), List.of());

        assertEquals(ResourceDescriptor.SizeDomain.RENDER,
                upscaled.resources().get(SemanticResource.SCENE_COLOR).sizeDomain(),
                "when upscaling, the scene is rasterised below display size");
        assertEquals(ResourceDescriptor.SizeDomain.NATIVE_DISPLAY,
                plain.resources().get(SemanticResource.SCENE_COLOR).sizeDomain(),
                "without upscaling the scene really is display sized; claiming otherwise would let the compiler"
                        + " believe a scene target and a display target can never alias");
    }

    @Test
    void dependentOptionCombinationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MetallumFramePipeline.Options(false, true, false, false),
                "frame interpolation without the temporal scaler has no linked history");
        assertThrows(IllegalArgumentException.class, () -> new MetallumFramePipeline.Options(false, false, false, true),
                "the reactive mask has no consumer without the temporal scaler");
        assertThrows(IllegalArgumentException.class, () -> new MetallumFramePipeline.Options(false, false, true, false),
                "object motion has no consumer without the temporal scaler");
    }

    @Test
    void everyBarrierNamesPassesThatExist() {
        for (MetallumFramePipeline.Options options : List.of(
                MetallumFramePipeline.Options.vanilla(),
                MetallumFramePipeline.Options.fullTemporal(),
                MetallumFramePipeline.Options.frameGeneration())) {
            CompiledFrameGraph graph = MetallumFramePipeline.compile(options, List.of());
            List<String> passNames = names(graph);
            for (CompiledFrameGraph.Barrier barrier : graph.barriers()) {
                assertTrue(passNames.contains(barrier.afterPass()),
                        "barrier references unknown pass " + barrier.afterPass());
                assertTrue(passNames.contains(barrier.beforePass()),
                        "barrier references unknown pass " + barrier.beforePass());
                assertTrue(passNames.indexOf(barrier.afterPass()) < passNames.indexOf(barrier.beforePass()),
                        "barrier " + barrier + " points backwards in the compiled order");
            }
        }
    }

    @Test
    void anExtensionCanInsertADeferredPassAndGetsItsBarriers() {
        FrameGraphExtension deferredLighting = new FrameGraphExtension() {
            @Override
            public String id() {
                return "test-deferred";
            }

            @Override
            public void declare(final FrameGraphBuilder graph) {
                graph.pass("pack-deferred", FramePass.Phase.SHADER_PACK_DEFERRED, pass -> pass
                        .read(SemanticResource.SCENE_DEPTH)
                        .readWrite(SemanticResource.SCENE_COLOR));
            }
        };

        CompiledFrameGraph graph = MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.frameGeneration(), List.of(deferredLighting));

        List<String> passNames = names(graph);
        assertTrue(passNames.indexOf("pack-deferred") > passNames.indexOf("reactive-mask"),
                "SHADER_PACK_DEFERRED sits after the reactive mask phase");
        assertTrue(passNames.indexOf("pack-deferred") < passNames.indexOf("temporal-upscale"),
                "a pack pass that rewrites scene colour must land before the scaler samples it");
        assertTrue(graph.barriers().contains(new CompiledFrameGraph.Barrier(
                        "pack-deferred", "temporal-upscale", SemanticResource.SCENE_COLOR,
                        CompiledFrameGraph.Hazard.READ_AFTER_WRITE)),
                "the extension declared an access, not a barrier; the compiler owes it the barrier");
    }

    @Test
    void aDisabledExtensionContributesNothing() {
        FrameGraphExtension disabled = new FrameGraphExtension() {
            @Override
            public String id() {
                return "test-disabled";
            }

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void declare(final FrameGraphBuilder graph) {
                throw new AssertionError("a disabled extension must never be asked to declare anything");
            }
        };

        assertEquals(names(MetallumFramePipeline.compile(MetallumFramePipeline.Options.frameGeneration(), List.of())),
                names(MetallumFramePipeline.compile(MetallumFramePipeline.Options.frameGeneration(), List.of(disabled))),
                "a disabled extension must not change the plan");
    }

    @Test
    void anExtensionCannotCollideWithABaselinePassName() {
        FrameGraphExtension colliding = new FrameGraphExtension() {
            @Override
            public String id() {
                return "test-collision";
            }

            @Override
            public void declare(final FrameGraphBuilder graph) {
                graph.pass("present", FramePass.Phase.SHADER_PACK_COMPOSITE,
                        pass -> pass.read(SemanticResource.SCENE_COLOR));
            }
        };

        assertThrows(FrameGraphException.class, () -> MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.frameGeneration(), List.of(colliding)),
                "an extension shadowing a baseline pass name must fail at compile time");
    }

    @Test
    void anExtensionCannotWriteFromAStageTheResourceForbids() {
        FrameGraphExtension bad = new FrameGraphExtension() {
            @Override
            public String id() {
                return "test-bad-stage";
            }

            @Override
            public void declare(final FrameGraphBuilder graph) {
                // UI_COLOR is a plain attachment; it carries no compute usage.
                graph.pass("pack-compute", FramePass.Phase.MOTION_MERGE,
                        pass -> pass.write(SemanticResource.UI_COLOR));
            }
        };

        assertThrows(FrameGraphException.class, () -> MetallumFramePipeline.compile(
                MetallumFramePipeline.Options.frameGeneration(), List.of(bad)),
                "the stage check is what stops an extension from having the backend allocate a texture without"
                        + " the usage flags its own kernel needs");
    }
}
