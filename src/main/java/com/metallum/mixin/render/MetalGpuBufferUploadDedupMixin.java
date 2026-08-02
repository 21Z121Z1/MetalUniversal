package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;

/** Adds content comparison state to Metal dynamic/uniform buffers. */
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
}
