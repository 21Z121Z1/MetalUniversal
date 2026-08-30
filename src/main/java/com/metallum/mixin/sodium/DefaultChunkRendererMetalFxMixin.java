package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalBindingTokenRegistry;
import com.metallum.client.metal.render.MetalCutoutReactivePipeline;
import com.metallum.client.metal.render.MetalFxManager;
import com.metallum.client.metal.render.MetalTokenBindingPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMetalFxMixin {
    @Unique
    private static final MetalBindingToken metallum$GLOBALS =
            MetalBindingTokenRegistry.resolve("u_Globals");
    @Unique
    private static final MetalBindingToken metallum$SECTION_TIME =
            MetalBindingTokenRegistry.resolve("u_SectionTimeInfo");
    @Unique
    private static final MetalBindingToken metallum$LIGHT_TEXTURE =
            MetalBindingTokenRegistry.resolve("u_LightTex");
    @Unique
    private static final MetalBindingToken metallum$BLOCK_TEXTURE =
            MetalBindingTokenRegistry.resolve("u_BlockTex");

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
        // Iris owns every gbuffer target, including the single [0] path. A
        // shader-pack draw therefore takes precedence over the independent
        // MetalFX coverage attachment; mixing both layouts would make final
        // read a different colortex0 than terrain wrote.
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

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform("
                            + "Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"
            ),
            remap = false
    )
    private void metallum$bindGlobalsByToken(
            final RenderPass renderPass,
            final String compatibilityName,
            final GpuBufferSlice value
    ) {
        if (renderPass instanceof MetalTokenBindingPass tokenPass) {
            tokenPass.metallum$setUniform(metallum$GLOBALS, compatibilityName, value);
            return;
        }
        renderPass.setUniform(compatibilityName, value);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform("
                            + "Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"
            ),
            remap = false
    )
    private void metallum$bindSectionTimeByToken(
            final RenderPass renderPass,
            final String compatibilityName,
            final GpuBuffer value
    ) {
        if (renderPass instanceof MetalTokenBindingPass tokenPass) {
            tokenPass.metallum$setUniform(metallum$SECTION_TIME, compatibilityName, value);
            return;
        }
        renderPass.setUniform(compatibilityName, value);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture("
                            + "Ljava/lang/String;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0
            ),
            remap = false
    )
    private void metallum$bindLightTextureByToken(
            final RenderPass renderPass,
            final String compatibilityName,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        if (renderPass instanceof MetalTokenBindingPass tokenPass) {
            tokenPass.metallum$bindTexture(metallum$LIGHT_TEXTURE, compatibilityName, textureView, sampler);
            return;
        }
        renderPass.bindTexture(compatibilityName, textureView, sampler);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture("
                            + "Ljava/lang/String;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 1
            ),
            remap = false
    )
    private void metallum$bindBlockTextureByToken(
            final RenderPass renderPass,
            final String compatibilityName,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        if (renderPass instanceof MetalTokenBindingPass tokenPass) {
            tokenPass.metallum$bindTexture(metallum$BLOCK_TEXTURE, compatibilityName, textureView, sampler);
            return;
        }
        renderPass.bindTexture(compatibilityName, textureView, sampler);
    }
}
