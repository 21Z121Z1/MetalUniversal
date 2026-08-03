package com.metallum.client.metal.render;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles string resource names into process-stable integer identities once.
 *
 * <p>Names remain part of the compatibility-facing Blaze3D API. Private Metal
 * hot paths can resolve them once and then compare/use integer token ids. The
 * registry does not own GPU resources and therefore does not extend their
 * lifetime.</p>
 */
public final class MetalBindingTokenRegistry {
    private static final String IRIS_SSBO_DESCRIPTOR_PREFIX = "iris_ssbo/";
    private static final ConcurrentMap<String, MetalBindingToken> TOKENS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    static {
        // These two Mojang blocks also invalidate the generated Iris draw block.
        // Registering them eagerly gives the dominant special-case path stable
        // identities before the first render pass is constructed.
        resolve("DynamicTransforms");
        resolve("Projection");
    }

    private MetalBindingTokenRegistry() {
    }

    public static MetalBindingToken resolve(final String name) {
        Objects.requireNonNull(name, "name");
        return TOKENS.computeIfAbsent(name, MetalBindingTokenRegistry::compile);
    }

    public static int tokenCount() {
        return TOKENS.size();
    }

    private static MetalBindingToken compile(final String name) {
        int id = NEXT_ID.getAndIncrement();
        if (id < 0) {
            throw new IllegalStateException("Metal binding token space exhausted");
        }
        int flags = switch (name) {
            case "DynamicTransforms", "Projection" -> MetalBindingToken.INVALIDATES_GENERATED_IRIS_BLOCK;
            default -> 0;
        };
        return new MetalBindingToken(id, parseLogicalStorageBinding(name), flags);
    }

    private static int parseLogicalStorageBinding(final String name) {
        if (!name.startsWith(IRIS_SSBO_DESCRIPTOR_PREFIX)) {
            return -1;
        }
        int start = IRIS_SSBO_DESCRIPTOR_PREFIX.length();
        int end = name.indexOf('/', start);
        if (end <= start) {
            return -1;
        }
        try {
            int binding = Integer.parseInt(name.substring(start, end));
            return binding >= 0 ? binding : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
