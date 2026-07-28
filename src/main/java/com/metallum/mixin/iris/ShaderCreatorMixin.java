package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalCrossShaderCompiler;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris&rarr;Metal shaderpack integration intercept.
 *
 * <p>{@link ShaderCreator#link} is the single public-static entry point in Iris
 * 26.2 that assembles a shaderpack program: it calls {@code glCreateProgram},
 * compiles each stage via {@code glCreateShader}/{@code glShaderSource}/
 * {@code glCompileShader}, attaches them, binds the vertex attributes and
 * finally calls {@code glLinkProgram}, returning a {@link PartialShader} that
 * wraps the GL program handle. On the Metal backend none of those GL calls are
 * valid (there is no GL context), so letting Iris run this path would produce a
 * cascade of silent GL failures.
 *
 * <p>When the Metal backend is <em>active</em> (see {@link MetalActive}), this
 * mixin intercepts {@code link} at {@code HEAD} and constructs a real Metal
 * render pipeline via
 * {@link MetalCrossShaderCompiler#compileShaderpackPipeline}. That method:
 * <ul>
 *   <li>compiles the (already TransformPatcher-patched, {@code #include}-
 *       expanded) GLSL through glslang&#8594;SPIR-V&#8594;SPIRV-Cross&#8594;MSL;</li>
 *   <li>constructs a {@code MetalCompiledRenderPipeline} (Metal pipeline state
 *       object) using the active {@code MetalDevice} from
 *       {@code MetalDeviceRegistry};</li>
 *   <li>caches the pipeline under the program name in
 *       {@code MetalCrossShaderCompiler.SHADERPACK_PIPELINE_CACHE} for retrieval
 *       by the forthcoming render-dispatch step.</li>
 * </ul>
 *
 * <p>After successful pipeline construction the mixin returns a
 * {@link PartialShader} wrapping sentinel GL handles ({@code 0}). This allows
 * Iris's {@code ShaderCreator.create} to proceed to {@code ExtendedShader}
 * construction. The {@code ExtendedShader} constructor will still attempt GL
 * calls with the sentinel handle; intercepting that is the next integration
 * phase (the Metal render-dispatch path that retrieves the cached pipeline and
 * issues Metal draw calls instead of GL ones).
 *
 * <p>If pipeline construction fails, the mixin wraps the blaze3d
 * {@code ShaderCompileException} (carrying glslang/SPIRV-Cross diagnostics) in
 * Iris's {@link ShaderCompileException} and throws it, so the failure surfaces
 * as a shaderpack compile error with a real diagnostic message.
 *
 * <p><b>Geometry / tessellation stages.</b> {@code link}'s signature accepts
 * geometry/tessControl/tessEval source strings. The current Metal pipeline is
 * vertex+fragment only; they are skipped (logging a warning). Their handling is
 * deferred to a future Metal tessellation/geometry pipeline.
 *
 * <p>When Metal is <em>not</em> active the mixin is a complete no-op and Iris's
 * GL path is untouched.
 */
@Environment(EnvType.CLIENT)
@Mixin(ShaderCreator.class)
public class ShaderCreatorMixin {
    @Inject(method = "link", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$redirectLinkOnMetal(
            String name,
            String vertex,
            String geometry,
            String tessControl,
            String tessEval,
            String fragment,
            VertexFormat vertexFormat,
            boolean isFallback,
            CallbackInfoReturnable<PartialShader> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }

        // Geometry/tessellation stages have no Metal equivalent in the current
        // vertex+fragment pipeline; warn and proceed with vertex+fragment only.
        if (geometry != null || tessControl != null || tessEval != null) {
            Metallum.LOGGER.warn(
                    "[MetalUniversal/Iris] Shaderpack program '{}' declares geometry/tessellation stages, "
                            + "which have no Metal equivalent in the current vertex+fragment pipeline; "
                            + "they are skipped.",
                    name
            );
        }

        // Construct a real Metal render pipeline (GLSL→SPIR-V→MSL→pipeline
        // state object) and cache it under `name`. defines=null: the GLSL
        // arriving here is already patched by Iris's TransformPatcher (includes
        // expanded, macros evaluated by JCPP, environment defines applied).
        // GlslangBridge still injects its COMPAT_PREAMBLE (IS_IRIS/MC_VERSION/
        // ...) but those macros are fresh defines and are harmless.
        // enablePointSize=false: POINTS topology is rare in shaderpacks; the
        // forthcoming render-dispatch step can reconstruct the pipeline with
        // point-size enabled if needed.
        final boolean compiled;
        try {
            compiled = MetalCrossShaderCompiler.compileShaderpackPipeline(
                    name, vertex, fragment, null, vertexFormat, false
            );
        } catch (Exception e) {
            Metallum.LOGGER.error(
                    "[MetalUniversal/Iris] Pipeline construction failed for shaderpack program '{}': {}",
                    name, e.getMessage(), e
            );
            // Wrap in Iris's ShaderCompileException so it surfaces as a
            // shaderpack compile error with real diagnostics.
            throw new ShaderCompileException(name, e);
        }

        if (!compiled) {
            // MetalDevice not available — should not happen since
            // MetalActive.isMetalActive() returned true, but guard anyway.
            throw new IllegalStateException(
                    "MetalActive reported Metal backend, but MetalDeviceRegistry has no active device; "
                            + "cannot construct pipeline for shaderpack program '" + name + "'."
            );
        }

        Metallum.LOGGER.info(
                "[MetalUniversal/Iris] Constructed and cached Metal render pipeline for shaderpack program '{}'; "
                        + "returning a no-op PartialShader. The Metal render-dispatch path is not yet wired, "
                        + "so Iris ExtendedShader construction will fail next.",
                name
        );

        // Return a PartialShader wrapping sentinel GL handles (0). The Metal
        // pipeline is cached in MetalCrossShaderCompiler.SHADERPACK_PIPELINE_CACHE
        // for retrieval by the forthcoming render-dispatch step. The
        // ExtendedShader constructor will still attempt GL calls with handle 0;
        // intercepting that is the next integration phase.
        cir.setReturnValue(new PartialShader(0, 0, 0, -1, -1, -1));
    }
}
