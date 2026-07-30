package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Converts the Metal zero-to-one projection into Iris's OpenGL pack space. */
final class MetalIrisDepthConvention {
    private MetalIrisDepthConvention() {
    }

    static Matrix4f packProjection(final Matrix4fc forwardZeroToOne) {
        Matrix4f result = new Matrix4f(forwardZeroToOne);
        result.m02(2.0F * forwardZeroToOne.m02() - forwardZeroToOne.m03());
        result.m12(2.0F * forwardZeroToOne.m12() - forwardZeroToOne.m13());
        result.m22(2.0F * forwardZeroToOne.m22() - forwardZeroToOne.m23());
        result.m32(2.0F * forwardZeroToOne.m32() - forwardZeroToOne.m33());
        return result;
    }

    static Matrix4f packProjectionInverse(final Matrix4fc forwardZeroToOneInverse) {
        Matrix4f forward = new Matrix4f(forwardZeroToOneInverse).invert();
        return packProjection(forward).invert();
    }
}
