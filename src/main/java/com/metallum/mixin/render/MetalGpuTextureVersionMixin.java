package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalMipmapTrackedTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds conservative content-version tracking to Metal textures. */
@Mixin(targets = "com.metallum.client.metal.render.MetalGpuTexture")
public abstract class MetalGpuTextureVersionMixin implements MetalMipmapTrackedTexture {
    @Unique
    private long metallum$contentVersion = 1L;
    @Unique
    private long metallum$mipmapVersion;

    @Inject(method = "markContentsDirty", at = @At("HEAD"))
    private void metallum$observeContentWrite(final CallbackInfo ci) {
        this.metallum$markContentsChanged();
    }

    @Override
    public void metallum$markContentsChanged() {
        this.metallum$contentVersion++;
        if (this.metallum$contentVersion == 0L) {
            this.metallum$contentVersion = 1L;
            this.metallum$mipmapVersion = 0L;
        }
    }

    @Override
    public boolean metallum$mipmapsCurrent() {
        return this.metallum$mipmapVersion == this.metallum$contentVersion;
    }

    @Override
    public void metallum$markMipmapsGenerated() {
        this.metallum$mipmapVersion = this.metallum$contentVersion;
    }
}
