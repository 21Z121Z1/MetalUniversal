package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalCrossShaderCompiler;
import com.metallum.client.metal.render.MetalCrossShaderCompiler.ShaderpackMslResult;
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
 * mixin intercepts {@code link} at {@code HEAD} and performs a <b>dry-compile</b>
 * of the shaderpack program's GLSL through the full
 * glslang&#8594;SPIRV-Cross&#8594;MSL pipeline via
 * {@link MetalCrossShaderCompiler#tryCompileShaderpackMsl}. The dry-compile:
 * <ul>
 *   <li>validates that the (already TransformPatcher-patched,
 *       {@code #include}-expanded) GLSL cross-compiles to valid MSL;</li>
 *   <li>caches the resulting MSL sources and entry-point names in
 *       {@link MetalCrossShaderCompiler#getCachedShaderpackMsl} for retrieval
 *       by the forthcoming pipeline-binding step;</li>
 *   <li>logs success (MSL source lengths) or failure (with glslang/SPIRV-Cross
 *       diagnostics) to the {@link Metallum} logger.</li>
 * </ul>
 *
 * <p>After a successful dry-compile the mixin still throws
 * {@link UnsupportedOperationException}, because returning a {@link PartialShader}
 * requires a GL program handle and the full Metal pipeline state (vertex format
 * bindings, depth/stencil/color targets, sampler/uniform bind-group entries)
 * is not yet assembled from Iris's {@code ProgramSource}/{@code ProgramDirectives}.
 * The cached MSL is the bridge to that next step: once a {@code MetalDevice}
 * accessor and the Iris&rarr;Metal binding mapping exist, the pipeline-binding
 * step can retrieve the cached MSL via
 * {@link MetalCrossShaderCompiler#getCachedShaderpackMsl} and construct a
 * {@code MetalCompiledRenderPipeline} without recompiling.
 *
 * <p>If the dry-compile fails, the mixin wraps the blaze3d
 * {@code ShaderCompileException} (carrying glslang/SPIRV-Cross diagnostics) in
 * Iris's {@link ShaderCompileException} and throws it, so the failure surfaces
 * as a shaderpack compile error with a real diagnostic message instead of a
 * generic "not wired" message.
 *
 * <p><b>Geometry / tessellation stages.</b> {@code link}'s signature accepts
 * geometry/tessControl/tessEval source strings. The current Metal pipeline is
 * vertex+fragment only; {@code tryCompileShaderpackMsl} accepts these stages
 * for API symmetry but skips them (logging a warning). Their handling is
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

        // Dry-compile the shaderpack GLSL through glslang→SPIRV-Cross→MSL.
        // defines=null: the GLSL arriving here is already patched by Iris's
        // TransformPatcher (includes expanded, macros evaluated by JCPP,
        // environment defines applied). GlslangBridge still injects its
        // COMPAT_PREAMBLE (IS_IRIS/MC_VERSION/...) but those macros are fresh
        // defines (the patched source has no #define directives left) and are
        // harmless.
        final ShaderpackMslResult result;
        try {
            result = MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                    name, vertex, geometry, tessControl, tessEval, fragment, null
            );
        } catch (Exception e) {
            Metallum.LOGGER.error(
                    "[MetalUniversal/Iris] Dry-compile failed for shaderpack program '{}': {}",
                    name, e.getMessage(), e
            );
            // Wrap in Iris's ShaderCompileException so it surfaces as a
            // shaderpack compile error with real diagnostics.
            throw new ShaderCompileException(name, e);
        }

        Metallum.LOGGER.info(
                "[MetalUniversal/Iris] Dry-compiled shaderpack program '{}' to MSL "
                        + "(vertex={} chars, fragment={} chars, entryPoints={}/{}) and cached it; "
                        + "Metal pipeline binding is not yet wired, refusing to return a GL program handle.",
                name,
                result.vertexMsl().length(),
                result.fragmentMsl().length(),
                result.vertexEntryPoint(),
                result.fragmentEntryPoint()
        );
        throw new UnsupportedOperationException(
                "Iris shaderpack program '" + name + "' was dry-compiled to MSL and cached "
                        + "(see MetalCrossShaderCompiler.getCachedShaderpackMsl), but the Metal pipeline "
                        + "binding (MetalDevice accessor + Iris bind-group/vertex-format/depth-stencil "
                        + "mapping) is not yet wired. Shaderpack rendering on Metal is unavailable until "
                        + "that step is implemented."
        );
    }
}
