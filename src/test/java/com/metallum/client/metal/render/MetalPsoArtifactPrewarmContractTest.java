package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalPsoArtifactPrewarmContractTest {
    @Test
    void renderPearlOwnsFrontendAndMetalConsumesNormalizedSpirv() throws Exception {
        String device = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        ));
        String compiler = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCrossShaderCompiler.java"
        ));
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(device.contains(".compilePipeline("));
        assertTrue(device.contains("synchronized (COMPILE_CHAIN_LOCK)"));
        assertTrue(device.contains("frontendPipelines"));
        assertTrue(device.contains("getOrCompileFrontendPipeline"));
        int precompileStart = device.indexOf("precompilePipeline(");
        int precompileEnd = device.indexOf("/** True when the background prewarm thread exists", precompileStart);
        String precompile = device.substring(precompileStart, precompileEnd);
        int firstLookup = precompile.indexOf("this.compiledPipelines.get(pipeline)");
        int secondLookup = precompile.indexOf("this.compiledPipelines.get(pipeline)", firstLookup + 1);
        assertTrue(firstLookup >= 0 && secondLookup > firstLookup,
                "precompilePipeline must re-check the cache after taking COMPILE_CHAIN_LOCK");
        assertTrue(compiler.contains("SpvModule"));
        assertTrue(compiler.contains("RenderPearl owns GLSL preprocessing"));
        assertTrue(compiler.contains("compilePending"));
        assertTrue(compiler.contains("renderPearlCacheKey("));
        assertTrue(compiler.contains("mutableSpirvCopy("));
        assertTrue(compiler.contains("MetalMslDiskCache.instance()"));
        assertTrue(!compiler.contains("tryLoadCacheLookup("));
        assertTrue(!compiler.contains("com.mojang.blaze3d.vulkan.glsl"));
        assertTrue(nativeSource.contains("pipelineCompilerQueue = DispatchQueue(label: \"com.metallum.pipeline-compiler\""));
    }
}
