package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

/** Publishes the current resource-pack shader/include library after each successful reload. */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMetalSourceMixin {
    @Unique private @Nullable ShaderSource metallum$pendingLibrary;

    @ModifyArg(method = "apply", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/PipelineCache;<init>(Lcom/mojang/renderpearl/api/device/GpuDevice;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)V"), index = 1)
    private ShaderSource metallum$rememberLiveShaderLibrary(final ShaderSource source) {
        ShaderSource prepared=MetalBackend.prepareShaderSource(source);
        metallum$pendingLibrary=prepared;
        return prepared;
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void metallum$publishAfterSuccessfulReload(final CallbackInfo ci) {
        ShaderSource source = metallum$pendingLibrary;
        metallum$pendingLibrary = null;
        if (source != null) {
            MetalBackend.captureShaderSource(source);
            if (Boolean.getBoolean("metallum.metalfx.captureMotionValidation")) {
                com.metallum.Metallum.LOGGER.info("Motion shader library after resource reload: entity={} previous={}",
                        source.getShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum", "core/entity_motion"),
                                com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX) != null,
                        source.getShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum", "core/entity_previous_motion"),
                                com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX) != null);
            }
        }
    }
}
