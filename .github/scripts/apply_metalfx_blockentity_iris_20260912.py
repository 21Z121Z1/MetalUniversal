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
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Exact staged-vertex owners for block-entity renderer families whose complete 26.2 output path is proven.
 *
 * <p>The supported families below emit entity-format {@code submitModel} geometry. Their animation is
 * CPU/model-state driven (chest lid, banner cloth, shulker lid, bell swing), so reusing the existing
 * transactional staged-position history is more faithful than inventing a rigid block transform.
 * Unsupported renderer families stay fail-closed in {@link MetalFxManager} until their collector output
 * surface is audited as a whole.</p>
 *
 * <p>A block position is the logical object identity. The block-entity type is carried as a negative,
 * process-stable synthetic generation so replacing (for example) a normal chest with another block-entity
 * type at the same position cannot inherit its previous vertex stream. Absence is already transactional:
 * {@link MetalPreviousVertexHistory#commitSubmittedFrame()} retains only owners present in the successfully
 * submitted source frame, so a disappear/reappear sequence has no stale predecessor.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalBlockEntityExactMotion {
    private static final long OBJECT_NAMESPACE = 0x4D4658424C4F434BL; // ASCII "MFXBLOCK".
    private static final Map<Object, Long> TYPE_GENERATIONS = new IdentityHashMap<>();
    private static long nextTypeGeneration = -0x424C4F434B000001L;

    private MetalBlockEntityExactMotion() {
    }

    static boolean supports(final BlockEntityRenderState state) {
        return state != null && supportsStateClass(state.getClass());
    }

    static boolean supportsStateClass(final Class<?> stateClass) {
        return stateClass != null && (
                ChestRenderState.class.isAssignableFrom(stateClass)
                        || BannerRenderState.class.isAssignableFrom(stateClass)
                        || ShulkerBoxRenderState.class.isAssignableFrom(stateClass)
                        || BellRenderState.class.isAssignableFrom(stateClass)
        );
    }

    static @Nullable MetalEntityMotionCapture.Sample sample(final BlockEntityRenderState state) {
        if (!supports(state) || state.blockPos == null || state.blockEntityType == null) {
            return null;
        }
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(
                objectId(state.blockPos.asLong()),
                generationForType(state.blockEntityType),
                identity,
                identity,
                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES
        );
    }

    static long objectId(final long packedBlockPos) {
        return mix(packedBlockPos ^ OBJECT_NAMESPACE);
    }

    static synchronized long generationForType(final Object blockEntityType) {
        if (blockEntityType == null) {
            throw new NullPointerException("blockEntityType");
        }
        Long existing = TYPE_GENERATIONS.get(blockEntityType);
        if (existing != null) {
            return existing;
        }
        if (nextTypeGeneration == Long.MIN_VALUE) {
            throw new IllegalStateException("Block-entity synthetic generation space exhausted");
        }
        long generation = nextTypeGeneration--;
        TYPE_GENERATIONS.put(blockEntityType, generation);
        return generation;
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
Path("src/main/java/com/metallum/client/metal/render/MetalBlockEntityExactMotion.java").write_text(block_exact)

block_test = r'''package com.metallum.client.metal.render;

import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MetalBlockEntityExactMotionTest {
    @Test
    void onlyAuditedModelFamiliesAreAdmitted() {
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(ChestRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(BannerRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(ShulkerBoxRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(BellRenderState.class));
        assertFalse(MetalBlockEntityExactMotion.supportsStateClass(BeaconRenderState.class));
    }

    @Test
    void blockPositionIdentityIsStableAndNamespaced() {
        long first = MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDEFL);
        assertEquals(first, MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDEFL));
        assertNotEquals(first, MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDF0L));
    }

    @Test
    void blockEntityTypesReceiveStableDistinctSyntheticGenerations() {
        Object chestType = new Object();
        Object bannerType = new Object();
        long chestGeneration = MetalBlockEntityExactMotion.generationForType(chestType);
        assertTrue(chestGeneration < 0L);
        assertEquals(chestGeneration, MetalBlockEntityExactMotion.generationForType(chestType));
        assertNotEquals(chestGeneration, MetalBlockEntityExactMotion.generationForType(bannerType));
    }
}
'''
Path("src/test/java/com/metallum/client/metal/render/MetalBlockEntityExactMotionTest.java").write_text(block_test)

mixin_path = Path("src/main/java/com/metallum/mixin/render/BlockEntityRenderDispatcherMetalFxMixin.java")
mixin = mixin_path.read_text()
mixin = replace_once(
    mixin,
    '''/** Observes each real block-entity state at the 26.2 dispatcher submit boundary. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMetalFxMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void metallum$observeBlockEntity(
            final S state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeBlockEntity(state);
    }
}
''',
    '''/** Brackets each real block-entity submission with its exact staged-motion owner when proven. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMetalFxMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void metallum$beginBlockEntity(
            final S state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera,
            final CallbackInfo ci
    ) {
        MetalFxManager.beginBlockEntitySubmission(state);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends BlockEntityRenderState> void metallum$endBlockEntity(
            final S state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera,
            final CallbackInfo ci
    ) {
        MetalFxManager.endBlockEntitySubmission();
    }
}
''',
    "block entity dispatcher bracket",
)
mixin_path.write_text(mixin)

manager_path = Path("src/main/java/com/metallum/client/metal/render/MetalFxManager.java")
manager = manager_path.read_text()
manager = replace_once(
    manager,
    '''    /**
     * Observes one real block-entity state at the Minecraft dispatcher submit
     * boundary.  The piston state is the only block-entity family with a
     * source-faithful staged motion producer in 26.2; all other states remain
     * explicitly unsupported rather than being mislabeled as absent.
     */
    public static void observeBlockEntity(final BlockEntityRenderState state) {
        MetalFxManager manager = active;
        if (manager == null || state == null) {
            return;
        }
        manager.observeBlockEntityInternal(state);
    }
''',
    '''    /**
     * Opens one real block-entity submit. Piston keeps its dedicated moving-block producer;
     * audited entity-format model families use exact staged previous vertices. Every other
     * renderer remains explicitly unsupported rather than being mislabeled as absent.
     */
    public static void beginBlockEntitySubmission(final BlockEntityRenderState state) {
        MetalFxManager manager = active;
        if (manager == null || state == null) {
            return;
        }
        manager.observeBlockEntityInternal(state);
        if (manager.effectiveMode != MetalFxConfig.Mode.TEMPORAL
                || manager.runtimeDisabled
                || !MetalBlockEntityExactMotion.supports(state)) {
            return;
        }
        MetalEntityMotionCapture.Sample sample = MetalBlockEntityExactMotion.sample(state);
        if (sample == null) {
            manager.observeUnsupportedProducer(
                    FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,
                    "audited-block-entity-motion-owner-unavailable"
            );
            return;
        }
        MetalEntityMotionCapture.attachState(state, sample);
        MetalEntityMotionCapture.requireExactState(state);
        manager.markExactProducerCandidate(
                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,
                sample
        );
        MetalEntityMotionCapture.beginEntitySubmission(state);
    }

    public static void endBlockEntitySubmission() {
        MetalEntityMotionCapture.endEntitySubmission();
    }

    /** Compatibility observation hook for source-level diagnostics that do not execute the dispatcher bracket. */
    public static void observeBlockEntity(final BlockEntityRenderState state) {
        MetalFxManager manager = active;
        if (manager != null && state != null) {
            manager.observeBlockEntityInternal(state);
        }
    }
''',
    "block entity public bracket",
)
manager = replace_once(
    manager,
    '''        observeUnsupportedProducer(
                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,
                "block-entity-renderer-has-no-previous-transform-contract"
        );
''',
    '''        if (MetalBlockEntityExactMotion.supports(state)) {
            observeProducer(
                    FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES, 1
            );
            return;
        }
        observeUnsupportedProducer(
                FrameSynthesisContract.ProducerDomain.BLOCK_ENTITIES,
                "block-entity-renderer-has-no-previous-transform-contract"
        );
''',
    "audited block entity admission",
)
manager = replace_once(
    manager,
    '''        sourceFrameStamp = new FrameSynthesisContract.FrameStamp(frameId, historyEpoch);
        frameSynthesisReceipts.beginFrame(sourceFrameStamp);
''',
    '''        sourceFrameStamp = new FrameSynthesisContract.FrameStamp(frameId, historyEpoch);
        frameSynthesisReceipts.beginFrame(sourceFrameStamp);
        if (!IrisMetalPipelineOverrides.frameGenerationMotionSemanticsProven()) {
            // The color path can be replaced by arbitrary Iris vertex deformation, clipping and
            // discard semantics while motion replay still uses Metallum's reduced vanilla shaders.
            // Until those semantics are reproduced per supported pack/program, make the mismatch
            // explicit in the frame receipt instead of allowing diagnostic overrides to look safe.
            observeUnsupportedProducer(
                    FrameSynthesisContract.ProducerDomain.MODDED_RENDERERS,
                    "iris-active-pipeline-motion-semantics-unproven"
            );
        }
''',
    "Iris source-frame fail-closed receipt",
)
manager_path.write_text(manager)

iris_path = Path("src/main/java/com/metallum/client/metal/render/IrisMetalPipelineOverrides.java")
iris = iris_path.read_text()
iris = replace_once(
    iris,
    '''    public static int activeGenerationForDiagnostics() {
        Instance instance = active;
        return instance == null ? -1 : instance.generation();
    }

''',
    '''    public static int activeGenerationForDiagnostics() {
        Instance instance = active;
        return instance == null ? -1 : instance.generation();
    }

    /**
     * Frame Interpolator motion replay is proven only for the vanilla clip/vertex semantics today.
     * An active Iris Metal generation may replace vertex position, clipping or alpha/discard behavior,
     * so Frame Generation must remain fail-closed until the motion path reproduces the selected pack.
     */
    static boolean frameGenerationMotionSemanticsProven() {
        return active == null;
    }

''',
    "Iris frame-generation semantic gate",
)
iris_path.write_text(iris)
