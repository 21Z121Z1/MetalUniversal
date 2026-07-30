package com.metallum.mixin.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalCutoutReactivePipeline;
import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMetalFxMixin {
    @Unique
    private static final AtomicBoolean metallum$namespaceWarned = new AtomicBoolean();

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

    @Inject(method = "begin", at = @At("RETURN"), remap = false)
    private void metallum$beginIrisTerrainPass(
            final TerrainRenderPass pass,
            final net.caffeinemc.mods.sodium.client.util.FogParameters parameters,
            final com.mojang.blaze3d.textures.GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        IrisMetalPipelineOverrides.beginTerrainPass(pass);
    }

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$compileCutoutReactivePipeline(
            final TerrainRenderPass pass,
            final CallbackInfoReturnable<RenderPipeline> cir
    ) {
        if (MetalCutoutReactivePipeline.isActiveCutoutPass()) {
            // The substituted pipeline's location is metallum:pipeline/terrain_cutout_reactive.
            // Shader-pack integrations that decide whether to override a terrain pipeline by
            // matching "sodium" in its namespace (Iris' IrisMetalPipelineOverrides.isSodiumPipeline
            // does exactly this) will not match it, and will skip CUTOUT terrain without logging
            // anything. Reachable only once MetalFX Temporal is on, so say so where it is visible.
            if (metallum$namespaceWarned.compareAndSet(false, true)) {
                Metallum.LOGGER.warn(
                        "MetalFX CUTOUT reactive pipeline is namespaced 'metallum', not 'sodium': "
                                + "shader-pack overrides keyed on a sodium namespace will silently "
                                + "skip CUTOUT terrain while MetalFX Temporal is enabled");
            }
            cir.setReturnValue(MetalCutoutReactivePipeline.forVertexFormat(this.vertexFormat));
        }
    }

    @Inject(method = "end", at = @At("RETURN"), remap = false)
    private void metallum$endCutoutReactivePass(
            final TerrainRenderPass pass,
            final CallbackInfo ci
    ) {
        IrisMetalPipelineOverrides.endTerrainPass();
        MetalCutoutReactivePipeline.endTerrainPass();
    }
}
