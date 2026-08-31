package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the shadow-composite semantic context to the OpenGL reference trace. */
@Mixin(value = ShadowCompositeRenderer.class, remap = false)
public abstract class IrisOpenGlShadowCompositeRendererPassTraceMixin {
    @Inject(method = "renderAll", at = @At("HEAD"))
    private void metallum$beginShadowComposite(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.beginGroup(this, "shadowcomp");
    }

    @Inject(method = "renderAll", at = @At("RETURN"))
    private void metallum$endShadowComposite(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.endGroup("shadowcomp");
    }
}
