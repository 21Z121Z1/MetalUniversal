package com.metallum.client.metal.render;

/** Immutable token layout compiled alongside one Iris raster shader program. */
public interface MetalIrisBindingTokenLayout {
    int metallum$uniformBindingCount();

    MetalBindingToken metallum$uniformBindingToken(int index);

    String metallum$uniformBindingName(int index);

    int metallum$samplerBindingCount();

    MetalBindingToken metallum$samplerBindingToken(int index);

    String metallum$samplerBindingName(int index);
}
