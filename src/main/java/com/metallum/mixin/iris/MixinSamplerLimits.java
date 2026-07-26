package com.metallum.mixin.iris;

import com.metallum.client.metal.iris.MetalIrisBridge;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stubs out {@code GlStateManager._getInteger} calls inside
 * {@link SamplerLimits}'s constructor on non-OpenGL backends.
 *
 * <p><b>Root cause:</b> {@code IrisRenderSystem.<clinit>} (line 45) eagerly
 * calls {@code SamplerLimits.get()}, which constructs a {@link SamplerLimits}
 * instance. The constructor (lines 15-17) calls
 * {@code GlStateManager._getInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS)} and
 * {@code GlStateManager._getInteger(GL20C.GL_MAX_DRAW_BUFFERS)} — both are
 * native GL queries that require a live OpenGL context. On a Metal backend
 * there is no GL context, so these calls crash with
 * {@code IllegalStateException: No GLCapabilities instance set for the current
 * thread}, causing {@code ExceptionInInitializerError} in
 * {@code IrisRenderSystem}.
 *
 * <p>The companion {@link MixinIrisRenderSystem} handles the
 * {@code supportsSSBO()} call (line 17) by short-circuiting it to
 * {@code false}. But lines 15-16 are <b>direct</b> calls to
 * {@code GlStateManager._getInteger} — they cannot be short-circuited by
 * redirecting {@code supportsSSBO()} alone.
 *
 * <p>This mixin {@link Redirect}s every
 * {@code GlStateManager._getInteger(int)} invocation inside
 * {@code SamplerLimits.<init>} and returns safe Metal-appropriate defaults
 * when {@link MetalIrisBridge#isNonGlBackend()} returns {@code true} (or when
 * the GL call itself would throw):
 *
 * <table>
 *   <tr><th>GL constant</th><th>Value</th><th>Stub return</th><th>Reason</th></tr>
 *   <tr><td>{@code GL_MAX_TEXTURE_IMAGE_UNITS}</td><td>{@code 0x8D63}</td>
 *       <td>32</td><td>Metal guarantees ≥31 texture units per stage; 32 is the
 *           typical Metal limit and matches what Iris expects on desktop GL.</td></tr>
 *   <tr><td>{@code GL_MAX_DRAW_BUFFERS}</td><td>{@code 0x8824}</td>
 *       <td>8</td><td>Metal supports 8 MRT color attachments (matching the
 *           default framebuffer of Iris composite passes).</td></tr>
 *   <tr><td>{@code GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS}</td><td>{@code 0x90DD}</td>
 *       <td>8</td><td>Only queried when {@code supportsSSBO()} returns true;
 *           stubbed for completeness.</td></tr>
 * </table>
 *
 * <p><b>Why not redirect {@code SamplerLimits.get()} entirely?</b> The
 * constructor is private, so we cannot construct a stub instance from outside.
 * Redirecting the GL calls inside the constructor is the most surgical fix —
 * it lets the constructor complete normally and populate the three
 * {@code final} fields with safe values.
 *
 * <p>{@code remap = false} on {@code @Mixin} because {@link SamplerLimits} is
 * an Iris class. The {@code @Redirect}'s {@code @At} targets
 * {@code GlStateManager._getInteger} which is a Mojang method — with the
 * default {@code remap = true} on {@code @At}, Fabric Loom remaps the
 * method name from the development mapping ({@code _getInteger}) to the
 * production obfuscated name at build time.
 */
@Mixin(SamplerLimits.class)
public class MixinSamplerLimits {

    /**
     * Redirects {@code GlStateManager._getInteger(int)} inside
     * {@code SamplerLimits.<init>}. Returns Metal-appropriate defaults on
     * non-GL backends or when the GL call would throw.
     */
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_getInteger(I)I")
    )
    private int metallum$stubGetIntegerOnNonGl(int pname) {
        if (MetalIrisBridge.isNonGlBackend()) {
            return stubGetInteger(pname);
        }
        // Defensive: even on a "GL" backend, the GL context may not be current
        // during early boot (when IrisRenderSystem.<clinit> fires before the
        // Metal device replaces the temporary GL device). Catch the failure
        // and return safe defaults instead of crashing <clinit>.
        try {
            return GlStateManager._getInteger(pname);
        } catch (Throwable t) {
            return stubGetInteger(pname);
        }
    }

    /**
     * Returns safe Metal-appropriate defaults for the GL_MAX_* queries that
     * {@code SamplerLimits.<init>} performs.
     */
    private static int stubGetInteger(int pname) {
        if (pname == GL20C.GL_MAX_TEXTURE_IMAGE_UNITS) return 32;
        if (pname == GL20C.GL_MAX_DRAW_BUFFERS) return 8;
        if (pname == GL45C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) return 8;
        // GL30C.GL_MAX_COLOR_ATTACHMENTS and other queries not used by
        // SamplerLimits, but return a safe non-zero default just in case.
        return 8;
    }
}
