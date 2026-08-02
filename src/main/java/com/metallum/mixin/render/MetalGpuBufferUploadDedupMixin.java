package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/** Adds content comparison and native-backing version state to Metal buffers. */
@Mixin(targets = "com.metallum.client.metal.render.MetalGpuBuffer")
public abstract class MetalGpuBufferUploadDedupMixin implements MetalUploadDedupBuffer {
    @Shadow
    abstract boolean isDynamic();

    @Shadow
    abstract ByteBuffer currentStorage();

    @Unique
    private boolean metallum$uploadInitialized;
    @Unique
    private long metallum$lastUploadOffset = -1L;
    @Unique
    private int metallum$lastUploadLength = -1;
    @Unique
    private long metallum$bindingVersion;

    @Inject(method = "swapBacking", at = @At("TAIL"))
    private void metallum$observeBackingSwap(
            final MemorySegment handle,
            final ByteBuffer storage,
            final CallbackInfo ci
    ) {
        this.metallum$bindingVersion++;
    }

    @Override
    public boolean metallum$matchesUpload(final long offset, final ByteBuffer data) {
        if (!this.isDynamic() || !this.metallum$uploadInitialized) {
            return false;
        }
        int length = data.remaining();
        if (offset != this.metallum$lastUploadOffset || length != this.metallum$lastUploadLength) {
            return false;
        }
        if (offset < 0L || offset > Integer.MAX_VALUE || offset + length > Integer.MAX_VALUE) {
            return false;
        }

        ByteBuffer existing = this.currentStorage();
        int start = Math.toIntExact(offset);
        int end = Math.addExact(start, length);
        if (end > existing.capacity()) {
            return false;
        }
        existing.position(start);
        existing.limit(end);
        return existing.slice().mismatch(data.duplicate()) < 0;
    }

    @Override
    public void metallum$recordUpload(final long offset, final ByteBuffer data) {
        if (!this.isDynamic()) {
            return;
        }
        this.metallum$uploadInitialized = true;
        this.metallum$lastUploadOffset = offset;
        this.metallum$lastUploadLength = data.remaining();
    }

    @Override
    public long metallum$bindingVersion() {
        return this.metallum$bindingVersion;
    }
}
