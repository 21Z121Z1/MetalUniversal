package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalArgumentBindingRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances all generation-owned argument snapshot rings after a real submit. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class IrisMetalArgumentSnapshotSubmitMixin {
    @Inject(method = "submit", at = @At("RETURN"), require = 0)
    private void metallum$advanceArgumentSnapshots(final CallbackInfo ci) {
        IrisMetalArgumentBindingRuntime.advanceAfterSubmit();
    }
}
