package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalTerrainBridge;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Replaces Sodium's terrain descriptor and PSO as one Iris generation unit. */
@Mixin(DefaultChunkRenderer.class)
public abstract class IrisSodiumDefaultChunkRendererMixin {
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
    private RenderPass metallum$createIrisTerrainPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        RenderPass irisPass = IrisMetalTerrainBridge.createRenderPass(
                encoder, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
        return irisPass == null
                ? encoder.createRenderPass(label, sceneColor, clearColor, sceneDepth, clearDepth)
                : irisPass;
    }

}
