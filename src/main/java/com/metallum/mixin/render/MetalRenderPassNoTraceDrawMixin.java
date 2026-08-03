package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;

/**
 * Allocation-free production draw lane for {@code MetalRenderPass}.
 *
 * <p>The target records detailed render-contract metadata by constructing a
 * parameter map and decimal strings after every draw. That is required in the
 * validation lane, but {@code contractPassToken < 0} means the recorder will
 * immediately discard those objects. This mixin executes the same Metal work
 * and cancels the original method only in that disabled state.</p>
 *
 * <p>The legacy methods remain untouched and are selected automatically when
 * render-contract capture is active or when the master switch is disabled.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassNoTraceDrawMixin {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.noTraceDrawFastPath", "true")
    );
    private static final boolean PASS_TIMING_ENABLED =
            Boolean.getBoolean("metallum.validation.gpuPassTiming");

    @Shadow
    @Final
    private long contractPassToken;

    @Shadow
    @Nullable
    private GpuBuffer indexBuffer;

    @Shadow
    private MTLIndexType indexType;

    @Invoker("renderEncoder")
    protected abstract MTLRenderCommandEncoder metallum$invokeRenderEncoder();

    @Invoker("bindDrawState")
    protected abstract void metallum$invokeBindDrawState(MTLRenderCommandEncoder encoder);

    @Invoker("primitiveTopology")
    protected abstract MTLPrimitiveType metallum$invokePrimitiveTopology();

    @Invoker("drawTriangleFan")
    protected abstract void metallum$invokeDrawTriangleFan(
            MTLRenderCommandEncoder encoder,
            int firstVertex,
            int vertexCount,
            int instanceCount,
            int baseInstance
    );

    @Invoker("drawIndexedNative")
    protected abstract void metallum$invokeDrawIndexedNative(
            MTLRenderCommandEncoder encoder,
            MetalGpuBuffer nativeIndexBuffer,
            int firstIndex,
            int indexCount,
            int baseVertex,
            int instanceCount,
            MTLIndexType indexType,
            int baseInstance
    );

    @Inject(method = "drawIndexed(IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metallum$drawIndexedWithoutTraceObjects(
            final int indexCount,
            final int instanceCount,
            final int firstIndex,
            final int vertexOffset,
            final int firstInstance,
            final CallbackInfo ci
    ) {
        if (!metallum$useFastPath()) {
            return;
        }
        if (this.indexBuffer == null) {
            Metallum.LOGGER.warn("[metallum] drawIndexed called with null index buffer, skipping draw");
            ci.cancel();
            return;
        }
        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);
        this.metallum$invokeDrawIndexedNative(
                encoder,
                (MetalGpuBuffer) this.indexBuffer,
                firstIndex,
                indexCount,
                vertexOffset,
                instanceCount,
                this.indexType,
                firstInstance
        );
        ci.cancel();
    }

    @Inject(method = "draw(IIII)V", at = @At("HEAD"), cancellable = true)
    private void metallum$drawWithoutTraceObjects(
            final int vertexCount,
            final int instanceCount,
            final int firstVertex,
            final int firstInstance,
            final CallbackInfo ci
    ) {
        if (!metallum$useFastPath()) {
            return;
        }
        MTLPrimitiveType primitiveType = this.metallum$invokePrimitiveTopology();
        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            this.metallum$invokeDrawTriangleFan(
                    encoder,
                    firstVertex,
                    vertexCount,
                    instanceCount,
                    firstInstance
            );
        } else {
            encoder.drawPrimitives(
                    primitiveType,
                    firstVertex,
                    vertexCount,
                    Math.max(1, instanceCount),
                    firstInstance
            );
        }
        ci.cancel();
    }

    @Inject(
            method = "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$multiDrawIndexedWithoutTraceObjects(
            final PointerBuffer firstIndexOffsets,
            final IntBuffer indexCounts,
            final IntBuffer vertexOffsets,
            final int drawCount,
            final CallbackInfo ci
    ) {
        if (!metallum$useFastPath()) {
            return;
        }
        MTLPrimitiveType primitiveType = this.metallum$invokePrimitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan multiDrawIndexed");
        }
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) this.indexBuffer;
        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                encoder.handle(),
                primitiveType.value,
                this.indexType.value,
                metallum$nativeHandle(nativeIndexBuffer),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)),
                drawCount,
                1L,
                0L
        );
        ci.cancel();
    }

    @Inject(
            method = "drawIndexedIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$drawIndexedIndirectWithoutTraceObjects(
            final GpuBufferSlice commands,
            final int drawCount,
            final CallbackInfo ci
    ) {
        if (!metallum$useFastPath()) {
            return;
        }
        if (drawCount <= 0) {
            ci.cancel();
            return;
        }
        MTLPrimitiveType primitiveType = this.metallum$invokePrimitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }
        if (this.indexBuffer == null) {
            Metallum.LOGGER.warn("[metallum] drawIndexedIndirect called with null index buffer, skipping draw");
            ci.cancel();
            return;
        }
        if (commands.buffer().isClosed()) {
            Metallum.LOGGER.warn("[metallum] drawIndexedIndirect called with closed indirect command buffer, skipping draw");
            ci.cancel();
            return;
        }
        long requiredBytes = (long) drawCount * VkDrawIndexedIndirectCommand.SIZEOF;
        if (commands.length() < requiredBytes) {
            Metallum.LOGGER.warn(
                    "[metallum] drawIndexedIndirect command buffer too small: need {} bytes, have {} (drawCount={})",
                    requiredBytes,
                    commands.length(),
                    drawCount
            );
            ci.cancel();
            return;
        }
        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);
        encoder.drawIndexedPrimitivesIndirect(
                primitiveType,
                this.indexType,
                metallum$nativeHandle((MetalGpuBuffer) this.indexBuffer),
                metallum$nativeHandle((MetalGpuBuffer) commands.buffer()),
                commands.offset(),
                drawCount,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
        ci.cancel();
    }

    @Inject(
            method = "drawIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$drawIndirectWithoutTraceObjects(
            final GpuBufferSlice commands,
            final int drawCount,
            final CallbackInfo ci
    ) {
        if (!metallum$useFastPath()) {
            return;
        }
        MTLPrimitiveType primitiveType = this.metallum$invokePrimitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }
        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);
        encoder.drawPrimitivesIndirect(
                primitiveType,
                metallum$nativeHandle((MetalGpuBuffer) commands.buffer()),
                commands.offset(),
                drawCount,
                VkDrawIndirectCommand.SIZEOF
        );
        ci.cancel();
    }

    /** Avoid the two clock reads per pass when pass timing is disabled. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"))
    private long metallum$startPassClockOnlyWhenEnabled() {
        return PASS_TIMING_ENABLED ? System.nanoTime() : 0L;
    }

    @Redirect(method = "finishTiming", at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"))
    private long metallum$finishPassClockOnlyWhenEnabled() {
        return PASS_TIMING_ENABLED ? System.nanoTime() : 0L;
    }

    private static MemorySegment metallum$nativeHandle(final MetalGpuBuffer buffer) {
        return ((MetalGpuBufferNativeHandleAccessor) buffer).metallum$invokeNativeHandle();
    }

    private boolean metallum$useFastPath() {
        return ENABLED && this.contractPassToken < 0L;
    }
}
