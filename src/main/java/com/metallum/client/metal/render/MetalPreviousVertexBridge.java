package com.metallum.client.metal.render;

/** Narrow public bridge for render mixins that observe source-frame camera state. */
public final class MetalPreviousVertexBridge {
    private MetalPreviousVertexBridge() {
    }

    public static void observeCamera(final double x, final double y, final double z) {
        MetalPreviousVertexHistory.observeCamera(x, y, z);
    }
}
