package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalComputeGroupingRuntime;
import com.metallum.client.metal.render.mtl.MTLCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLComputeCommandEncoder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps one native compute encoder across a hazard-independent logical group. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class IrisMetalCommandEncoderComputeGroupingMixin {
    @Shadow @Nullable
    private MTLCommandEncoder currentEncoder;

    @Inject(method = "computeCommandEncoder", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallum$reuseComputeEncoder(
            final CallbackInfoReturnable<MTLComputeCommandEncoder> cir
    ) {
        if (!IrisMetalComputeGroupingRuntime.mayReuseEncoder()) return;
        if (this.currentEncoder instanceof MTLComputeCommandEncoder compute) {
            cir.setReturnValue(compute);
        } else {
            // A submit, render/blit transition or exceptional path ended the
            // encoder before the planned group completed. Never attach the
            // remaining logical count to a new unrelated encoder.
            IrisMetalComputeGroupingRuntime.abort();
        }
    }

    @Inject(method = "endComputePass", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallum$deferComputeEncoderClose(
            final MTLComputeCommandEncoder encoder,
            final CallbackInfo ci
    ) {
        if (this.currentEncoder == encoder && IrisMetalComputeGroupingRuntime.deferClose()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCommandEncoder", at = @At("HEAD"), require = 0)
    private void metallum$abortGroupingBeforeRender(
            final CallbackInfoReturnable<?> cir
    ) {
        IrisMetalComputeGroupingRuntime.abort();
    }

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void metallum$abortGroupingBeforeSubmit(final CallbackInfo ci) {
        IrisMetalComputeGroupingRuntime.abort();
    }
}
