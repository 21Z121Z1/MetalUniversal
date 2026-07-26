package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * B2-1 pipeline-override registry: the Metal-side equivalent of Iris's
 * {@code MixinShaderManager_Overrides} HEAD injection into
 * {@code GlDevice.getOrCompilePipeline}.
 *
 * <p>When a shader pack is active, {@link MetalDevice}'s pipeline-compile
 * funnel consults {@link #tryCompile} first. Sodium terrain pipelines are
 * recognized with Iris's own production discrimination
 * ({@code IrisPipelines.getPipeline} bytecode): namespace contains
 * {@code "sodium"}; translucent when the color target carries a blend
 * function; cutout when the shader defines mention {@code CUTOUT}; solid
 * otherwise. A recognized pipeline is answered with a PSO compiled through the
 * <b>stock</b> chain ({@code MetalCrossShaderCompiler}: vanilla GlslCompiler
 * &rarr; by-name rebind &rarr; SPIRV-Cross &rarr; Metal PSO) from a synthetic
 * {@link RenderPipeline} that carries the Iris-patched pack sources, the
 * XHFP chunk vertex format from {@link WorldRenderingSettings}, and an MRT
 * color-target list derived from the program's DRAWBUFFERS directive
 * (draw buffer 0 aliases the sodium pipeline's own target — the main
 * framebuffer — until the B2-3 composite chain lands).</p>
 *
 * <p>Failures anywhere in translation or compilation fail <b>open</b>: the
 * error is logged once per terrain kind and the pipeline falls back to the
 * untouched native compile, so a broken pack degrades to vanilla-looking
 * terrain instead of a dead client.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalPipelineOverrides {
    /** Formats for extended (non-alias) DRAWBUFFERS targets; B2-1 fixes RGBA8, pack format directives are B2-3 scope. */
    static final GpuFormat EXTENDED_TARGET_FORMAT = GpuFormat.RGBA8_UNORM;

    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static volatile @Nullable Instance active;

    /**
     * Whether the sodium terrain render pass carries the pack's extra
     * DRAWBUFFERS attachments.
     *
     * <p>{@link MetalCompiledRenderPipeline} selects its PSO by the attachment
     * signature of the pass being drawn into, so a program declaring
     * {@code /* DRAWBUFFERS:02 *}{@code /} can only be bound once the pass
     * really has those targets. Until the terrain pass is extended (handoff
     * step S6) multi-target kinds fail open and keep sodium's own shader.</p>
     *
     * <p>Compilation itself is independent of this — the offline gate sets it
     * to exercise the full translate→compile chain for every kind.</p>
     */
    private static volatile boolean extendedTerrainTargets;

    static void setExtendedTerrainTargets(final boolean supported) {
        extendedTerrainTargets = supported;
    }

    private IrisMetalPipelineOverrides() {
    }

    enum TerrainKind {
        SOLID(ShaderKey.SODIUM_TERRAIN_SOLID),
        CUTOUT(ShaderKey.SODIUM_TERRAIN_CUTOUT),
        TRANSLUCENT(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT);

        final ShaderKey shaderKey;

        TerrainKind(final ShaderKey shaderKey) {
            this.shaderKey = shaderKey;
        }
    }

    static Instance activate(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
    ) {
        Instance instance = new Instance(GENERATIONS.incrementAndGet(), programSet, textureMap);
        active = instance;
        return instance;
    }

    static void deactivate() {
        Instance previous = active;
        active = null;
        if (previous != null) {
            previous.close();
        }
    }

    /** Per-frame uniform refresh; driven by {@link MetalWorldRenderingPipeline#beginLevelRendering()}. */
    static void updateFrame() {
        Instance instance = active;
        if (instance != null) {
            instance.uniformValues.updateFrame();
        }
    }

    /**
     * Draw-time resource fallback for a bound terrain override, consulted by
     * {@link MetalRenderPass} when a name the PSO declares has no value set.
     *
     * <p>Sodium sets the resources <i>its own</i> shader needs; the pack's
     * program declares more. Rather than teach the sodium mixin about pack
     * resources (at pass-creation time sodium has not yet bound its textures,
     * so they cannot be forwarded), the gap is closed here, where everything
     * sodium bound is already visible.</p>
     *
     * @return the resolved binding, or {@code null} to let the caller raise the
     *         normal missing-resource error
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler fallbackTexture(
            final MetalDevice device,
            final MetalCompiledRenderPipeline pipeline,
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.resolveTexture(device, pipeline, name, bound);
    }

    /** Uniform-buffer counterpart of {@link #fallbackTexture}. */
    static @Nullable GpuBufferSlice fallbackUniform(
            final MetalDevice device, final MetalCompiledRenderPipeline pipeline, final String name
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.resolveUniform(device, pipeline, name);
    }

    static @Nullable Instance active() {
        return active;
    }

    /**
     * Pipeline-compile hook. Returns a compiled override for recognized sodium
     * terrain pipelines while a pack runtime is active, or {@code null} to let
     * the caller compile the pipeline natively.
     */
    static @Nullable MetalCompiledRenderPipeline tryCompile(
            final MetalDevice device,
            final RenderPipeline pipeline,
            final @Nullable ShaderSource fallbackSource
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.compileOverride(device, pipeline, fallbackSource);
    }

    static final class Instance {
        private final int generation;
        private final Map<TerrainKind, MetalIrisShaderCompiler.GlslProgram> programs = new EnumMap<>(TerrainKind.class);
        private final Map<TerrainKind, RenderPipeline> syntheticPipelines = new EnumMap<>(TerrainKind.class);
        private final Map<Identifier, String> generatedGlsl = new HashMap<>();
        private final Set<TerrainKind> reportedFailures = EnumSet.noneOf(TerrainKind.class);
        /**
         * Compiled override -> kind, so draw-time fallbacks know whose block to
         * bind. Concurrent because {@code MetalDevice} gained a background
         * prewarm thread: overrides may now be compiled off the render thread
         * while a draw is reading this map.
         */
        private final Map<MetalCompiledRenderPipeline, TerrainKind> compiledKinds =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private final IrisMetalUniformValues uniformValues;
        private final Set<String> reportedPlaceholders = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private @Nullable IrisMetalPlaceholderTextures placeholders;
        /** The device the overrides were compiled on; needed to drop them again on teardown. */
        private @Nullable MetalDevice device;
        private boolean reportedMissingVertexFormat;
        private boolean closed;

        private Instance(
                final int generation,
                final ProgramSet programSet,
                final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
        ) {
            this.generation = generation;
            this.uniformValues = new IrisMetalUniformValues(programSet.getPackDirectives().getSunPathRotation());
            for (TerrainKind kind : TerrainKind.values()) {
                ProgramSource source = resolveSource(programSet, kind.shaderKey.getProgram());
                if (source == null) {
                    Metallum.LOGGER.warn(
                            "[metallum-iris] no pack program for {} (fallback chain of {} exhausted); terrain kind stays native",
                            kind, kind.shaderKey.getProgram()
                    );
                    continue;
                }
                try {
                    MetalIrisShaderCompiler.GlslProgram program = MetalIrisShaderCompiler.translateSodiumTerrain(
                            source.getName(), source, kind.shaderKey.getAlphaTest(), textureMap
                    );
                    this.programs.put(kind, program);
                    this.uniformValues.register(kind, program);
                    Metallum.LOGGER.info(
                            "[metallum-iris] translated sodium terrain {} from pack program {} (drawBuffers={})",
                            kind, source.getName(),
                            java.util.Arrays.toString(this.programs.get(kind).drawBuffers())
                    );
                } catch (MetalIrisShaderCompiler.TranslationException e) {
                    Metallum.LOGGER.error(
                            "[metallum-iris] translation of {} ({}) failed in phase {}: {}; terrain kind stays native",
                            kind, source.getName(), e.phase(), e.getMessage()
                    );
                }
            }
        }

        int generation() {
            return this.generation;
        }

        MetalIrisShaderCompiler.@Nullable GlslProgram program(final TerrainKind kind) {
            return this.programs.get(kind);
        }

        /** The DRAWBUFFERS-derived color-target layout for a kind, {@code {0}} when the directive is absent. */
        int[] drawBuffersFor(final TerrainKind kind) {
            MetalIrisShaderCompiler.GlslProgram program = this.programs.get(kind);
            if (program == null || program.drawBuffers().length == 0) {
                return new int[]{0};
            }
            return program.drawBuffers();
        }

        static TerrainKind discriminate(final RenderPipeline pipeline) {
            ColorTargetState target = pipeline.getColorTargetState();
            if (target != null && target.blendFunction().isPresent()) {
                return TerrainKind.TRANSLUCENT;
            }
            if (pipeline.getShaderDefines().asSourceDirectives().contains("CUTOUT")) {
                return TerrainKind.CUTOUT;
            }
            return TerrainKind.SOLID;
        }

        static boolean isSodiumPipeline(final RenderPipeline pipeline) {
            return pipeline.getLocation().getNamespace().contains("sodium");
        }

        private @Nullable MetalCompiledRenderPipeline compileOverride(
                final MetalDevice device,
                final RenderPipeline pipeline,
                final @Nullable ShaderSource fallbackSource
        ) {
            if (!isSodiumPipeline(pipeline)) {
                return null;
            }
            TerrainKind kind = discriminate(pipeline);
            MetalIrisShaderCompiler.GlslProgram program = this.programs.get(kind);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = drawBuffersFor(kind);
            if (drawBuffers.length > 1 && !extendedTerrainTargets) {
                // The compiled PSO is looked up by the render pass's attachment
                // signature, so a multi-target program can only be used once the
                // sodium terrain pass actually carries those extra attachments
                // (handoff step S6). Until then this kind fails open rather than
                // producing a PSO nothing can bind.
                if (this.reportedFailures.add(kind)) {
                    Metallum.LOGGER.warn(
                            "[metallum-iris] terrain {} writes DRAWBUFFERS {} but the sodium terrain pass still has a"
                                    + " single attachment; staying native for this kind until the pass is extended",
                            kind, java.util.Arrays.toString(drawBuffers)
                    );
                }
                return null;
            }
            try {
                VertexFormat chunkFormat = chunkVertexFormat();
                if (chunkFormat == null) {
                    if (!this.reportedMissingVertexFormat) {
                        this.reportedMissingVertexFormat = true;
                        Metallum.LOGGER.error(
                                "[metallum-iris] WorldRenderingSettings has no chunk vertex format; terrain overrides disabled"
                        );
                    }
                    return null;
                }
                RenderPipeline synthetic = this.syntheticPipelines.computeIfAbsent(
                        kind, k -> buildSynthetic(k, program, pipeline, chunkFormat)
                );
                ShaderSource source = (id, type) -> {
                    String generated = this.generatedGlsl.get(id);
                    if (generated != null) {
                        return generated;
                    }
                    return fallbackSource == null ? null : fallbackSource.get(id, type);
                };
                Metallum.LOGGER.info(
                        "[metallum-iris] compiling terrain override {} for {} via {}",
                        kind, pipeline.getLocation(), synthetic.getLocation()
                );
                MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(device, synthetic, source);
                this.compiledKinds.put(compiled, kind);
                this.device = device;
                return compiled;
            } catch (Throwable t) {
                if (this.reportedFailures.add(kind)) {
                    Metallum.LOGGER.error(
                            "[metallum-iris] terrain override {} failed to compile; staying native for this kind",
                            kind, t
                    );
                }
                return null;
            }
        }

        private RenderPipeline buildSynthetic(
                final TerrainKind kind,
                final MetalIrisShaderCompiler.GlslProgram program,
                final RenderPipeline source,
                final VertexFormat chunkFormat
        ) {
            String base = "iris/gen" + this.generation + "/sodium_terrain_" + kind.name().toLowerCase(Locale.ROOT);
            Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
            Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
            this.generatedGlsl.put(vertexId, program.vertexGlsl());
            this.generatedGlsl.put(fragmentId, program.fragmentGlsl());

            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("metallum", base))
                    .withVertexShader(vertexId)
                    .withFragmentShader(fragmentId)
                    .withCull(source.isCull())
                    .withPolygonMode(source.getPolygonMode())
                    .withPrimitiveTopology(source.getPrimitiveTopology());

            ColorTargetState sourceTarget = source.getColorTargetState();
            if (sourceTarget == null) {
                throw new IllegalStateException("Sodium pipeline " + source.getLocation() + " has no color target");
            }
            int[] drawBuffers = drawBuffersFor(kind);
            for (int index = 0; index < drawBuffers.length; index++) {
                if (drawBuffers[index] == 0) {
                    // B2-1 display semantics: colortex0 aliases the main framebuffer.
                    builder.withColorTargetState(index, sourceTarget);
                } else {
                    builder.withColorTargetState(index, new ColorTargetState(
                            Optional.empty(), EXTENDED_TARGET_FORMAT, ColorTargetState.WRITE_ALL
                    ));
                }
            }

            DepthStencilState depth = source.getDepthStencilState();
            if (depth != null) {
                builder.withDepthStencilState(depth);
            }

            // Sodium's own layout comes over verbatim — it declares texel
            // buffers (u_SectionTimeInfo, R32_SINT) with formats the patched
            // shader still consumes; only names the pack adds get appended.
            Set<String> declared = new java.util.HashSet<>();
            for (BindGroupLayout layout : source.getBindGroupLayouts()) {
                builder.withBindGroupLayout(layout);
                layout.getUniforms().forEach(uniform -> declared.add(uniform.name()));
                declared.addAll(layout.getSamplers());
            }
            BindGroupLayout.Builder extras = BindGroupLayout.builder();
            for (String blockName : program.uniformBlockNames()) {
                if (declared.add(blockName)) {
                    extras.withUniform(blockName, UniformType.UNIFORM_BUFFER);
                }
            }
            for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
                if (!declared.add(sampler.name())) {
                    continue;
                }
                if (sampler.glslType().toLowerCase(Locale.ROOT).contains("samplerbuffer")) {
                    throw new IllegalStateException(
                            "Pack sampler '" + sampler.name() + "' (" + sampler.glslType()
                                    + ") is a texel buffer with no known GpuFormat; not supported in B2-1"
                    );
                }
                extras.withSampler(sampler.name());
            }
            builder.withBindGroupLayout(extras.build());
            builder.withVertexBinding(0, chunkFormat);
            return builder.build();
        }

        /**
         * Resolves a sampler the pack declared but sodium never bound.
         *
         * <p>Two names map to real content: the pack's {@code gtexture} is the
         * block atlas sodium binds as {@code u_BlockTex}, and {@code lightmap}
         * is its {@code u_LightTex}. Everything else — noise textures, shadow
         * maps, previous-pass buffers — has no source until the shadow pass and
         * composite chain exist, so it gets a 1×1 placeholder of the matching
         * kind (depth+compare for {@code sampler2DShadow}, colour otherwise).</p>
         */
        private MetalRenderPass.@Nullable TextureViewAndSampler resolveTexture(
                final MetalDevice device,
                final MetalCompiledRenderPipeline pipeline,
                final String name,
                final Map<String, MetalRenderPass.TextureViewAndSampler> bound
        ) {
            if (this.closed || !this.compiledKinds.containsKey(pipeline)) {
                return null;
            }
            MetalRenderPass.TextureViewAndSampler alias = switch (name) {
                case "gtexture", "tex", "texture" -> bound.get("u_BlockTex");
                case "lightmap" -> bound.get("u_LightTex");
                default -> null;
            };
            if (alias != null) {
                return alias;
            }
            IrisMetalPlaceholderTextures textures = placeholders(device);
            boolean shadow = isShadowSampler(this.compiledKinds.get(pipeline), name);
            if (this.reportedPlaceholders.add(name)) {
                Metallum.LOGGER.info(
                        "[metallum-iris] pack sampler '{}' has no source in B2-1; bound a 1x1 {} placeholder",
                        name, shadow ? "shadow" : "colour"
                );
            }
            return shadow ? textures.shadow() : textures.color();
        }

        private boolean isShadowSampler(final TerrainKind kind, final String name) {
            MetalIrisShaderCompiler.GlslProgram program = this.programs.get(kind);
            if (program == null) {
                return false;
            }
            for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
                if (sampler.name().equals(name)) {
                    return sampler.glslType().toLowerCase(Locale.ROOT).contains("shadow");
                }
            }
            return false;
        }

        private IrisMetalPlaceholderTextures placeholders(final MetalDevice device) {
            IrisMetalPlaceholderTextures existing = this.placeholders;
            if (existing == null) {
                existing = new IrisMetalPlaceholderTextures(device);
                this.placeholders = existing;
            }
            return existing;
        }

        private @Nullable GpuBufferSlice resolveUniform(
                final MetalDevice device, final MetalCompiledRenderPipeline pipeline, final String name
        ) {
            if (this.closed || !MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(name)) {
                return null;
            }
            TerrainKind kind = this.compiledKinds.get(pipeline);
            return kind == null ? null : this.uniformValues.slice(device, kind);
        }

        /** Offline-gate hook: the bytes last written for a kind's uniform block. */
        java.nio.@Nullable ByteBuffer uniformStaging(final TerrainKind kind) {
            return this.uniformValues.lastUpload(kind);
        }

        private void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            // The overrides are cached against sodium's own RenderPipeline
            // objects, which outlive this instance; without dropping the cache a
            // pack reload (or turning shaders off) would keep drawing terrain
            // with the previous pack's PSOs.
            if (this.device != null) {
                this.device.clearPipelineCache();
                this.device = null;
            }
            this.uniformValues.close();
            if (this.placeholders != null) {
                this.placeholders.close();
                this.placeholders = null;
            }
            this.compiledKinds.clear();
        }
    }

    private static @Nullable ProgramSource resolveSource(final ProgramSet programSet, final ProgramId start) {
        ProgramId current = start;
        while (current != null) {
            Optional<ProgramSource> source = programSet.get(current);
            if (source.isPresent()) {
                return source.get();
            }
            current = current.getFallback().orElse(null);
        }
        return null;
    }

    /** The Blaze3D vertex format of the active sodium chunk vertex type, if a pack runtime configured one. */
    static @Nullable VertexFormat chunkVertexFormat() {
        var chunkVertexType = WorldRenderingSettings.INSTANCE.getVertexFormat();
        return chunkVertexType == null ? null : chunkVertexType.getVertexFormat();
    }
}
