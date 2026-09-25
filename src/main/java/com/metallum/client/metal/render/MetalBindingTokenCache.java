package com.metallum.client.metal.render;

/**
 * Small direct-mapped identity cache in front of {@link MetalBindingTokenRegistry}.
 *
 * <p>Shader pipeline resource names are normally stable String objects. The
 * identity cache makes that common path one array probe. Equal-but-distinct
 * names safely fall through to the registry, which preserves canonical token
 * identity.</p>
 */
public final class MetalBindingTokenCache {
    private static final int DEFAULT_CAPACITY = 16;

    private final String[] names;
    private final MetalBindingToken[] tokens;
    private final int mask;

    public MetalBindingTokenCache() {
        this(DEFAULT_CAPACITY);
    }

    MetalBindingTokenCache(final int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Metal binding token cache capacity must be a power of two >= 2");
        }
        this.names = new String[capacity];
        this.tokens = new MetalBindingToken[capacity];
        this.mask = capacity - 1;
    }

    public MetalBindingToken resolve(final String name) {
        int index = System.identityHashCode(name) & this.mask;
        if (this.names[index] == name) {
            return this.tokens[index];
        }
        MetalBindingToken token = MetalBindingTokenRegistry.resolve(name);
        this.names[index] = name;
        this.tokens[index] = token;
        return token;
    }

    public void clear() {
        java.util.Arrays.fill(this.names, null);
        java.util.Arrays.fill(this.tokens, null);
    }
}
