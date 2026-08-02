package com.metallum.client.metal.render;

/** Internal content-version contract used by exact texture fast paths. */
public interface MetalMipmapTrackedTexture {
    void metallum$markContentsChanged();

    long metallum$contentVersion();

    boolean metallum$mipmapsCurrent();

    void metallum$markMipmapsGenerated();
}
