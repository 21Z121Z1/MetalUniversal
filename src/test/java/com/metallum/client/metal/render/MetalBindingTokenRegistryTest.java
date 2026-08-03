package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
