package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translation front-end for Iris shader-pack programs on the Metal backend
 * (B2-2). One program travels:
 *
 * <pre>
 * pack GLSL (ProgramSource, already include-resolved + jcpp-preprocessed)
 *   -> Iris TransformPatcher (glsl-transformer AST; same patcher Iris feeds
 *      to glShaderSource on the GL backend — core-profile output, gl_FragData
 *      and legacy built-ins rewritten, iris_* attributes/uniforms introduced)
 *   -> loose-uniform wrapping (below)
 *   -> shaderc, Vulkan 1.2 semantics with auto binding/location assignment
 *   -> SPIRV-Cross MSL backend with the same options as
 *      {@link MetalCrossShaderCompiler} (MSL 4.0, macOS, decoration binding,
 *      FLIP_VERTEX_Y)
 * </pre>
 *
 * <p><b>Loose-uniform wrapping.</b> Patched pack sources keep the GL model of
 * hundreds of default-block uniforms ({@code uniform mat4 gbufferModelView;}),
 * which Vulkan-semantics GLSL rejects. All non-opaque global uniforms are
 * therefore collected into one {@code layout(std140) uniform
 * MetallumIrisUniforms} block; member access syntax is unchanged, so the
 * shader body compiles untouched. Initializers are dropped (Iris supplies
 * every uniform each frame on the GL path; the Metal uniform provider will do
 * the same). Opaque types (samplers/images) stay put and receive bindings via
 * shaderc's auto-binding.</p>
 *
 * <p><b>Interface locations.</b> Vertex outputs and fragment inputs get
 * auto-assigned locations per stage. shaderc assigns them in declaration
 * order, which matches between stages for glsl-transformer output (both
 * stages emit the shared varyings in source order), but this is not a
 * guaranteed invariant; the PSO-link step of B2-3 must pair stages by name
 * and inject explicit locations before trusting draws.</p>
 *
 * <p>Class notes: this class references Iris types and must only be loaded
 * when Iris is on the classpath (B2 code paths and the translation test).
 * Stage validation on the actual device happens in the caller via
 * {@link MetalDevice#getOrCompileFunction(String, String)}.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalIrisShaderCompiler {
    private static final int MSL_VERSION_4_0 = 0x040000;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern KERNEL_ENTRY_PATTERN = Pattern.compile("\\bkernel\\s+\\w+\\s+(\\w+)\\s*\\(");
    /**
     * A global-scope loose uniform statement: everything from {@code uniform}
     * to the terminating semicolon, provided no brace intervenes (which would
     * make it a uniform block, left untouched).
     */
    private static final Pattern UNIFORM_STATEMENT_PATTERN = Pattern.compile("(?m)^[ \\t]*uniform\\b([^;{}]*);");
    private static final Pattern OPAQUE_TYPE_PATTERN = Pattern.compile("[iu]?(sampler|image|texture)\\w*|atomic_uint");
    private static final Set<String> PRECISION_QUALIFIERS = Set.of("lowp", "mediump", "highp");
    private static final String UNIFORM_BLOCK_NAME = "MetallumIrisUniforms";
    /**
     * Identifiers that are legal in GL-dialect GLSL but collide with keywords
     * further down the chain, seen in real packs: {@code sampler} is a
     * Vulkan-GLSL type keyword (Potato: {@code textureBicubic(sampler2D
     * sampler, ...)} fails in glslang), and C++/MSL keywords pass SPIRV-Cross
     * unrenamed into invalid MSL (BSL: {@code bool new = ...}). Renamed
     * wholesale before compilation; {@code texture} cannot be treated this
     * way because it is also the GLSL builtin sampling function.
     */
    private static final Pattern HOSTILE_IDENTIFIER_PATTERN = Pattern.compile(
            "\\b(new|delete|this|template|typename|namespace|operator|private|public|protected|virtual"
                    + "|using|mutable|friend|extern|register|typedef|union|enum|auto|char|short|signed"
                    + "|unsigned|class|constexpr|nullptr|throw|try|catch|kernel|device|constant|thread"
                    + "|threadgroup|half|sampler)\\b"
    );

    private MetalIrisShaderCompiler() {
    }

    enum StageKind {
        VERTEX(Shaderc.shaderc_glsl_vertex_shader, VERTEX_ENTRY_PATTERN),
        FRAGMENT(Shaderc.shaderc_glsl_fragment_shader, FRAGMENT_ENTRY_PATTERN),
        COMPUTE(Shaderc.shaderc_glsl_compute_shader, KERNEL_ENTRY_PATTERN);

        final int shadercKind;
        final Pattern entryPattern;

        StageKind(final int shadercKind, final Pattern entryPattern) {
            this.shadercKind = shadercKind;
            this.entryPattern = entryPattern;
        }
    }

    /** Phase names used in {@link TranslationException} for per-stage failure attribution. */
    static final String PHASE_PATCH = "iris-patch";
    static final String PHASE_WRAP = "uniform-wrap";
    static final String PHASE_GLSL_TO_SPIRV = "glsl->spirv";
    static final String PHASE_SPIRV_TO_MSL = "spirv->msl";
    static final String PHASE_UNSUPPORTED_STAGE = "unsupported-stage";

    record TranslatedStage(
            StageKind kind,
            String patchedGlsl,
            String wrappedGlsl,
            String msl,
            String entryPoint,
            List<String> blockedUniforms,
            boolean forcedVersion450
    ) {
    }

    record TranslatedProgram(
            String name,
            Optional<TranslatedStage> vertex,
            Optional<TranslatedStage> fragment,
            Optional<TranslatedStage> compute
    ) {
    }

    static final class TranslationException extends RuntimeException {
        private final String programName;
        private final String phase;
        private final @Nullable StageKind stageKind;
        /** Offending intermediate source (wrapped GLSL), when the failing phase had one. */
        private @Nullable String sourceDump;

        TranslationException(final String programName, final String phase, final @Nullable StageKind stageKind, final String message) {
            this(programName, phase, stageKind, message, null);
        }

        TranslationException(
                final String programName,
                final String phase,
                final @Nullable StageKind stageKind,
                final String message,
                final @Nullable Throwable cause
        ) {
            super("[" + programName + (stageKind != null ? "/" + stageKind : "") + "] " + phase + ": " + message, cause);
            this.programName = programName;
            this.phase = phase;
            this.stageKind = stageKind;
        }

        @Nullable
        String sourceDump() {
            return sourceDump;
        }

        String programName() {
            return programName;
        }

        String phase() {
            return phase;
        }

        @Nullable
        StageKind stageKind() {
            return stageKind;
        }
    }

    /** composite / deferred / prepare / begin / shadowcomp / final family. */
    static TranslatedProgram translateComposite(
            final String name,
            final String vertexSource,
            final @Nullable String geometrySource,
            final String fragmentSource,
            final TextureStage stage
    ) {
        rejectUnsupportedStages(name, geometrySource, null, null);
        Map<PatchShaderType, String> patched;
        try {
            patched = TransformPatcher.patchComposite(name, vertexSource, null, fragmentSource, stage, emptyTextureMap());
        } catch (TranslationException e) {
            throw e;
        } catch (Throwable t) {
            throw new TranslationException(name, PHASE_PATCH, null, String.valueOf(t.getMessage()), t);
        }
        return translatePatchedPair(name, patched);
    }

    /** gbuffers_* / shadow family via the vanilla-format patcher. */
    static TranslatedProgram translateVanillaGbuffers(final String name, final ProgramSource source) {
        rejectUnsupportedStages(
                name,
                source.getGeometrySource().orElse(null),
                source.getTessControlSource().orElse(null),
                source.getTessEvalSource().orElse(null)
        );
        String vertex = source.getVertexSource().orElseThrow(
                () -> new TranslationException(name, PHASE_PATCH, StageKind.VERTEX, "missing vertex source"));
        String fragment = source.getFragmentSource().orElseThrow(
                () -> new TranslationException(name, PHASE_PATCH, StageKind.FRAGMENT, "missing fragment source"));
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(AlphaTest.ALWAYS);
        // Attribute inputs mirror the fullest vanilla vertex layout (color, uv,
        // overlay, light, normal); the exact per-ShaderKey inputs arrive with
        // the B2 pipeline-override work. Booleans follow Iris's own call site:
        // (isLines, isClouds, hasChunkOffset).
        ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, true, true, true);
        Map<PatchShaderType, String> patched;
        try {
            patched = TransformPatcher.patchVanilla(
                    name, vertex, null, null, null, fragment,
                    alpha, false, false, true, inputs, emptyTextureMap()
            );
        } catch (TranslationException e) {
            throw e;
        } catch (Throwable t) {
            throw new TranslationException(name, PHASE_PATCH, null, String.valueOf(t.getMessage()), t);
        }
        return translatePatchedPair(name, patched);
    }

    /** setup / shadowcomp / per-stage compute arrays ({@code .csh}). */
    static TranslatedProgram translateCompute(final String name, final String computeSource, final TextureStage stage) {
        String patched;
        try {
            patched = TransformPatcher.patchCompute(name, computeSource, stage, emptyTextureMap());
        } catch (Throwable t) {
            throw new TranslationException(name, PHASE_PATCH, StageKind.COMPUTE, String.valueOf(t.getMessage()), t);
        }
        TranslatedStage cs = translateStage(name, StageKind.COMPUTE, patched);
        return new TranslatedProgram(name, Optional.empty(), Optional.empty(), Optional.of(cs));
    }

    private static TranslatedProgram translatePatchedPair(final String name, final Map<PatchShaderType, String> patched) {
        String vertex = patched.get(PatchShaderType.VERTEX);
        String fragment = patched.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            throw new TranslationException(
                    name, PHASE_PATCH, null,
                    "patcher returned stages " + patched.keySet() + " (need VERTEX+FRAGMENT)"
            );
        }
        TranslatedStage vs = translateStage(name, StageKind.VERTEX, vertex);
        TranslatedStage fs = translateStage(name, StageKind.FRAGMENT, fragment);
        return new TranslatedProgram(name, Optional.of(vs), Optional.of(fs), Optional.empty());
    }

    static TranslatedStage translateStage(final String name, final StageKind kind, final String patchedGlsl) {
        WrappedGlsl wrapped;
        try {
            wrapped = wrapLooseUniforms(patchedGlsl);
        } catch (RuntimeException e) {
            TranslationException te = new TranslationException(name, PHASE_WRAP, kind, String.valueOf(e.getMessage()), e);
            te.sourceDump = patchedGlsl;
            throw te;
        }
        SpirvResult spirv;
        String msl;
        try {
            spirv = glslToSpirv(name, kind, wrapped.source());
            msl = spirvToMsl(name, kind, spirv.spirv());
        } catch (TranslationException e) {
            e.sourceDump = wrapped.source();
            throw e;
        }
        Matcher entry = kind.entryPattern.matcher(msl);
        String entryPoint = entry.find() ? entry.group(1) : "main0";
        return new TranslatedStage(
                kind, patchedGlsl, wrapped.source(), msl, entryPoint,
                wrapped.blockedUniforms(), spirv.forcedVersion450()
        );
    }

    private static void rejectUnsupportedStages(
            final String name,
            final @Nullable String geometry,
            final @Nullable String tessControl,
            final @Nullable String tessEval
    ) {
        if (geometry != null) {
            throw new TranslationException(name, PHASE_UNSUPPORTED_STAGE, null,
                    "geometry shaders have no Metal equivalent (mesh-shader emulation is out of scope)");
        }
        if (tessControl != null || tessEval != null) {
            throw new TranslationException(name, PHASE_UNSUPPORTED_STAGE, null,
                    "tessellation shaders are not supported on the Metal backend");
        }
    }

    private static Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> emptyTextureMap() {
        return new Object2ObjectOpenHashMap<>();
    }

    // ------------------------------------------------------------------
    // Loose-uniform wrapping
    // ------------------------------------------------------------------

    record WrappedGlsl(String source, List<String> blockedUniforms) {
    }

    static WrappedGlsl wrapLooseUniforms(final String glsl) {
        String src = renameHostileIdentifiers(stripComments(glsl));
        Matcher matcher = UNIFORM_STATEMENT_PATTERN.matcher(src);
        StringBuilder body = new StringBuilder(src.length());
        List<String> members = new ArrayList<>();
        Set<String> memberNames = new LinkedHashSet<>();
        int last = 0;
        while (matcher.find()) {
            String statement = matcher.group(1).trim();
            List<String> tokens = leadingTokens(statement);
            int typeIndex = 0;
            while (typeIndex < tokens.size() && PRECISION_QUALIFIERS.contains(tokens.get(typeIndex))) {
                typeIndex++;
            }
            if (typeIndex >= tokens.size()) {
                continue;
            }
            String type = tokens.get(typeIndex);
            if (OPAQUE_TYPE_PATTERN.matcher(type).matches()) {
                continue; // samplers/images stay loose; shaderc auto-binds them
            }
            int declaratorsStart = statement.indexOf(type) + type.length();
            String declarators = statement.substring(declaratorsStart);
            body.append(src, last, matcher.start());
            last = matcher.end();
            for (String declarator : splitTopLevel(declarators)) {
                String member = parseDeclarator(type, declarator);
                if (member == null) {
                    throw new IllegalStateException("Cannot parse uniform declarator '" + declarator + "' (type " + type + ")");
                }
                String memberName = member.substring(member.indexOf(' ') + 1).replaceAll("\\[.*", "");
                if (memberNames.add(memberName)) {
                    members.add(member);
                }
            }
        }
        if (members.isEmpty()) {
            return new WrappedGlsl(src, List.of());
        }
        body.append(src, last, src.length());

        StringBuilder block = new StringBuilder("layout(std140) uniform " + UNIFORM_BLOCK_NAME + " {\n");
        for (String member : members) {
            block.append("    ").append(member).append(";\n");
        }
        block.append("};\n");

        String rewritten = body.toString();
        int insertAt = directivePreludeEnd(rewritten);
        String out = rewritten.substring(0, insertAt) + block + rewritten.substring(insertAt);
        List<String> names = new ArrayList<>(memberNames);
        return new WrappedGlsl(out, List.copyOf(names));
    }

    /** First few whitespace-separated identifiers of a declaration head. */
    private static List<String> leadingTokens(final String statement) {
        List<String> tokens = new ArrayList<>(4);
        Matcher m = Pattern.compile("[A-Za-z_]\\w*").matcher(statement);
        while (m.find() && tokens.size() < 4) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /** Split declarators on commas that sit outside parens/brackets (initializers may contain calls). */
    private static List<String> splitTopLevel(final String declarators) {
        List<String> parts = new ArrayList<>(2);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < declarators.length(); i++) {
            char c = declarators.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(declarators.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(declarators.substring(start));
        return parts;
    }

    /** {@code name[expr] = init} -> {@code "type name[expr]"}; initializers dropped. */
    @Nullable
    private static String parseDeclarator(final String type, final String declarator) {
        Matcher m = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*)").matcher(declarator);
        if (!m.find() || m.group(1).isEmpty()) {
            return null;
        }
        String arrays = m.group(2).replaceAll("\\s+", "");
        return type + " " + m.group(1) + arrays;
    }

    /** Index just past the leading run of blank / preprocessor-directive lines. */
    private static int directivePreludeEnd(final String source) {
        int index = 0;
        int length = source.length();
        while (index < length) {
            int lineEnd = source.indexOf('\n', index);
            if (lineEnd < 0) {
                lineEnd = length - 1;
            }
            String line = source.substring(index, lineEnd + 1).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return index;
            }
            index = lineEnd + 1;
        }
        return index;
    }

    /** Whole-word rename of {@link #HOSTILE_IDENTIFIER_PATTERN} matches (declaration and use sites alike). */
    static String renameHostileIdentifiers(final String source) {
        return HOSTILE_IDENTIFIER_PATTERN.matcher(source).replaceAll("metallum_id_$1");
    }

    /** Replace comments with spaces (newlines preserved so diagnostics keep line numbers). */
    static String stripComments(final String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < length && !(source.charAt(i) == '*' && i + 1 < length && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < length) {
                    out.append("  ");
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // GLSL -> SPIR-V -> MSL
    // ------------------------------------------------------------------

    private record SpirvResult(ByteBuffer spirv, boolean forcedVersion450) {
    }

    private static SpirvResult glslToSpirv(final String name, final StageKind kind, final String source) {
        String firstError = null;
        for (boolean force450 : new boolean[]{false, true}) {
            long compiler = Shaderc.shaderc_compiler_initialize();
            long options = Shaderc.shaderc_compile_options_initialize();
            if (compiler == 0L || options == 0L) {
                throw new TranslationException(name, PHASE_GLSL_TO_SPIRV, kind, "failed to initialize shaderc");
            }
            try {
                Shaderc.shaderc_compile_options_set_target_env(
                        options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2
                );
                // Pack sources have GL-style resource declarations: no explicit
                // bindings or interface locations. Let shaderc assign both.
                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
                if (force450) {
                    // Retry lane for sources whose declared #version predates
                    // what glslang accepts under Vulkan semantics.
                    Shaderc.shaderc_compile_options_set_forced_version_profile(
                            options, 450, Shaderc.shaderc_profile_core
                    );
                }
                long result = Shaderc.shaderc_compile_into_spv(
                        compiler, source, kind.shadercKind, name, "main", options
                );
                try {
                    int status = Shaderc.shaderc_result_get_compilation_status(result);
                    if (status != Shaderc.shaderc_compilation_status_success) {
                        String message = Shaderc.shaderc_result_get_error_message(result);
                        if (firstError == null) {
                            firstError = message;
                        }
                        continue;
                    }
                    ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                    if (bytes == null || bytes.remaining() < 20) {
                        throw new TranslationException(name, PHASE_GLSL_TO_SPIRV, kind, "shaderc produced empty SPIR-V");
                    }
                    ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(bytes.order());
                    copy.put(bytes.duplicate());
                    copy.flip();
                    return new SpirvResult(copy, force450);
                } finally {
                    Shaderc.shaderc_result_release(result);
                }
            } finally {
                Shaderc.shaderc_compile_options_release(options);
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
        throw new TranslationException(name, PHASE_GLSL_TO_SPIRV, kind, String.valueOf(firstError));
    }

    private static String spirvToMsl(final String name, final StageKind kind, final ByteBuffer spirvBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            int wordCount = spirvWords.remaining();

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(name, kind, Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(name, kind, Spvc.spvc_context_parse_spirv(context, spirvWords, wordCount, pIr), "spvc_context_parse_spirv");
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(name, kind, Spvc.spvc_context_create_compiler(
                        context, Spvc.SPVC_BACKEND_MSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                ), "spvc_context_create_compiler");
                long compiler = pCompiler.get(0);

                PointerBuffer pOptions = stack.mallocPointer(1);
                checkSpvc(name, kind, Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "spvc_compiler_create_compiler_options");
                long options = pOptions.get(0);
                checkSpvc(name, kind, Spvc.spvc_compiler_options_set_uint(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "set_uint(MSL_PLATFORM)");
                checkSpvc(name, kind, Spvc.spvc_compiler_options_set_uint(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0), "set_uint(MSL_VERSION)");
                checkSpvc(name, kind, Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true), "set_bool(MSL_ENABLE_DECORATION_BINDING)");
                checkSpvc(name, kind, Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true), "set_bool(MSL_TEXTURE_BUFFER_NATIVE)");
                if (kind != StageKind.COMPUTE) {
                    checkSpvc(name, kind, Spvc.spvc_compiler_options_set_bool(
                            options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true), "set_bool(FLIP_VERTEX_Y)");
                }
                checkSpvc(name, kind, Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(name, kind, Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                return MemoryUtil.memUTF8(pSource.get(0));
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void checkSpvc(final String name, final StageKind kind, final int result, final String stage) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new TranslationException(name, PHASE_SPIRV_TO_MSL, kind, stage + " -> " + result);
        }
    }
}
