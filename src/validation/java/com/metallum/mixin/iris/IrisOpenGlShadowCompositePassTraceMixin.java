package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records Iris shadow-composite pass descriptors before their direct draw. */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowCompositeRenderer$Pass", remap = false)
public abstract class IrisOpenGlShadowCompositePassTraceMixin {
    @Inject(method = "setupState", at = @At("HEAD"))
    private void metallum$beginShadowCompositePass(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.beginLogicalPass(this, null);
    }
}
