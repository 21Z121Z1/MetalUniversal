package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalBindingTokenRegistry;
import com.metallum.client.metal.render.MetalTokenBindingPass;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


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
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline("
                            + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)"
                            + "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"
            ),
            remap = false
    )
    private com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline metallum$useIrisTerrainPipeline(
            final RenderPipeline pipeline
    ) {
        return RenderSystem.getCompiledPipeline(IrisMetalPipelineOverrides.pipelineForTerrain(pipeline));
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform("
                            + "Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"
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
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform("
                            + "Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V"
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
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform("
                            + "Ljava/lang/String;"
                            + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
                            + "Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
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
        renderPass.setUniform(compatibilityName, textureView, sampler);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform("
                            + "Ljava/lang/String;"
                            + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
                            + "Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
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
        renderPass.setUniform(compatibilityName, textureView, sampler);
    }
}
