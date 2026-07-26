package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris static initializers ({@code SamplerLimits.<init>} during
 * {@code IrisRenderSystem.<clinit>}) query GL limits through
 * {@code GlStateManager._getInteger} the moment the class is referenced —
 * class initialization cannot be cancelled, so the query primitive itself
 * answers with conservative constants while Iris is dormant on Metal. On the
 * Metal backend nothing legitimate reaches GlStateManager (the vanilla GL
 * backend is inactive), so this cannot mask real GL state.
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GlStateManagerCompatMixin {
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 34930;
    private static final int GL_MAX_DRAW_BUFFERS = 34852;

    @Inject(method = "_getInteger", at = @At("HEAD"), cancellable = true)
    private static void metallum$fakeGlLimitsWhileDormant(final int pname, final CallbackInfoReturnable<Integer> cir) {
        if (!MetalIrisCompat.holdIrisDormant()) {
            return;
        }
        cir.setReturnValue(switch (pname) {
            case GL_MAX_TEXTURE_IMAGE_UNITS -> 16;
            case GL_MAX_DRAW_BUFFERS -> 8;
            default -> 8;
        });
    }
}
