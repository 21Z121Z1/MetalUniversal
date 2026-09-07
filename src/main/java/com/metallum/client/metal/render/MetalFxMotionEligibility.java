package com.metallum.client.metal.render;

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
     * <p>Living entities still remain fail-closed. Boat paddles also deform in setupAnim, but
     * Boat model submits are now admitted only as exact staged-previous-position objects; their
     * water-mask depth patch has a separately verified POSITION-only exact ABI.</p>
     */
    static int incompleteEntityReason(final EntityRenderState state) {
        if (state instanceof LivingEntityRenderState) {
            return NON_RIGID_ENTITY;
        }
        if (state instanceof BoatRenderState) {
            // Candidate only. MetalFxManager marks the whole object exact-required, so paddle
            // deformation and the optional water-mask draw must both match staged history.
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
}
