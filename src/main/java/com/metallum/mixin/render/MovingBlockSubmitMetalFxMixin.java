package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the observing entity to a moving-block submit while its owner is still on
 * the stack.
 *
 * <p>The submit is constructed inside {@code FallingBlockRenderer.submit}, which
 * runs between {@code beginEntitySubmission} and {@code endEntitySubmission}, so
 * the current sample is the falling block's own. Geometry is built much later, in
 * a different phase, which is why the association has to be recorded here.</p>
 *
 * <p>Keyed by the render state rather than the submit, for the reason documented
 * on {@code MetalEntityMotionCapture.beginMovingBlockBuild}.</p>
 */
@Mixin(MovingBlockFeatureRenderer.Submit.class)
public abstract class MovingBlockSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureMovingBlockOwner(
            final Matrix4fc pose,
            final MovingBlockRenderState movingBlockRenderState,
            final int outlineColor,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(movingBlockRenderState);
    }
}
