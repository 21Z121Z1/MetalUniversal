package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bridges the 26.3 option-state transparency decision to MetalFX's OIT inputs. */
@Mixin(GameRenderer.class)
public abstract class GameRenderStateMetalFxMixin {
    @Inject(method = "useImprovedTransparency", at = @At("RETURN"), cancellable = true)
    private void metallum$enableMetalFxTransparency(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalFxManager.usesTransparencyTargets()) {
            cir.setReturnValue(true);
        }
    }
}
