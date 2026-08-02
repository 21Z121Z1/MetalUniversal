package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalOptimizationBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Builds and retires the generation-owned advanced optimization plan. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalOptimizationBootstrapMixin {
    @Inject(method = "create", at = @At("RETURN"))
    private static void metallum$buildOptimizationPlan(
            final int generation,
            final Object programSet,
            final int targetCount,
            final java.util.BitSet initialFlipState,
            final CallbackInfoReturnable<Object> cir
    ) {
        IrisMetalOptimizationBootstrap.onPostChainCreated(cir.getReturnValue());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void metallum$retireOptimizationPlan(final CallbackInfo ci) {
        IrisMetalOptimizationBootstrap.onPostChainClosed();
    }
}
