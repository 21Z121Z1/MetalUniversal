package com.metallum.client.metal.render;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class MetalCompiledRenderPipelineNumericLookupTest {
    @Test
    void sparseIndexMapPreservesFirstDuplicateAndUnknownSemantics() {
        MetalCompiledRenderPipeline.ResourceBinding first = resource("first", 7);
        MetalCompiledRenderPipeline.ResourceBinding duplicate = resource("duplicate", 7);
        MetalCompiledRenderPipeline.ResourceBinding sparse = resource("sparse", 1_000_000);
        MetalCompiledRenderPipeline.ResourceBinding negative = resource("negative", -3);
        List<MetalCompiledRenderPipeline.ResourceBinding> resources =
                List.of(first, duplicate, sparse, negative);
        MetalCompiledRenderPipeline.NumericResourceIndex index =
                new MetalCompiledRenderPipeline.NumericResourceIndex(resources);

        for (int bindingIndex : new int[]{7, 1_000_000, -3, 8, Integer.MAX_VALUE}) {
            assertSame(legacyLookup(resources, bindingIndex), index.get(bindingIndex));
        }
        assertSame(first, index.get(7));
        assertSame(sparse, index.get(1_000_000));
        assertSame(negative, index.get(-3));
        assertNull(index.get(8));
        assertNull(index.get(Integer.MAX_VALUE));
    }

    @Test
    void zeroAndExtremeIndicesDoNotRequireDenseStorage() {
        MetalCompiledRenderPipeline.ResourceBinding zero = resource("zero", 0);
        MetalCompiledRenderPipeline.ResourceBinding minimum = resource("minimum", Integer.MIN_VALUE);
        MetalCompiledRenderPipeline.ResourceBinding maximum = resource("maximum", Integer.MAX_VALUE);
        MetalCompiledRenderPipeline.NumericResourceIndex index =
                new MetalCompiledRenderPipeline.NumericResourceIndex(List.of(zero, minimum, maximum));
        assertSame(zero, index.get(0));
        assertSame(minimum, index.get(Integer.MIN_VALUE));
        assertSame(maximum, index.get(Integer.MAX_VALUE));
        assertNull(index.get(1));
        assertNull(new MetalCompiledRenderPipeline.NumericResourceIndex(List.of()).get(0));
    }

    @Test
    void eachPipelineIndexMapIsGenerationLocal() {
        MetalCompiledRenderPipeline.ResourceBinding firstGeneration = resource("first-generation", 12);
        MetalCompiledRenderPipeline.ResourceBinding secondGeneration = resource("second-generation", 12);

        MetalCompiledRenderPipeline.NumericResourceIndex first =
                new MetalCompiledRenderPipeline.NumericResourceIndex(List.of(firstGeneration));
        MetalCompiledRenderPipeline.NumericResourceIndex second =
                new MetalCompiledRenderPipeline.NumericResourceIndex(List.of(secondGeneration));

        assertSame(firstGeneration, first.get(12));
        assertSame(secondGeneration, second.get(12));
    }

    private static MetalCompiledRenderPipeline.ResourceBinding legacyLookup(
            List<MetalCompiledRenderPipeline.ResourceBinding> resources,
            int bindingIndex
    ) {
        for (MetalCompiledRenderPipeline.ResourceBinding resource : resources) {
            if (resource.bindingIndex() == bindingIndex) {
                return resource;
            }
        }
        return null;
    }

    private static MetalCompiledRenderPipeline.ResourceBinding resource(String name, int index) {
        return new MetalCompiledRenderPipeline.ResourceBinding(
                MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                name,
                index,
                MetalCompiledRenderPipeline.STAGE_ALL,
                null
        );
    }
}
