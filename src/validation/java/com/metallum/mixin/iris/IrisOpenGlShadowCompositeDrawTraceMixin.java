package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Closes a shadow-composite pass after Iris submits its fullscreen RenderPass draw. */
@Mixin(value = net.irisshaders.iris.shadows.ShadowCompositeRenderer.class, remap = false)
public abstract class IrisOpenGlShadowCompositeDrawTraceMixin {
    @Inject(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V",
                    shift = At.Shift.AFTER
            )
    )
    private void metallum$recordShadowCompositeDraw(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.logicalDraw();
        IrisOpenGlPassTrace.finishLogicalPass();
    }
}
