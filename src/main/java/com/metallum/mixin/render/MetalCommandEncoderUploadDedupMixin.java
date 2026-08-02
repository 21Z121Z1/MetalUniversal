package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/** Suppresses uploads whose destination range already contains identical bytes. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalCommandEncoderUploadDedupMixin {
    @Inject(method = "writeToBuffer", at = @At("HEAD"), cancellable = true)
    private void metallum$skipIdenticalUpload(
            final GpuBufferSlice destination,
            final ByteBuffer data,
            final CallbackInfo ci
    ) {
        if (destination.buffer() instanceof MetalUploadDedupBuffer dedup
                && dedup.metallum$matchesUpload(destination.offset(), data)) {
            IrisMetalPerformanceCounters.recordUniformUploadSkipped(data.remaining());
            ci.cancel();
        }
    }

    @Inject(method = "writeToBuffer", at = @At("RETURN"))
    private void metallum$rememberUpload(
            final GpuBufferSlice destination,
            final ByteBuffer data,
            final CallbackInfo ci
    ) {
        if (destination.buffer() instanceof MetalUploadDedupBuffer dedup) {
            dedup.metallum$recordUpload(destination.offset(), data);
        }
    }
}
