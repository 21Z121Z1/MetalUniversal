package com.metallum.client.metal.render;

/** Internal content-version contract used to avoid redundant mipmap generation. */
public interface MetalMipmapTrackedTexture {
    void metallum$markContentsChanged();

    boolean metallum$mipmapsCurrent();

    void metallum$markMipmapsGenerated();
}
