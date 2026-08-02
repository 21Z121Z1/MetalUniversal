package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.samplers.IrisSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code IrisSamplers.initRenderer} creates its static GL sampler objects via
 * raw {@code glGenSamplers}; cancelled while Iris is dormant on Metal.
 */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class IrisSamplersCompatMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlSamplerInit(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }
}
