package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MetalFrameIdentityCacheTest {
    @Test
    void keysUseIdentityAndVariant() {
        MetalFrameIdentityCache<Object, String> cache = new MetalFrameIdentityCache<>();
        Object first = new String("block");
        Object equalButDistinct = new String("block");

        cache.put(first, 1, "uniform");
        cache.put(first, 2, "index");
        cache.put(equalButDistinct, 1, "other-uniform");

        assertEquals("uniform", cache.get(first, 1));
        assertEquals("index", cache.get(first, 2));
        assertEquals("other-uniform", cache.get(equalButDistinct, 1));
        assertNull(cache.get(new String("block"), 1));
        assertEquals(2, cache.identityCount());
        assertEquals(3, cache.valueCount());
    }

    @Test
    void replacingVariantDoesNotInflateValueCount() {
        MetalFrameIdentityCache<Object, Object> cache = new MetalFrameIdentityCache<>();
        Object identity = new Object();
        Object first = new Object();
        Object replacement = new Object();

        cache.put(identity, 7, first);
        cache.put(identity, 7, replacement);

        assertSame(replacement, cache.get(identity, 7));
        assertEquals(1, cache.identityCount());
        assertEquals(1, cache.valueCount());
    }

    @Test
    void clearDropsOnlyCacheReferences() {
        MetalFrameIdentityCache<Object, Object> cache = new MetalFrameIdentityCache<>();
        Object identity = new Object();
        Object value = new Object();
        cache.put(identity, 0, value);

        cache.clear();

        assertNull(cache.get(identity, 0));
        assertEquals(0, cache.identityCount());
        assertEquals(0, cache.valueCount());
        assertSame(value, value);
    }
}
