package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalRenderFusionRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes each real Iris raster pass to the physical-resource fusion policy. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalRenderFusionPolicyMixin {
    @Inject(method = "executePass", at = @At("HEAD"), require = 0)
    private void metallum$beginRasterPass(
            @Coerce final Object device,
            @Coerce final Object targets,
            @Coerce final Object resources,
            @Coerce final Object plannedPass,
            final CallbackInfo ci
    ) {
        IrisMetalRenderFusionRuntime.beginPass(plannedPass);
    }

    @Inject(method = "executePass", at = @At("RETURN"), require = 0)
    private void metallum$finishRasterPass(
            @Coerce final Object device,
            @Coerce final Object targets,
            @Coerce final Object resources,
            @Coerce final Object plannedPass,
            final CallbackInfo ci
    ) {
        IrisMetalRenderFusionRuntime.endPass();
    }

    @Inject(method = "executeComputeGroup", at = @At("HEAD"), require = 0)
    private void metallum$breakBeforeCompute(final CallbackInfo ci) {
        IrisMetalRenderFusionRuntime.breakChain();
    }

    @Inject(method = "executeFinal", at = @At("HEAD"), require = 0)
    private void metallum$breakBeforeFinal(final CallbackInfo ci) {
        IrisMetalRenderFusionRuntime.breakChain();
    }

    @Inject(method = "executeColorSpace", at = @At("HEAD"), require = 0)
    private void metallum$breakBeforeColorSpace(final CallbackInfo ci) {
        IrisMetalRenderFusionRuntime.breakChain();
    }
}
