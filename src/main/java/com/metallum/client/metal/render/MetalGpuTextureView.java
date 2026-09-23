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
    private final GpuTexture texture;
    private final int baseMipLevel;
    private final int mipLevels;
    private final boolean alphaOneSwizzle;
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        this(texture, baseMipLevel, mipLevels, false);
    }

    MetalGpuTextureView(
            final GpuTexture texture,
            final int baseMipLevel,
            final int mipLevels,
            final boolean alphaOneSwizzle
    ) {
        this.texture = texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;
        this.alphaOneSwizzle = alphaOneSwizzle;
        ((MetalGpuTexture) texture).addView();
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
        if (mipLevel < 0 || mipLevel >= this.mipLevels) {
            throw new IllegalArgumentException("Mip level out of view range: " + mipLevel);
        }
        return this.texture.getWidth(this.baseMipLevel + mipLevel);
    }

    @Override
    public int getHeight(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mipLevels) {
            throw new IllegalArgumentException("Mip level out of view range: " + mipLevel);
        }
        return this.texture.getHeight(this.baseMipLevel + mipLevel);
    }

    /** Storage images must expose physical channels, never a sampling swizzle. */
    void validateStorageBinding() {
        if (this.closed) {
            throw new IllegalStateException("Storage image view is closed");
        }
        if (this.alphaOneSwizzle) {
            throw new IllegalArgumentException("A sampled alpha-one view cannot be bound as a storage image");
        }
    }

    MemorySegment nativeHandle() {
        if (this.closed) {
            throw new IllegalStateException("Texture view is closed");
        }

        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        if (!this.alphaOneSwizzle
                && this.baseMipLevel() == 0
                && this.mipLevels() >= texture.getMipLevels()) {
            return texture.nativeHandle();
        }
        if (this.nativeHandle == null) {
            MemorySegment viewHandle = this.alphaOneSwizzle
                    ? MetalNativeBridge.metallum_create_texture_view_alpha_one(
                            texture.nativeHandle(), this.baseMipLevel(), this.mipLevels()
                    )
                    : MetalNativeBridge.metallum_create_texture_view(
                            texture.nativeHandle(), this.baseMipLevel(), this.mipLevels()
                    );
            if (MetalNativeBridge.isNullHandle(viewHandle)) {
                throw new IllegalStateException(
                        "Failed to create Metal texture view for mip range "
                                + this.baseMipLevel() + "+" + this.mipLevels()
                                + (this.alphaOneSwizzle ? " with alpha=1 swizzle" : "")
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
            ((MetalGpuTexture) this.texture()).queueNativeRelease(handle);
        }
        this.closed = true;
        ((MetalGpuTexture) this.texture()).removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
