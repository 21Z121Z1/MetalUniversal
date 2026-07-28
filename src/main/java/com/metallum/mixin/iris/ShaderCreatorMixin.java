package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Foundational Iris&rarr;Metal integration intercept for shaderpack programs.
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
 * <p>This mixin {@code @Inject}s at {@code HEAD} of {@code link} and, when the
 * Metal backend is the <em>active</em> backend (see {@link MetalActive}), throws
 * an {@link UnsupportedOperationException} with a clear message naming the
 * intended replacement ({@code MetalCrossShaderCompiler.compileShaderpack}).
 * This makes the integration boundary explicit and turns a silent failure into a
 * loud, diagnosable one. When Metal is <em>not</em> active the mixin is a
 * complete no-op and Iris's GL path is untouched.
 *
 * <p><b>What is deferred.</b> The actual routing of Iris shaderpack programs to
 * {@code MetalCrossShaderCompiler.compileShaderpack(device, name, vertexGlsl,
 * fragmentGlsl, defines, bindGroupEntries, vertexAttributeFormats,
 * enablePointSize, cull, polygonMode, primitiveTopology, vertexFormatBindings,
 * depthStencilState, colorTarget)} is the next step and is intentionally NOT
 * done here. It requires mapping Iris's {@code ProgramSource} /
 * {@code ProgramDirectives} / {@code TransformPatcher} output onto
 * {@code compileShaderpack}'s arguments, in particular:
 * <ul>
 *   <li>building the {@code List<VulkanBindGroupLayout.Entry>} from Iris's
 *       sampler/uniform declarations and the shaderpack's texture map;</li>
 *   <li>deriving {@code Map<String,GpuFormat> vertexAttributeFormats} and the
 *       {@code VertexFormat[]} bindings from the Iris {@code VertexFormat}
 *       (and its {@code VertexFormatExtension} attribute binding);</li>
 *   <li>translating Iris {@code AlphaTest}/{@code BlendModeOverride}/
 *       {@code FogMode} and the target color/depth attachments into
 *       {@code DepthStencilState}/{@code ColorTargetState};</li>
 *   <li>obtaining the active {@code MetalDevice} to pass as {@code device};</li>
 *   <li>choosing {@code polygonMode}/{@code primitiveTopology}/{@code cull}/
 *       {@code enablePointSize} from the program context (e.g. shadow pass,
 *       lines, POINTS topology).</li>
 * </ul>
 * Geometry/tessellation stages (present in {@code link}'s signature) have no
 * Metal equivalent in {@code compileShaderpack} (which is vertex+fragment only);
 * their handling must also be designed as part of that follow-up.
 *
 * <p>The non-Metal path is unchanged: when {@link MetalActive#isMetalActive()}
 * returns {@code false} the injector returns immediately without cancelling, so
 * Iris compiles and links the program through OpenGL exactly as before.
 */
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
        Metallum.LOGGER.error(
                "[MetalUniversal/Iris] Refusing to GL-link Iris shaderpack program '{}' on the Metal "
                        + "backend: there is no GL context on Metal. Iris shaderpack rendering on the "
                        + "Metal backend is not yet wired through ShaderCreator.link; see "
                        + "MetalCrossShaderCompiler.compileShaderpack.",
                name
        );
        throw new UnsupportedOperationException(
                "Iris shaderpack rendering on Metal backend is not yet wired through "
                        + "ShaderCreator.link; see MetalCrossShaderCompiler.compileShaderpack "
                        + "(program '" + name + "')"
        );
    }
}
