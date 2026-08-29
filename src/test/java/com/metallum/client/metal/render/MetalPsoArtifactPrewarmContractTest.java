package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalPsoArtifactPrewarmContractTest {
    @Test
    void diskArtifactLookupRunsBeforeLockedCompileWithoutBypassingIris() throws Exception {
        String device = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        ));
        String compiler = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCrossShaderCompiler.java"
        ));
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        int lookup = device.indexOf("MetalCrossShaderCompiler.tryLoadCacheLookup(pipeline, effectiveSource)");
        int submitCompile = device.indexOf("this.submitPrewarmTask(() ->");
        int lockedCompile = device.indexOf("synchronized (COMPILE_CHAIN_LOCK)", submitCompile);
        assertTrue(lookup >= 0 && submitCompile > lookup && lockedCompile > submitCompile);

        int override = device.indexOf("IrisMetalPipelineOverrides.tryCompile(this, pipeline, source)");
        int generic = device.indexOf("MetalCrossShaderCompiler.compile(this, pipeline, source, preloadedLookup)");
        assertTrue(override >= 0 && generic > override);

        assertTrue(device.contains("Executors.newFixedThreadPool(PREWARM_LOOKUP_WORKERS"));
        assertTrue(device.contains("Executors.newSingleThreadExecutor"));
        assertTrue(compiler.contains("record CacheLookup("));
        assertTrue(compiler.contains("does not create Metal functions/PSOs"));
        assertTrue(compiler.contains("Float.floatToIntBits(lookup.sampleLodBias())"));
        assertTrue(nativeSource.contains("pipelineCompilerQueue = DispatchQueue(label: \"com.metallum.pipeline-compiler\""));
    }
}
