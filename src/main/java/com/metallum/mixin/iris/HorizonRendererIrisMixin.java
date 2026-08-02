package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Routes Iris's horizon fan through the active Metal {@code gbuffers_skybasic}
 * program and generation-owned attachments.
 */
@Mixin(HorizonRenderer.class)
public abstract class HorizonRendererIrisMixin {
    @Unique
    private IrisMetalPipelineOverrides.CoreDrawOverride metallum$horizonDraw;

    @Inject(
            method = "renderHorizon(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lorg/joml/Vector4f;)V",
            at = @At("HEAD")
    )
    private void metallum$beginHorizonDraw(
            final Matrix4fc modelView,
            final Matrix4fc projection,
            final Vector4f fogColor,
            final CallbackInfo ci
    ) {
        this.metallum$horizonDraw = null;
    }

    @Redirect(
            method = "renderHorizon(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lorg/joml/Vector4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                            + "Ljava/util/function/Supplier;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/Optional;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/OptionalDouble;"
                            + ")Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass metallum$createHorizonPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipelineNullable();
        IrisMetalPipelineOverrides.CoreDrawOverride override = IrisMetalPipelineOverrides.prepareCoreDraw(
                RenderPipelines.SKY,
                worldPipeline,
                label,
                sceneColor,
                clearColor,
                sceneDepth,
                clearDepth
        );
        this.metallum$horizonDraw = override;
        return override == null
                ? encoder.createRenderPass(label, sceneColor, clearColor, sceneDepth, clearDepth)
                : encoder.createRenderPass(override.descriptor());
    }

    @Redirect(
            method = "renderHorizon(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lorg/joml/Vector4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private void metallum$setHorizonPipeline(final RenderPass renderPass, final RenderPipeline source) {
        if (source != RenderPipelines.SKY) {
            throw new IllegalStateException(
                    "Iris Metal horizon descriptor was prepared for "
                            + RenderPipelines.SKY.getLocation() + " but draw selected " + source.getLocation()
            );
        }
        IrisMetalPipelineOverrides.CoreDrawOverride override = this.metallum$horizonDraw;
        renderPass.setPipeline(override == null ? source : override.pipeline());
    }

    @Inject(
            method = "renderHorizon(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lorg/joml/Vector4f;)V",
            at = @At("RETURN")
    )
    private void metallum$endHorizonDraw(
            final Matrix4fc modelView,
            final Matrix4fc projection,
            final Vector4f fogColor,
            final CallbackInfo ci
    ) {
        this.metallum$horizonDraw = null;
    }
}
