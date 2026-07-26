package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMetalFxMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Inject(method = "addAlwaysOnTopPass", at = @At("HEAD"))
    private void metallum$addTransparencyReactivePass(
            final FrameGraphBuilder frame,
            final FeatureRenderDispatcher.PreparedFrame featureFrame,
            final GpuBufferSlice fog,
            final CallbackInfo ci
    ) {
        MetalFxManager.addTransparencyReactivePass(frame, targets);
    }
}
