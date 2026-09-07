from pathlib import Path
import json


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}")
    p.write_text(text.replace(old, new, 1))


def write(path: str, content: str) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    "    private final MetalMotionStateStore motionStateStore = new MetalMotionStateStore();\n",
    "    private final MetalMotionStateStore motionStateStore = new MetalMotionStateStore();\n"
    "    private final MetalFxMotionEligibility motionEligibility = new MetalFxMotionEligibility();\n",
)
replace_once(
    manager,
    "    /**\n"
    "     * Replays the exact staged entity geometry into the object-motion and\n",
    "    /** Marks a submitted entity whose complete previous geometry is not represented by the motion pass. */\n"
    "    public static void observeFrameInterpolationEntity(final EntityRenderState state) {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null && state != null) {\n"
    "            manager.motionEligibility.reject(MetalFxMotionEligibility.incompleteEntityReason(state));\n"
    "        }\n"
    "    }\n\n"
    "    /** First-person geometry has no exact previous local pose yet; reject only frame interpolation. */\n"
    "    public static void observeFirstPersonMotion() {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null) manager.motionEligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);\n"
    "    }\n\n"
    "    /** Quad particles store only the current extracted pose; reactive Temporal handling remains enabled. */\n"
    "    public static void observeParticleMotion() {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null) manager.motionEligibility.reject(MetalFxMotionEligibility.PARTICLE);\n"
    "    }\n\n"
    "    /** Moving blocks without an entity-owned staged replay (notably pistons) have no previous pose sidecar. */\n"
    "    public static void observeUnownedMovingBlockMotion() {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null) manager.motionEligibility.reject(MetalFxMotionEligibility.MOVING_BLOCK);\n"
    "    }\n\n"
    "    /**\n"
    "     * Replays the exact staged entity geometry into the object-motion and\n",
)
replace_once(
    manager,
    "    private void beginFrameInternal() {\n"
    "        reloadConfigIfRequested();\n"
    "        recordFramePacingDiagnostics();\n",
    "    private void beginFrameInternal() {\n"
    "        reloadConfigIfRequested();\n"
    "        motionEligibility.beginFrame();\n"
    "        recordFramePacingDiagnostics();\n",
)
replace_once(
    manager,
    "        if (!frameGenerationEnabled || runtimeDisabled || !frameUsesUpscaledTarget\n",
    "        if (!motionEligibility.eligible()) {\n"
    "            // A real source frame containing geometry without exact previous-position motion must not\n"
    "            // enter MTLFXFrameInterpolator. Reset the next admitted pair so it cannot bridge across\n"
    "            // this skipped source frame; MetalFX Temporal still receives its reactive/history masks.\n"
    "            frameResetForPresent = true;\n"
    "            return null;\n"
    "        }\n"
    "        if (!frameGenerationEnabled || runtimeDisabled || !frameUsesUpscaledTarget\n",
)

capture = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java"
replace_once(
    capture,
    "    public static void beginMovingBlockBuild(final Object renderState) {\n"
    "        beginBuild(renderState, true);\n"
    "    }\n",
    "    public static boolean hasMovingBlockOwner(final Object renderState) {\n"
    "        return enabled && renderState != null && SUBMITS.containsKey(renderState);\n"
    "    }\n\n"
    "    public static void beginMovingBlockBuild(final Object renderState) {\n"
    "        beginBuild(renderState, true);\n"
    "    }\n",
)

entity_mixin = "src/main/java/com/metallum/mixin/render/EntityRenderDispatcherMetalFxMixin.java"
replace_once(
    entity_mixin,
    "    ) {\n"
    "        MetalEntityMotionCapture.beginEntitySubmission(state);\n"
    "    }\n\n"
    "    @Inject(method = \"submit\", at = @At(\"RETURN\"))\n",
    "    ) {\n"
    "        // Admission is tied to actual submission rather than extraction, so culled entities do not\n"
    "        // unnecessarily suppress interpolation for the frame.\n"
    "        MetalFxManager.observeFrameInterpolationEntity(state);\n"
    "        MetalEntityMotionCapture.beginEntitySubmission(state);\n"
    "    }\n\n"
    "    @Inject(method = \"submit\", at = @At(\"RETURN\"))\n",
)

moving_mixin = "src/main/java/com/metallum/mixin/render/MovingBlockFeatureRendererMetalFxMixin.java"
replace_once(
    moving_mixin,
    "        // The level argument is the submit's MovingBlockRenderState, which is the\n"
    "        // key the submit constructor recorded the owner under.\n"
    "        MetalEntityMotionCapture.beginMovingBlockBuild(level);\n",
    "        // Falling blocks inherit an entity Sample through MovingBlockSubmitMetalFxMixin and already\n"
    "        // replay exact staged block geometry under the previous/current entity transform. Pistons and\n"
    "        // other non-entity moving blocks do not; reject frame interpolation instead of inventing motion.\n"
    "        if (!MetalEntityMotionCapture.hasMovingBlockOwner(level)) {\n"
    "            com.metallum.client.metal.render.MetalFxManager.observeUnownedMovingBlockMotion();\n"
    "        }\n"
    "        MetalEntityMotionCapture.beginMovingBlockBuild(level);\n",
)

eligibility = '''package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;

/**
 * Per-render-frame admission state for Metal frame interpolation.
 *
 * <p>MetalFX Temporal has pixel-level reactive/history rejection, while
 * MTLFXFrameInterpolator consumes the finished color/depth/motion source frame.
 * If any submitted primitive lacks an exact previous-position motion producer,
 * the whole source frame is kept real and the next interpolated pair is reset.
 * Rejection is monotonic only within the current frame.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalFxMotionEligibility {
    static final int NON_RIGID_ENTITY = 1;
    static final int UNKNOWN_ENTITY = 1 << 1;
    static final int FIRST_PERSON = 1 << 2;
    static final int PARTICLE = 1 << 3;
    static final int MOVING_BLOCK = 1 << 4;
    static final int DISPLAY_ENTITY = 1 << 5;

    private int rejectedReasons;

    void beginFrame() {
        rejectedReasons = 0;
    }

    void reject(final int reason) {
        if (reason != 0) rejectedReasons |= reason;
    }

    boolean eligible() {
        return rejectedReasons == 0;
    }

    int rejectedReasons() {
        return rejectedReasons;
    }

    /**
     * Whitelist only renderer families whose current staged geometry is rigid and whose complete
     * changing root transform is reconstructed by MetalEntityObjectPose.
     *
     * <p>Living entities change ModelPart vertices through setupAnim. Boats also animate child
     * geometry (paddles), and display block/text paths are not all associated with the current
     * model/item staged-replay carrier. They therefore remain fail-closed for frame interpolation
     * until previous local vertices are supplied, rather than receiving plausible-but-wrong root
     * vectors.</p>
     */
    static int incompleteEntityReason(final EntityRenderState state) {
        if (state instanceof LivingEntityRenderState || state instanceof BoatRenderState) {
            return NON_RIGID_ENTITY;
        }
        if (state instanceof ItemEntityRenderState
                || state instanceof MinecartRenderState
                || state instanceof ArrowRenderState
                || state instanceof FallingBlockRenderState) {
            return 0;
        }
        // Item display may be captured by ItemFeature, but block/text submit through different
        // feature families. Treat the family uniformly until all subtypes have exact replay.
        if (state instanceof DisplayEntityRenderState) {
            return DISPLAY_ENTITY;
        }
        return UNKNOWN_ENTITY;
    }
}
'''
write("src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java", eligibility)

first_person = '''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame-interpolation admission for first-person geometry.
 *
 * Minecraft 26.2 computes swing, bob, equip/use transforms inside submitArmWithItem from current
 * interpolated player/item state. Until a previous local pose is carried through the staged
 * geometry path, zero object motion is only an approximation. Hook the first pushPose inside the
 * non-scoping branch so a scoped call that submits nothing does not reject the frame.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMetalFxMixin {
    @Inject(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V")
    )
    private void metallum$observeFirstPersonGeometry(
            final AbstractClientPlayer player,
            final float frameInterp,
            final float xRot,
            final InteractionHand hand,
            final float attack,
            final ItemStack itemStack,
            final float inverseArmHeight,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final int lightCoords,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeFirstPersonMotion();
    }
}
'''
write("src/main/java/com/metallum/mixin/render/ItemInHandRendererMetalFxMixin.java", first_person)

particle = '''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects frame interpolation only when a quad-particle group is actually submitted.
 *
 * QuadParticleRenderState stores current extracted position/rotation/scale but no previous pose.
 * Its submit method invokes submitQuadParticleGroup only when particleCount > 0, so this hook does
 * not suppress interpolation merely because ParticleEngine.extract ran with an empty group.
 */
@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleRenderStateMetalFxMixin {
    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitQuadParticleGroup(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;)V"
            )
    )
    private void metallum$observeSubmittedParticles(
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeParticleMotion();
    }
}
'''
write("src/main/java/com/metallum/mixin/render/QuadParticleRenderStateMetalFxMixin.java", particle)

test = '''package com.metallum.client.metal.render;

import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxMotionEligibilityTest {
    @Test
    void rigidCapturedFamiliesAreAdmitted() {
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new ItemEntityRenderState()));
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new MinecartRenderState()));
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new ArrowRenderState()));
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new FallingBlockRenderState()));
    }

    @Test
    void nonRigidAndUnknownFamiliesFailClosed() {
        assertEquals(
                MetalFxMotionEligibility.NON_RIGID_ENTITY,
                MetalFxMotionEligibility.incompleteEntityReason(new LivingEntityRenderState())
        );
        assertEquals(
                MetalFxMotionEligibility.UNKNOWN_ENTITY,
                MetalFxMotionEligibility.incompleteEntityReason(new EntityRenderState())
        );
    }

    @Test
    void rejectionIsMonotonicWithinFrameAndResetsAtBoundary() {
        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();
        assertTrue(eligibility.eligible());

        eligibility.reject(MetalFxMotionEligibility.PARTICLE);
        eligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);
        assertFalse(eligibility.eligible());
        assertEquals(
                MetalFxMotionEligibility.PARTICLE | MetalFxMotionEligibility.FIRST_PERSON,
                eligibility.rejectedReasons()
        );

        eligibility.beginFrame();
        assertTrue(eligibility.eligible());
        assertEquals(0, eligibility.rejectedReasons());
    }
}
'''
write("src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java", test)

mixins_path = Path("src/main/resources/metallum.mixins.json")
mixins = json.loads(mixins_path.read_text())
client = mixins["client"]
anchor = client.index("render.EntityRenderDispatcherMetalFxMixin") + 1
for name in [
    "render.ItemInHandRendererMetalFxMixin",
    "render.QuadParticleRenderStateMetalFxMixin",
]:
    if name not in client:
        client.insert(anchor, name)
        anchor += 1
mixins_path.write_text(json.dumps(mixins, indent=2) + "\n")

stale = Path("src/main/java/com/metallum/mixin/render/ParticleEngineMetalFxMixin.java")
if stale.exists():
    stale.unlink()
