package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.metallum.client.validation.MetalValidationClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMetalFxMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void metallum$beginFrameBeforeExtraction(final boolean renderLevel, final CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        MetalFxManager.beginFrame();
        MetalValidationClient.beforeFrame(minecraft.gameRenderer);
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void metallum$endValidationFrame(final boolean renderLevel, final CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        MetalValidationClient.afterFrame(minecraft.gameRenderer);
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I", opcode = org.objectweb.asm.Opcodes.GETFIELD)
    )
    private int metallum$reportedWidth(final RenderTarget target) {
        return MetalFxManager.reportedWidth(target.width);
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I", opcode = org.objectweb.asm.Opcodes.GETFIELD)
    )
    private int metallum$reportedHeight(final RenderTarget target) {
        return MetalFxManager.reportedHeight(target.height);
    }

    @Redirect(
            method = "renderFrame",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
    )
    private RenderTarget metallum$presentNativeResolution(final GameRenderer renderer) {
        return MetalFxManager.presentTarget(renderer);
    }
}
