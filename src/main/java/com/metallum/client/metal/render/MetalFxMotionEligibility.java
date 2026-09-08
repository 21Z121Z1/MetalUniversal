package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.EvokerFangsRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.FireworkRocketRenderState;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.LlamaSpitRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.client.renderer.entity.state.MinecartTntRenderState;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.entity.state.ThrownTridentRenderState;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.client.renderer.entity.state.WitherSkullRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypeIds;

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
    static final int MISSING_HISTORY = 1 << 6;
    static final int SHARED_AUXILIARY = 1 << 7;

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
     * Whitelist only renderer families whose current staged geometry has an exact previous-source
     * representation. Rigid families use MetalEntityObjectPose; ordinary display block/item
     * geometry additionally proves local-topology continuity in MetalDisplayMotionSafety.
     *
     * <p>Living entities and Boat paddles deform through CPU-side model animation. They are
     * admitted only as exact staged-previous-position candidates: every submitted draw must match
     * the previous successfully submitted source frame before interpolation is allowed. Boat's
     * optional water-mask depth patch has a separately verified POSITION-only exact ABI.</p>
     */
    static boolean requiresExactPreviousPositions(final EntityRenderState state) {
        if (state instanceof LivingEntityRenderState
                || state instanceof BoatRenderState
                || state instanceof ThrownItemRenderState
                || state instanceof FireworkRocketRenderState
                || state instanceof ItemClusterRenderState
                || state instanceof ThrownTridentRenderState
                || state instanceof WitherSkullRenderState
                || state instanceof LlamaSpitRenderState
                || state instanceof EvokerFangsRenderState
                || state instanceof TntRenderState
                || state instanceof MinecartTntRenderState
                || state instanceof PaintingRenderState
                || isExactGenericEntityType(state)) {
            return true;
        }
        return state instanceof DisplayEntityRenderState displayState
                && MetalDisplayMotionSafety.requiresExactPreviousPositions(displayState);
    }

    static int incompleteEntityReason(final EntityRenderState state) {
        if (state instanceof LivingEntityRenderState) {
            // Candidate only. EntityRenderDispatcherMetalFxMixin marks the whole object
            // exact-required, so setupAnim/model-layer deformation can never fall back to a
            // rigid root approximation.
            return 0;
        }
        if (state instanceof BoatRenderState) {
            // Candidate only. MetalFxManager marks the whole object exact-required, so paddle
            // deformation and the optional water-mask draw must both match staged history.
            return 0;
        }
        if (state instanceof TntRenderState || state instanceof MinecartTntRenderState) {
            // Fuse swell deforms the rendered block. MinecartTntRenderState is also a
            // MinecartRenderState, so it must be exact-required before rigid minecart admission.
            // Cart ModelFeature and TNT BlockModelFeature draws both use staged carriers; any
            // unsupported material or manifest mismatch keeps interpolation fail-closed.
            return 0;
        }
        if (state instanceof PaintingRenderState) {
            // Painting emits pose-transformed ENTITY custom geometry through the existing exact
            // custom-geometry carrier. Variant/size/topology changes are guarded by the complete
            // staged manifest before history can be consumed.
            return 0;
        }
        if (isExactGenericEntityType(state)) {
            // Dragon fireballs use ENTITY cutout custom geometry; leash knots use the ordinary
            // ModelFeature path. Do not admit all EntityRenderState users: WindCharge shares this
            // Java state class but uses its own breeze-wind pipeline and remains fail-closed.
            return 0;
        }
        if (state instanceof ThrownItemRenderState
                || state instanceof FireworkRocketRenderState
                || state instanceof ItemClusterRenderState
                || state instanceof ThrownTridentRenderState
                || state instanceof WitherSkullRenderState
                || state instanceof LlamaSpitRenderState
                || state instanceof EvokerFangsRenderState) {
            // Candidate only. These renderers feed Item/Model staged builders already tracked by
            // MetalPreviousVertexHistory. Any unsupported material/overlay or manifest change
            // leaves exact coverage incomplete and rejects frame interpolation.
            return 0;
        }
        if (state instanceof ItemEntityRenderState
                || state instanceof MinecartRenderState
                || state instanceof ArrowRenderState
                || state instanceof FallingBlockRenderState) {
            return 0;
        }
        if (state instanceof DisplayEntityRenderState displayState) {
            return MetalDisplayMotionSafety.isFrameInterpolationSafe(displayState) ? 0 : DISPLAY_ENTITY;
        }
        return UNKNOWN_ENTITY;
    }

    private static boolean isExactGenericEntityType(final EntityRenderState state) {
        return state != null
                && state.entityType != null
                && isExactGenericEntityTypeKey(state.entityType.builtInRegistryHolder().key());
    }

    static boolean isExactGenericEntityTypeKey(final ResourceKey<EntityType<?>> key) {
        return EntityTypeIds.DRAGON_FIREBALL.equals(key)
                || EntityTypeIds.LEASH_KNOT.equals(key);
    }
}
