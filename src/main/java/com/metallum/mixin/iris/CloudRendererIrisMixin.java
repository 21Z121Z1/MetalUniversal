package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
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
 * Routes CloudRenderer's procedural direct draw through the active Iris
 * {@code gbuffers_clouds} program and generation-owned attachments.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererIrisMixin {
    @Unique
    private @Nullable RenderPipeline metallum$cloudSource;
    @Unique
    private IrisMetalPipelineOverrides.@Nullable CoreDrawOverride metallum$cloudDraw;

    @Inject(
            method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            at = @At("HEAD")
    )
    private void metallum$beginCloudDraw(
            final int color,
            final CloudStatus cloudStatus,
            final float bottomY,
            final int range,
            final Vec3 cameraPosition,
            final long gameTime,
            final float partialTicks,
            final CallbackInfo ci
    ) {
        this.metallum$cloudDraw = null;
        this.metallum$cloudSource = cloudStatus == CloudStatus.FANCY
                ? RenderPipelines.CLOUDS
                : RenderPipelines.FLAT_CLOUDS;
    }

    @Redirect(
            method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
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
    private RenderPass metallum$createCloudPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        RenderPipeline source = this.metallum$cloudSource;
        if (source == null) {
            throw new IllegalStateException("Cloud render pass opened without a selected Mojang pipeline");
        }
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
        this.metallum$cloudDraw = override;
        return override == null
                ? encoder.createRenderPass(label, sceneColor, clearColor, sceneDepth, clearDepth)
                : encoder.createRenderPass(override.descriptor());
    }

    @Redirect(
            method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private void metallum$setCloudPipeline(final RenderPass renderPass, final RenderPipeline source) {
        RenderPipeline expected = this.metallum$cloudSource;
        IrisMetalPipelineOverrides.CoreDrawOverride override = this.metallum$cloudDraw;
        if (expected != source) {
            throw new IllegalStateException(
                    "Iris Metal cloud descriptor was prepared for "
                            + (expected == null ? "<none>" : expected.getLocation())
                            + " but draw selected " + source.getLocation()
            );
        }
        renderPass.setPipeline(override == null ? source : override.pipeline());
    }

    @Inject(
            method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            at = @At("RETURN")
    )
    private void metallum$endCloudDraw(
            final int color,
            final CloudStatus cloudStatus,
            final float bottomY,
            final int range,
            final Vec3 cameraPosition,
            final long gameTime,
            final float partialTicks,
            final CallbackInfo ci
    ) {
        this.metallum$cloudDraw = null;
        this.metallum$cloudSource = null;
    }
}
