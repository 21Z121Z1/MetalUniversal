package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Iris reload teardown from issuing OpenGL texture-unit calls on Metal. */
@Mixin(value = PipelineManager.class, remap = false)
public abstract class IrisPipelineManagerCompatMixin {
    @Inject(method = "resetTextureState", at = @At("HEAD"), cancellable = true)
    private void metallum$skipOpenGlTextureReset(final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }
}
