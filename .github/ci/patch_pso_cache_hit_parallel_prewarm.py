from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1))

# ---------------------------------------------------------------------------
# MetalCrossShaderCompiler: split pure-ish artifact lookup from translation /
# function / PSO creation. The latter remains under MetalDevice's established
# COMPILE_CHAIN_LOCK. A preloaded lookup carries the exact LOD-bias input that
# participated in its key; stale bias is rejected and recomputed by compile().
# ---------------------------------------------------------------------------
compiler = ROOT / "src/main/java/com/metallum/client/metal/render/MetalCrossShaderCompiler.java"
replace_once(
    compiler,
    '''    static MetalCompiledRenderPipeline compile(final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource) {\n        float sampleLodBias = MetalFxManager.shaderSampleLodBias();\n        try {\n            // S8: disk-cache the translated five-tuple. The raw sources are\n            // fetched again inside getOrCompileShader on a miss; that double\n            // fetch is string work in the microsecond range and cheaper than\n            // threading prepared sources through the vanilla-shaped chain.\n            MetalMslDiskCache diskCache = MetalMslDiskCache.instance();\n            String cacheKey = null;\n            if (diskCache != null) {\n                String rawVertex = shaderSource.get(pipeline.getVertexShader(), ShaderType.VERTEX);\n                String rawFragment = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);\n                if (rawVertex != null && rawFragment != null) {\n                    cacheKey = MetalMslDiskCache.key(\n                            MetalDevice.prepareShaderSource(rawVertex, pipeline.getShaderDefines()),\n                            MetalDevice.prepareShaderSource(rawFragment, pipeline.getShaderDefines()),\n                            // explicitFragmentOutputLocations parses the raw\n                            // (comment-carrying) text, not the prepared one.\n                            rawFragment,\n                            vertexFormatSignature(pipeline),\n                            bindGroupSignature(pipeline),\n                            Integer.toHexString(Float.floatToIntBits(sampleLodBias)),\n                            MetalMslDiskCache.CACHE_SALT\n                    );\n                    MetalMslDiskCache.Entry cached = diskCache.load(cacheKey);\n                    if (cached != null) {\n                        MetalMslDiskCache.recordHit();\n                        if (device.isDebuggingEnabled()) {\n                            Metallum.LOGGER.info("[metallum] MSL cache hit for {}", pipeline.getLocation());\n                        }\n                        return new MetalCompiledRenderPipeline(\n                                device,\n                                pipeline,\n                                cached.vertexMsl(),\n                                cached.fragmentMsl(),\n                                cached.vertexEntryPoint(),\n                                cached.fragmentEntryPoint(),\n                                cached.resources(),\n                                cached.genericVertexInputs()\n                        );\n                    }\n                }\n            }\n            long translateStart = System.nanoTime();''',
    '''    record CacheLookup(\n            @Nullable MetalMslDiskCache diskCache,\n            @Nullable String cacheKey,\n            @Nullable MetalMslDiskCache.Entry cached,\n            float sampleLodBias\n    ) {\n    }\n\n    /**\n     * Performs only stable-source preparation, cache-key hashing and disk JSON\n     * lookup. It deliberately does not create Metal functions/PSOs and does not\n     * mutate hit/miss telemetry until a locked compile actually consumes it.\n     */\n    static CacheLookup tryLoadCacheLookup(\n            final RenderPipeline pipeline, final ShaderSource shaderSource\n    ) {\n        float sampleLodBias = MetalFxManager.shaderSampleLodBias();\n        MetalMslDiskCache diskCache = MetalMslDiskCache.instance();\n        if (diskCache == null) {\n            return new CacheLookup(null, null, null, sampleLodBias);\n        }\n        String rawVertex = shaderSource.get(pipeline.getVertexShader(), ShaderType.VERTEX);\n        String rawFragment = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);\n        if (rawVertex == null || rawFragment == null) {\n            return new CacheLookup(diskCache, null, null, sampleLodBias);\n        }\n        String cacheKey = MetalMslDiskCache.key(\n                MetalDevice.prepareShaderSource(rawVertex, pipeline.getShaderDefines()),\n                MetalDevice.prepareShaderSource(rawFragment, pipeline.getShaderDefines()),\n                // explicitFragmentOutputLocations parses the raw comment-carrying text.\n                rawFragment,\n                vertexFormatSignature(pipeline),\n                bindGroupSignature(pipeline),\n                Integer.toHexString(Float.floatToIntBits(sampleLodBias)),\n                MetalMslDiskCache.CACHE_SALT\n        );\n        return new CacheLookup(diskCache, cacheKey, diskCache.load(cacheKey), sampleLodBias);\n    }\n\n    static MetalCompiledRenderPipeline compile(\n            final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource\n    ) {\n        return compile(device, pipeline, shaderSource, null);\n    }\n\n    static MetalCompiledRenderPipeline compile(\n            final MetalDevice device,\n            final RenderPipeline pipeline,\n            final ShaderSource shaderSource,\n            @Nullable final CacheLookup preloadedLookup\n    ) {\n        try {\n            CacheLookup lookup = preloadedLookup;\n            float currentLodBias = MetalFxManager.shaderSampleLodBias();\n            if (lookup == null\n                    || Float.floatToIntBits(lookup.sampleLodBias()) != Float.floatToIntBits(currentLodBias)) {\n                lookup = tryLoadCacheLookup(pipeline, shaderSource);\n            }\n            float sampleLodBias = lookup.sampleLodBias();\n            MetalMslDiskCache diskCache = lookup.diskCache();\n            String cacheKey = lookup.cacheKey();\n            MetalMslDiskCache.Entry cached = lookup.cached();\n            if (cached != null) {\n                MetalMslDiskCache.recordHit();\n                if (device.isDebuggingEnabled()) {\n                    Metallum.LOGGER.info("[metallum] MSL cache hit for {}", pipeline.getLocation());\n                }\n                return new MetalCompiledRenderPipeline(\n                        device,\n                        pipeline,\n                        cached.vertexMsl(),\n                        cached.fragmentMsl(),\n                        cached.vertexEntryPoint(),\n                        cached.fragmentEntryPoint(),\n                        cached.resources(),\n                        cached.genericVertexInputs()\n                );\n            }\n            long translateStart = System.nanoTime();'''
)
# store() already assumes diskCache when cacheKey exists; make that explicit.
replace_once(
    compiler,
    '''            if (cacheKey != null) {\n                diskCache.store(cacheKey, new MetalMslDiskCache.Entry(''',
    '''            if (cacheKey != null && diskCache != null) {\n                diskCache.store(cacheKey, new MetalMslDiskCache.Entry('''
)

# ---------------------------------------------------------------------------
# MetalDevice: retain the existing serial compiler executor as the sole
# background contender for COMPILE_CHAIN_LOCK. Add a bounded lookup pool that
# only computes/loads stable MSL artifacts, then hands the result to that serial
# executor. Thus several disk/cache lookups overlap without multiplying native
# compiler / SPIRV-Cross concurrency or changing Iris override authority.
# ---------------------------------------------------------------------------
device = ROOT / "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
replace_once(
    device,
    '''    @Nullable\n    private final ExecutorService prewarmExecutor;''',
    '''    @Nullable\n    private final ExecutorService prewarmExecutor;\n    @Nullable\n    private final ExecutorService prewarmLookupExecutor;\n    private static final int PREWARM_LOOKUP_WORKERS = Math.max(\n            1,\n            Math.min(4, Integer.getInteger(\n                    "metallum.opt.prewarmLookupWorkers",\n                    Math.max(1, Runtime.getRuntime().availableProcessors() / 2)\n            ))\n    );'''
)
replace_once(
    device,
    '''        this.prewarmExecutor = ASYNC_PRECOMPILE && RENDER_PIPELINE_IDENTITY_EQUALS\n                ? Executors.newSingleThreadExecutor(runnable -> {\n                    Thread thread = new Thread(runnable, "metallum-pso-prewarm");\n                    thread.setDaemon(true);\n                    return thread;\n                })\n                : null;''',
    '''        this.prewarmExecutor = ASYNC_PRECOMPILE && RENDER_PIPELINE_IDENTITY_EQUALS\n                ? Executors.newSingleThreadExecutor(runnable -> {\n                    Thread thread = new Thread(runnable, "metallum-pso-prewarm-compile");\n                    thread.setDaemon(true);\n                    return thread;\n                })\n                : null;\n        this.prewarmLookupExecutor = this.prewarmExecutor == null\n                ? null\n                : Executors.newFixedThreadPool(PREWARM_LOOKUP_WORKERS, runnable -> {\n                    Thread thread = new Thread(runnable, "metallum-pso-artifact-lookup");\n                    thread.setDaemon(true);\n                    return thread;\n                });'''
)
replace_once(
    device,
    '''        if (this.prewarmExecutor != null) {\n            int generation = this.pipelineCacheGeneration;\n            this.prewarmExecutor.execute(() -> {\n                try {\n                    this.compileInBackground(pipeline, effectiveSource, generation);\n                } catch (Throwable t) {\n                    // First real use on the render thread recompiles and\n                    // surfaces the error with vanilla's own handling.\n                    Metallum.LOGGER.warn("[metallum] background precompile failed for {}", pipeline.getLocation(), t);\n                }\n            });\n            return PENDING_PRECOMPILE;\n        }''',
    '''        if (this.prewarmExecutor != null && this.prewarmLookupExecutor != null) {\n            int generation = this.pipelineCacheGeneration;\n            try {\n                this.prewarmLookupExecutor.execute(() -> {\n                    try {\n                        MetalCrossShaderCompiler.CacheLookup lookup =\n                                MetalCrossShaderCompiler.tryLoadCacheLookup(pipeline, effectiveSource);\n                        if (generation != this.pipelineCacheGeneration\n                                || this.compiledPipelines.containsKey(pipeline)) {\n                            return;\n                        }\n                        this.submitPrewarmTask(() -> {\n                            try {\n                                this.compileInBackground(pipeline, effectiveSource, generation, lookup);\n                            } catch (Throwable t) {\n                                // First real use on the render thread recompiles and\n                                // surfaces the error with vanilla's own handling.\n                                Metallum.LOGGER.warn(\n                                        "[metallum] background precompile failed for {}",\n                                        pipeline.getLocation(), t\n                                );\n                            }\n                        });\n                    } catch (Throwable t) {\n                        Metallum.LOGGER.warn(\n                                "[metallum] background MSL artifact lookup failed for {}",\n                                pipeline.getLocation(), t\n                        );\n                    }\n                });\n            } catch (java.util.concurrent.RejectedExecutionException ignored) {\n            }\n            return PENDING_PRECOMPILE;\n        }'''
)
replace_once(
    device,
    '''    private MetalCompiledRenderPipeline compileWithIrisOverride(\n            final RenderPipeline pipeline, final ShaderSource source\n    ) {\n        MetalCompiledRenderPipeline override = IrisMetalPipelineOverrides.tryCompile(this, pipeline, source);\n        return override != null ? override : MetalCrossShaderCompiler.compile(this, pipeline, source);\n    }''',
    '''    private MetalCompiledRenderPipeline compileWithIrisOverride(\n            final RenderPipeline pipeline, final ShaderSource source\n    ) {\n        return compileWithIrisOverride(pipeline, source, null);\n    }\n\n    private MetalCompiledRenderPipeline compileWithIrisOverride(\n            final RenderPipeline pipeline,\n            final ShaderSource source,\n            @Nullable final MetalCrossShaderCompiler.CacheLookup preloadedLookup\n    ) {\n        // Iris remains the first and only override authority. A generic MSL\n        // artifact may be looked up speculatively, but can never win this race.\n        MetalCompiledRenderPipeline override = IrisMetalPipelineOverrides.tryCompile(this, pipeline, source);\n        return override != null\n                ? override\n                : MetalCrossShaderCompiler.compile(this, pipeline, source, preloadedLookup);\n    }'''
)
replace_once(
    device,
    '''    private void compileInBackground(final RenderPipeline pipeline, final ShaderSource source, final int generation) {\n        if (this.compiledPipelines.containsKey(pipeline)) {\n            return;\n        }\n        synchronized (COMPILE_CHAIN_LOCK) {''',
    '''    private void compileInBackground(\n            final RenderPipeline pipeline,\n            final ShaderSource source,\n            final int generation,\n            final MetalCrossShaderCompiler.CacheLookup lookup\n    ) {\n        if (this.compiledPipelines.containsKey(pipeline)) {\n            return;\n        }\n        synchronized (COMPILE_CHAIN_LOCK) {'''
)
replace_once(
    device,
    '''            this.compiledPipelines.computeIfAbsent(pipeline, p -> compileWithIrisOverride(p, source));\n        }\n    }''',
    '''            this.compiledPipelines.computeIfAbsent(\n                    pipeline, p -> compileWithIrisOverride(p, source, lookup)\n            );\n        }\n    }'''
)
# Close lookup workers before the serial compiler executor, so no new compile
# tasks can be enqueued while the compiler executor is being torn down.
replace_once(
    device,
    '''        if (this.prewarmExecutor != null) {\n            // Stop background compiles before tearing down the caches they\n            // populate; a straggler past the 5s bail-out still serializes\n            // against clearPipelineCache via COMPILE_CHAIN_LOCK.\n            this.prewarmExecutor.shutdownNow();''',
    '''        if (this.prewarmLookupExecutor != null) {\n            this.prewarmLookupExecutor.shutdownNow();\n            try {\n                if (!this.prewarmLookupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {\n                    Metallum.LOGGER.warn("[metallum] PSO artifact lookup workers still busy at shutdown");\n                }\n            } catch (InterruptedException e) {\n                Thread.currentThread().interrupt();\n            }\n        }\n        if (this.prewarmExecutor != null) {\n            // Stop background compiles after lookup producers. A straggler past\n            // the 5s bail-out still serializes with cache teardown via the lock.\n            this.prewarmExecutor.shutdownNow();'''
)

# ---------------------------------------------------------------------------
# Contract: lookup must be outside the global compile lock; generic cache data
# must still be downstream of Iris override; native compile remains serial-safe.
# ---------------------------------------------------------------------------
test = ROOT / "src/test/java/com/metallum/client/metal/render/MetalPsoArtifactPrewarmContractTest.java"
test.write_text(r'''package com.metallum.client.metal.render;

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
''')

print('parallel MSL artifact prewarm patch applied')
