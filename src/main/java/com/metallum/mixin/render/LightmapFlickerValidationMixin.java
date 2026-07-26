package com.metallum.mixin.render;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes the lightmap's torch-flicker random walk during automated
 * validation runs. The flicker perturbs every block-lit pixel each tick from
 * an unseeded RandomSource, which is invisible noise in normal play but
 * breaks byte-identical golden frame captures — the validation scene is a
 * sealed, purely block-lit room, so the flicker modulates the entire frame.
 * Zeroed after vanilla tick() so needsUpdate semantics stay untouched.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapFlickerValidationMixin {
    private static final boolean METALLUM_VALIDATION =
            Boolean.getBoolean("metallum.validation.enabled");

    @Shadow
    private float blockLightFlicker;

    @Inject(method = "tick", at = @At("TAIL"))
    private void metallum$freezeFlickerForValidation(final CallbackInfo callbackInfo) {
        if (METALLUM_VALIDATION) {
            this.blockLightFlicker = 0.0F;
        }
    }
}
