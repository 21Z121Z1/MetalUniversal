package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalHandCoverageRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns exact hand-coverage lifetime at Iris's existing frame and depthtex2 boundaries. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPipelineOverrides")
public abstract class IrisMetalHandCoverageBoundaryMixin {
    @Inject(method = "updateFrame", at = @At("HEAD"), remap = false)
    private static void metallum$beginHandCoverageFrame(final CallbackInfo ci) {
        IrisMetalHandCoverageRuntime.beginFrame();
    }

    @Inject(method = "captureNoHandDepth", at = @At("TAIL"), remap = false)
    private static void metallum$beginExactHandCoverage(final CallbackInfo ci) {
        IrisMetalHandCoverageRuntime.beginHandPhase();
    }
}
