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
        assertTrue(compiler.contains("SpvModule"));
        assertTrue(compiler.contains("RenderPearl owns GLSL preprocessing"));
        assertTrue(compiler.contains("compilePending"));
        assertTrue(compiler.contains("record CacheLookup("));
        assertTrue(!compiler.contains("com.mojang.blaze3d.vulkan.glsl"));
        assertTrue(nativeSource.contains("pipelineCompilerQueue = DispatchQueue(label: \"com.metallum.pipeline-compiler\""));
    }
}
