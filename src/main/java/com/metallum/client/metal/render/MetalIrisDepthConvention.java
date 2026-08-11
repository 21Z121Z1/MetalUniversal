package com.metallum.client.metal.render;

import com.mojang.blaze3d.platform.CompareOp;
import net.irisshaders.iris.Iris;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Adapts Mojang's reverse-Z Metal convention to the forward-depth contract
 * exposed by Iris to legacy OpenGL shader packs.
 *
 * <p>The conversion is enabled only while the Metal semantic pipeline owns an
 * active Iris pack. Shaders-off rendering keeps Mojang's native reverse-Z
 * compare, clear and bias values unchanged.</p>
 */
final class MetalIrisDepthConvention {
    private MetalIrisDepthConvention() {
    }

    static boolean enabledForMetalBackend() {
        return MetalActive.isMetalActive() && packInUseQuick();
    }

    private static boolean packInUseQuick() {
        try {
            // The test classpath intentionally shadows Iris with a smaller
            // facade, so keep this optional rather than linking test startup
            // to a production-only method.
            return (boolean) Iris.class.getMethod("isPackInUseQuick").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean shouldAdaptDepth(final boolean metalActive, final boolean packInUse) {
        return metalActive && packInUse;
    }

    static CompareOp hardwareCompare(final CompareOp mojangReverseCompare) {
        return adaptCompare(mojangReverseCompare, enabledForMetalBackend());
    }

    static CompareOp adaptCompare(final CompareOp compare, final boolean enabled) {
        if (!enabled) {
            return compare;
        }
        return switch (compare) {
            case ALWAYS_PASS -> CompareOp.ALWAYS_PASS;
            case LESS_THAN -> CompareOp.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            case EQUAL -> CompareOp.EQUAL;
            case NOT_EQUAL -> CompareOp.NOT_EQUAL;
            case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case GREATER_THAN -> CompareOp.LESS_THAN;
            case NEVER_PASS -> CompareOp.NEVER_PASS;
        };
    }

    static double hardwareClear(final double mojangReverseClear) {
        return adaptClear(mojangReverseClear, enabledForMetalBackend());
    }

    static double adaptClear(final double clear, final boolean enabled) {
        return enabled ? Math.clamp(1.0 - clear, 0.0, 1.0) : clear;
    }

    static float hardwareDepthBias(final float mojangReverseBias) {
        return enabledForMetalBackend() ? -mojangReverseBias : mojangReverseBias;
    }

    static Matrix4f packProjection(final Matrix4fc forwardZeroToOne) {
        if (!enabledForMetalBackend()) {
            return new Matrix4f(forwardZeroToOne);
        }
        return zeroToOneToOpenGl(forwardZeroToOne);
    }

    static Matrix4f packProjection(final Matrix4fc forwardZeroToOne, final boolean enabled) {
        if (!enabled) {
            return new Matrix4f(forwardZeroToOne);
        }
        return zeroToOneToOpenGl(forwardZeroToOne);
    }

    static Matrix4f packProjectionInverse(final Matrix4fc forwardZeroToOneInverse) {
        if (!enabledForMetalBackend()) {
            return new Matrix4f(forwardZeroToOneInverse);
        }
        Matrix4f forward = new Matrix4f(forwardZeroToOneInverse).invert();
        return zeroToOneToOpenGl(forward).invert();
    }

    static Matrix4f packProjectionInverse(final Matrix4fc forwardZeroToOneInverse, final boolean enabled) {
        if (!enabled) {
            return new Matrix4f(forwardZeroToOneInverse);
        }
        return zeroToOneToOpenGl(new Matrix4f(forwardZeroToOneInverse).invert()).invert();
    }

    private static Matrix4f zeroToOneToOpenGl(final Matrix4fc forwardZeroToOne) {
        Matrix4f result = new Matrix4f(forwardZeroToOne);
        result.m02(2.0F * forwardZeroToOne.m02() - forwardZeroToOne.m03());
        result.m12(2.0F * forwardZeroToOne.m12() - forwardZeroToOne.m13());
        result.m22(2.0F * forwardZeroToOne.m22() - forwardZeroToOne.m23());
        result.m32(2.0F * forwardZeroToOne.m32() - forwardZeroToOne.m33());
        return result;
    }
}
