package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.oit.OitStage;
import com.mojang.renderpearl.api.commands.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flushes motion replays after feature execution while staged draw buffers remain valid. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFeatureFrameMetalFxMixin {
    @Inject(method = "executeOit", at = @At("HEAD"))
    private void metallum$beginOitColorReceipts(final OitStage stage, final RenderPass pass, final CallbackInfo ci) {
        if (stage == OitStage.ACCUMULATE) MetalFxManager.beginTransparencyPhase();
    }

    @Inject(method = "executeOit", at = @At("RETURN"))
    private void metallum$endOitColorReceipts(final OitStage stage, final RenderPass pass, final CallbackInfo ci) {
        if (stage == OitStage.ACCUMULATE) MetalFxManager.endTransparencyPhase();
    }
    @Inject(method = "executeTranslucent", at = @At("HEAD"))
    private void metallum$beginTranslucentReceiptPhase(final CallbackInfo ci) {
        MetalFxManager.beginTransparencyPhase();
    }

    @Inject(method = "executeTranslucent", at = @At("RETURN"))
    private void metallum$endTranslucentReceiptPhase(final CallbackInfo ci) {
        MetalFxManager.endTransparencyPhase();
    }

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
