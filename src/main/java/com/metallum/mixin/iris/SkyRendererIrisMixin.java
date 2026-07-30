package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SkyRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Routes SkyRenderer's direct Blaze3D draws through the same Iris core
 * program/attachment contract as prepared RenderType draws.
 *
 * <p>SkyRenderer owns persistent vertex buffers and opens RenderPass objects
 * directly, so it never enters {@code PreparedRenderType.drawFromBuffer}.
 * The pass descriptor and PSO must be replaced together before either reaches
 * the Metal backend.</p>
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererIrisMixin {
    @Unique
    private static final ThreadLocal<IrisMetalPipelineOverrides.CoreDrawOverride> METALLUM_SKY_DRAW =
            new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<RenderPipeline> METALLUM_SKY_SOURCE = new ThreadLocal<>();

    @Redirect(
            method = {
                    "renderSkyDisc(I)V",
                    "renderDarkDisc()V"
            },
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
    private RenderPass metallum$createBasicSkyPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        return metallum$createSkyPass(
                encoder, RenderPipelines.SKY, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
    }

    @Redirect(
            method = {
                    "renderSun(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    "renderMoon(Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    "renderEndFlash(Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V"
            },
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
    private RenderPass metallum$createTexturedSkyPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        return metallum$createSkyPass(
                encoder, RenderPipelines.CELESTIAL, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
    }

    @Redirect(
            method = "renderStars(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
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
    private RenderPass metallum$createStarsPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        return metallum$createSkyPass(
                encoder, RenderPipelines.STARS, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
    }

    @Redirect(
            method = "renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V",
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
    private RenderPass metallum$createSunrisePass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        return metallum$createSkyPass(
                encoder, RenderPipelines.SUNRISE_SUNSET, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
    }

    @Redirect(
            method = "renderEndSky()V",
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
    private RenderPass metallum$createEndSkyPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        return metallum$createSkyPass(
                encoder, RenderPipelines.END_SKY, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
    }

    @Redirect(
            method = {
                    "renderSkyDisc(I)V",
                    "renderDarkDisc()V",
                    "renderSun(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    "renderMoon(Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    "renderStars(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    "renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V",
                    "renderEndSky()V",
                    "renderEndFlash(Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private void metallum$setSkyPipeline(final RenderPass renderPass, final RenderPipeline source) {
        IrisMetalPipelineOverrides.CoreDrawOverride override = METALLUM_SKY_DRAW.get();
        RenderPipeline expected = METALLUM_SKY_SOURCE.get();
        try {
            if (override != null && expected != source) {
                throw new IllegalStateException(
                        "Iris Metal sky descriptor was prepared for "
                                + expected.getLocation() + " but draw selected " + source.getLocation()
                );
            }
            renderPass.setPipeline(override == null ? source : override.pipeline());
        } finally {
            METALLUM_SKY_DRAW.remove();
            METALLUM_SKY_SOURCE.remove();
        }
    }

    @Unique
    private static RenderPass metallum$createSkyPass(
            final CommandEncoder encoder,
            final RenderPipeline source,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        METALLUM_SKY_DRAW.remove();
        METALLUM_SKY_SOURCE.remove();
        WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipelineNullable();
        IrisMetalPipelineOverrides.CoreDrawOverride override = IrisMetalPipelineOverrides.prepareCoreDraw(
                source,
                worldPipeline,
                label,
                sceneColor,
                clearColor,
                sceneDepth,
                clearDepth
        );
        if (override == null) {
            return encoder.createRenderPass(label, sceneColor, clearColor, sceneDepth, clearDepth);
        }
        METALLUM_SKY_SOURCE.set(source);
        METALLUM_SKY_DRAW.set(override);
        return encoder.createRenderPass(override.descriptor());
    }
}
