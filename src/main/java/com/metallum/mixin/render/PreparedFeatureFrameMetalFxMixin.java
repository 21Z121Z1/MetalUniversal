package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flushes motion replays after feature execution while staged draw buffers remain valid. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFeatureFrameMetalFxMixin {
    @Inject(
            method = "close",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;endDraw()V"
            )
    )
    private void metallum$flushObjectMotionBeforeEndDraw(final CallbackInfo ci) {
        MetalFxManager.flushEntityMotionReplays();
    }
}
