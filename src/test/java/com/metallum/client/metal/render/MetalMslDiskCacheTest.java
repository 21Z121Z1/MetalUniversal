package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MetalMslDiskCacheTest {
    @TempDir
    Path cacheDirectory;

    private static MetalMslDiskCache.Entry entry(String vertex) {
        return new MetalMslDiskCache.Entry(vertex, "fragment", "v", "f", List.of(), List.of());
    }

    @Test void segmentBoundariesCannotCollideWithEmbeddedNul() {
        assertNotEquals(MetalMslDiskCache.key("a\0b", "c"), MetalMslDiskCache.key("a", "b\0c"));
    }

    @Test void validJsonWithCorruptedShaderIsRejectedAndCanBeRebuilt() throws Exception {
        MetalMslDiskCache cache = new MetalMslDiskCache(cacheDirectory);
        cache.store("shader", entry("original vertex"));
        Path file = cacheDirectory.resolve("shader.json");
        Files.writeString(file, Files.readString(file).replace("original vertex", "different vertex"));
        assertNull(cache.load("shader"));
        assertFalse(Files.exists(file));
        cache.store("shader", entry("rebuilt vertex"));
        assertEquals(entry("rebuilt vertex"), cache.load("shader"));
    }

    @Test void entryCopiedUnderAnotherKeyCannotBecomeAHit() throws Exception {
        MetalMslDiskCache cache = new MetalMslDiskCache(cacheDirectory);
        cache.store("source", entry("vertex"));
        Files.copy(cacheDirectory.resolve("source.json"), cacheDirectory.resolve("target.json"));
        assertNull(cache.load("target"));
        assertEquals(entry("vertex"), cache.load("source"));
    }

    @Test void concurrentWritersPublishAnEntireEntry() throws Exception {
        MetalMslDiskCache cache = new MetalMslDiskCache(cacheDirectory);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                String value = "vertex " + i;
                results.add(workers.submit(() -> cache.store("shared", entry(value))));
            }
            for (var result : results) result.get();
        }
        assertTrue(cache.load("shared").vertexMsl().startsWith("vertex "));
        try (var files = Files.list(cacheDirectory)) {
            assertEquals(List.of("shared.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

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
