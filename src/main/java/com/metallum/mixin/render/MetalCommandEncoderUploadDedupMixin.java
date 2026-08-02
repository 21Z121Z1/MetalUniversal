package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.nio.ByteBuffer;

/** Suppresses identical uploads and trims changed uploads to their dirty byte range. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalCommandEncoderUploadDedupMixin {
    @Inject(method = "writeToBuffer", at = @At("HEAD"), cancellable = true)
    private void metallum$skipIdenticalUpload(
            final GpuBufferSlice destination,
            final ByteBuffer data,
            final CallbackInfo ci
    ) {
        if (!(destination.buffer() instanceof MetalUploadDedupBuffer dedup)) {
            return;
        }
        MetalUploadDedupBuffer.UploadRange range =
                dedup.metallum$diffUpload(destination.offset(), data);
        if (range.isEmpty()) {
            IrisMetalPerformanceCounters.recordUniformUploadSkipped(data.remaining());
            ci.cancel();
        }
    }

    @ModifyArgs(
            method = "writeToBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/metallum/client/metal/render/MetalCommandEncoder;orphanWrite(Lcom/metallum/client/metal/render/MetalGpuBuffer;JLjava/nio/ByteBuffer;)V"
            )
    )
    private void metallum$trimDynamicUpload(final Args args) {
        Object buffer = args.get(0);
        long offset = args.get(1);
        ByteBuffer data = args.get(2);
        if (!(buffer instanceof MetalUploadDedupBuffer dedup)) {
            return;
        }

        MetalUploadDedupBuffer.UploadRange range = dedup.metallum$diffUpload(offset, data);
        int originalLength = data.remaining();
        if (range.isEmpty() || range.isFull(originalLength)) {
            return;
        }

        ByteBuffer changed = data.duplicate();
        int originalPosition = changed.position();
        changed.position(originalPosition + range.start());
        changed.limit(originalPosition + range.end());
        changed = changed.slice().order(data.order());

        args.set(1, Math.addExact(offset, range.start()));
        args.set(2, changed);
        IrisMetalPerformanceCounters.recordUniformUploadTrimmed(originalLength, range.length());
    }
}
