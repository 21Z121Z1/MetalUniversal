package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalCopyTrackedTexture;
import com.metallum.client.metal.render.MetalMipmapTrackedTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds conservative content, mipmap and full-copy version tracking to Metal textures. */
@Mixin(targets = "com.metallum.client.metal.render.MetalGpuTexture")
public abstract class MetalGpuTextureVersionMixin
        implements MetalMipmapTrackedTexture, MetalCopyTrackedTexture {
    @Unique
    private long metallum$contentVersion = 1L;
    @Unique
    private long metallum$mipmapVersion;
    @Unique
    private Object metallum$copySource;
    @Unique
    private long metallum$copySourceVersion;
    @Unique
    private int metallum$copyMipLevel = -1;
    @Unique
    private int metallum$copyWidth;
    @Unique
    private int metallum$copyHeight;

    @Inject(method = "markContentsDirty", at = @At("HEAD"))
    private void metallum$observeContentWrite(final CallbackInfo ci) {
        this.metallum$markContentsChanged();
    }

    @Override
    public void metallum$markContentsChanged() {
        this.metallum$contentVersion++;
        this.metallum$copySource = null;
        this.metallum$copySourceVersion = 0L;
        this.metallum$copyMipLevel = -1;
        this.metallum$copyWidth = 0;
        this.metallum$copyHeight = 0;
        if (this.metallum$contentVersion == 0L) {
            this.metallum$contentVersion = 1L;
            this.metallum$mipmapVersion = 0L;
        }
    }

    @Override
    public long metallum$contentVersion() {
        return this.metallum$contentVersion;
    }

    @Override
    public boolean metallum$mipmapsCurrent() {
        return this.metallum$mipmapVersion == this.metallum$contentVersion;
    }

    @Override
    public void metallum$markMipmapsGenerated() {
        this.metallum$mipmapVersion = this.metallum$contentVersion;
    }

    @Override
    public boolean metallum$matchesFullCopy(
            final Object source,
            final long sourceContentVersion,
            final int mipLevel,
            final int width,
            final int height
    ) {
        return this.metallum$copySource == source
                && this.metallum$copySourceVersion == sourceContentVersion
                && this.metallum$copyMipLevel == mipLevel
                && this.metallum$copyWidth == width
                && this.metallum$copyHeight == height;
    }

    @Override
    public void metallum$recordFullCopy(
            final Object source,
            final long sourceContentVersion,
            final int mipLevel,
            final int width,
            final int height
    ) {
        this.metallum$copySource = source;
        this.metallum$copySourceVersion = sourceContentVersion;
        this.metallum$copyMipLevel = mipLevel;
        this.metallum$copyWidth = width;
        this.metallum$copyHeight = height;
    }
}
