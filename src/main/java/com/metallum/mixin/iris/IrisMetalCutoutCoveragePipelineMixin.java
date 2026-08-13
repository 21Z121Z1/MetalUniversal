package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalCutoutCoverageRuntime;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a generation-owned CUTOUT coverage target without consuming an Iris colortex slot. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPipelineOverrides$Instance")
public abstract class IrisMetalCutoutCoveragePipelineMixin {
    @Inject(method = "buildSynthetic", at = @At("HEAD"), remap = false)
    private void metallum$beginCutoutCoverageBuild(
            @Coerce final Object kind,
            @Coerce final Object program,
            final RenderPipeline source,
            final VertexFormat chunkFormat,
            final CallbackInfoReturnable<RenderPipeline> cir
    ) {
        IrisMetalCutoutCoverageRuntime.beginSyntheticBuild(kind, program);
    }

    @ModifyArg(
            method = "buildSynthetic",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 1
            ),
            index = 1,
            remap = false
    )
    private Object metallum$injectCutoutCoverageOutput(final Object fragmentSource) {
        return IrisMetalCutoutCoverageRuntime.transformGeneratedFragment(fragmentSource);
    }

    @Redirect(
            method = "buildSynthetic",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;build()"
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            ),
            remap = false
    )
    private RenderPipeline metallum$appendCutoutCoverageTarget(final RenderPipeline.Builder builder) {
        return IrisMetalCutoutCoverageRuntime.finishSyntheticBuild(builder);
    }
}
