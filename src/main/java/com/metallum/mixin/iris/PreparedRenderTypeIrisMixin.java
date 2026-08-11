package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalCoreDrawBridge;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Gives fixed-Iris core prepared draws an atomic MRT descriptor and PSO. */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeIrisMixin {
    @Shadow
    @Final
    private RenderPipeline pipeline;

    @Inject(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At("HEAD")
    )
    private void metallum$beginCoreDraw(
            final GpuBuffer vertexBuffer,
            final GpuBuffer indexBuffer,
            final IndexType indexType,
            final int baseVertex,
            final int firstIndex,
            final int indexCount,
            final CallbackInfo ci
    ) {
        IrisMetalCoreDrawBridge.clear();
    }

    @Redirect(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass metallum$createCoreRenderPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipelineNullable();
        IrisMetalCoreDrawBridge.CoreDrawOverride override = IrisMetalCoreDrawBridge.prepareCoreDraw(
                this.pipeline,
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
        IrisMetalCoreDrawBridge.begin(override);
        try {
            return IrisMetalCoreDrawBridge.createRenderPass(encoder, override.descriptor());
        } catch (RuntimeException | Error failure) {
            // The normal RETURN injection cannot run when pass construction
            // fails. Do not leak the generation-owned draw into the next pass.
            IrisMetalCoreDrawBridge.clear();
            throw failure;
        }
    }

    @Inject(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At("RETURN")
    )
    private void metallum$endCoreDraw(
            final GpuBuffer vertexBuffer,
            final GpuBuffer indexBuffer,
            final IndexType indexType,
            final int baseVertex,
            final int firstIndex,
            final int indexCount,
            final CallbackInfo ci
    ) {
        IrisMetalCoreDrawBridge.clear();
    }
}
