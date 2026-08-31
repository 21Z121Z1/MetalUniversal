package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an explicit semantic context around Iris's OpenGL composite renderer. */
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class IrisOpenGlCompositeRendererPassTraceMixin {
    @Shadow @Final private CompositePass compositePass;

    @Inject(method = "renderAll", at = @At("HEAD"))
    private void metallum$beginComposite(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.beginGroup(this, this.compositePass.name());
    }

    @Inject(method = "renderAll", at = @At("RETURN"))
    private void metallum$endComposite(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.endGroup(this.compositePass.name());
    }
}
