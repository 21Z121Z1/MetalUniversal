package com.metallum.client.metal.render;

/** Tracks the exact source/version of the last full-surface texture copy. */
public interface MetalCopyTrackedTexture {
    boolean metallum$matchesFullCopy(
            Object source,
            long sourceContentVersion,
            int mipLevel,
            int width,
            int height
    );

    void metallum$recordFullCopy(
            Object source,
            long sourceContentVersion,
            int mipLevel,
            int width,
            int height
    );
}
