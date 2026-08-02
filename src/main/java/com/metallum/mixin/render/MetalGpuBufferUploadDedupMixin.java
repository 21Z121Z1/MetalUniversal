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

/** Adds content-diff and native-backing version state to Metal buffers. */
@Mixin(targets = "com.metallum.client.metal.render.MetalGpuBuffer")
public abstract class MetalGpuBufferUploadDedupMixin implements MetalUploadDedupBuffer {
    @Shadow
    abstract boolean isDynamic();

    @Shadow
    abstract ByteBuffer currentStorage();

    @Unique
    private boolean metallum$uploadInitialized;
    @Unique
    private long metallum$bindingVersion;

    @Inject(method = "swapBacking", at = @At("TAIL"))
    private void metallum$observeBackingSwap(
            final MemorySegment handle,
            final ByteBuffer storage,
            final CallbackInfo ci
    ) {
        this.metallum$uploadInitialized = true;
        this.metallum$bindingVersion++;
    }

    @Override
    public UploadRange metallum$diffUpload(final long offset, final ByteBuffer data) {
        int length = data.remaining();
        if (!this.isDynamic() || !this.metallum$uploadInitialized || length == 0) {
            return length == 0 ? UploadRange.empty() : UploadRange.full(length);
        }
        if (offset < 0L || offset > Integer.MAX_VALUE || offset + length > Integer.MAX_VALUE) {
            return UploadRange.full(length);
        }

        ByteBuffer storage = this.currentStorage();
        int destinationStart = Math.toIntExact(offset);
        int destinationEnd = Math.addExact(destinationStart, length);
        if (destinationEnd > storage.capacity()) {
            return UploadRange.full(length);
        }

        storage.position(destinationStart);
        storage.limit(destinationEnd);
        ByteBuffer existing = storage.slice();
        ByteBuffer source = data.duplicate();
        int first = existing.mismatch(source);
        if (first < 0) {
            return UploadRange.empty();
        }

        int sourcePosition = source.position();
        int last = length - 1;
        while (last > first && existing.get(last) == source.get(sourcePosition + last)) {
            last--;
        }
        return new UploadRange(first, last + 1);
    }

    @Override
    public long metallum$bindingVersion() {
        return this.metallum$bindingVersion;
    }
}
