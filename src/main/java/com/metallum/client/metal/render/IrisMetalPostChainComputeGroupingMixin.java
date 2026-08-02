package com.metallum.client.metal.render;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Starts hazard-checked grouping scopes around real post-chain compute arrays. */
@Mixin(IrisMetalPostChain.class)
abstract class IrisMetalPostChainComputeGroupingMixin {
    @Shadow @Final
    private boolean concurrentCompute;

    @Unique
    private boolean metallum$computeGroupingActive;

    @Inject(method = "executeComputeGroup", at = @At("HEAD"), require = 0)
    private void metallum$beginComputeGrouping(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final IrisMetalPostChain.ResourceProvider resources,
            final List<?> computes,
            final List<String> executed,
            final CallbackInfo ci
    ) {
        this.metallum$computeGroupingActive = IrisMetalComputeGroupingRuntime.begin(
                computes,
                this.concurrentCompute
        );
    }

    @Inject(method = "executeComputeGroup", at = @At("RETURN"), require = 0)
    private void metallum$finishComputeGrouping(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final IrisMetalPostChain.ResourceProvider resources,
            final List<?> computes,
            final List<String> executed,
            final CallbackInfo ci
    ) {
        // The final logical pass normally closes the shared native encoder.
        // This is only a leak guard for zero-dispatch or future early-return paths.
        if (this.metallum$computeGroupingActive) {
            IrisMetalComputeGroupingRuntime.abort();
            this.metallum$computeGroupingActive = false;
        }
    }
}
