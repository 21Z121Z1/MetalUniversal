package com.metallum.mixin.iris;

import com.metallum.Metallum;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Foundational Iris&rarr;Metal integration intercept for individual GL shader
 * objects.
 *
 * <p>{@link GlShader}'s constructor invokes {@code super(createShader(...))}
 * where the private-static {@code createShader(ShaderType, String, String)}
 * performs the actual GL work: {@code glCreateShader}, {@code glShaderSource},
 * {@code glCompileShader} and a compile-status query. Because
 * {@code createShader} is evaluated as a {@code super(...)} argument it runs
 * <em>before</em> the constructor body, so the only Mixin-feasible way to
 * prevent those GL calls is to inject at {@code HEAD} of {@code createShader}
 * itself (one cannot cancel a constructor before {@code super()} in Mixin).
 *
 * <p>When the Metal backend is the <em>active</em> backend (see
 * {@link MetalActive}) this injector cancels {@code createShader} and returns a
 * sentinel handle ({@code 0}) instead, so no GL entry points are touched. The
 * resulting {@link GlShader} wraps a no-op handle. This keeps Iris's other
 * partial-init paths (e.g. standalone shader objects built outside
 * {@link ShaderCreator#link}) from issuing GL calls during the window before
 * the higher-level {@link ShaderCreatorMixin} guard fires.
 *
 * <p><b>What is deferred.</b> The real GLSL&rarr;MSL routing for these
 * standalone shader objects is deferred to the same follow-up described on
 * {@link ShaderCreatorMixin}: mapping the GLSL source onto
 * {@code MetalCrossShaderCompiler.compileShaderpack} (or an equivalent
 * single-stage Metal compile path) and feeding the resulting Metal shader back
 * to Iris's pipeline. Until that lands, Iris shaderpack rendering on Metal is
 * expected to fail loudly at {@link ShaderCreatorMixin} rather than silently
 * here.
 *
 * <p>The non-Metal path is unchanged: when {@link MetalActive#isMetalActive()}
 * returns {@code false} the injector returns immediately without cancelling, so
 * Iris compiles the shader through OpenGL exactly as before.
 */
@Mixin(GlShader.class)
public class GlShaderMixin {
    @Inject(method = "createShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipGlShaderCreateOnMetal(
            ShaderType type,
            String name,
            String src,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        Metallum.LOGGER.warn(
                "[MetalUniversal/Iris] Skipping GL compile of Iris shader '{}' (stage {}) on the Metal "
                        + "backend; GLSL->MSL routing for standalone GlShader objects is not yet implemented.",
                name, type
        );
        cir.setReturnValue(0);
    }
}
