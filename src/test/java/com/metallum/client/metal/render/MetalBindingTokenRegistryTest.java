package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalBindingTokenRegistryTest {
    @Test
    void equalNamesResolveToOneStableToken() {
        String first = new String("Sampler0");
        String second = new String("Sampler0");

        MetalBindingToken firstToken = MetalBindingTokenRegistry.resolve(first);
        MetalBindingToken secondToken = MetalBindingTokenRegistry.resolve(second);

        assertSame(firstToken, secondToken);
        assertEquals(firstToken.id(), secondToken.id());
        assertFalse(firstToken.invalidatesGeneratedIrisBlock());
    }

    @Test
    void identityCachePreservesCanonicalTokenAcrossClearAndDistinctStrings() {
        MetalBindingTokenCache cache = new MetalBindingTokenCache(4);
        String stableName = new String("NoiseTexture");

        MetalBindingToken first = cache.resolve(stableName);
        assertSame(first, cache.resolve(stableName));
        assertSame(first, cache.resolve(new String("NoiseTexture")));

        cache.clear();
        assertSame(first, cache.resolve(stableName));
    }

    @Test
    void identityCacheRequiresPowerOfTwoCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new MetalBindingTokenCache(1));
        assertThrows(IllegalArgumentException.class, () -> new MetalBindingTokenCache(3));
    }

    @Test
    void generatedDrawBlockDependenciesKeepTheirSemanticFlag() {
        assertTrue(MetalBindingTokenRegistry.resolve("DynamicTransforms")
                .invalidatesGeneratedIrisBlock());
        assertTrue(MetalBindingTokenRegistry.resolve("Projection")
                .invalidatesGeneratedIrisBlock());
    }

    @Test
    void irisStorageDescriptorCompilesLogicalBindingOnce() {
        MetalBindingToken valid = MetalBindingTokenRegistry.resolve("iris_ssbo/17/Particles");
        MetalBindingToken malformed = MetalBindingTokenRegistry.resolve("iris_ssbo/not-a-number/Particles");
        MetalBindingToken ordinary = MetalBindingTokenRegistry.resolve("Globals");

        assertTrue(valid.isStorageBufferDescriptor());
        assertEquals(17, valid.logicalStorageBinding());
        assertFalse(malformed.isStorageBufferDescriptor());
        assertEquals(-1, malformed.logicalStorageBinding());
        assertFalse(ordinary.isStorageBufferDescriptor());
    }
}
