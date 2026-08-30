package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalArgumentBindingRuntime;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the real render binding lifecycle in generation/in-flight snapshots. */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class IrisMetalArgumentSnapshotMixin {
    @Inject(method = "setPipeline", at = @At("RETURN"), require = 0)
    private void metallum$attachArgumentLayout(
            final RenderPipeline pipeline,
            final CallbackInfo ci
    ) {
        IrisMetalArgumentBindingRuntime.attachPipeline(this);
    }

    @Inject(
            method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("RETURN"),
            require = 0
    )
    private void metallum$trackUniform(
            final String name,
            final GpuBufferSlice slice,
            final CallbackInfo ci
    ) {
        IrisMetalArgumentBindingRuntime.bindBuffer(this, name, slice);
    }

    @Inject(method = "bindStorageBuffer", at = @At("RETURN"), require = 0)
    private void metallum$trackStorageBuffer(
            final int binding,
            final GpuBufferSlice slice,
            final CallbackInfo ci
    ) {
        IrisMetalArgumentBindingRuntime.bindStorageBuffer(this, binding, slice);
    }

    @Inject(method = "bindTexture", at = @At("RETURN"), require = 0)
    private void metallum$trackTextureAndSampler(
            final String name,
            final GpuTextureView view,
            final GpuSampler sampler,
            final CallbackInfo ci
    ) {
        IrisMetalArgumentBindingRuntime.bindTexture(this, name, view, sampler);
    }

    @Inject(method = "bindStorageImage", at = @At("RETURN"), require = 0)
    private void metallum$trackStorageImage(
            final String name,
            final GpuTextureView view,
            final CallbackInfo ci
    ) {
        IrisMetalArgumentBindingRuntime.bindStorageImage(this, name, view);
    }

    @Inject(method = "bindDrawState", at = @At("RETURN"), require = 0)
    private void metallum$markArgumentSnapshotEncoded(
            final MTLRenderCommandEncoder encoder,
            final CallbackInfo ci
    ) {
        IrisMetalArgumentBindingRuntime.markEncoded(this);
    }
}
