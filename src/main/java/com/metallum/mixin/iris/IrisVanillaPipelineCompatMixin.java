package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code VanillaRenderingPipeline} is Metal-safe except for one method:
 * {@code beginLevelRendering} touches {@code GL.getCapabilities()} /
 * {@code glClipControl} / {@code GlStateManager._glUseProgram} (reverse-Z
 * bookkeeping the Metal backend already owns). With that call cancelled, the
 * real vanilla pipeline object serves every per-frame Iris hook while dormant.
 */
@Mixin(value = VanillaRenderingPipeline.class, remap = false)
public abstract class IrisVanillaPipelineCompatMixin {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), cancellable = true)
    private void metallum$skipGlClipControl(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }
}
