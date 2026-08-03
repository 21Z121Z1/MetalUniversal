package com.metallum.mixin.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MetalRenderStateFlushable;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves command-buffer debug-group ordering when draw commands are delayed
 * inside the experimental render command packet.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassCommandPacketBoundaryMixin {
    @Shadow
    @Nullable
    private MTLRenderCommandEncoder nativeEncoder;

    @Inject(method = {"pushDebugGroup", "popDebugGroup"}, at = @At("HEAD"))
    private void metallum$flushBeforeDebugBoundary(final CallbackInfo ci) {
        MTLRenderCommandEncoder encoder = this.nativeEncoder;
        if (encoder == null || MetalNativeBridge.isNullHandle(encoder.handle())) {
            return;
        }
        ((MetalRenderStateFlushable) encoder).metallum$flushRenderState(encoder.handle());
    }
}
