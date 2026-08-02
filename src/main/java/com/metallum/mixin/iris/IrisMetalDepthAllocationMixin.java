package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalDepthAllocationRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Releases dead depth histories and recreates them if a missed consumer appears. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalRenderTargets")
public abstract class IrisMetalDepthAllocationMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void metallum$registerDepthTargets(final CallbackInfo ci) {
        IrisMetalDepthAllocationRuntime.register(this);
    }

    @Inject(method = "createDepthTextures", at = @At("RETURN"), require = 0)
    private void metallum$pruneAfterDepthReallocation(
            final int width,
            final int height,
            final CallbackInfo ci
    ) {
        IrisMetalDepthAllocationRuntime.register(this);
    }

    @Inject(method = "noTranslucentsDepthTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallum$resolveDepthtex1Texture(final CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(IrisMetalDepthAllocationRuntime.ensureDepthtex1(this));
    }

    @Inject(method = "noTranslucentsDepthView", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallum$resolveDepthtex1View(final CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(IrisMetalDepthAllocationRuntime.ensureDepthtex1View(this));
    }

    @Inject(method = "noHandDepthTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallum$resolveDepthtex2Texture(final CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(IrisMetalDepthAllocationRuntime.ensureDepthtex2(this));
    }

    @Inject(method = "noHandDepthView", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallum$resolveDepthtex2View(final CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(IrisMetalDepthAllocationRuntime.ensureDepthtex2View(this));
    }

    @Inject(method = "captureNoTranslucentsDepth", at = @At("HEAD"), require = 0)
    private void metallum$ensureDepthtex1Capture(
            final @Coerce Object encoder,
            final CallbackInfo ci
    ) {
        IrisMetalDepthAllocationRuntime.ensureCaptureDestination(this, 1);
    }

    @Inject(method = "captureNoHandDepth", at = @At("HEAD"), require = 0)
    private void metallum$ensureDepthtex2Capture(
            final @Coerce Object encoder,
            final CallbackInfo ci
    ) {
        IrisMetalDepthAllocationRuntime.ensureCaptureDestination(this, 2);
    }
}
