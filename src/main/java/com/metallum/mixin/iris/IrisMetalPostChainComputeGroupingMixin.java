package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalComputeGroupingRuntime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Starts hazard-checked grouping scopes around real post-chain compute arrays. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalPostChainComputeGroupingMixin {
    @Shadow @Final
    private boolean concurrentCompute;

    @Unique
    private boolean metallum$computeGroupingActive;

    @Inject(method = "executeComputeGroup", at = @At("HEAD"), require = 0)
    private void metallum$beginComputeGrouping(
            final @Coerce Object device,
            final @Coerce Object targets,
            final @Coerce Object resources,
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
            final @Coerce Object device,
            final @Coerce Object targets,
            final @Coerce Object resources,
            final List<?> computes,
            final List<String> executed,
            final CallbackInfo ci
    ) {
        if (this.metallum$computeGroupingActive) {
            IrisMetalComputeGroupingRuntime.abort();
            this.metallum$computeGroupingActive = false;
        }
    }
}
