package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMetalFxMixin {
    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "com/mojang/blaze3d/pipeline/MainTarget")
    )
    private MainTarget metallum$createSceneTarget(final int width, final int height) {
        return MetalFxManager.createSceneTarget(width, height);
    }

    @Redirect(
            method = "resize",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;resize(II)V")
    )
    private void metallum$resizeSceneTarget(final RenderTarget target, final int width, final int height) {
        MetalFxManager.resizeSceneTarget(target, width, height);
    }

    @Redirect(
            method = "render",
            at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I", opcode = org.objectweb.asm.Opcodes.GETFIELD)
    )
    private int metallum$reportedWidth(final RenderTarget target) {
        return MetalFxManager.reportedWidth(target.width);
    }

    @Redirect(
            method = "render",
            at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I", opcode = org.objectweb.asm.Opcodes.GETFIELD)
    )
    private int metallum$reportedHeight(final RenderTarget target) {
        return MetalFxManager.reportedHeight(target.height);
    }

    @ModifyArg(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
            ),
            index = 0
    )
    private Matrix4f metallum$prepareSceneProjection(final Matrix4f projectionMatrix) {
        GameRenderer renderer = (GameRenderer) (Object) this;
        var state = renderer.gameRenderState();
        return MetalFxManager.prepareSceneProjection(
                state.levelRenderState.cameraRenderState,
                projectionMatrix,
                state.windowRenderState.width,
                state.windowRenderState.height
        );
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$preserveWorldDepthBeforeHand(
            final net.minecraft.client.DeltaTracker deltaTracker,
            final CallbackInfo ci
    ) {
        MetalFxManager.preserveWorldDepthBeforeHand((GameRenderer) (Object) this);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$upscaleBeforeGui(final net.minecraft.client.DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
        MetalFxManager.beforeGui((GameRenderer) (Object) this);
    }

    @Redirect(
            method = "processBlurEffect",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;", opcode = org.objectweb.asm.Opcodes.GETFIELD)
    )
    private RenderTarget metallum$blurUiTarget(final GameRenderer renderer) {
        return MetalFxManager.blurTarget(renderer.mainRenderTarget());
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void metallum$resetOnWorldChange(final net.minecraft.client.multiplayer.ClientLevel level, final CallbackInfo ci) {
        MetalFxManager.resetHistory("world change");
    }

    @Inject(method = "resetData", at = @At("TAIL"))
    private void metallum$resetOnRendererReset(final CallbackInfo ci) {
        MetalFxManager.resetHistory("renderer reset");
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void metallum$close(final CallbackInfo ci) {
        MetalFxManager.close();
    }
}
