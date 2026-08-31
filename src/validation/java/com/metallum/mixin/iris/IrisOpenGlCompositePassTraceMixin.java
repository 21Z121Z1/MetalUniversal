package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records Iris composite pass descriptors before their direct OpenGL draw. */
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass", remap = false)
public abstract class IrisOpenGlCompositePassTraceMixin {
    @Inject(method = "setupState", at = @At("HEAD"))
    private void metallum$beginCompositePass(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.beginLogicalPass(this, null);
    }
}
