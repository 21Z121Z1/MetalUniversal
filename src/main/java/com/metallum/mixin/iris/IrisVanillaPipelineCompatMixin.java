package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Iris's shaders-off pipeline from issuing OpenGL clip-control calls on Metal. */
@Mixin(value = VanillaRenderingPipeline.class, remap = false)
public abstract class IrisVanillaPipelineCompatMixin {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), cancellable = true)
    private void metallum$skipGlClipControl(final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }
}
