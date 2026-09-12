#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


block_exact = r'''package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;

/**
 * Audited Minecraft 26.2 block-entity families whose complete collector output is entity-format
 * {@code submitModel} geometry and can therefore reuse Metallum's exact staged previous-position
 * history. This is intentionally an exact-class allowlist: a modded subclass may add collector
 * outputs or shader semantics that were never audited and must stay fail-closed.
 */
@Environment(EnvType.CLIENT)
final class MetalBlockEntityExactMotion {
    private static final long OBJECT_NAMESPACE = 0x4D4658424C4F434BL; // ASCII "MFXBLOCK".

    private MetalBlockEntityExactMotion() {
    }

    static boolean supports(final BlockEntityRenderState state) {
        return state != null && supportsStateClass(state.getClass());
    }

    static boolean supportsStateClass(final Class<?> stateClass) {
        return stateClass == ChestRenderState.class
                || stateClass == BannerRenderState.class
                || stateClass == ShulkerBoxRenderState.class
                || stateClass == BellRenderState.class;
    }

    static long objectId(final long packedBlockPos) {
        return mix(packedBlockPos ^ OBJECT_NAMESPACE);
    }

    private static long mix(final long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }
}
'''
# Keep the intentionally excluded Beacon import out of product code; the test below owns that evidence.
block_exact = block_exact.replace('import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;\n', '')
Path("src/main/java/com/metallum/client/metal/render/MetalBlockEntityExactMotion.java").write_text(block_exact)

block_test = r'''package com.metallum.client.metal.render;

import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalBlockEntityExactMotionTest {
    @Test
    void onlyExactAuditedVanillaStateClassesAreAdmitted() {
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(ChestRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(BannerRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(ShulkerBoxRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(BellRenderState.class));
        assertFalse(MetalBlockEntityExactMotion.supportsStateClass(BeaconRenderState.class));
        assertFalse(MetalBlockEntityExactMotion.supportsStateClass(BlockEntityRenderState.class));
    }

    @Test
    void blockPositionIdentityIsStableAndNamespaced() {
        long first = MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDEFL);
        assertEquals(first, MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDEFL));
        assertNotEquals(first, MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDF0L));
    }
}
'''
Path("src/test/java/com/metallum/client/metal/render/MetalBlockEntityExactMotionTest.java").write_text(block_test)

scope_test = r'''package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MetalEntityMotionCaptureSubmissionScopeTest {
    @AfterEach
    void reset() {
        MetalEntityMotionCapture.setEnabled(false);
        MetalEntityMotionCapture.setEnabled(true);
    }

    @Test
    void nestedSubmissionRestoresOuterOwner() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        Object outerState = new Object();
        Object innerState = new Object();
        Object outerBefore = new Object();
        Object inner = new Object();
        Object outerAfter = new Object();
        MetalEntityMotionCapture.Sample outerSample = sample(101L, 1L);
        MetalEntityMotionCapture.Sample innerSample = sample(202L, 2L);
        MetalEntityMotionCapture.attachState(outerState, outerSample);
        MetalEntityMotionCapture.attachState(innerState, innerSample);

        MetalEntityMotionCapture.beginEntitySubmission(outerState);
        MetalEntityMotionCapture.captureModelSubmit(outerBefore);
        MetalEntityMotionCapture.beginEntitySubmission(innerState);
        MetalEntityMotionCapture.captureModelSubmit(inner);
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.captureModelSubmit(outerAfter);
        MetalEntityMotionCapture.endEntitySubmission();

        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(outerBefore).objectId());
        assertEquals(innerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(inner).objectId());
        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(outerAfter).objectId());
    }

    @Test
    void unsupportedNestedSubmissionDoesNotInheritOuterOwner() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        Object outerState = new Object();
        Object unsupportedState = new Object();
        Object leaked = new Object();
        Object restored = new Object();
        MetalEntityMotionCapture.Sample outerSample = sample(303L, 3L);
        MetalEntityMotionCapture.attachState(outerState, outerSample);

        MetalEntityMotionCapture.beginEntitySubmission(outerState);
        MetalEntityMotionCapture.beginEntitySubmission(unsupportedState);
        MetalEntityMotionCapture.captureModelSubmit(leaked);
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.captureModelSubmit(restored);
        MetalEntityMotionCapture.endEntitySubmission();

        assertNull(MetalEntityMotionCapture.sampleForSubmit(leaked));
        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(restored).objectId());
    }

    private static MetalEntityMotionCapture.Sample sample(final long objectId, final long generation) {
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(objectId, generation, identity, identity);
    }
}
'''
Path("src/test/java/com/metallum/client/metal/render/MetalEntityMotionCaptureSubmissionScopeTest.java").write_text(scope_test)

capture_path = Path("src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java")
capture = capture_path.read_text()
capture = replace_once(
    capture,
    '''import java.util.AbstractList;\nimport java.util.IdentityHashMap;\nimport java.util.List;\nimport java.util.Map;\n''',
    '''import java.util.AbstractList;\nimport java.util.ArrayDeque;\nimport java.util.Deque;\nimport java.util.IdentityHashMap;\nimport java.util.List;\nimport java.util.Map;\n''',
    "submission stack imports",
)
capture = replace_once(
    capture,
    '''    private record DrawCapture(\n            Sample sample,\n            MetalPreviousVertexHistory.DrawToken previousVertexToken\n    ) {\n    }\n\n    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();\n    private static final ThreadLocal<Object> ENTITY_SUBMISSION_STATE = new ThreadLocal<>();\n''',
    '''    private record DrawCapture(\n            Sample sample,\n            MetalPreviousVertexHistory.DrawToken previousVertexToken\n    ) {\n    }\n\n    private record SubmissionFrame(@Nullable Sample sample, @Nullable Object state) {\n    }\n\n    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();\n    private static final ThreadLocal<Object> ENTITY_SUBMISSION_STATE = new ThreadLocal<>();\n    private static final ThreadLocal<Deque<SubmissionFrame>> ENTITY_SUBMISSION_STACK =\n            ThreadLocal.withInitial(ArrayDeque::new);\n''',
    "submission stack field",
)
capture = replace_once(
    capture,
    '''        ENTITY_SUBMISSION.remove();\n        ENTITY_SUBMISSION_STATE.remove();\n        MODEL_BUILD.remove();\n''',
    '''        ENTITY_SUBMISSION.remove();\n        ENTITY_SUBMISSION_STATE.remove();\n        ENTITY_SUBMISSION_STACK.remove();\n        MODEL_BUILD.remove();\n''',
    "submission stack reset",
)
capture = replace_once(
    capture,
    '''    public static void beginEntitySubmission(final Object state) {\n        if (!enabled) {\n            return;\n        }\n        Sample sample = STATES.get(state);\n        if (sample == null) {\n            ENTITY_SUBMISSION.remove();\n            ENTITY_SUBMISSION_STATE.remove();\n        } else {\n            ENTITY_SUBMISSION.set(sample);\n            ENTITY_SUBMISSION_STATE.set(state);\n            entitySubmissionsMatched++;\n        }\n    }\n\n    public static void endEntitySubmission() {\n        if (enabled) {\n            ENTITY_SUBMISSION.remove();\n            ENTITY_SUBMISSION_STATE.remove();\n        }\n    }\n''',
    '''    public static void beginEntitySubmission(final Object state) {\n        if (!enabled) {\n            return;\n        }\n        Deque<SubmissionFrame> stack = ENTITY_SUBMISSION_STACK.get();\n        stack.push(new SubmissionFrame(ENTITY_SUBMISSION.get(), ENTITY_SUBMISSION_STATE.get()));\n        Sample sample = STATES.get(state);\n        if (sample == null) {\n            // Clear rather than inherit: unsupported/nonnative nested submissions must never\n            // accidentally capture their staged geometry under the outer entity owner.\n            ENTITY_SUBMISSION.remove();\n            ENTITY_SUBMISSION_STATE.remove();\n        } else {\n            ENTITY_SUBMISSION.set(sample);\n            ENTITY_SUBMISSION_STATE.set(state);\n            entitySubmissionsMatched++;\n        }\n    }\n\n    public static void endEntitySubmission() {\n        if (!enabled) {\n            return;\n        }\n        Deque<SubmissionFrame> stack = ENTITY_SUBMISSION_STACK.get();\n        if (stack.isEmpty()) {\n            // Defensive fail-closed recovery for an unmatched end call.\n            ENTITY_SUBMISSION.remove();\n            ENTITY_SUBMISSION_STATE.remove();\n            ENTITY_SUBMISSION_STACK.remove();\n            return;\n        }\n        SubmissionFrame previous = stack.pop();\n        if (previous.sample() == null) {\n            ENTITY_SUBMISSION.remove();\n        } else {\n            ENTITY_SUBMISSION.set(previous.sample());\n        }\n        if (previous.state() == null) {\n            ENTITY_SUBMISSION_STATE.remove();\n        } else {\n            ENTITY_SUBMISSION_STATE.set(previous.state());\n        }\n        if (stack.isEmpty()) {\n            ENTITY_SUBMISSION_STACK.remove();\n        }\n    }\n''',
    "nestable entity submission scope",
)
capture_path.write_text(capture)

mixin = r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ModelFeatureRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures block-entity lifetime at extraction and brackets the exact renderer invocation. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMetalFxMixin {
    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private <E extends BlockEntity, S extends BlockEntityRenderState> void metallum$captureBlockEntityMotion(
            final E blockEntity,
            final float partialTicks,
            final ModelFeatureRenderer.CrumblingOverlay breakProgress,
            final boolean isGloballyRendered,
            final CallbackInfoReturnable<S> cir
    ) {
        S state = cir.getReturnValue();
        if (state != null) {
            MetalFxManager.captureBlockEntityMotion(blockEntity, state);
        }
    }

    @Redirect(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            )
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void metallum$submitBlockEntity(
            final BlockEntityRenderer renderer,
            final BlockEntityRenderState state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera
    ) {
        MetalFxManager.beginBlockEntitySubmission(state);
        try {
            renderer.submit(state, poseStack, submitNodeCollector, camera);
        } finally {
            // The outer dispatcher still owns Minecraft's CrashReport catch. This finally only restores
            // Metallum's lexical owner, including nested or exceptional renderer submissions.
            MetalFxManager.endBlockEntitySubmission();
        }
    }
}
'''
Path("src/main/java/com/metallum/mixin/render/BlockEntityRenderDispatcherMetalFxMixin.java").write_text(mixin)

manager_path = Path("src/main/java/com/metallum/client/metal/render/MetalFxManager.java")
manager = manager_path.read_text()
manager = replace_once(
    manager,
    '''import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;\nimport net.minecraft.world.level.block.state.BlockState;\n''',
    '''import net.minecraft.world.level.block.entity.BlockEntity;\nimport net.minecraft.world.level.block.piston.PistonMovingBlockEntity;\nimport net.minecraft.world.level.block.state.BlockState;\n''',
    "block entity import",
)
manager = replace_once(
    manager,
    '''    private final Map<Entity, Long> entityGenerations = new IdentityHashMap<>();\n    private final Map<PistonMovingBlockEntity, PistonMotionGeneration> pistonGenerations = new IdentityHashMap<>();\n''',
    '''    private final Map<Entity, Long> entityGenerations = new IdentityHashMap<>();\n    private final Map<BlockEntity, Long> blockEntityGenerations = new IdentityHashMap<>();\n    private final Map<PistonMovingBlockEntity, PistonMotionGeneration> pistonGenerations = new IdentityHashMap<>();\n''',
    "block entity lifetime generations",
)
manager = replace_once(
    manager,
    '''    private boolean sourceFrameStampInvalidated;\n    private final Set<PistonHeadRenderState> pistonExactCandidates =\n''',
    '''    private boolean sourceFrameStampInvalidated;\n    // Sticky for the whole source frame. An Iris generation can be selected and retired between\n    // beginFrame and presentation; once any unproven override can have affected color geometry,\n    // that source frame must never enter MTLFXFrameInterpolator.\n    private boolean irisMotionSemanticsUnprovenThisFrame;\n    private final Set<PistonHeadRenderState> pistonExactCandidates =\n''',
    "Iris source-frame sticky veto",
)
manager = replace_once(
    manager,
    '''    /**\n     * Observes one real block-entity state at the Minecraft dispatcher submit\n     * boundary.  The piston state is the only block-entity family with a\n     * source-faithful staged motion producer in 26.2; all other states remain\n     * explicitly unsupported rather than being mislabeled as absent.\n     */\n    public static void observeBlockEntity(final BlockEntityRenderState state) {\n        MetalFxManager manager = active;\n        if (manager == null || state == null) {\n            return;\n        }\n        manager.observeBlockEntityInternal(state);\n    }\n''',
    '''    /** Captures the real block-entity object lifetime onto its extracted render state. */\n    public static void captureBlockEntityMotion(\n            final BlockEntity blockEntity,\n            final BlockEntityRenderState state\n    ) {\n        MetalFxManager manager = active;\n        if (manager != null && blockEntity != null && state != null) {\n            manager.captureBlockEntityMotionInternal(blockEntity, state);\n        }\n    }\n\n    /**\n     * Opens the exact lexical submit owner. Unsupported states deliberately open an empty capture\n     * scope so nested block-entity rendering cannot inherit an outer entity owner.\n     */\n    public static void beginBlockEntitySubmission(final BlockEntityRenderState state) {\n        MetalEntityMotionCapture.beginEntitySubmission(state);\n        MetalFxManager manager = active;\n        if (manager == null || state == null) {\n            return;\n        }\n        manager.observeBlockEntityInternal(state);\n        if (manager.effectiveMode != MetalFxConfig.Mode.TEMPORAL\n                || manager.runtimeDisabled\n                || !MetalBlockEntityExactMotion.supports(state)) {\n            return;\n        }\n        MetalEntityMotionCapture.Sample sample = MetalEntityMotionCapture.sampleForState(state);\n        if (sample == null) {\n            manager.observeUnsupportedProducer(\n                    FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,\n                    "audited-block-entity-lifetime-owner-unavailable"\n            );\n            return;\n        }\n        MetalEntityMotionCapture.requireExactState(state);\n        manager.markExactProducerCandidate(\n                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,\n                sample\n        );\n    }\n\n    public static void endBlockEntitySubmission() {\n        MetalEntityMotionCapture.endEntitySubmission();\n    }\n\n    /** Compatibility observation hook for diagnostics that do not execute the dispatcher redirect. */\n    public static void observeBlockEntity(final BlockEntityRenderState state) {\n        MetalFxManager manager = active;\n        if (manager != null && state != null) {\n            manager.observeBlockEntityInternal(state);\n        }\n    }\n\n    /** Called by Iris generation selection; the veto remains set even if that generation retires later this frame. */\n    static void observeIrisMotionSemanticsUnproven() {\n        MetalFxManager manager = active;\n        if (manager != null) {\n            manager.irisMotionSemanticsUnprovenThisFrame = true;\n        }\n    }\n''',
    "block entity public lifecycle",
)
manager = replace_once(
    manager,
    '''    private void observeBlockEntityInternal(final BlockEntityRenderState state) {\n''',
    '''    private void captureBlockEntityMotionInternal(\n            final BlockEntity blockEntity,\n            final BlockEntityRenderState state\n    ) {\n        if (effectiveMode != MetalFxConfig.Mode.TEMPORAL || runtimeDisabled\n                || !MetalBlockEntityExactMotion.supports(state)) {\n            return;\n        }\n        long generation = blockEntityGenerations.computeIfAbsent(\n                blockEntity, ignored -> nextEntityGeneration++\n        );\n        Matrix4f identity = new Matrix4f();\n        MetalEntityMotionCapture.attachState(\n                state,\n                new MetalEntityMotionCapture.Sample(\n                        MetalBlockEntityExactMotion.objectId(blockEntity.getBlockPos().asLong()),\n                        generation,\n                        identity,\n                        identity,\n                        FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES\n                )\n        );\n    }\n\n    private void observeBlockEntityInternal(final BlockEntityRenderState state) {\n''',
    "block entity extraction lifetime capture",
)
manager = replace_once(
    manager,
    '''        observeUnsupportedProducer(\n                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,\n                "block-entity-renderer-has-no-previous-transform-contract"\n        );\n''',
    '''        if (MetalBlockEntityExactMotion.supports(state)) {\n            observeProducer(\n                    FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES, 1\n            );\n            return;\n        }\n        observeUnsupportedProducer(\n                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,\n                "block-entity-renderer-has-no-previous-transform-contract"\n        );\n''',
    "audited block entity observation",
)
manager = replace_once(
    manager,
    '''        sourceFrameStamp = null;\n        sourceFrameStampInvalidated = false;\n        pistonExactCandidates.clear();\n''',
    '''        sourceFrameStamp = null;\n        sourceFrameStampInvalidated = false;\n        irisMotionSemanticsUnprovenThisFrame =\n                !IrisMetalPipelineOverrides.frameGenerationMotionSemanticsProven();\n        pistonExactCandidates.clear();\n''',
    "Iris sticky initialization",
)
manager = replace_once(
    manager,
    '''        sourceFrameStamp = new FrameSynthesisContract.FrameStamp(frameId, historyEpoch);\n        frameSynthesisReceipts.beginFrame(sourceFrameStamp);\n''',
    '''        sourceFrameStamp = new FrameSynthesisContract.FrameStamp(frameId, historyEpoch);\n        frameSynthesisReceipts.beginFrame(sourceFrameStamp);\n        if (irisMotionSemanticsUnprovenThisFrame) {\n            frameSynthesisReceipts.observeUnsupported(\n                    FrameSynthesisContract.ProducerDomain.MODDED_RENDERERS,\n                    1,\n                    "iris-active-pipeline-motion-semantics-unproven"\n            );\n        }\n''',
    "Iris receipt fail closed",
)
manager = replace_once(
    manager,
    '''        if (!MetalEntityMotionCapture.exactCoverageComplete()) {\n''',
    '''        if (irisMotionSemanticsUnprovenThisFrame\n                || !IrisMetalPipelineOverrides.frameGenerationMotionSemanticsProven()) {\n            irisMotionSemanticsUnprovenThisFrame = true;\n            if (telemetryCandidate) {\n                MetalFxMotionTelemetry.recordSourceFrame(\n                        frameId,\n                        false,\n                        0,\n                        "iris-active-pipeline-motion-semantics-unproven"\n                );\n            }\n            frameResetForPresent = true;\n            return null;\n        }\n        if (!MetalEntityMotionCapture.exactCoverageComplete()) {\n''',
    "final Iris frame-generation admission veto",
)
# Block-entity object-lifetime generations are history state and must not cross a reset or shutdown.
manager = replace_once(
    manager,
    '''        entityGenerations.clear();\n        pistonGenerations.clear();\n        phase = 0;\n''',
    '''        entityGenerations.clear();\n        blockEntityGenerations.clear();\n        pistonGenerations.clear();\n        phase = 0;\n''',
    "reset block entity generations",
)
manager = replace_once(
    manager,
    '''        entityGenerations.clear();\n        pistonGenerations.clear();\n        MetalEntityMotionPipeline.clear();\n''',
    '''        entityGenerations.clear();\n        blockEntityGenerations.clear();\n        pistonGenerations.clear();\n        MetalEntityMotionPipeline.clear();\n''',
    "close block entity generations",
)
manager_path.write_text(manager)

iris_path = Path("src/main/java/com/metallum/client/metal/render/IrisMetalPipelineOverrides.java")
iris = iris_path.read_text()
iris = replace_once(
    iris,
    '''        active = instance;\n        IrisMetalPackLifecycle.onSemanticPipelineSelected(instance.generation());\n''',
    '''        active = instance;\n        // Selection can occur after MetalFX beginFrame. Make the mismatch sticky in the current\n        // source frame so a same-frame retirement cannot make that frame look vanilla again.\n        MetalFxManager.observeIrisMotionSemanticsUnproven();\n        IrisMetalPackLifecycle.onSemanticPipelineSelected(instance.generation());\n''',
    "Iris generation selection veto",
)
iris = replace_once(
    iris,
    '''    public static int activeGenerationForDiagnostics() {\n        Instance instance = active;\n        return instance == null ? -1 : instance.generation();\n    }\n\n''',
    '''    public static int activeGenerationForDiagnostics() {\n        Instance instance = active;\n        return instance == null ? -1 : instance.generation();\n    }\n\n    /**\n     * Frame Interpolator motion replay is proven only for vanilla clip/vertex semantics today.\n     * Any active Iris generation may replace vertex position, clipping or alpha/discard behavior.\n     */\n    static boolean frameGenerationMotionSemanticsProven() {\n        return active == null;\n    }\n\n''',
    "Iris frame-generation semantic gate",
)
iris_path.write_text(iris)

coverage_test_path = Path("src/test/java/com/metallum/client/metal/render/MetalExactMotionCoverageTest.java")
coverage_test = coverage_test_path.read_text()
coverage_test = replace_once(
    coverage_test,
    '''    @Test\n    void requiredObjectNeedsWholeManifestAndEveryExactPlan() {\n''',
    '''    @Test\n    void firstRequiredFrameWithoutPreviousManifestFailsClosed() {\n        RenderPipeline pipeline = pipeline("exact_first_frame");\n        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(13L, 2L, new Matrix4f(), new Matrix4f());\n        VertexFormat format = pipeline.getVertexFormatBinding(0);\n        MetalPreviousVertexHistory.Signature signature = new MetalPreviousVertexHistory.Signature(\n                pipeline.getLocation().toString(), format.getElements(), format.getVertexSize(),\n                PrimitiveTopology.TRIANGLES, 1, 3\n        );\n\n        MetalPreviousVertexHistory.beginFrame();\n        MetalExactMotionCoverage.beginFrame();\n        MetalExactMotionCoverage.require(sample);\n        MetalPreviousVertexHistory.DrawToken current = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);\n        MetalPreviousVertexHistory.stageSnapshot(current, signature, new float[] {1.0F, 0.0F, 0.0F});\n\n        assertFalse(MetalExactMotionCoverage.complete());\n    }\n\n    @Test\n    void requiredObjectNeedsWholeManifestAndEveryExactPlan() {\n''',
    "first-frame exact coverage regression",
)
coverage_test_path.write_text(coverage_test)

source_contract = r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFrameGenerationSemanticGateSourceContractTest {
    @Test
    void blockEntityDispatcherUsesExceptionSafeExactInvokeRedirect() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/render/BlockEntityRenderDispatcherMetalFxMixin.java"
        ));
        assertTrue(source.contains("@Redirect("));
        assertTrue(source.contains("BlockEntityRenderer;submit"));
        assertTrue(source.contains("try {"));
        assertTrue(source.contains("finally {"));
        assertFalse(source.contains("@Inject(method = \"submit\", at = @At(\"HEAD\"))"));
    }

    @Test
    void auditedBlockEntityAllowlistCannotAdmitSubclassesImplicitly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalBlockEntityExactMotion.java"
        ));
        assertTrue(source.contains("stateClass == ChestRenderState.class"));
        assertTrue(source.contains("stateClass == BannerRenderState.class"));
        assertTrue(source.contains("stateClass == ShulkerBoxRenderState.class"));
        assertTrue(source.contains("stateClass == BellRenderState.class"));
        assertFalse(source.contains("isAssignableFrom"));
    }

    @Test
    void IrisSelectionAndFinalAdmissionBothFailClosed() throws Exception {
        String iris = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPipelineOverrides.java"
        ));
        String manager = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
        ));
        assertTrue(iris.contains("MetalFxManager.observeIrisMotionSemanticsUnproven();"));
        assertTrue(manager.contains("irisMotionSemanticsUnprovenThisFrame"));
        assertTrue(manager.contains("iris-active-pipeline-motion-semantics-unproven"));
        assertTrue(manager.contains("!IrisMetalPipelineOverrides.frameGenerationMotionSemanticsProven()"));
    }
}
'''
Path("src/test/java/com/metallum/client/metal/render/MetalFrameGenerationSemanticGateSourceContractTest.java").write_text(source_contract)
