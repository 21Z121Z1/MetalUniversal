package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the backend-neutral OpenGL RenderPassDescriptor boundary. */
@Mixin(value = CommandEncoder.class, remap = false)
public abstract class IrisOpenGlCommandEncoderPassTraceMixin {
    @Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;", at = @At("RETURN"))
    private void metallum$recordRenderPass(
            final RenderPassDescriptor descriptor,
            final CallbackInfoReturnable<RenderPass> callbackInfo
    ) {
        IrisOpenGlPassTrace.createdRenderPass(callbackInfo.getReturnValue(), descriptor);
    }
}
