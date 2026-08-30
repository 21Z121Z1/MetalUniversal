package com.metallum.mixin.sodium;

/** Exposes the per-segment allocation generation installed by the Sodium mixin. */
public interface GlBufferSegmentGeneration {
    long metallum$generation();
}
