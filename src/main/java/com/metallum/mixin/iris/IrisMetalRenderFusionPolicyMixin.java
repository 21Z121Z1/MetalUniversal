package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalRenderFusionRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publishes each real Iris raster pass to the physical-resource fusion policy. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalRenderFusionPolicyMixin {
    /**
     * A stage is the ownership boundary for the cross-pass fusion chain. Keep
     * that boundary exception-safe so a failed post stage cannot leave a
     * previous-pass signature installed for unrelated rendering.
     */
    @WrapMethod(method = "executeStage", require = 1)
    @Coerce
    private Object metallum$executeStageWithCleanup(
            @Coerce final Object stage,
            @Coerce final Object device,
            @Coerce final Object targets,
            @Coerce final Object resources,
            final Operation<Object> original
    ) {
        IrisMetalRenderFusionRuntime.breakChain();
        try {
            return original.call(stage, device, targets, resources);
        } finally {
            IrisMetalRenderFusionRuntime.breakChain();
        }
    }

    /**
     * Only a fully executed raster pass may become the previous pass used for
     * encoder-fusion admission. On failure, discard both the pending and prior
     * signatures instead of allowing stale state to survive the aborted pass.
     */
    @WrapMethod(method = "executePass", require = 1)
    private void metallum$executeRasterPassWithCleanup(
            @Coerce final Object device,
            @Coerce final Object targets,
            @Coerce final Object resources,
            @Coerce final Object plannedPass,
            final Operation<Void> original
    ) {
        boolean completed = false;
        IrisMetalRenderFusionRuntime.beginPass(plannedPass);
        try {
            original.call(device, targets, resources, plannedPass);
            completed = true;
        } finally {
            if (completed) {
                IrisMetalRenderFusionRuntime.endPass();
            } else {
                IrisMetalRenderFusionRuntime.breakChain();
            }
        }
    }

    @Inject(method = "executeComputeGroup", at = @At("HEAD"), require = 0)
    private void metallum$breakBeforeCompute(final CallbackInfo ci) {
        IrisMetalRenderFusionRuntime.breakChain();
    }

    @Inject(method = "executeFinal", at = @At("HEAD"), require = 0)
    private void metallum$breakBeforeFinal(final CallbackInfoReturnable<Object> cir) {
        IrisMetalRenderFusionRuntime.breakChain();
    }

    @Inject(method = "executeColorSpace", at = @At("HEAD"), require = 0)
    private void metallum$breakBeforeColorSpace(final CallbackInfoReturnable<Boolean> cir) {
        IrisMetalRenderFusionRuntime.breakChain();
    }
}
