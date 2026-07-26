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
    private static final int GL_VENDOR = 7936;
    private static final int GL_RENDERER = 7937;
    private static final int GL_VERSION = 7938;
    private static final int GL_SHADING_LANGUAGE_VERSION = 35724;
    private static final int GL_NUM_EXTENSIONS = 33309;
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
            // StandardMacros walks glGetStringi(GL_EXTENSIONS, 0..n-1) to export
            // MC_GL_EXT_* macros. Reporting zero extensions keeps that loop empty
            // instead of feeding it n fabricated names.
            case GL_NUM_EXTENSIONS -> 0;
            default -> 8;
        });
    }

    /**
     * {@code StandardMacros.createStandardEnvironmentDefines} builds the pack
     * preprocessor environment from {@code glGetString}:
     * {@code GL_VERSION}/{@code GL_SHADING_LANGUAGE_VERSION} are parsed by a
     * semver regex into {@code MC_GL_VERSION}/{@code MC_GLSL_VERSION}, and
     * {@code GL_VENDOR}/{@code GL_RENDERER} are substring-matched into one
     * {@code MC_GL_VENDOR_*}/{@code MC_GL_RENDERER_*} macro.
     *
     * <p>The values below are the same pinned GL 4.6 environment the offline
     * translation matrix uses (see the test-classpath shadow at
     * {@code src/test/java/net/irisshaders/iris/gl/shader/StandardMacros.java}),
     * so a pack that translates offline sees an identical environment in game.
     * Neither vendor nor renderer string matches any of Iris's known-hardware
     * substrings, so both land on {@code *_OTHER} — deliberate: we do not want
     * packs taking vendor-specific GL code paths on a Metal device.</p>
     */
    @Inject(method = "_getString", at = @At("HEAD"), cancellable = true)
    private static void metallum$fakeGlStringsWhileDormant(final int pname, final CallbackInfoReturnable<String> cir) {
        if (!MetalIrisCompat.holdIrisDormant()) {
            return;
        }
        cir.setReturnValue(switch (pname) {
            case GL_VENDOR -> "Metallum";
            case GL_RENDERER -> "Metallum Metal";
            case GL_VERSION, GL_SHADING_LANGUAGE_VERSION -> "4.6.0";
            default -> "";
        });
    }
}
