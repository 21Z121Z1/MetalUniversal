package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalArgumentBindingRuntime;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances all generation-owned argument snapshot rings after a real submit. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class IrisMetalArgumentSnapshotSubmitMixin {
    @Shadow @Nullable
    private MTLCommandBuffer commandBuffer;

    @Unique
    private boolean metallum$hadArgumentSubmit;

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void metallum$captureArgumentSubmit(final CallbackInfo ci) {
        this.metallum$hadArgumentSubmit = this.commandBuffer != null;
    }

    @Inject(method = "submit", at = @At("RETURN"), require = 0)
    private void metallum$advanceArgumentSnapshots(final CallbackInfo ci) {
        if (this.metallum$hadArgumentSubmit) {
            IrisMetalArgumentBindingRuntime.advanceAfterSubmit();
            this.metallum$hadArgumentSubmit = false;
        }
    }
}
