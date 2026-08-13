package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalHandCoverageRuntime;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds generation-stable exact coverage to every Iris first-person hand PSO. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPipelineOverrides$Instance")
public abstract class IrisMetalHandCoveragePipelineMixin {
    @Inject(method = "buildCoreSynthetic", at = @At("HEAD"), remap = false)
    private void metallum$beginHandCoverageBuild(
            final RenderPipeline source,
            @Coerce final Object key,
            @Coerce final Object program,
            final CallbackInfoReturnable<RenderPipeline> cir
    ) {
        IrisMetalHandCoverageRuntime.beginSyntheticBuild(key, program);
    }

    @ModifyArg(
            method = "buildCoreSynthetic",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 1
            ),
            index = 1,
            remap = false
    )
    private Object metallum$injectHandCoverageOutput(final Object fragmentSource) {
        return IrisMetalHandCoverageRuntime.transformGeneratedFragment(fragmentSource);
    }

    @Redirect(
            method = "buildCoreSynthetic",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;build()"
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            ),
            remap = false
    )
    private RenderPipeline metallum$appendHandCoverageTarget(final RenderPipeline.Builder builder) {
        return IrisMetalHandCoverageRuntime.finishSyntheticBuild(builder);
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void metallum$releaseHandCoverage(final CallbackInfo ci) {
        IrisMetalHandCoverageRuntime.releaseOwner(this);
    }
}
