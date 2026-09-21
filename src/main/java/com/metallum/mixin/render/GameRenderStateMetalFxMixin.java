package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.state.GameRenderState;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderState.class)
public abstract class GameRenderStateMetalFxMixin {
    @Inject(method = "useShaderTransparency", at = @At("RETURN"), cancellable = true)
    private void metallum$enableMetalFxTransparency(final CallbackInfoReturnable<Boolean> cir) {
        // Sodium's translucent TerrainRenderPass otherwise resolves the
        // main LevelRenderer frame-graph handle while Iris is rendering into
        // its independent shadow targets. That handle is not live during the
        // shadow callback; the Metal terrain redirect supplies the shadow
        // descriptor instead, so do not select the shader-transparency target.
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            cir.setReturnValue(false);
            return;
        }
        if (MetalFxManager.usesTransparencyTargets()) {
            cir.setReturnValue(true);
        }
    }
}
