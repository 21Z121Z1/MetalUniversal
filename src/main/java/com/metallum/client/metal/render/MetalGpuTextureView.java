package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView implements GpuTextureView {
    private final MetalGpuTexture texture;
    private final int baseMipLevel;
    private final int mipLevels;
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        this.texture = (MetalGpuTexture) texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;
        this.texture.addView();
    }

    @Override
    public GpuTexture texture() {
        return this.texture;
    }

    @Override
    public int baseMipLevel() {
        return this.baseMipLevel;
    }

    @Override
    public int mipLevels() {
        return this.mipLevels;
    }

    @Override
    public int getWidth(final int mipLevel) {
        return this.texture.getWidth(this.baseMipLevel + mipLevel);
    }

    @Override
    public int getHeight(final int mipLevel) {
        return this.texture.getHeight(this.baseMipLevel + mipLevel);
    }

    MemorySegment nativeHandle() {
        if (this.closed) {
            throw new IllegalStateException("Texture view is closed");
        }

        MetalGpuTexture texture = this.texture;
        if (this.baseMipLevel == 0 && this.mipLevels >= texture.getMipLevels()) {
            return texture.nativeHandle();
        }
        if (this.nativeHandle == null) {
            MemorySegment viewHandle = MetalNativeBridge.metallum_create_texture_view(
                    texture.nativeHandle(),
                    this.baseMipLevel,
                    this.mipLevels
            );
            if (MetalNativeBridge.isNullHandle(viewHandle)) {
                throw new IllegalStateException(
                        "Failed to create Metal texture view for mip range " + this.baseMipLevel + "+" + this.mipLevels
                );
            }
            this.nativeHandle = viewHandle;
        }
        return this.nativeHandle;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        if (this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.texture.queueNativeRelease(handle);
        }
        this.closed = true;
        this.texture.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
