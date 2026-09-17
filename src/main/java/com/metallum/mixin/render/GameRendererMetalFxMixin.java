package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.MetalPreviousVertexBridge;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
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
    @ModifyArg(
            method = "preloadUiShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/PipelineCache;<init>(Lcom/mojang/renderpearl/api/device/GpuDevice;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)V"
            ),
            index = 1
    )
    private static ShaderSource metallum$captureRenderPearlShaderSource(final ShaderSource shaderSource) {
        return MetalBackend.captureShaderSource(shaderSource);
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "com/mojang/blaze3d/pipeline/MainTarget")
    )
    private MainTarget metallum$createSceneTarget(final int width, final int height) {
        return new MainTarget(MetalFxManager.sceneWidth(width), MetalFxManager.sceneHeight(height));
    }

    @Redirect(
            method = "resize",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;resize(II)V")
    )
    private void metallum$resizeSceneTarget(final RenderTarget target, final int width, final int height) {
        target.resize(MetalFxManager.sceneWidth(width), MetalFxManager.sceneHeight(height));
        MetalFxManager.resetHistory("resize");
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
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
            ),
            index = 0
    )
    private Matrix4f metallum$prepareSceneProjection(final Matrix4f projectionMatrix) {
        GameRenderer renderer = (GameRenderer) (Object) this;
        var state = renderer.gameRenderState();
        var cameraState = state.levelRenderState.cameraRenderState;
        if (cameraState.initialized) {
            MetalPreviousVertexBridge.observeCamera(
                    cameraState.pos.x,
                    cameraState.pos.y,
                    cameraState.pos.z
            );
        }
        return MetalFxManager.prepareSceneProjection(
                cameraState,
                projectionMatrix,
                state.windowRenderState.width,
                state.windowRenderState.height
        );
    }

    @Inject(
            method = "render3dHud",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$preserveWorldDepthBeforeHand(
            final net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
            final net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
            final net.minecraft.client.renderer.state.OptionsRenderState optionsState,
            final boolean consistentDepthRequired,
            final CallbackInfo ci
    ) {
        MetalFxManager.preserveWorldDepthBeforeHand((GameRenderer) (Object) this);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$upscaleBeforeGui(final CallbackInfo ci) {
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
