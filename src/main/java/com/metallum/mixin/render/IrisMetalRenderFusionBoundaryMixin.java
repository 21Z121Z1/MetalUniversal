package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalRenderFusionRuntime;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ends an existing encoder when the current Iris pass was not fusion-approved. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class IrisMetalRenderFusionBoundaryMixin {
    @Invoker("endEncoder")
    protected abstract void metallum$endEncoder();

    @Inject(method = "renderCommandEncoder", at = @At("HEAD"), require = 0)
    private void metallum$applyFusionBoundary(
            final CallbackInfoReturnable<MTLRenderCommandEncoder> cir
    ) {
        if (IrisMetalRenderFusionRuntime.forceBoundary()) {
            this.metallum$endEncoder();
        }
    }
}
