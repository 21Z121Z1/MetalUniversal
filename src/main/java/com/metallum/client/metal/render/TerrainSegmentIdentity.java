package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;

import java.lang.reflect.Method;

/**
 * Reads optional Sodium segment identity methods without loading a class from
 * the Fabric-defined mixin package directly. Mixin-owned interfaces are
 * transformation details, not application APIs; the renderer only reflects
 * the two narrow identity probes added by the optional Sodium adapter.
 */
final class TerrainSegmentIdentity {
    private static final String FREE_METHOD = "metallum$isFree";
    private static final String GENERATION_METHOD = "metallum$generation";

    private TerrainSegmentIdentity() {
    }

    static boolean isFree(final BufferSegment segment) {
        Object value = invoke(segment, FREE_METHOD);
        return Boolean.TRUE.equals(value);
    }

    /** Returns -1 when the generation method is not present or cannot be read. */
    static long generation(final BufferSegment segment) {
        Object value = invoke(segment, GENERATION_METHOD);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static Object invoke(final BufferSegment segment, final String methodName) {
        if (segment == null) {
            return null;
        }
        try {
            Method method = segment.getClass().getMethod(methodName);
            return method.invoke(segment);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
