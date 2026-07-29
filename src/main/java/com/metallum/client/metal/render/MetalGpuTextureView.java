package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
    private final MetalGpuTexture metalTexture;
    private final MTLPixelFormat mtlPixelFormat;
    private final boolean reinterpretsPixelFormat;
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        this(requireMetalTexture(texture), baseMipLevel, mipLevels, null);
    }

    MetalGpuTextureView(
            final GpuTexture texture,
            final int baseMipLevel,
            final int mipLevels,
            final MTLPixelFormat mtlPixelFormat
    ) {
        this(
                requireMetalTexture(texture),
                baseMipLevel,
                mipLevels,
                Objects.requireNonNull(mtlPixelFormat, "mtlPixelFormat")
        );
    }

    private MetalGpuTextureView(
            final MetalGpuTexture texture,
            final int baseMipLevel,
            final int mipLevels,
            @Nullable final MTLPixelFormat requestedPixelFormat
    ) {
        super(validate(texture, baseMipLevel, mipLevels, requestedPixelFormat), baseMipLevel, mipLevels);
        this.metalTexture = texture;
        this.mtlPixelFormat = requestedPixelFormat == null ? texture.mtlPixelFormat() : requestedPixelFormat;
        this.reinterpretsPixelFormat = this.mtlPixelFormat != texture.mtlPixelFormat();
        texture.addView();
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MemorySegment nativeHandle() {
        if (this.closed) {
            throw new IllegalStateException("Texture view is closed");
        }

        if (!this.reinterpretsPixelFormat
                && this.baseMipLevel() == 0
                && this.mipLevels() == this.metalTexture.getMipLevels()) {
            return this.metalTexture.nativeHandle();
        }
        if (this.nativeHandle == null) {
            MemorySegment viewHandle = this.reinterpretsPixelFormat
                    ? MetalNativeBridge.metallum_create_texture_view_v2(
                            this.metalTexture.nativeHandle(),
                            this.mtlPixelFormat,
                            this.baseMipLevel(),
                            this.mipLevels()
                    )
                    : MetalNativeBridge.metallum_create_texture_view(
                            this.metalTexture.nativeHandle(),
                            this.baseMipLevel(),
                            this.mipLevels()
                    );
            if (MetalNativeBridge.isNullHandle(viewHandle)) {
                throw new IllegalStateException(
                        "Failed to create Metal texture view " + this.metalTexture.mtlPixelFormat()
                                + " -> " + this.mtlPixelFormat + " for mip range "
                                + this.baseMipLevel() + "+" + this.mipLevels()
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
            this.metalTexture.queueNativeRelease(handle);
        }
        this.closed = true;
        this.metalTexture.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    private static MetalGpuTexture requireMetalTexture(final GpuTexture texture) {
        if (!(texture instanceof MetalGpuTexture metalTexture)) {
            throw new IllegalArgumentException("Texture view requires a Metal texture");
        }
        return metalTexture;
    }

    private static MetalGpuTexture validate(
            final MetalGpuTexture texture,
            final int baseMipLevel,
            final int mipLevels,
            @Nullable final MTLPixelFormat requestedPixelFormat
    ) {
        if (texture.isClosed()) {
            throw new IllegalArgumentException("Can't create texture view with closed texture");
        }
        if (baseMipLevel < 0 || mipLevels <= 0
                || (long) baseMipLevel + mipLevels > texture.getMipLevels()) {
            throw new IllegalArgumentException(
                    "Invalid texture view mip range " + baseMipLevel + "+" + mipLevels
                            + " for " + texture.getMipLevels() + " mip levels"
            );
        }

        MTLPixelFormat basePixelFormat = texture.mtlPixelFormat();
        MTLPixelFormat viewPixelFormat = requestedPixelFormat == null ? basePixelFormat : requestedPixelFormat;
        if (!basePixelFormat.isViewCompatibleWith(viewPixelFormat)) {
            throw new IllegalArgumentException(
                    "Incompatible Metal texture view formats: " + basePixelFormat + " -> " + viewPixelFormat
            );
        }
        if (viewPixelFormat != basePixelFormat
                && (texture.usage() & MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW) == 0) {
            throw new IllegalArgumentException(
                    "Texture " + texture.getLabel() + " was not created with USAGE_PIXEL_FORMAT_VIEW"
            );
        }
        return texture;
    }
}
