package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
    private final boolean alphaOneSwizzle;
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        this(texture, baseMipLevel, mipLevels, false);
    }

    /**
     * Creates a sampled view with an optional logical RGB alpha=1 swizzle.
     * Iris exposes RGB targets as three-component textures even when Metal's
     * renderable backing format is RGBA; the swizzle keeps the physical alpha
     * channel out of shader-visible sampled values.
     */
    MetalGpuTextureView(
            final GpuTexture texture,
            final int baseMipLevel,
            final int mipLevels,
            final boolean alphaOneSwizzle
    ) {
        super(texture, baseMipLevel, mipLevels);
        this.alphaOneSwizzle = alphaOneSwizzle;
        ((MetalGpuTexture) texture).addView();
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
