package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Frame-local identity/variant cache.
 *
 * <p>This is the Java equivalent of keeping lightweight views in a MobileGL
 * frame arena: one backing object may expose several usage variants, but a
 * stable backing/variant pair is materialized only once during the frame.
 * Clearing the cache never closes values; GPU-facing slices retain their value
 * objects until their normal frame lifetime expires.</p>
 */
public final class MetalFrameIdentityCache<K, V> {
    private final IdentityHashMap<K, Int2ObjectOpenHashMap<V>> values = new IdentityHashMap<>();
    private int valueCount;

    public V get(final K identity, final int variant) {
        Objects.requireNonNull(identity, "identity");
        Int2ObjectOpenHashMap<V> variants = this.values.get(identity);
        return variants == null ? null : variants.get(variant);
    }

    public void put(final K identity, final int variant, final V value) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(value, "value");
        Int2ObjectOpenHashMap<V> variants = this.values.computeIfAbsent(
                identity,
                ignored -> new Int2ObjectOpenHashMap<>(2)
        );
        V previous = variants.put(variant, value);
        if (previous == null) {
            this.valueCount++;
        }
    }

    public int identityCount() {
        return this.values.size();
    }

    public int valueCount() {
        return this.valueCount;
    }

    public void clear() {
        this.values.clear();
        this.valueCount = 0;
    }
}
