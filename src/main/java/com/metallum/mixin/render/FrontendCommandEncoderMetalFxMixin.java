package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Declare the auxiliary MRT in the public RenderPearl pass, not secretly in Swift. */
@Mixin(FrontendCommandEncoder.class)
public abstract class FrontendCommandEncoderMetalFxMixin {
    @ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At("HEAD"), argsOnly = true)
    private RenderPassDescriptor metallum$declareCutoutMrt(final RenderPassDescriptor descriptor) {
        return MetalFxManager.withCutoutReactiveAttachment(descriptor);
    }
}
