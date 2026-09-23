package com.metallum.client.metal.render;

/** Physical Metal texture dimensionality for backend-owned resources. */
enum MetalTextureDimension {
    ONE_D(1),
    TWO_D(2),
    THREE_D(3);

    final long nativeValue;

    MetalTextureDimension(final long nativeValue) {
        this.nativeValue = nativeValue;
    }
}
