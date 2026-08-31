package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Closes a direct Iris composite pass after its OpenGL fullscreen draw. */
@Mixin(value = net.irisshaders.iris.pipeline.CompositeRenderer.class, remap = false)
public abstract class IrisOpenGlCompositeDrawTraceMixin {
    @Inject(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V",
                    shift = At.Shift.AFTER
            )
    )
    private void metallum$recordCompositeDraw(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.logicalDraw();
        IrisOpenGlPassTrace.finishLogicalPass();
    }
}
