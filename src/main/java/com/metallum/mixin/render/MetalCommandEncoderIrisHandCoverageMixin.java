package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalHandCoverageRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Consumes exact Iris hand coverage immediately before the fused temporal motion encode. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalCommandEncoderIrisHandCoverageMixin {
    @Inject(method = "encodeMetalFxV2", at = @At("HEAD"), remap = false)
    private void metallum$prepareExactIrisHandMotion(final CallbackInfoReturnable<Boolean> cir) {
        IrisMetalHandCoverageRuntime.prepareExactMotionBeforeTemporalEncode();
    }
}
