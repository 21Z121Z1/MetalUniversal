package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/** Tracks pipeline, texture bindings, draws, and close for each OpenGL RenderPass. */
@Mixin(value = RenderPass.class, remap = false)
public abstract class IrisOpenGlRenderPassTraceMixin {
    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void metallum$recordPipeline(final RenderPipeline pipeline, final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.pipeline((RenderPass) (Object) this, pipeline);
    }

    @Inject(method = "bindTexture", at = @At("RETURN"))
    private void metallum$recordTexture(
            final String name,
            final GpuTextureView view,
            final GpuSampler sampler,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.bindTexture((RenderPass) (Object) this, name, view);
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"))
    private void metallum$recordIndexedDraw(
            final int indexCount,
            final int instanceCount,
            final int firstIndex,
            final int baseVertex,
            final int firstInstance,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.renderPassDraw((RenderPass) (Object) this);
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void metallum$recordDraw(
            final int vertexCount,
            final int instanceCount,
            final int firstVertex,
            final int firstInstance,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.renderPassDraw((RenderPass) (Object) this);
    }

    @Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
    private void metallum$recordMultiIndexedDraw(
            final IntBuffer counts,
            final int countsOffset,
            final int instanceCount,
            final int firstInstance,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.renderPassDraw((RenderPass) (Object) this);
    }

    @Inject(method = "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
    private void metallum$recordPointerMultiIndexedDraw(
            final PointerBuffer indexCounts,
            final IntBuffer baseVertices,
            final IntBuffer firstInstances,
            final int drawCount,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.renderPassDraw((RenderPass) (Object) this);
    }

    @Inject(method = "drawIndexedIndirect", at = @At("HEAD"))
    private void metallum$recordIndexedIndirectDraw(
            final com.mojang.blaze3d.buffers.GpuBufferSlice buffer,
            final int offset,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.renderPassDraw((RenderPass) (Object) this);
    }

    @Inject(method = "drawIndirect", at = @At("HEAD"))
    private void metallum$recordIndirectDraw(
            final com.mojang.blaze3d.buffers.GpuBufferSlice buffer,
            final int offset,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.renderPassDraw((RenderPass) (Object) this);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum$close(final CallbackInfo callbackInfo) {
        IrisOpenGlPassTrace.closedRenderPass((RenderPass) (Object) this);
    }
}
