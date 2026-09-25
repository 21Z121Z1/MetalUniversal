package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMetalFxMixin {
    @Redirect(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
    )
    private RenderTarget metallum$drawToNativeResolution(final GameRenderer renderer) {
        return MetalFxManager.guiTarget(renderer);
    }

    @Redirect(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"
            )
    )
    private void metallum$clearDepthOnlyWhenUiTargetHasDepth(
            final CommandEncoder encoder,
            final GpuTexture depthTexture,
            final double clearDepth
    ) {
        // MetalFX GUI composition deliberately uses a color-only target. The
        // 26.3 vanilla GUI path clears depth after the blur split without
        // checking hasDepth(), so forwarding its null depth texture would
        // fail in RenderPearl before the pass can be submitted.
        if (depthTexture != null) {
            encoder.clearDepthTexture(depthTexture, clearDepth);
        }
    }
}
