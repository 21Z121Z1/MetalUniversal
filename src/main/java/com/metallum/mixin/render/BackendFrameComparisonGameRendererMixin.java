package com.metallum.mixin.render;

import com.metallum.client.validation.BackendFrameComparisonClient;
import net.minecraft.client.DeltaTracker;
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
    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void metallum$fixIrisSystemTime(
            final DeltaTracker deltaTracker,
            final CallbackInfo ci
    ) {
        BackendFrameComparisonClient.beforeLevelRender();
    }
}
