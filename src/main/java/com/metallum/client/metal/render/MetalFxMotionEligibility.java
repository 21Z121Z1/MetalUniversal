package com.metallum.client.metal.render;

import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;

/**
 * Per-source-frame admission control for MTLFXFrameInterpolator.
 *
 * <p>Temporal upscaling can tolerate pixels that deliberately fall back to
 * camera-only motion plus disocclusion/reactive masks. Frame interpolation
 * cannot: a real source frame containing visible geometry with no exact
 * previous-position representation must not enter the interpolator pair.</p>
 */
final class MetalFxMotionEligibility {
    static final int NON_RIGID_ENTITY = 1 << 0;
    static final int DISPLAY_ENTITY = 1 << 1;
    static final int UNKNOWN_ENTITY = 1 << 2;
    static final int FIRST_PERSON = 1 << 3;
    static final int PARTICLE = 1 << 4;
    static final int MOVING_BLOCK = 1 << 5;
    static final int MISSING_HISTORY = 1 << 6;

    private int rejectedReasons;

    void beginFrame() {
        rejectedReasons = 0;
    }

    void reject(final int reason) {
        rejectedReasons |= reason;
    }

    boolean eligible() {
        return rejectedReasons == 0;
    }

    int rejectedReasons() {
        return rejectedReasons;
    }

    static int incompleteEntityReason(final EntityRenderState state) {
        if (state instanceof DisplayEntityRenderState displayState) {
            return MetalDisplayMotionSafety.isFrameInterpolationSafe(displayState) ? 0 : DISPLAY_ENTITY;
        }
        if (state instanceof LivingEntityRenderState || state instanceof BoatRenderState) {
            return NON_RIGID_ENTITY;
        }
        if (state instanceof ItemEntityRenderState
                || state instanceof MinecartRenderState
                || state instanceof ArrowRenderState
                || state instanceof FallingBlockRenderState) {
            return 0;
        }
        return UNKNOWN_ENTITY;
    }
}
