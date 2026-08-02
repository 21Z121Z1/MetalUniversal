package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalMslDiskCacheTest {
    @TempDir
    Path cacheDirectory;

    @Test
    void genericVertexInputsSurviveCacheRoundTrip() {
        MetalMslDiskCache cache = new MetalMslDiskCache(cacheDirectory);
        MetalMslDiskCache.Entry entry = new MetalMslDiskCache.Entry(
                "vertex msl",
                "fragment msl",
                "vertexMain",
                "fragmentMain",
                List.of(new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                        "Globals",
                        0,
                        MetalCompiledRenderPipeline.STAGE_VERTEX,
                        GpuFormat.R32_UINT
                )),
                List.of(
                        new MetalCrossShaderCompiler.GenericVertexInput(
                                2, MetalCrossShaderCompiler.BaseType.INT, 3
                        ),
                        new MetalCrossShaderCompiler.GenericVertexInput(
                                5, MetalCrossShaderCompiler.BaseType.UINT, 4
                        )
                )
        );

        cache.store("generic-current-roundtrip", entry);

        assertEquals(entry, cache.load("generic-current-roundtrip"));
    }
}
