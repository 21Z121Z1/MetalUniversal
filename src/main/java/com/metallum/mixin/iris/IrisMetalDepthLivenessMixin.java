package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalOptimizationBootstrap;
import com.metallum.client.metal.render.IrisMetalDepthAllocationRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes depthtex1/depthtex2 capture blits only when the live generation plan
 * proves the resource has no consumer. The feature remains opt-in.
 */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPipelineOverrides")
public abstract class IrisMetalDepthLivenessMixin {
    @Inject(method = "captureNoTranslucentsDepth", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipUnusedDepthtex1(final CallbackInfo ci) {
        if (Boolean.getBoolean("metallum.iris.experimental.resourcePruning")
                && !IrisMetalOptimizationBootstrap.depthtex1Required()) {
            IrisMetalDepthAllocationRuntime.recordCaptureSkipped(1);
            ci.cancel();
        }
    }

    @Inject(method = "captureNoHandDepth", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipUnusedDepthtex2(final CallbackInfo ci) {
        if (Boolean.getBoolean("metallum.iris.experimental.resourcePruning")
                && !IrisMetalOptimizationBootstrap.depthtex2Required()) {
            IrisMetalDepthAllocationRuntime.recordCaptureSkipped(2);
            ci.cancel();
        }
    }
}
