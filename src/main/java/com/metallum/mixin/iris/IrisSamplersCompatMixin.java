package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import net.irisshaders.iris.samplers.IrisSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Iris from creating OpenGL sampler objects on a Metal device. */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class IrisSamplersCompatMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlSamplerInit(final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }
}
