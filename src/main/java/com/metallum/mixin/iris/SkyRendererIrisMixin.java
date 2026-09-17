package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes every 26.3 sky sub-draw through the active Iris synthetic PSO. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererIrisMixin {
    private static final String RENDER_PASS = "Lcom/mojang/renderpearl/api/commands/RenderPass;";
    private static final String POSE_STACK = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String PIPELINE = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;";
    private static final String COMPILED = "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;";

    @Redirect(
            method = {
                    "renderSkyDisc(" + RENDER_PASS + "Lorg/joml/Vector3fc;)V",
                    "renderDarkDisc(" + RENDER_PASS + ")V",
                    "renderSun(" + RENDER_PASS + "F" + POSE_STACK + ")V",
                    "renderMoon(" + RENDER_PASS + "Lnet/minecraft/world/level/MoonPhase;F" + POSE_STACK + ")V",
                    "renderStars(" + RENDER_PASS + "F" + POSE_STACK + ")V",
                    "renderSunriseAndSunset(" + RENDER_PASS + POSE_STACK + "FLorg/joml/Vector4fc;)V",
                    "renderEndSky(" + RENDER_PASS + ")V",
                    "renderEndFlash(" + RENDER_PASS + POSE_STACK + "FFF)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline("
                            + PIPELINE + ")" + COMPILED
            )
    )
    private CompiledRenderPipeline metallum$compileSkyPipeline(final RenderPipeline source) {
        return RenderSystem.getCompiledPipeline(IrisMetalPipelineOverrides.pipelineForCore(source));
    }
}