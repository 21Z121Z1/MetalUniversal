package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.IrisMetalCutoutCoverageRuntime;
import com.metallum.client.metal.render.MetalCutoutReactivePipeline;
import com.metallum.client.metal.render.MetalFxManager;
import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMetalFxMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                            + "getTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            ),
            remap = false
    )
    private RenderTarget metallum$avoidUnavailableFrameGraphTarget(final TerrainRenderPass pass) {
        if (IrisMetalPipelineOverrides.shouldBypassTerrainTargetEvaluation()) {
            return Minecraft.getInstance().gameRenderer.mainRenderTarget();
        }
        return pass.getTarget();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                            + "Ljava/util/function/Supplier;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/Optional;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/OptionalDouble;"
                            + ")Lcom/mojang/blaze3d/systems/RenderPass;"
            ),
            remap = false
    )
    private RenderPass metallum$attachCutoutCoverage(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView depthTexture,
            final OptionalDouble clearDepth
    ) {
        // Iris CUTOUT keeps the pack's compact DRAWBUFFERS list and appends a
        // generation-owned R8 coverage attachment after it. This descriptor
        // must match the synthetic CUTOUT PSO even on frames where MetalFX is
        // disabled, otherwise the pipeline cache would see two signatures.
        RenderPass irisCutoutPass = IrisMetalCutoutCoverageRuntime.createTerrainRenderPass(
                encoder, label, colorTexture, clearColor, depthTexture, clearDepth
        );
        if (irisCutoutPass != null) {
            return irisCutoutPass;
        }
        RenderPass irisPass = IrisMetalPipelineOverrides.createTerrainRenderPass(
                encoder, label, colorTexture, clearColor, depthTexture, clearDepth
        );
        if (irisPass != null) {
            return irisPass;
        }
        if (!MetalCutoutReactivePipeline.isActiveCutoutPass()) {
            return encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth);
        }
        GpuTextureView coverage = MetalFxManager.cutoutReactiveAttachment(
                colorTexture.getWidth(0),
                colorTexture.getHeight(0)
        );
        if (coverage == null) {
            return encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth);
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
                .withColorAttachment(colorTexture, clearColor)
                .withColorAttachment(coverage)
                .withDepthAttachment(depthTexture, clearDepth)
                .withRenderArea(new RenderPass.RenderArea(
                        0,
                        0,
                        colorTexture.getWidth(0),
                        colorTexture.getHeight(0)
                ));
        return encoder.createRenderPass(descriptor);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            ),
            remap = false
    )
    private void metallum$useIrisTerrainPipeline(
            final RenderPass renderPass,
            final RenderPipeline pipeline
    ) {
        renderPass.setPipeline(IrisMetalPipelineOverrides.pipelineForTerrain(pipeline));
    }
}
