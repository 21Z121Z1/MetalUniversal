package com.metallum.client.metal.render.mtl;

import java.lang.foreign.MemorySegment;

/** Exposes packet flush boundaries to direct native draw and debug-group code. */
public interface MetalRenderStateFlushable {
    void metallum$flushPendingRenderState();

    /**
     * Adapter for redirects which already receive the target encoder handle.
     * The implementation owns the canonical handle, so the argument is only a
     * call-site compatibility aid and is intentionally ignored.
     */
    default void metallum$flushRenderState(final MemorySegment ignoredEncoder) {
        metallum$flushPendingRenderState();
    }
}
