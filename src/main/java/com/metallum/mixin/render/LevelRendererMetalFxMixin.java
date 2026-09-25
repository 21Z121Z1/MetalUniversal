package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMetalFxMixin {
    @Shadow @Final private LevelTargetBundle targets;

    /**
     * 26.3 folded the old always-on-top helper into {@code addMainPass} and
     * moved transparency to the OIT targets owned by that pass. Register the
     * reactive graph pass after the main pass has declared its OIT reads/writes
     * so FrameGraph can order the mask after accumulation without relying on a
     * removed 26.2 helper.
     */
    @Inject(method = "addMainPass", at = @At("TAIL"))
    private void metallum$addTransparencyReactivePass(
            final FrameGraphBuilder frame,
            final FeatureRenderDispatcher.PreparedFrame featureFrame,
            final GpuBufferSlice fog,
            final ChunkSectionsToRender chunkSectionsToRender,
            final boolean consistentDepthRequired,
            final CallbackInfo ci
    ) {
        MetalFxManager.addTransparencyReactivePass(frame, targets);
    }
}
