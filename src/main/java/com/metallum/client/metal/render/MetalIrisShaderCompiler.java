package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
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
    private static final Pattern OPAQUE_UNIFORM_STATEMENT_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\b([^;{}]*);"
    );
    private static final Pattern STORAGE_BUFFER_BLOCK_PATTERN = Pattern.compile(
            "(?s)(?:layout\\s*\\(([^)]*)\\)\\s*)?"
                    + "(?:(?:coherent|volatile|restrict|readonly|writeonly)\\s+)*"
                    + "buffer\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\{"
    );
    private static final Pattern BINDING_QUALIFIER_PATTERN = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)\\b");
    private static final Pattern OPAQUE_TYPE_PATTERN = Pattern.compile("[iu]?(sampler|image|texture)\\w*|atomic_uint");
    private static final Set<String> PRECISION_QUALIFIERS = Set.of(
            "lowp", "mediump", "highp", "readonly", "writeonly", "coherent", "volatile", "restrict"
    );
    static final String UNIFORM_BLOCK_NAME = "MetallumIrisUniforms";
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
            boolean forcedVersion450,
            @Nullable ComputeReflection computeReflection
    ) {
    }

    enum ComputeResourceKind {
        UNIFORM_BUFFER,
        STORAGE_BUFFER,
        SAMPLED_IMAGE,
        STORAGE_IMAGE,
        TEXEL_BUFFER,
        STORAGE_TEXEL_BUFFER,
        SEPARATE_SAMPLER,
        ATOMIC_COUNTER
    }

    record ComputeResource(
            ComputeResourceKind kind,
            String name,
            int binding,
            int imageDimension
    ) {
    }

    record ComputeReflection(
            int localSizeX,
            int localSizeY,
            int localSizeZ,
            List<ComputeResource> resources,
            List<UniformMember> uniformLayout,
            int uniformBlockSize
    ) {
        ComputeReflection {
            resources = List.copyOf(resources);
            uniformLayout = List.copyOf(uniformLayout);
        }
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
        ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, true, true, true);
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(AlphaTest.ALWAYS);
        return translatePatchedPair(
                name,
                patchVanillaGbuffers(name, source, alpha, false, false, inputs, emptyTextureMap())
        );
    }

    /**
     * Production vanilla gbuffer path. Parameters mirror Iris 1.11.2's
     * {@code IrisRenderingPipeline#createShader} and {@code ShaderCreator#create}.
     */
    static GlslProgram translateVanillaGbuffers(
            final String name,
            final ProgramSource source,
            final ShaderKey key,
            final boolean nativeLineProgramPresent,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
    ) {
        VanillaPatchSemantics semantics = vanillaPatchSemantics(key, nativeLineProgramPresent);
        AlphaTest alpha = source.getDirectives()
                .getAlphaTestOverride()
                .orElse(semantics.fallbackAlpha());
        Map<PatchShaderType, String> patched = patchVanillaGbuffers(
                name,
                source,
                alpha,
                semantics.lines(),
                semantics.clouds(),
                semantics.attributes(),
                textureMap
        );
        String patchedVertex = patched.get(PatchShaderType.VERTEX);
        String patchedFragment = patched.get(PatchShaderType.FRAGMENT);
        if (patchedVertex == null || patchedFragment == null) {
            throw new TranslationException(
                    name, PHASE_PATCH, null,
                    "patchVanilla returned stages " + patched.keySet() + " (need VERTEX+FRAGMENT)"
            );
        }
        return linkVanillaPatchedPair(
                name,
                patchedVertex,
                patchedFragment,
                source.getDirectives().getDrawBuffers(),
                OptionalDouble.of(alpha.reference())
        );
    }

    record VanillaPatchSemantics(
            AlphaTest fallbackAlpha,
            boolean lines,
            boolean clouds,
            ShaderAttributeInputs attributes
    ) {
    }

    static VanillaPatchSemantics vanillaPatchSemantics(
            final ShaderKey key,
            final boolean nativeLineProgramPresent
    ) {
        boolean lines = key == ShaderKey.LINES && nativeLineProgramPresent;
        ShaderAttributeInputs attributes = new ShaderAttributeInputs(
                key.getVertexFormat(),
                key.shouldIgnoreLightmap(),
                lines,
                key.isGlint(),
                key.isText(),
                false
        );
        return new VanillaPatchSemantics(key.getAlphaTest(), lines, key == ShaderKey.CLOUDS, attributes);
    }

    private static Map<PatchShaderType, String> patchVanillaGbuffers(
            final String name,
            final ProgramSource source,
            final AlphaTest alpha,
            final boolean isLines,
            final boolean isClouds,
            final ShaderAttributeInputs inputs,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
    ) {
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
        try {
            return TransformPatcher.patchVanilla(
                    name, vertex, null, null, null, fragment,
                    alpha, isLines, isClouds, true, inputs, textureMap
            );
        } catch (TranslationException e) {
            throw e;
        } catch (Throwable t) {
            throw new TranslationException(name, PHASE_PATCH, null, String.valueOf(t.getMessage()), t);
        }
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
            wrapped = wrapLooseUniforms(name, patchedGlsl);
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
        ComputeReflection computeReflection = kind == StageKind.COMPUTE
                ? reflectCompute(name, spirv.spirv(), wrapped)
                : null;
        return new TranslatedStage(
                kind, patchedGlsl, wrapped.source(), msl, entryPoint,
                wrapped.blockedUniforms(), spirv.forcedVersion450(), computeReflection
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

    record WrappedGlsl(
            String source,
            List<String> blockedUniforms,
            List<UniformMember> uniformLayout,
            int uniformBlockSize
    ) {
    }

    static WrappedGlsl wrapLooseUniforms(final String glsl) {
        return wrapLooseUniforms("wrapped-compute", glsl);
    }

    private static WrappedGlsl wrapLooseUniforms(final String name, final String glsl) {
        String src = renameHostileIdentifiers(stripComments(glsl));
        LooseExtraction extraction = extractLooseUniforms(src);
        List<LooseUniform> deduped = dedupeByName(List.of(extraction.uniforms()));
        if (deduped.isEmpty()) {
            return new WrappedGlsl(src, List.of(), List.of(), 0);
        }
        List<UniformMember> layout = computeStd140Layout(name, deduped);
        int blockSize = alignUp(layout.getLast().offset() + layout.getLast().byteSize(), 16);
        String out = insertUniformBlock(extraction.body(), renderUniformBlock(deduped));
        return new WrappedGlsl(
                out,
                deduped.stream().map(LooseUniform::name).toList(),
                layout,
                blockSize
        );
    }

    /** One loose default-block uniform declarator, initializer already dropped. */
    private record LooseUniform(String type, String name, String arraySuffix) {
        String glslDeclaration() {
            return type + " " + name + arraySuffix;
        }
    }

    private record LooseExtraction(String body, List<LooseUniform> uniforms) {
    }

    /**
     * Removes every non-opaque loose uniform statement from {@code src}
     * (already comment-stripped and hostile-renamed), reporting the removed
     * declarators in source order. Opaque (sampler/image) uniforms stay in
     * the body.
     */
    private static LooseExtraction extractLooseUniforms(final String src) {
        Matcher matcher = UNIFORM_STATEMENT_PATTERN.matcher(src);
        StringBuilder body = new StringBuilder(src.length());
        List<LooseUniform> uniforms = new ArrayList<>();
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
                continue; // samplers/images stay loose; binding assignment happens downstream
            }
            int declaratorsStart = statement.indexOf(type) + type.length();
            String declarators = statement.substring(declaratorsStart);
            body.append(src, last, matcher.start());
            last = matcher.end();
            for (String declarator : splitTopLevel(declarators)) {
                LooseUniform uniform = parseLooseDeclarator(type, declarator);
                if (uniform == null) {
                    throw new IllegalStateException("Cannot parse uniform declarator '" + declarator + "' (type " + type + ")");
                }
                uniforms.add(uniform);
            }
        }
        body.append(src, last, src.length());
        return new LooseExtraction(body.toString(), uniforms);
    }

    /** First declaration wins; later same-name declarations must agree on type and arrayness. */
    private static List<LooseUniform> dedupeByName(final List<List<LooseUniform>> stageUniformLists) {
        Map<String, LooseUniform> byName = new java.util.LinkedHashMap<>();
        for (List<LooseUniform> stage : stageUniformLists) {
            for (LooseUniform uniform : stage) {
                LooseUniform previous = byName.putIfAbsent(uniform.name(), uniform);
                if (previous != null
                        && (!previous.type().equals(uniform.type()) || !previous.arraySuffix().equals(uniform.arraySuffix()))) {
                    throw new IllegalStateException(
                            "Uniform '" + uniform.name() + "' declared as " + previous.glslDeclaration()
                                    + " and " + uniform.glslDeclaration() + " across stages"
                    );
                }
            }
        }
        return List.copyOf(byName.values());
    }

    private static String renderUniformBlock(final List<LooseUniform> members) {
        StringBuilder block = new StringBuilder("layout(std140) uniform " + UNIFORM_BLOCK_NAME + " {\n");
        for (LooseUniform member : members) {
            block.append("    ").append(member.glslDeclaration()).append(";\n");
        }
        block.append("};\n");
        return block.toString();
    }

    private static String insertUniformBlock(final String body, final String block) {
        int insertAt = directivePreludeEnd(body);
        return body.substring(0, insertAt) + block + body.substring(insertAt);
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

    /** {@code name[expr] = init} -> ({@code type}, {@code name}, {@code [expr]}); initializers dropped. */
    @Nullable
    private static LooseUniform parseLooseDeclarator(final String type, final String declarator) {
        Matcher m = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*)").matcher(declarator);
        if (!m.find() || m.group(1).isEmpty()) {
            return null;
        }
        String arrays = m.group(2).replaceAll("\\s+", "");
        return new LooseUniform(type, m.group(1), arrays);
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

    private static ComputeReflection reflectCompute(
            final String name,
            final ByteBuffer spirvBytes,
            final WrappedGlsl wrapped
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(name, StageKind.COMPUTE, Spvc.spvc_context_create(pContext), "spvc_context_create(reflect)");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(
                        name,
                        StageKind.COMPUTE,
                        Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr),
                        "spvc_context_parse_spirv(reflect)"
                );
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        name,
                        StageKind.COMPUTE,
                        Spvc.spvc_context_create_compiler(
                                context,
                                Spvc.SPVC_BACKEND_NONE,
                                pIr.get(0),
                                Spvc.SPVC_CAPTURE_MODE_COPY,
                                pCompiler
                        ),
                        "spvc_context_create_compiler(reflect)"
                );
                long compiler = pCompiler.get(0);
                int localSizeX = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                        compiler, Spv.SpvExecutionModeLocalSize, 0
                ));
                int localSizeY = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                        compiler, Spv.SpvExecutionModeLocalSize, 1
                ));
                int localSizeZ = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                        compiler, Spv.SpvExecutionModeLocalSize, 2
                ));

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(
                        name,
                        StageKind.COMPUTE,
                        Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                        "spvc_compiler_create_shader_resources(reflect)"
                );
                long resources = pResources.get(0);
                List<ComputeResource> reflected = new ArrayList<>();
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
                        ComputeResourceKind.UNIFORM_BUFFER, false, reflected, name
                );
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
                        ComputeResourceKind.STORAGE_BUFFER, false, reflected, name
                );
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                        ComputeResourceKind.SAMPLED_IMAGE, true, reflected, name
                );
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
                        ComputeResourceKind.SAMPLED_IMAGE, true, reflected, name
                );
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
                        ComputeResourceKind.STORAGE_IMAGE, true, reflected, name
                );
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
                        ComputeResourceKind.SEPARATE_SAMPLER, false, reflected, name
                );
                collectComputeResources(
                        stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_ATOMIC_COUNTER,
                        ComputeResourceKind.ATOMIC_COUNTER, false, reflected, name
                );

                List<ComputeResource> normalized = new ArrayList<>(reflected.size());
                for (ComputeResource resource : reflected) {
                    ComputeResourceKind kind = resource.kind();
                    if (resource.imageDimension() == Spv.SpvDimBuffer) {
                        kind = kind == ComputeResourceKind.STORAGE_IMAGE
                                ? ComputeResourceKind.STORAGE_TEXEL_BUFFER
                                : ComputeResourceKind.TEXEL_BUFFER;
                    }
                    normalized.add(new ComputeResource(
                            kind, resource.name(), resource.binding(), resource.imageDimension()
                    ));
                }
                validateComputeBindings(name, normalized);
                return new ComputeReflection(
                        localSizeX,
                        localSizeY,
                        localSizeZ,
                        normalized,
                        wrapped.uniformLayout(),
                        wrapped.uniformBlockSize()
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void collectComputeResources(
            final MemoryStack stack,
            final long compiler,
            final long resources,
            final int resourceType,
            final ComputeResourceKind kind,
            final boolean image,
            final List<ComputeResource> output,
            final String programName
    ) {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(
                programName,
                StageKind.COMPUTE,
                Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pList, pCount),
                "spvc_resources_get_resource_list_for_type(" + resourceType + ")"
        );
        int count = Math.toIntExact(pCount.get(0));
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (SpvcReflectedResource resource : list) {
            if (!Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationBinding)) {
                throw new TranslationException(
                        programName,
                        PHASE_SPIRV_TO_MSL,
                        StageKind.COMPUTE,
                        "resource '" + resource.nameString() + "' has no reflected binding"
                );
            }
            int dimension = image
                    ? Spvc.spvc_type_get_image_dimension(
                            Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id())
                    )
                    : -1;
            output.add(new ComputeResource(
                    kind,
                    resource.nameString(),
                    Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationBinding),
                    dimension
            ));
        }
    }

    private static void validateComputeBindings(
            final String programName,
            final List<ComputeResource> resources
    ) {
        Map<Integer, String> buffers = new java.util.HashMap<>();
        Map<Integer, String> textures = new java.util.HashMap<>();
        Map<Integer, String> samplers = new java.util.HashMap<>();
        Set<String> names = new java.util.HashSet<>();
        for (ComputeResource resource : resources) {
            if (!names.add(resource.kind() + ":" + resource.name())) {
                throw new TranslationException(
                        programName, PHASE_SPIRV_TO_MSL, StageKind.COMPUTE,
                        "duplicate reflected resource '" + resource.name() + "'"
                );
            }
            Map<Integer, String> namespace = switch (resource.kind()) {
                case UNIFORM_BUFFER, STORAGE_BUFFER, ATOMIC_COUNTER -> buffers;
                case SEPARATE_SAMPLER -> samplers;
                case SAMPLED_IMAGE, STORAGE_IMAGE, TEXEL_BUFFER, STORAGE_TEXEL_BUFFER -> textures;
            };
            String previous = namespace.putIfAbsent(resource.binding(), resource.name());
            if (previous != null && !previous.equals(resource.name())) {
                throw new TranslationException(
                        programName, PHASE_SPIRV_TO_MSL, StageKind.COMPUTE,
                        "resources '" + previous + "' and '" + resource.name()
                                + "' share Metal binding " + resource.binding()
                );
            }
        }
    }

    private static void checkSpvc(final String name, final StageKind kind, final int result, final String stage) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new TranslationException(name, PHASE_SPIRV_TO_MSL, kind, stage + " -> " + result);
        }
    }

    // ------------------------------------------------------------------
    // B2-1: paired-stage linking for the stock pipeline compile chain
    // ------------------------------------------------------------------
    //
    // The B2-2 matrix above compiles each stage in isolation (device-library
    // proof). Executable PSOs instead go through the *stock* chain
    // (vanilla GlslCompiler -> IntermediaryShaderModule.rebind -> Spvc ->
    // MetalCompiledRenderPipeline), which pairs varyings by name and assigns
    // bindings from the RenderPipeline's BindGroupLayout. This lane therefore
    // stops at GLSL and reports the metadata the synthetic RenderPipeline
    // needs: the unified std140 uniform block (one identical text in both
    // stages — per-stage blocks would alias the same binding with different
    // layouts), the sampler/UBO names for the bind-group layout, and the
    // pack's DRAWBUFFERS mapping for the MRT color-target list.

    static final String PHASE_LINK = "pair-link";

    /**
     * Sodium's per-draw values, which must stay outside the pack uniform block.
     *
     * <p>Sodium's own {@code block_layer_opaque.vsh} declares these three in a
     * {@code layout(push_constant) uniform PC} block (its {@code #else} branch
     * declares them as loose GL uniforms, and that is the branch Iris's
     * {@code patchSodium} output carries). {@link MetalDrawContext#updateData}
     * writes exactly this block — 20 bytes, {@code u_RegionOffset} at 0,
     * {@code u_CurrentTime} at 12, {@code u_RegionID} at 16 — once per render
     * region and hands it to the pass as {@code "push_constants"}.</p>
     *
     * <p>Folding them into {@code MetallumIrisUniforms} would compile fine and
     * render wrong: every region would read a zero {@code u_RegionOffset}, so
     * all chunks would collapse onto the region origin. Re-emitting the
     * push-constant block verbatim keeps the override on exactly the same
     * per-draw ABI as sodium's own pipeline.</p>
     *
     * <p>The declared order and types are the ABI; {@code partitionSodiumPushConstants}
     * fails loudly if the patched source disagrees, so a sodium change surfaces
     * as a translation error instead of silent geometry corruption.</p>
     */
    private static final List<LooseUniform> SODIUM_PUSH_CONSTANTS = List.of(
            new LooseUniform("vec3", "u_RegionOffset", ""),
            new LooseUniform("int", "u_CurrentTime", ""),
            new LooseUniform("uint", "u_RegionID", "")
    );

    /** Byte size {@link MetalDrawContext} writes for {@link #SODIUM_PUSH_CONSTANTS}. */
    static final int SODIUM_PUSH_CONSTANT_BYTES = 20;

    private static final String SODIUM_PUSH_CONSTANT_BLOCK = """
            layout(push_constant) uniform MetallumSodiumPushConstants {
                vec3 u_RegionOffset;
                int u_CurrentTime;
                uint u_RegionID;
            };""";

    /**
     * Splits sodium's per-draw uniforms out of the collected loose uniforms.
     * Returns the pack-owned remainder; the sodium ones are re-emitted by
     * {@link #SODIUM_PUSH_CONSTANT_BLOCK}.
     */
    private static List<LooseUniform> partitionSodiumPushConstants(
            final String name, final List<LooseUniform> uniforms
    ) {
        Map<String, LooseUniform> byName = new java.util.LinkedHashMap<>();
        for (LooseUniform sodium : SODIUM_PUSH_CONSTANTS) {
            byName.put(sodium.name(), sodium);
        }
        List<LooseUniform> pack = new ArrayList<>(uniforms.size());
        int matched = 0;
        for (LooseUniform uniform : uniforms) {
            LooseUniform expected = byName.get(uniform.name());
            if (expected == null) {
                pack.add(uniform);
                continue;
            }
            if (!expected.equals(uniform)) {
                throw new TranslationException(
                        name, PHASE_LINK, null,
                        "sodium push constant '" + uniform.name() + "' is declared as '"
                                + uniform.glslDeclaration() + "' but MetalDrawContext writes '"
                                + expected.glslDeclaration() + "'; the per-draw ABI changed"
                );
            }
            matched++;
        }
        if (matched != 0 && matched != SODIUM_PUSH_CONSTANTS.size()) {
            throw new TranslationException(
                    name, PHASE_LINK, null,
                    "patched source declares " + matched + " of " + SODIUM_PUSH_CONSTANTS.size()
                            + " sodium push constants; the block must be all-or-nothing"
            );
        }
        return pack;
    }

    /** std140 member of the unified {@code MetallumIrisUniforms} block. */
    record UniformMember(String type, String name, int arrayCount, int offset, int byteSize) {
    }

    record SamplerDecl(String name, String glslType) {
        boolean isStorageImage() {
            return glslType.toLowerCase(java.util.Locale.ROOT).matches("[iu]?image.*");
        }

        boolean isTexelBuffer() {
            return glslType.toLowerCase(java.util.Locale.ROOT).contains("samplerbuffer");
        }
    }

    record StorageBufferDecl(int binding) {
    }

    record GlslProgram(
            String name,
            String vertexPatched,
            String fragmentPatched,
            String vertexGlsl,
            String fragmentGlsl,
            List<UniformMember> uniformLayout,
            int uniformBlockSize,
            List<SamplerDecl> samplers,
            List<StorageBufferDecl> storageBuffers,
            List<String> uniformBlockNames,
            int[] drawBuffers,
            OptionalDouble alphaTestReference
    ) {
        GlslProgram {
            alphaTestReference = Objects.requireNonNull(alphaTestReference, "alphaTestReference");
        }

        boolean hasUniformBlock() {
            return !uniformLayout.isEmpty();
        }
    }

    /**
     * Sodium terrain family. Patch arguments mirror Iris's own
     * {@code ShaderCreator.create} bytecode: {@code patchSodium(name, vsh,
     * gsh, tcs, tes, fsh, alphaTest, textureMap, false)}.
     */
    static GlslProgram translateSodiumTerrain(
            final String name,
            final ProgramSource source,
            final AlphaTest fallbackAlpha,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
    ) {
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
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
        Map<PatchShaderType, String> patched;
        try {
            patched = TransformPatcher.patchSodium(name, vertex, null, null, null, fragment, alpha, textureMap, false);
        } catch (Throwable t) {
            throw new TranslationException(name, PHASE_PATCH, null, String.valueOf(t.getMessage()), t);
        }
        String patchedVertex = patched.get(PatchShaderType.VERTEX);
        String patchedFragment = patched.get(PatchShaderType.FRAGMENT);
        if (patchedVertex == null || patchedFragment == null) {
            throw new TranslationException(
                    name, PHASE_PATCH, null,
                    "patchSodium returned stages " + patched.keySet() + " (need VERTEX+FRAGMENT)"
            );
        }
        return linkPatchedPair(
                name,
                patchedVertex,
                patchedFragment,
                source.getDirectives().getDrawBuffers(),
                OptionalDouble.of(alpha.reference())
        );
    }

    static GlslProgram linkPatchedPair(
            final String name,
            final String patchedVertex,
            final String patchedFragment,
            final int[] drawBuffers
    ) {
        return linkPatchedPair(
                name, patchedVertex, patchedFragment, drawBuffers, OptionalDouble.empty()
        );
    }

    static GlslProgram linkPatchedPair(
            final String name,
            final String patchedVertex,
            final String patchedFragment,
            final int[] drawBuffers,
            final OptionalDouble alphaTestReference
    ) {
        try {
            String vertexSrc = renameHostileIdentifiers(stripComments(patchedVertex));
            String fragmentSrc = renameHostileIdentifiers(stripComments(patchedFragment));
            LooseExtraction vertexLoose = extractLooseUniforms(vertexSrc);
            LooseExtraction fragmentLoose = extractLooseUniforms(fragmentSrc);
            List<LooseUniform> vertexPack = partitionSodiumPushConstants(name, vertexLoose.uniforms());
            List<LooseUniform> fragmentPack = partitionSodiumPushConstants(name, fragmentLoose.uniforms());
            List<LooseUniform> unified = dedupeByName(List.of(vertexPack, fragmentPack));

            List<UniformMember> layout = computeStd140Layout(name, unified);
            String vertexOut = vertexLoose.body();
            String fragmentOut = fragmentLoose.body();
            if (!layout.isEmpty()) {
                String block = renderUniformBlock(unified);
                vertexOut = insertUniformBlock(vertexOut, block);
                fragmentOut = insertUniformBlock(fragmentOut, block);
            }
            // Sodium's per-draw values must stay in the push-constant block the
            // draw context feeds; only the stage that declared them gets it.
            if (vertexPack.size() != vertexLoose.uniforms().size()) {
                vertexOut = insertUniformBlock(vertexOut, SODIUM_PUSH_CONSTANT_BLOCK);
            }
            if (fragmentPack.size() != fragmentLoose.uniforms().size()) {
                fragmentOut = insertUniformBlock(fragmentOut, SODIUM_PUSH_CONSTANT_BLOCK);
            }

            Map<String, String> samplers = new java.util.LinkedHashMap<>();
            collectSamplerDecls(vertexOut, samplers);
            collectSamplerDecls(fragmentOut, samplers);
            List<SamplerDecl> samplerList = samplers.entrySet().stream()
                    .map(e -> new SamplerDecl(e.getKey(), e.getValue()))
                    .toList();

            Set<Integer> storageBindings = new LinkedHashSet<>();
            collectStorageBufferDecls(name, vertexOut, storageBindings);
            collectStorageBufferDecls(name, fragmentOut, storageBindings);
            List<StorageBufferDecl> storageBuffers = storageBindings.stream()
                    .map(StorageBufferDecl::new)
                    .toList();

            Set<String> blockNames = new LinkedHashSet<>();
            collectUniformBlockNames(vertexOut, blockNames);
            collectUniformBlockNames(fragmentOut, blockNames);

            int blockSize = layout.isEmpty()
                    ? 0
                    : alignUp(layout.getLast().offset() + layout.getLast().byteSize(), 16);
            return new GlslProgram(
                    name,
                    patchedVertex,
                    patchedFragment,
                    vertexOut,
                    fragmentOut,
                    layout,
                    blockSize,
                    samplerList,
                    storageBuffers,
                    List.copyOf(blockNames),
                    drawBuffers.clone(),
                    alphaTestReference
            );
        } catch (TranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranslationException(name, PHASE_LINK, null, String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * Iris's vanilla transformer prefixes Mojang's built-in uniform blocks so
     * its OpenGL program can manage them itself.  A Mojang GPU API draw already
     * binds the same std140 payloads under their stock names, so the Metal path
     * restores those names before resource reflection.  Pack-owned blocks are
     * left untouched.
     */
    static GlslProgram linkVanillaPatchedPair(
            final String name,
            final String patchedVertex,
            final String patchedFragment,
            final int[] drawBuffers
    ) {
        return linkVanillaPatchedPair(
                name, patchedVertex, patchedFragment, drawBuffers, OptionalDouble.empty()
        );
    }

    static GlslProgram linkVanillaPatchedPair(
            final String name,
            final String patchedVertex,
            final String patchedFragment,
            final int[] drawBuffers,
            final OptionalDouble alphaTestReference
    ) {
        return linkPatchedPair(
                name,
                remapVanillaBuiltInUniformBlocks(patchedVertex),
                remapVanillaBuiltInUniformBlocks(patchedFragment),
                drawBuffers,
                alphaTestReference
        );
    }

    static String remapVanillaBuiltInUniformBlocks(final String source) {
        String remapped = source;
        remapped = renameUniformBlock(remapped, "iris_DynamicTransforms", "DynamicTransforms");
        remapped = renameUniformBlock(remapped, "iris_Projection", "Projection");
        remapped = renameUniformBlock(remapped, "iris_Fog", "Fog");
        remapped = renameUniformBlock(remapped, "iris_Globals", "Globals");
        remapped = renameUniformBlock(remapped, "iris_CloudInfo", "CloudInfo");
        return remapped;
    }

    private static String renameUniformBlock(
            final String source,
            final String irisName,
            final String mojangName
    ) {
        Pattern declaration = Pattern.compile(
                "\\buniform\\s+" + Pattern.quote(irisName) + "\\s*\\{"
        );
        return declaration.matcher(source).replaceAll("uniform " + mojangName + " {");
    }

    // ------------------------------------------------------------------
    // std140 layout for the unified block
    // ------------------------------------------------------------------

    private record Std140Type(int alignment, int byteSize) {
    }

    private static final Map<String, Std140Type> STD140_TYPES = Map.ofEntries(
            Map.entry("float", new Std140Type(4, 4)),
            Map.entry("int", new Std140Type(4, 4)),
            Map.entry("uint", new Std140Type(4, 4)),
            Map.entry("bool", new Std140Type(4, 4)),
            Map.entry("vec2", new Std140Type(8, 8)),
            Map.entry("ivec2", new Std140Type(8, 8)),
            Map.entry("uvec2", new Std140Type(8, 8)),
            Map.entry("bvec2", new Std140Type(8, 8)),
            Map.entry("vec3", new Std140Type(16, 12)),
            Map.entry("ivec3", new Std140Type(16, 12)),
            Map.entry("uvec3", new Std140Type(16, 12)),
            Map.entry("bvec3", new Std140Type(16, 12)),
            Map.entry("vec4", new Std140Type(16, 16)),
            Map.entry("ivec4", new Std140Type(16, 16)),
            Map.entry("uvec4", new Std140Type(16, 16)),
            Map.entry("bvec4", new Std140Type(16, 16)),
            // std140 matrix columns are padded to vec4 stride.
            Map.entry("mat2", new Std140Type(16, 32)),
            Map.entry("mat3", new Std140Type(16, 48)),
            Map.entry("mat4", new Std140Type(16, 64))
    );

    /**
     * Deterministic std140 layout of the unified block. Offsets are verified
     * against SPIR-V reflection by the offline test — any divergence between
     * this table and glslang's layout is a test failure, not a silent skew.
     */
    private static List<UniformMember> computeStd140Layout(final String name, final List<LooseUniform> members) {
        List<UniformMember> layout = new ArrayList<>(members.size());
        int cursor = 0;
        for (LooseUniform member : members) {
            Std140Type type = STD140_TYPES.get(member.type());
            if (type == null) {
                throw new TranslationException(
                        name, PHASE_LINK, null,
                        "uniform '" + member.name() + "' has type '" + member.type() + "' with no std140 rule (extend STD140_TYPES)"
                );
            }
            int arrayCount = parseArrayCount(name, member);
            int alignment;
            int byteSize;
            if (arrayCount > 0) {
                int stride = alignUp(type.byteSize(), 16);
                alignment = 16;
                byteSize = stride * arrayCount;
            } else {
                alignment = type.alignment();
                byteSize = type.byteSize();
            }
            int offset = alignUp(cursor, alignment);
            layout.add(new UniformMember(member.type(), member.name(), arrayCount, offset, byteSize));
            cursor = offset + byteSize;
        }
        return List.copyOf(layout);
    }

    /** 0 for scalars; a positive literal count for {@code [N]} declarators. */
    private static int parseArrayCount(final String name, final LooseUniform member) {
        String suffix = member.arraySuffix();
        if (suffix.isEmpty()) {
            return 0;
        }
        Matcher m = Pattern.compile("^\\[(\\d+)\\]$").matcher(suffix);
        if (!m.matches()) {
            throw new TranslationException(
                    name, PHASE_LINK, null,
                    "uniform '" + member.name() + "' array suffix '" + suffix + "' is not a single literal size"
            );
        }
        return Integer.parseInt(m.group(1));
    }

    private static int alignUp(final int value, final int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    // ------------------------------------------------------------------
    // Resource enumeration for the synthetic BindGroupLayout
    // ------------------------------------------------------------------

    private static void collectSamplerDecls(final String source, final Map<String, String> out) {
        Matcher matcher = OPAQUE_UNIFORM_STATEMENT_PATTERN.matcher(source);
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
            if (!OPAQUE_TYPE_PATTERN.matcher(type).matches()) {
                continue;
            }
            int declaratorsStart = statement.indexOf(type) + type.length();
            for (String declarator : splitTopLevel(statement.substring(declaratorsStart))) {
                LooseUniform decl = parseLooseDeclarator(type, declarator);
                if (decl == null) {
                    continue;
                }
                String previous = out.putIfAbsent(decl.name(), type);
                if (previous != null && !previous.equals(type)) {
                    throw new IllegalStateException(
                            "Sampler '" + decl.name() + "' declared as " + previous + " and " + type + " across stages"
                    );
                }
            }
        }
    }

    private static void collectStorageBufferDecls(
            final String programName,
            final String source,
            final Set<Integer> output
    ) {
        Matcher matcher = STORAGE_BUFFER_BLOCK_PATTERN.matcher(source);
        while (matcher.find()) {
            String layout = matcher.group(1);
            Matcher binding = layout == null
                    ? BINDING_QUALIFIER_PATTERN.matcher("")
                    : BINDING_QUALIFIER_PATTERN.matcher(layout);
            if (!binding.find()) {
                throw new TranslationException(
                        programName, PHASE_LINK, null,
                        "raster SSBO block has no explicit layout(binding=N) contract"
                );
            }
            output.add(Integer.parseInt(binding.group(1)));
        }
    }

    private static final Pattern UNIFORM_BLOCK_PATTERN =
            Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+([A-Za-z_]\\w*)\\s*\\{");

    private static void collectUniformBlockNames(final String source, final Set<String> out) {
        Matcher matcher = UNIFORM_BLOCK_PATTERN.matcher(source);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    // ------------------------------------------------------------------
    // std140 ground-truth reflection (test verification aid)
    // ------------------------------------------------------------------

    record ReflectedUniformBlock(Map<String, Integer> memberOffsets, long declaredSize) {
    }

    /**
     * Compiles {@code wrappedGlsl} through the shaderc lane and reflects the
     * actual member offsets glslang assigned to {@code blockName}. Used by the
     * offline test to prove {@link #computeStd140Layout} matches the compiled
     * truth — a divergence is a test failure, never a silent skew at runtime.
     */
    static @Nullable ReflectedUniformBlock reflectUniformBlock(
            final String name,
            final StageKind kind,
            final String wrappedGlsl,
            final String blockName
    ) {
        SpirvResult spirv = glslToSpirv(name, kind, wrappedGlsl);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirv.spirv().asIntBuffer();
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

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(name, kind, Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(name, kind, Spvc.spvc_resources_get_resource_list_for_type(
                        pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, pList, pCount
                ), "spvc_resources_get_resource_list_for_type");
                org.lwjgl.util.spvc.SpvcReflectedResource.Buffer list =
                        org.lwjgl.util.spvc.SpvcReflectedResource.create(pList.get(0), (int) pCount.get(0));
                for (org.lwjgl.util.spvc.SpvcReflectedResource resource : list) {
                    if (!blockName.equals(resource.nameString())) {
                        continue;
                    }
                    int baseTypeId = resource.base_type_id();
                    long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId);
                    int memberCount = Spvc.spvc_type_get_num_member_types(typeHandle);
                    Map<String, Integer> offsets = new java.util.LinkedHashMap<>(memberCount);
                    for (int index = 0; index < memberCount; index++) {
                        String memberName = Spvc.spvc_compiler_get_member_name(compiler, baseTypeId, index);
                        IntBuffer pOffset = stack.mallocInt(1);
                        checkSpvc(name, kind, Spvc.spvc_compiler_type_struct_member_offset(
                                compiler, typeHandle, index, pOffset
                        ), "spvc_compiler_type_struct_member_offset");
                        offsets.put(memberName, pOffset.get(0));
                    }
                    PointerBuffer pSize = stack.mallocPointer(1);
                    checkSpvc(name, kind, Spvc.spvc_compiler_get_declared_struct_size(
                            compiler, typeHandle, pSize
                    ), "spvc_compiler_get_declared_struct_size");
                    return new ReflectedUniformBlock(offsets, pSize.get(0));
                }
                return null;
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }
}
