package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;

/**
 * Per-render-frame admission state for Metal frame interpolation.
 *
 * <p>MetalFX Temporal can suppress history for individual pixels, but
 * {@code MTLFXFrameInterpolator} consumes the finished motion texture without
 * the project's reactive/confidence masks. A rendered primitive whose exact
 * previous geometry is unavailable therefore makes the current source-frame
 * pair unsafe for interpolation. This tracker is deliberately monotonic inside
 * a frame: once one such primitive is observed, that frame can only be
 * presented as a real frame.</p>
 *
 * <p>The tracker is reset by the same {@link MetalFxManager#beginFrame()} owner
 * that resets object-motion observations, so the rejection cannot leak across
 * frame transactions and does not require stopping/restarting the native
 * presenter.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalFxMotionEligibility {
    static final int NON_RIGID_ENTITY = 1;
    static final int UNKNOWN_ENTITY = 1 << 1;
    static final int FIRST_PERSON = 1 << 2;
    static final int PARTICLE = 1 << 3;
    static final int MOVING_BLOCK = 1 << 4;

    private int rejectedReasons;

    void beginFrame() {
        rejectedReasons = 0;
    }

    void reject(final int reason) {
        if (reason == 0) {
            return;
        }
        rejectedReasons |= reason;
    }

    boolean eligible() {
        return rejectedReasons == 0;
    }

    int rejectedReasons() {
        return rejectedReasons;
    }

    /**
     * Returns zero only for renderer families whose root object transform is
     * currently reconstructed from Minecraft 26.2 source semantics. A living
     * renderer is rejected even though its root translation/body yaw is known:
     * {@code EntityModel.setupAnim} changes child-part vertices independently,
     * so a root matrix is not complete frame-interpolation motion.
     */
    static int incompleteEntityReason(final EntityRenderState state) {
        if (state instanceof LivingEntityRenderState) {
            return NON_RIGID_ENTITY;
        }
        if (state instanceof DisplayEntityRenderState
                || state instanceof ItemEntityRenderState
                || state instanceof MinecartRenderState
                || state instanceof BoatRenderState
                || state instanceof ArrowRenderState) {
            return 0;
        }
        return UNKNOWN_ENTITY;
    }
}
