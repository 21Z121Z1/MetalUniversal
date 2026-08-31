package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records Iris final-pass lifecycle around the vanilla RenderPass API. */
@Mixin(value = FinalPassRenderer.class, remap = false)
public abstract class IrisOpenGlFinalPassTraceMixin {
    @Inject(method = "renderFinalPass", at = @At("HEAD"))
    private void metallum$beginFinalPass(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.beginFinalPass(this);
    }

    @Inject(
            method = "renderFinalPass",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V",
                    shift = At.Shift.AFTER
            )
    )
    private void metallum$recordFinalDraw(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.logicalDraw();
        IrisOpenGlPassTrace.finishLogicalPass();
    }

    @Inject(method = "renderFinalPass", at = @At("RETURN"))
    private void metallum$endFinalPass(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.endFinalPass();
    }
}
