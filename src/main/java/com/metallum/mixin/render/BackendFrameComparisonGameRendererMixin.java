package com.metallum.mixin.render;

import com.metallum.client.validation.BackendFrameComparisonClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies deterministic Iris system time after Iris's render-HEAD timer update
 * and before either backend begins uploading level-render uniforms.
 */
@Mixin(GameRenderer.class)
abstract class BackendFrameComparisonGameRendererMixin {
    @Inject(method = "extract", at = @At("TAIL"))
    private void metallum$fixLightmapFlicker(final CallbackInfo ci) {
        // Tick-driven random flicker keeps changing even when world simulation
        // is frozen. Pin only that scenario input, before the lightmap is rendered.
        if (Boolean.getBoolean("metallum.backend.compare.freeze-simulation")) {
            ((GameRenderer) (Object) this).gameRenderState().lightmapRenderState.blockFactor = 1.4F;
        }
    }

    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void metallum$fixIrisSystemTime(final CallbackInfo ci) {
        BackendFrameComparisonClient.beforeLevelRender();
    }
}
