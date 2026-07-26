package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalCutoutReactivePipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMetalFxMixin {
    @Shadow @Final protected VertexFormat vertexFormat;

    @Inject(method = "begin", at = @At("HEAD"), remap = false)
    private void metallum$beginCutoutReactivePass(
            final TerrainRenderPass pass,
            final net.caffeinemc.mods.sodium.client.util.FogParameters parameters,
            final com.mojang.blaze3d.textures.GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        MetalCutoutReactivePipeline.beginTerrainPass(pass);
    }

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$compileCutoutReactivePipeline(
            final TerrainRenderPass pass,
            final CallbackInfoReturnable<RenderPipeline> cir
    ) {
        if (MetalCutoutReactivePipeline.isActiveCutoutPass()) {
            cir.setReturnValue(MetalCutoutReactivePipeline.forVertexFormat(this.vertexFormat));
        }
    }

    @Inject(method = "end", at = @At("RETURN"), remap = false)
    private void metallum$endCutoutReactivePass(
            final TerrainRenderPass pass,
            final CallbackInfo ci
    ) {
        MetalCutoutReactivePipeline.endTerrainPass();
    }
}
