package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.metal.render.MetalBackend;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Keeps source identity with its own pending compile, including reload/background compilation. */
@Mixin(PipelineBuilder.class)
public abstract class PipelineBuilderMetalSourceMixin {
    @WrapMethod(method = "compilePipeline")
    private CompletableFuture<CompiledRenderPipeline.Pending> metallum$preserveSourceIdentity(
            final RenderPipeline pipeline, final ShaderSource source, final Executor executor,
            final Operation<CompletableFuture<CompiledRenderPipeline.Pending>> original) {
        ShaderSource compilationSource=MetalBackend.captureCompileInputs(source);
        return original.call(pipeline, compilationSource, executor)
                .thenApply(pending -> MetalBackend.preservePipelineSource(pending,pipeline,compilationSource));
    }
}
