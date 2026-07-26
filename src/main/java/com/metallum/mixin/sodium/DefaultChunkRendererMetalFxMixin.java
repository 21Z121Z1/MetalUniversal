package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalCutoutReactivePipeline;
import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
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
        if (!MetalCutoutReactivePipeline.isActiveCutoutPass()) {
            return encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth);
        }
        GpuTextureView coverage = MetalFxManager.cutoutReactiveAttachment();
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
}
