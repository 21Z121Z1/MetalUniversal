from pathlib import Path
import json


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}")
    p.write_text(text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str, expected: int) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} patch anchors, found {count}")
    p.write_text(text.replace(old, new))


def write(path: str, content: str) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    "import net.minecraft.client.renderer.entity.state.EntityRenderState;\n",
    "import net.minecraft.client.renderer.entity.state.EntityRenderState;\n"
    "import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;\n"
    "import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;\n"
    "import net.minecraft.world.level.block.state.BlockState;\n",
)
replace_once(
    manager,
    "    private final Map<Entity, Long> entityGenerations = new IdentityHashMap<>();\n"
    "    private long nextEntityGeneration = 1L;\n",
    "    private final Map<Entity, Long> entityGenerations = new IdentityHashMap<>();\n"
    "    private final Map<PistonMovingBlockEntity, PistonMotionGeneration> pistonGenerations = new IdentityHashMap<>();\n"
    "    private long nextEntityGeneration = 1L;\n",
)
replace_once(
    manager,
    "    /** Marks a submitted entity whose complete previous geometry is not represented by the motion pass. */\n",
    "    /**\n"
    "     * Captures the exact interpolated translation used by Minecraft 26.2's piston renderer.\n"
    "     * The moving block is keyed by the PistonMovingBlockEntity lifetime plus its exact BlockState\n"
    "     * variant, so the SHORT-head topology transition starts a fresh history instead of reusing\n"
    "     * vertices from a different model. The unshifted source-piston base receives an identity sample.\n"
    "     */\n"
    "    public static void capturePistonMotion(\n"
    "            final PistonMovingBlockEntity blockEntity,\n"
    "            final PistonHeadRenderState state\n"
    "    ) {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null && blockEntity != null && state != null) {\n"
    "            manager.capturePistonMotionInternal(blockEntity, state);\n"
    "        }\n"
    "    }\n\n"
    "    /** Marks a submitted entity whose complete previous geometry is not represented by the motion pass. */\n",
)
replace_once(
    manager,
    "    private void captureEntityMotionInternal(final Entity entity, final EntityRenderState state) {\n",
    "    private void capturePistonMotionInternal(\n"
    "            final PistonMovingBlockEntity blockEntity,\n"
    "            final PistonHeadRenderState state\n"
    "    ) {\n"
    "        if (effectiveMode != MetalFxConfig.Mode.TEMPORAL || runtimeDisabled || state.block == null) {\n"
    "            return;\n"
    "        }\n\n"
    "        BlockState blockState = state.block.blockState;\n"
    "        PistonMotionGeneration generationState = pistonGenerations.get(blockEntity);\n"
    "        // BlockState values are canonical immutable state-definition entries in vanilla. Identity is\n"
    "        // deliberately conservative here: even a semantically-equal replacement instance gets a fresh\n"
    "        // generation and therefore one real-only frame, never motion from uncertain topology.\n"
    "        if (generationState == null || generationState.blockState() != blockState) {\n"
    "            generationState = new PistonMotionGeneration(blockState, nextEntityGeneration++);\n"
    "            pistonGenerations.put(blockEntity, generationState);\n"
    "        }\n\n"
    "        long objectId = blockEntity.getBlockPos().asLong();\n"
    "        long generation = generationState.generation();\n"
    "        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(objectId, generation);\n"
    "        Matrix4f currentObject = MetalPistonMotion.offsetTransform(state.xOffset, state.yOffset, state.zOffset);\n"
    "        if (!motionStateStore.observeIfFrameOpen(key, currentObject)) {\n"
    "            return;\n"
    "        }\n"
    "        Matrix4f previousObject = motionStateStore.previous(key);\n"
    "        MetalEntityMotionCapture.attachMovingBlockState(\n"
    "                state.block,\n"
    "                new MetalEntityMotionCapture.Sample(objectId, generation, currentObject, previousObject)\n"
    "        );\n"
    "        if (previousObject == null) {\n"
    "            // First visibility, a skipped submitted frame, a reset, or a model/topology transition has\n"
    "            // no exact previous moving-block pose. Keep this source frame real and seed the next one.\n"
    "            motionEligibility.reject(MetalFxMotionEligibility.MOVING_BLOCK);\n"
    "        }\n\n"
    "        if (state.base != null) {\n"
    "            // PistonHeadRenderer submits the retraction base without x/y/zOffset. It is static world\n"
    "            // geometry, so identity object motion is exact; camera motion remains in the clip matrices.\n"
    "            Matrix4f identity = new Matrix4f();\n"
    "            MetalEntityMotionCapture.attachMovingBlockState(\n"
    "                    state.base,\n"
    "                    new MetalEntityMotionCapture.Sample(objectId, generation, identity, identity)\n"
    "            );\n"
    "        }\n"
    "    }\n\n"
    "    private void captureEntityMotionInternal(final Entity entity, final EntityRenderState state) {\n",
)
replace_all(
    manager,
    "        entityGenerations.clear();\n",
    "        entityGenerations.clear();\n        pistonGenerations.clear();\n",
    expected=2,
)
replace_once(
    manager,
    "    record FrameGenerationInput(\n",
    "    private record PistonMotionGeneration(BlockState blockState, long generation) {\n"
    "    }\n\n"
    "    record FrameGenerationInput(\n",
)

capture = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java"
replace_once(
    capture,
    "    public static boolean hasMovingBlockOwner(final Object renderState) {\n"
    "        return enabled && renderState != null && SUBMITS.containsKey(renderState);\n"
    "    }\n",
    "    /** Associates block-entity-owned moving geometry directly with its exact motion sample. */\n"
    "    public static void attachMovingBlockState(final Object renderState, final Sample sample) {\n"
    "        if (enabled && renderState != null && sample != null) {\n"
    "            SUBMITS.put(renderState, sample);\n"
    "            modelSubmitsCaptured++;\n"
    "        }\n"
    "    }\n\n"
    "    public static boolean hasMovingBlockOwner(final Object renderState) {\n"
    "        return enabled && renderState != null && SUBMITS.containsKey(renderState);\n"
    "    }\n",
)

piston_math = '''package com.metallum.client.metal.render;

import org.joml.Matrix4f;

/** Source-semantic transform helpers for Minecraft's translating piston moving block. */
final class MetalPistonMotion {
    private MetalPistonMotion() {
    }

    /**
     * PistonHeadRenderer submits only the moving block under translate(xOffset, yOffset, zOffset).
     * Every other transform in that block-entity path is constant for the same render-state model and
     * therefore cancels in previous * inverse(current).
     */
    static Matrix4f offsetTransform(final float xOffset, final float yOffset, final float zOffset) {
        return new Matrix4f().translation(xOffset, yOffset, zOffset);
    }
}
'''
write("src/main/java/com/metallum/client/metal/render/MetalPistonMotion.java", piston_math)

piston_mixin = '''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the exact x/y/z offset already extracted by the vanilla 26.2 piston renderer. */
@Mixin(PistonHeadRenderer.class)
public abstract class PistonHeadRendererMetalFxMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void metallum$capturePistonMotion(
            final PistonMovingBlockEntity blockEntity,
            final PistonHeadRenderState state,
            final float partialTicks,
            final Vec3 cameraPosition,
            final ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress,
            final CallbackInfo ci
    ) {
        MetalFxManager.capturePistonMotion(blockEntity, state);
    }
}
'''
write("src/main/java/com/metallum/mixin/render/PistonHeadRendererMetalFxMixin.java", piston_mixin)

piston_test = '''package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalPistonMotionTest {
    private static final float EPSILON = 1.0e-6F;

    @Test
    void currentToPreviousIsExactOffsetDelta() {
        Matrix4f previous = MetalPistonMotion.offsetTransform(-0.75F, 0.0F, 0.0F);
        Matrix4f current = MetalPistonMotion.offsetTransform(-0.50F, 0.0F, 0.0F);
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(1L, 1L, current, previous);

        Vector3f point = MetalEntityMotionCapture.objectCurrentToPrevious(sample)
                .transformPosition(new Vector3f(2.0F, 3.0F, 4.0F));
        assertEquals(1.75F, point.x, EPSILON);
        assertEquals(3.0F, point.y, EPSILON);
        assertEquals(4.0F, point.z, EPSILON);
    }

    @Test
    void arbitraryAxisOffsetsPreservePreviousPosition() {
        Matrix4f previous = MetalPistonMotion.offsetTransform(0.0F, 0.25F, 0.0F);
        Matrix4f current = MetalPistonMotion.offsetTransform(0.0F, 0.75F, 0.0F);
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(2L, 1L, current, previous);

        Vector3f currentPoint = new Vector3f(1.0F, 2.75F, -4.0F);
        Vector3f previousPoint = MetalEntityMotionCapture.objectCurrentToPrevious(sample)
                .transformPosition(new Vector3f(currentPoint));
        assertEquals(1.0F, previousPoint.x, EPSILON);
        assertEquals(2.25F, previousPoint.y, EPSILON);
        assertEquals(-4.0F, previousPoint.z, EPSILON);
    }
}
'''
write("src/test/java/com/metallum/client/metal/render/MetalPistonMotionTest.java", piston_test)

mixins_path = Path("src/main/resources/metallum.mixins.json")
mixins = json.loads(mixins_path.read_text())
client = mixins["client"]
name = "render.PistonHeadRendererMetalFxMixin"
if name not in client:
    anchor = client.index("render.MovingBlockFeatureRendererMetalFxMixin") + 1
    client.insert(anchor, name)
mixins_path.write_text(json.dumps(mixins, indent=2) + "\n")
