package com.metallum.client.metal.render.mtl;

/** Exposes the packet flush boundary to direct native draw batching code. */
public interface MetalRenderStateFlushable {
    void metallum$flushPendingRenderState();
}
