package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.properties.IndirectPointer;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.joml.Vector2f;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Metal implementation of Iris's shadow target and shadow-composite state
 * machine. Minecraft still owns scene extraction, shadow camera matrices and
 * LevelRenderer submission; this class owns only backend semantics that must
 * not pass through an OpenGL framebuffer.
 *
 * <p>The ordering contract mirrors Iris 1.11.2:</p>
 * <ol>
 *     <li>clear physical forward-Z depth to 1 and clear both sides of enabled
 *         shadowcolor targets;</li>
 *     <li>render opaque shadow geometry to shadowcolor main + shadowtex0;</li>
 *     <li>snapshot shadowtex0 into shadowtex1 before translucents;</li>
 *     <li>render translucent shadow geometry to the same main attachments;</li>
 *     <li>run each shadowcomp pass against its construction-time flip
 *         snapshot, writing the opposite physical side, then publish the final
 *         flip set for main-world sampling.</li>
 * </ol>
 *
 * <p>No fallback texture is provided here. A declared shadow resource that
 * cannot be resolved remains unbound so the Metal draw fails at the actual
 * resource boundary instead of rendering with fabricated input.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowPipeline implements AutoCloseable {
    /** Physical Metal depth after Iris's GL reverse-Z compatibility transform. */
    static final double SHADOW_DEPTH_CLEAR = 1.0;

    enum Phase {
        READY,
        OPAQUE,
        TRANSLUCENT,
        COMPOSITE,
        COMPLETE,
        CLOSED
    }

    /** Minimal seam the main pipeline must implement around LevelRenderer. */
    interface LevelRendererAdapter {
        void renderOpaqueShadows();

        void renderTranslucentShadows();
    }

    /** Dispatch is supplied by the shared Metal compute backend. */
    @FunctionalInterface
    interface ComputeDispatcher {
        void dispatch(
                ComputeSource source,
                MetalIrisShaderCompiler.TranslatedProgram translated,
                int width,
                int height
        );
    }

    record ShadowProgram(
            ShaderKey key,
            ProgramSource source,
            MetalIrisShaderCompiler.GlslProgram translated,
            VertexFormat vertexFormat,
            int[] drawBuffers
    ) {
        ShadowProgram {
            drawBuffers = drawBuffers.clone();
        }

        @Override
        public int[] drawBuffers() {
            return drawBuffers.clone();
        }
    }

    /**
     * Physical raster state for an Iris shadow draw on Metal. Iris disables
     * source-pipeline culling for shadow programs, while its OpenGL reverse-Z
     * adapter reverses depth comparison and polygon offset whenever a pack is
     * active. Metal bypasses that GL adapter, so the shadow synthetic pipeline
     * must apply the equivalent conversion explicitly.
     */
    record ShadowRasterState(boolean cull, @Nullable DepthStencilState depthStencil) {
    }

    static ShadowRasterState adaptRasterState(@Nullable final DepthStencilState sourceDepth) {
        if (sourceDepth == null) {
            return new ShadowRasterState(false, null);
        }
        if (MetalIrisDepthConvention.enabledForMetalBackend()) {
            // The backend-wide forward-depth adapter performs this conversion
            // for every pipeline state. Keep the source state logical here so
            // the shadow path is not inverted twice.
            return new ShadowRasterState(false, sourceDepth);
        }
        return new ShadowRasterState(false, new DepthStencilState(
                reverseDepthCompare(sourceDepth.depthTest()),
                sourceDepth.writeDepth(),
                -sourceDepth.depthBiasScaleFactor(),
                -sourceDepth.depthBiasConstant()
        ));
    }

    private static CompareOp reverseDepthCompare(final CompareOp compare) {
        return switch (compare) {
            case ALWAYS_PASS -> CompareOp.ALWAYS_PASS;
            case LESS_THAN -> CompareOp.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            case EQUAL -> CompareOp.EQUAL;
            case NOT_EQUAL -> CompareOp.NOT_EQUAL;
            case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case GREATER_THAN -> CompareOp.LESS_THAN;
            case NEVER_PASS -> CompareOp.NEVER_PASS;
        };
    }

    record ShadowCompositePass(
            int index,
            String name,
            @Nullable ProgramSource source,
            List<ComputeSource> computes,
            BitSet readsFromAlt,
            BitSet flippedAtLeastOnce,
            int[] drawBuffers,
            ViewportData viewport
    ) {
        ShadowCompositePass {
            computes = List.copyOf(computes);
            readsFromAlt = (BitSet) readsFromAlt.clone();
            flippedAtLeastOnce = (BitSet) flippedAtLeastOnce.clone();
            drawBuffers = drawBuffers.clone();
        }

        @Override
        public BitSet readsFromAlt() {
            return (BitSet) readsFromAlt.clone();
        }

        @Override
        public BitSet flippedAtLeastOnce() {
            return (BitSet) flippedAtLeastOnce.clone();
        }

        @Override
        public int[] drawBuffers() {
            return drawBuffers.clone();
        }

        boolean hasRenderProgram() {
            return source != null;
        }
    }

    private final ProgramFallbackResolver resolver;
    private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap;
    private final PackShadowDirectives shadowDirectives;
    private final IrisMetalShadowTargets targets;
    private final Map<ShaderKey, ShadowProgram> shadowPrograms = new EnumMap<>(ShaderKey.class);
    private final Map<ProgramSource, MetalIrisShaderCompiler.GlslProgram> compositePrograms =
            new IdentityHashMap<>();
    private final Map<ComputeSource, MetalIrisShaderCompiler.TranslatedProgram> computePrograms =
            new IdentityHashMap<>();
    private final Map<ComputeSource, ShadowCompute> computeExecutables = new IdentityHashMap<>();
    private final Map<ShadowCompositePass, RenderPipeline> compositePipelines = new IdentityHashMap<>();
    private final Map<Identifier, String> generatedSources = new java.util.LinkedHashMap<>();
    private final List<ComputeSource> shadowComputes;
    private final List<ShadowCompositePass> compositePasses;
    private final BitSet finalReadsFromAlt;
    private final int targetCount;
    private final int generation;
    private final boolean enabled;
    private boolean fullClearRequired = true;
    private Set<Integer> activeShadowMipTargets = Set.of();
    private int nextCompositePass;
    private Phase phase = Phase.READY;

    private static final class ShadowCompute {
        private final ComputeSource source;
        private final MetalIrisShaderCompiler.TranslatedStage translated;
        private final MetalIrisShaderCompiler.ComputeReflection reflection;
        private final IrisMetalPostChain.PassInfo info;
        private final String uniformToken;
        private @Nullable MetalComputePipeline pipeline;

        private ShadowCompute(
                final ComputeSource source,
                final MetalIrisShaderCompiler.TranslatedStage translated,
                final BitSet readsFromAlt,
                final int targetCount
        ) {
            this.source = source;
            this.translated = translated;
            this.reflection = Objects.requireNonNull(
                    translated.computeReflection(), "shadow compute reflection for " + source.getName()
            );
            this.info = new IrisMetalPostChain.PassInfo(
                    IrisMetalPostChain.Stage.SHADOW_COMPOSITE,
                    source.getName(),
                    new int[0],
                    readsFromAlt,
                    readsFromAlt,
                    new BitSet(targetCount),
                    this.reflection.resources().stream()
                            .filter(resource -> resource.kind()
                                    == MetalIrisShaderCompiler.ComputeResourceKind.SAMPLED_IMAGE)
                            .map(MetalIrisShaderCompiler.ComputeResource::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
            );
            this.uniformToken = "shadow-compute:" + source.getName();
        }
    }

    IrisMetalShadowPipeline(final MetalDevice device, final ProgramSet programSet) {
        this(device, programSet, 0);
    }

    IrisMetalShadowPipeline(final MetalDevice device, final ProgramSet programSet, final int generation) {
        this.generation = generation;
        PackDirectives packDirectives = programSet.getPackDirectives();
        this.shadowDirectives = packDirectives.getShadowDirectives();
        this.resolver = new ProgramFallbackResolver(programSet);
        this.enabled = shadowDirectives.isShadowEnabled().orElse(true)
                && this.resolver.resolveNullable(ProgramId.ShadowSolid) != null;
        this.textureMap = packDirectives.getTextureMap();
        this.targetCount = programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
        this.shadowComputes = nonNullComputes(programSet.getShadowCompute());
        CompositePlan plan = buildCompositePlan(programSet, packDirectives, targetCount);
        this.compositePasses = plan.passes();
        this.finalReadsFromAlt = plan.finalReadsFromAlt();
        boolean computeMayWriteShadowColor = !this.shadowComputes.isEmpty()
                || this.compositePasses.stream().anyMatch(pass -> !pass.computes().isEmpty());

        boolean[] nearestColor = new boolean[targetCount];
        boolean[] mipmappedColor = new boolean[targetCount];
        GpuFormat[] colorFormats = new GpuFormat[targetCount];
        java.util.LinkedHashSet<Integer> alphaOneSampleTargets = new java.util.LinkedHashSet<>();
        for (int index = 0; index < targetCount; index++) {
            PackShadowDirectives.SamplingSettings settings =
                    shadowDirectives.getColorSamplingSettings().computeIfAbsent(
                            index, ignored -> new PackShadowDirectives.SamplingSettings());
            nearestColor[index] = settings.getNearest();
            mipmappedColor[index] = settings.getMipmap();
            String formatName = settings.getFormat().name();
            colorFormats[index] = formatForInternalName(formatName);
            if (IrisMetalRenderTargets.logicalRgbBackedByRgba(formatName)) {
                alphaOneSampleTargets.add(index);
            }
        }
        for (ShadowCompositePass pass : this.compositePasses) {
            if (!pass.hasRenderProgram()) {
                continue;
            }
            for (int target : pass.source().getDirectives().getMipmappedBuffers()) {
                checkTarget(target, targetCount, "shadow composite mipmap");
                mipmappedColor[target] = true;
            }
        }
        boolean[] nearestDepth = new boolean[2];
        boolean[] mipmappedDepth = new boolean[2];
        for (int index = 0; index < 2; index++) {
            PackShadowDirectives.DepthSamplingSettings settings =
                    shadowDirectives.getDepthSamplingSettings().get(index);
            nearestDepth[index] = settings.getNearest();
            mipmappedDepth[index] = settings.getMipmap();
        }
        this.targets = new IrisMetalShadowTargets(
                device,
                colorFormats,
                shadowDirectives.getResolution(),
                nearestColor,
                mipmappedColor,
                nearestDepth,
                mipmappedDepth,
                computeMayWriteShadowColor,
                alphaOneSampleTargets
        );
    }

    void prepare(final MetalDevice device, final ShaderSource fallback) {
        ensureOpen();
        for (ComputeSource source : this.shadowComputes) {
            prepareCompute(device, compute(source, new BitSet(this.targetCount)));
        }
        for (ShadowCompositePass pass : this.compositePasses) {
            for (ComputeSource source : pass.computes()) {
                prepareCompute(device, compute(source, pass.readsFromAlt()));
            }
            if (pass.hasRenderProgram()) {
                RenderPipeline pipeline = this.compositePipelines.computeIfAbsent(
                        pass, this::buildCompositePipeline
                );
                ShaderSource source = (identifier, type) -> {
                    String generated = this.generatedSources.get(identifier);
                    return generated != null ? generated : fallback.get(identifier, type);
                };
                CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, source);
                if (!device.asyncPrewarmEnabled() && !compiled.isValid()) {
                    throw new IllegalStateException(
                            "Metal shadow composite pipeline is invalid for " + pass.name()
                    );
                }
            }
        }
    }

    void registerUniforms(final IrisMetalUniformValues values) {
        Objects.requireNonNull(values, "values");
        for (ComputeSource source : this.shadowComputes) {
            registerComputeUniforms(values, compute(source, new BitSet(this.targetCount)));
        }
        for (ShadowCompositePass pass : this.compositePasses) {
            for (ComputeSource source : pass.computes()) {
                registerComputeUniforms(values, compute(source, pass.readsFromAlt()));
            }
            if (pass.hasRenderProgram()) {
                IrisMetalPostChain.PassInfo info = passInfo(pass);
                values.register(
                        IrisMetalPostChain.uniformToken(info),
                        "shadow_composite_" + pass.name(),
                        translatedComposite(pass)
                );
            }
        }
    }

    private static void registerComputeUniforms(
            final IrisMetalUniformValues values,
            final ShadowCompute compute
    ) {
        values.registerCompute(
                compute.uniformToken,
                "shadow_compute_" + compute.info.name(),
                compute.reflection
        );
    }

    private static void prepareCompute(final MetalDevice device, final ShadowCompute compute) {
        if (compute.pipeline == null) {
            compute.pipeline = MetalComputePipeline.compileTranslated(
                    device, "iris/shadow/compute/" + compute.source.getName(), compute.translated
            );
        }
    }

    private ShadowCompute compute(final ComputeSource source, final BitSet readsFromAlt) {
        ShadowCompute existing = this.computeExecutables.get(source);
        if (existing != null) {
            return existing;
        }
        MetalIrisShaderCompiler.TranslatedProgram translated = this.computePrograms.computeIfAbsent(
                source, this::translateComputeProgram
        );
        ShadowCompute created = new ShadowCompute(
                source,
                translated.compute().orElseThrow(() -> new IllegalStateException(
                        "Translated shadow compute has no compute stage: " + source.getName()
                )),
                readsFromAlt,
                this.targetCount
        );
        this.computeExecutables.put(source, created);
        return created;
    }

    private IrisMetalPostChain.PassInfo passInfo(final ShadowCompositePass pass) {
        MetalIrisShaderCompiler.GlslProgram program = translatedComposite(pass);
        return new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.SHADOW_COMPOSITE,
                pass.name(),
                pass.drawBuffers(),
                pass.readsFromAlt(),
                pass.readsFromAlt(),
                pass.flippedAtLeastOnce(),
                program.samplers().stream()
                        .map(MetalIrisShaderCompiler.SamplerDecl::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
        );
    }

    private RenderPipeline buildCompositePipeline(final ShadowCompositePass pass) {
        MetalIrisShaderCompiler.GlslProgram program = translatedComposite(pass);
        String base = "iris/gen" + this.generation + "/shadowcomp/" + pass.index();
        Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
        Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
        this.generatedSources.put(vertexId, program.vertexGlsl());
        this.generatedSources.put(fragmentId, program.fragmentGlsl());
        BindGroupLayout.Builder bindings = BindGroupLayout.builder();
        Set<String> names = new java.util.HashSet<>();
        for (String block : program.uniformBlockNames()) {
            if (!names.add(block)) {
                throw new IllegalStateException("Duplicate shadow composite resource '" + block + "'");
            }
            bindings.withUniform(block, UniformType.UNIFORM_BUFFER);
        }
        for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
            if (!names.add(sampler.name())) {
                throw new IllegalStateException("Duplicate shadow composite resource '" + sampler.name() + "'");
            }
            if (sampler.isStorageImage()) {
                continue;
            }
            if (sampler.isTexelBuffer()) {
                throw new UnsupportedOperationException(
                        "Shadow composite sampler buffer '" + sampler.name() + "' has no typed Metal binding"
                );
            }
            bindings.withSampler(sampler.name());
        }
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", base))
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
        if (!names.isEmpty()) {
            builder.withBindGroupLayout(bindings.build());
        }
        ProgramDirectives directives = pass.source().getDirectives();
        Optional<BlendFunction> global = directives.getBlendModeOverride()
                .flatMap(IrisMetalPipelineOverrides::irisBlendFunction);
        for (int slot = 0; slot < pass.drawBuffers().length; slot++) {
            int logicalTarget = pass.drawBuffers()[slot];
            Optional<BlendFunction> blend = global;
            for (var override : directives.getBufferBlendOverrides()) {
                if (override.index() == logicalTarget) {
                    blend = override.blendMode() == null
                            ? Optional.empty()
                            : Optional.of(IrisMetalPipelineOverrides.irisBlendFunction(override.blendMode()));
                }
            }
            builder.withColorTargetState(
                    slot,
                    new ColorTargetState(blend, this.targetFormat(logicalTarget), ColorTargetState.WRITE_ALL)
            );
        }
        return builder.build();
    }

    boolean enabled() {
        return enabled;
    }

    Phase phase() {
        return phase;
    }

    int resolution() {
        return targets.resolution();
    }

    int targetCount() {
        return targetCount;
    }

    GpuFormat targetFormat(final int target) {
        if (target < 0 || target >= targetCount) {
            throw new IllegalArgumentException(
                    "Iris shadowcolor target out of range: " + target + " (count=" + targetCount + ")"
            );
        }
        return targets.colorTargets().format(target);
    }

    IrisMetalShadowTargets targets() {
        ensureOpen();
        return targets;
    }

    List<ShadowCompositePass> compositePasses() {
        return compositePasses;
    }

    BitSet finalReadsFromAlt() {
        return (BitSet) finalReadsFromAlt.clone();
    }

    /**
     * Resolves and translates exactly the shadow family selected by Iris's
     * {@code IrisPipelines -> ShaderKey} mapping. The caller must pass that
     * resolved key; using a main-world key is rejected rather than silently
     * compiling a gbuffer program into the shadow pass.
     */
    Optional<ShadowProgram> program(final ShaderKey key) {
        ensureOpen();
        if (!key.isShadow()) {
            throw new IllegalArgumentException("Not an Iris shadow ShaderKey: " + key);
        }
        if (shadowPrograms.containsKey(key)) {
            return Optional.of(shadowPrograms.get(key));
        }
        ProgramSource source = resolver.resolveNullable(key.getProgram());
        if (source == null) {
            return Optional.empty();
        }
        VertexFormat vertexFormat = resolveVertexFormat(key);
        MetalIrisShaderCompiler.GlslProgram translated = translateShadowProgram(key, source, vertexFormat);
        int[] drawBuffers = shadowDrawBuffers(source.getDirectives());
        validateDrawBuffers(drawBuffers, targetCount, source.getName());
        ShadowProgram result = new ShadowProgram(key, source, translated, vertexFormat, drawBuffers);
        shadowPrograms.put(key, result);
        return Optional.of(result);
    }

    /**
     * Clears and enters opaque geometry. Standalone shadow compute programs
     * run between depth clear and color clear, matching Iris's frame ordering.
     */
    void beginFrame(final MetalCommandEncoder encoder, @Nullable final ComputeDispatcher computeDispatcher) {
        requirePhase(Phase.READY, Phase.COMPLETE);
        if (!enabled) {
            throw new IllegalStateException("The active pack explicitly disabled shadow rendering");
        }
        encoder.clearDepthTexture(
                targets.shadowDepthTexture(),
                MetalIrisDepthConvention.enabledForMetalBackend() ? 0.0 : SHADOW_DEPTH_CLEAR
        );
        dispatchComputes(shadowComputes, computeDispatcher);

        BitSet main = new BitSet(targetCount);
        BitSet alt = new BitSet(targetCount);
        alt.set(0, targetCount);
        for (int index = 0; index < targetCount; index++) {
            PackShadowDirectives.SamplingSettings settings =
                    shadowDirectives.getColorSamplingSettings().get(index);
            if (fullClearRequired || settings.getClear()) {
                Vector4f clear = settings.getClearColor();
                encoder.clearColorTexture(targets.colorTexture(index, main), clear);
                encoder.clearColorTexture(targets.colorTexture(index, alt), clear);
            }
        }
        fullClearRequired = false;
        nextCompositePass = 0;
        phase = Phase.OPAQUE;
    }

    /** Drives only the two LevelRenderer submission points and the depth copy between them. */
    void renderGeometry(final MetalCommandEncoder encoder, final LevelRendererAdapter adapter) {
        requirePhase(Phase.OPAQUE);
        adapter.renderOpaqueShadows();
        captureOpaqueDepth(encoder);
        adapter.renderTranslucentShadows();
        finishGeometry(encoder);
    }

    /** Executes a frame only when every declared shadow stage has a connected Metal implementation. */
    void executeFrame(
            final MetalDevice device,
            final LevelRendererAdapter adapter,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(resources, "resources");
        if (!enabled) {
            return;
        }
        MetalCommandEncoder encoder = device.commandEncoder();
        beginFrame(encoder, (source, translated, width, height) ->
                executeCompute(device, compute(source, new BitSet(this.targetCount)), resources));
        renderGeometry(encoder, adapter);
        for (ShadowCompositePass pass : this.compositePasses) {
            for (ComputeSource source : pass.computes()) {
                executeCompute(device, compute(source, pass.readsFromAlt()), resources);
            }
            if (pass.hasRenderProgram()) {
                executeCompositeRaster(device, pass, resources);
            }
            completeCompositePass(pass);
        }
        finishComposites();
    }

    private void executeCompositeRaster(
            final MetalDevice device,
            final ShadowCompositePass pass,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        IrisMetalPostChain.PassInfo info = passInfo(pass);
        int viewportX = (int) (this.resolution() * pass.viewport().viewportX());
        int viewportY = (int) (this.resolution() * pass.viewport().viewportY());
        int viewportWidth = (int) (this.resolution() * pass.viewport().scale());
        int viewportHeight = (int) (this.resolution() * pass.viewport().scale());
        Set<Integer> mipmapped = pass.source().getDirectives().getMipmappedBuffers();
        this.targets.generatePassColorMipmaps(device.commandEncoder(), pass.readsFromAlt(), mipmapped);
        this.activeShadowMipTargets = Set.copyOf(mipmapped);
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor = this.targets.createShadowCompositeDescriptor(
                "iris shadowcomp " + pass.name(),
                pass.drawBuffers(),
                pass.readsFromAlt(),
                viewportX,
                viewportY,
                viewportWidth,
                viewportHeight
        )) {
            MetalCommandEncoder encoder = device.commandEncoder();
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            try {
                RenderPipeline pipeline = Objects.requireNonNull(
                        this.compositePipelines.get(pass), "shadow composite pipeline"
                );
                MetalIrisShaderCompiler.GlslProgram program = compositeProgram(pass);
                Optional<BlendFunction> globalBlend = pass.source().getDirectives().getBlendModeOverride()
                        .flatMap(IrisMetalPipelineOverrides::irisBlendFunction);
                IrisMetalUniformValues.DrawUniformContext uniformContext =
                        IrisMetalUniformValues.requiresDrawContext(program.uniformLayout())
                                ? shadowUniformContext(info, pass.readsFromAlt(), resources, globalBlend)
                                : IrisMetalUniformValues.DrawUniformContext.empty();
                renderPass.setPipeline(pipeline);
                for (MetalIrisShaderCompiler.StorageBufferDecl storage : program.storageBuffers()) {
                    GpuBufferSlice slice = resources.storageBuffer(storage.binding());
                    if (slice == null) {
                        throw new IllegalStateException(
                                "Shadow composite " + pass.name() + " is missing SSBO binding "
                                        + storage.binding()
                        );
                    }
                    renderPass.bindStorageBuffer(storage.binding(), slice);
                }
                for (String block : program.uniformBlockNames()) {
                    GpuBufferSlice slice = resources.uniform(
                            info,
                            block,
                            IrisMetalPostChain.uniformToken(info),
                            uniformContext
                    );
                    if (slice == null) {
                        throw new IllegalStateException(
                                "Shadow composite " + pass.name() + " is missing uniform block '" + block + "'"
                        );
                    }
                    renderPass.setUniform(block, slice);
                }
                for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
                    if (sampler.isStorageImage()) {
                        int shadowTarget = shadowColorImageIndex(sampler.name());
                        GpuTextureView image;
                        if (shadowTarget >= 0) {
                            if (shadowTarget >= this.targetCount) {
                                throw new IllegalStateException(
                                        "Shadow composite storage image '" + sampler.name()
                                                + "' exceeds target count " + this.targetCount
                                );
                            }
                            image = this.targets.colorView(shadowTarget, pass.readsFromAlt());
                        } else {
                            image = resources.storageImage(info, sampler.name());
                        }
                        if (image == null) {
                            throw new IllegalStateException(
                                    "Shadow composite " + pass.name() + " is missing storage image '"
                                            + sampler.name() + "'"
                            );
                        }
                        renderPass.bindStorageImage(sampler.name(), image);
                        continue;
                    }
                    IrisMetalPostChain.TextureBinding binding = resources.texture(info, sampler);
                    if (binding == null) {
                        MetalRenderPass.TextureViewAndSampler shadow = resolveShadowSampler(
                                sampler, pass.readsFromAlt(), info.declaresSampler("watershadow")
                        );
                        if (shadow != null) {
                            binding = new IrisMetalPostChain.TextureBinding(
                                    shadow.textureView(), shadow.sampler()
                            );
                        }
                    }
                    if (binding == null) {
                        throw new IllegalStateException(
                                "Shadow composite " + pass.name() + " is missing sampler '" + sampler.name() + "'"
                        );
                    }
                    renderPass.bindTexture(sampler.name(), binding.view(), binding.sampler());
                }
                GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
                renderPass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
                renderPass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
                renderPass.drawIndexed(6, 1, 0, 0, 0);
            } finally {
                encoder.submitRenderPass();
            }
        } finally {
            this.activeShadowMipTargets = Set.of();
        }
    }

    private void executeCompute(
            final MetalDevice device,
            final ShadowCompute compute,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        try (MetalComputePass pass = device.commandEncoder().createComputePass()) {
            pass.setPipeline(Objects.requireNonNull(compute.pipeline, "shadow compute pipeline"));
            bindComputeResources(pass, compute, resources);
            dispatchCompute(pass, compute, resources);
        }
    }

    private void bindComputeResources(
            final MetalComputePass pass,
            final ShadowCompute compute,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        IrisMetalUniformValues.DrawUniformContext uniformContext =
                IrisMetalUniformValues.requiresDrawContext(compute.reflection.uniformLayout())
                        ? shadowUniformContext(
                                compute.info, compute.info.readsFromAlt(), resources, Optional.empty()
                        )
                        : IrisMetalUniformValues.DrawUniformContext.empty();
        for (MetalIrisShaderCompiler.ComputeResource resource : compute.reflection.resources()) {
            switch (resource.kind()) {
                case UNIFORM_BUFFER -> bindBuffer(
                        pass,
                        resource.binding(),
                        requireBuffer(
                                resources.uniform(
                                        compute.info,
                                        resource.name(),
                                        compute.uniformToken,
                                        uniformContext
                                ),
                                compute, "uniform block", resource.name()
                        )
                );
                case STORAGE_BUFFER -> bindBuffer(
                        pass,
                        resource.binding(),
                        requireBuffer(
                                resources.storageBuffer(resource.binding()),
                                compute, "SSBO binding", Integer.toString(resource.binding())
                        )
                );
                case SAMPLED_IMAGE -> {
                    IrisMetalPostChain.TextureBinding binding = requireTexture(compute, resource.name(), resources);
                    MetalGpuTextureView view = metalView(binding.view(), compute, resource.name());
                    pass.bindTextureView(resource.binding(), view);
                    pass.bindSampler(
                            resource.binding(), metalSampler(binding.sampler(), compute, resource.name()).nativeHandle()
                    );
                }
                case SEPARATE_SAMPLER -> {
                    IrisMetalPostChain.TextureBinding binding = requireTexture(compute, resource.name(), resources);
                    pass.bindSampler(
                            resource.binding(), metalSampler(binding.sampler(), compute, resource.name()).nativeHandle()
                    );
                }
                case STORAGE_IMAGE -> {
                    GpuTextureView view = storageImage(compute, resource.name(), resources);
                    MetalGpuTextureView metal = metalView(view, compute, resource.name());
                    ((MetalGpuTexture) metal.texture()).markContentsDirty();
                    pass.bindTextureView(resource.binding(), metal);
                }
                case TEXEL_BUFFER, STORAGE_TEXEL_BUFFER, ATOMIC_COUNTER -> throw new IllegalStateException(
                        "Unsupported shadow compute resource survived admission: "
                                + resource.kind() + " " + resource.name()
                );
            }
        }
    }

    private IrisMetalUniformValues.DrawUniformContext shadowUniformContext(
            final IrisMetalPostChain.PassInfo info,
            final BitSet readsFromAlt,
            final IrisMetalPostChain.ResourceProvider resources,
            final Optional<BlendFunction> globalBlend
    ) {
        MetalIrisShaderCompiler.SamplerDecl sampler =
                new MetalIrisShaderCompiler.SamplerDecl("shadowcolor0", "sampler2D");
        IrisMetalPostChain.TextureBinding primary = resources.texture(info, sampler);
        if (primary == null) {
            MetalRenderPass.TextureViewAndSampler shadow = resolveShadowSampler(
                    sampler, readsFromAlt, info.declaresSampler("watershadow")
            );
            if (shadow != null) {
                primary = new IrisMetalPostChain.TextureBinding(
                        shadow.textureView(), shadow.sampler()
                );
            }
        }
        if (primary == null) {
            throw new IllegalStateException(
                    "Iris shadow pass " + info.name() + " has no logical texture-unit-0 shadowcolor0 binding"
            );
        }
        return new IrisMetalUniformValues.DrawUniformContext(
                primary.view(), 0, 0, globalBlend
        );
    }

    private IrisMetalPostChain.TextureBinding requireTexture(
            final ShadowCompute compute,
            final String name,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        MetalIrisShaderCompiler.SamplerDecl sampler =
                new MetalIrisShaderCompiler.SamplerDecl(name, "sampler2D");
        IrisMetalPostChain.TextureBinding binding = resources.texture(compute.info, sampler);
        if (binding == null) {
            MetalRenderPass.TextureViewAndSampler shadow = resolveShadowSampler(
                    sampler, compute.info.readsFromAlt(), compute.info.declaresSampler("watershadow")
            );
            if (shadow != null) {
                binding = new IrisMetalPostChain.TextureBinding(shadow.textureView(), shadow.sampler());
            }
        }
        if (binding == null) {
            throw new IllegalStateException(
                    "Iris shadow compute " + compute.info.name() + " is missing sampled texture '" + name + "'"
            );
        }
        return binding;
    }

    private GpuTextureView storageImage(
            final ShadowCompute compute,
            final String name,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        int shadowTarget = shadowColorImageIndex(name);
        if (shadowTarget >= 0) {
            if (shadowTarget >= this.targetCount) {
                throw new IllegalStateException(
                        "Shadow storage image '" + name + "' exceeds target count " + this.targetCount
                );
            }
            return this.targets.colorView(shadowTarget, compute.info.readsFromAlt());
        }
        GpuTextureView view = resources.storageImage(compute.info, name);
        if (view == null) {
            throw new IllegalStateException(
                    "Iris shadow compute " + compute.info.name() + " is missing storage image '" + name + "'"
            );
        }
        return view;
    }

    private static int shadowColorImageIndex(final String name) {
        String prefix = "shadowcolorimg";
        if (!name.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Nullable GpuTextureView resolveStorageImage(final String name) {
        int target = shadowColorImageIndex(name);
        if (target < 0 || target >= this.targetCount || this.targets == null) {
            return null;
        }
        return this.targets.colorTargets().sampleReadView(target);
    }

    private static GpuBufferSlice requireBuffer(
            final @Nullable GpuBufferSlice slice,
            final ShadowCompute compute,
            final String kind,
            final String identity
    ) {
        if (slice == null) {
            throw new IllegalStateException(
                    "Iris shadow compute " + compute.info.name() + " is missing " + kind + " '" + identity + "'"
            );
        }
        return slice;
    }

    private static void bindBuffer(
            final MetalComputePass pass,
            final int binding,
            final GpuBufferSlice slice
    ) {
        if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
            throw new IllegalStateException("Iris shadow compute resource is not a Metal buffer");
        }
        pass.bindBuffer(binding, buffer, slice.offset());
    }

    private static MetalGpuTextureView metalView(
            final GpuTextureView view,
            final ShadowCompute compute,
            final String name
    ) {
        if (!(view instanceof MetalGpuTextureView metal)) {
            throw new IllegalStateException(
                    "Iris shadow compute " + compute.info.name() + " resource '" + name
                            + "' is not a Metal texture view"
            );
        }
        return metal;
    }

    private static MetalGpuSampler metalSampler(
            final GpuSampler sampler,
            final ShadowCompute compute,
            final String name
    ) {
        if (!(sampler instanceof MetalGpuSampler metal)) {
            throw new IllegalStateException(
                    "Iris shadow compute " + compute.info.name() + " resource '" + name
                            + "' is not a Metal sampler"
            );
        }
        return metal;
    }

    private void dispatchCompute(
            final MetalComputePass pass,
            final ShadowCompute compute,
            final IrisMetalPostChain.ResourceProvider resources
    ) {
        IndirectPointer indirect = compute.source.getIndirectPointer();
        if (indirect != null) {
            GpuBufferSlice slice = requireBuffer(
                    resources.storageBuffer(indirect.buffer()),
                    compute,
                    "indirect SSBO binding",
                    Integer.toString(indirect.buffer())
            );
            if (indirect.offset() < 0L || indirect.offset() > slice.length() - 12L) {
                throw new IllegalStateException(
                        "Iris shadow compute " + compute.info.name() + " indirect range exceeds SSBO "
                                + indirect.buffer()
                );
            }
            if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
                throw new IllegalStateException("Iris shadow indirect buffer is not backed by Metal");
            }
            pass.dispatchIndirect(buffer, Math.addExact(slice.offset(), indirect.offset()));
            return;
        }
        Vector3i absolute = compute.source.getWorkGroups();
        if (absolute != null) {
            pass.dispatchGroups(absolute.x(), absolute.y(), absolute.z());
            return;
        }
        Vector2f relative = compute.source.getWorkGroupRelative();
        float scaleX = relative == null ? 1.0F : relative.x();
        float scaleY = relative == null ? 1.0F : relative.y();
        int threadsX = Math.max(1, (int) Math.ceil(this.resolution() * scaleX));
        int threadsY = Math.max(1, (int) Math.ceil(this.resolution() * scaleY));
        pass.dispatchThreadsCovering(threadsX, threadsY, 1);
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createGbufferDescriptor(
            final String label,
            final ShadowProgram program
    ) {
        requirePhase(Phase.OPAQUE, Phase.TRANSLUCENT);
        return targets.createShadowGbufferDescriptor(label, program.drawBuffers(), null, null);
    }

    /** Descriptor backed by generation-owned full views for a draw whose lifetime escapes this call. */
    RenderPassDescriptor createPersistentGbufferDescriptor(
            final String label,
            final ShadowProgram program
    ) {
        requirePhase(Phase.OPAQUE, Phase.TRANSLUCENT);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        BitSet main = new BitSet(targetCount);
        for (int target : program.drawBuffers()) {
            descriptor.withColorAttachment(targets.colorView(target, main));
        }
        descriptor.withDepthAttachment(targets.shadowDepthView());
        return descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resolution(), resolution()));
    }

    void captureOpaqueDepth(final MetalCommandEncoder encoder) {
        requirePhase(Phase.OPAQUE);
        targets.captureNoTranslucentsDepth(encoder);
        phase = Phase.TRANSLUCENT;
    }

    void finishGeometry(final MetalCommandEncoder encoder) {
        requirePhase(Phase.TRANSLUCENT);
        targets.generateDepthMipmaps(encoder);
        targets.generateConfiguredColorMipmaps(encoder);
        phase = Phase.COMPOSITE;
    }

    MetalIrisShaderCompiler.GlslProgram compositeProgram(final ShadowCompositePass pass) {
        ensureExpectedCompositePass(pass);
        return translatedComposite(pass);
    }

    private MetalIrisShaderCompiler.GlslProgram translatedComposite(final ShadowCompositePass pass) {
        ProgramSource source = pass.source();
        if (source == null) {
            throw new IllegalArgumentException("Shadow composite pass " + pass.index() + " is compute-only");
        }
        return compositePrograms.computeIfAbsent(source, this::translateCompositeProgram);
    }

    List<MetalIrisShaderCompiler.TranslatedProgram> compositeComputes(final ShadowCompositePass pass) {
        ensureExpectedCompositePass(pass);
        if (pass.computes().isEmpty()) {
            return List.of();
        }
        List<MetalIrisShaderCompiler.TranslatedProgram> translated = new ArrayList<>(pass.computes().size());
        for (ComputeSource source : pass.computes()) {
            translated.add(computePrograms.computeIfAbsent(source, this::translateComputeProgram));
        }
        return List.copyOf(translated);
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createCompositeDescriptor(
            final ShadowCompositePass pass
    ) {
        ensureExpectedCompositePass(pass);
        if (!pass.hasRenderProgram()) {
            throw new IllegalArgumentException("Shadow composite pass " + pass.index() + " is compute-only");
        }
        ViewportData viewport = pass.viewport();
        int x = (int) (resolution() * viewport.viewportX());
        int y = (int) (resolution() * viewport.viewportY());
        int width = (int) (resolution() * viewport.scale());
        int height = (int) (resolution() * viewport.scale());
        return targets.createShadowCompositeDescriptor(
                "iris shadowcomp " + pass.name(),
                pass.drawBuffers(),
                pass.readsFromAlt(),
                x,
                y,
                width,
                height
        );
    }

    void completeCompositePass(final ShadowCompositePass pass) {
        ensureExpectedCompositePass(pass);
        nextCompositePass++;
    }

    void finishComposites() {
        requirePhase(Phase.COMPOSITE);
        if (nextCompositePass != compositePasses.size()) {
            throw new IllegalStateException(
                    "Shadow composite chain incomplete: completed " + nextCompositePass
                            + " of " + compositePasses.size() + " passes"
            );
        }
        targets.publishFlipState(finalReadsFromAlt);
        phase = Phase.COMPLETE;
    }

    /**
     * Resolves only Iris shadow aliases. Unknown names return {@code null} so
     * the caller can ask the atlas/custom-texture providers; known aliases
     * never fall back to a placeholder.
     */
    MetalRenderPass.@Nullable TextureViewAndSampler resolveShadowSampler(
            final MetalIrisShaderCompiler.SamplerDecl sampler,
            final BitSet readsFromAlt,
            final boolean waterShadowDeclared
    ) {
        Objects.requireNonNull(sampler, "sampler");
        return resolveShadowSampler(
                sampler.name(), isComparisonSampler(sampler), readsFromAlt, waterShadowDeclared
        );
    }

    /**
     * Main-world post programs may sample only the completed shadow frame and
     * therefore always observe the shadow-composite chain's published side.
     * Unknown names still delegate to the caller's other resource providers.
     */
    MetalRenderPass.@Nullable TextureViewAndSampler resolveWorldShadowSampler(
            final MetalIrisShaderCompiler.SamplerDecl sampler,
            final boolean waterShadowDeclared
    ) {
        Objects.requireNonNull(sampler, "sampler");
        if (!isShadowSamplerName(sampler.name())) {
            return null;
        }
        requirePhase(Phase.COMPLETE);
        return resolveShadowSampler(sampler, finalReadsFromAlt, waterShadowDeclared);
    }

    /** GLSL sampler type, not the resource name, selects Metal depth comparison. */
    static boolean isComparisonSampler(final MetalIrisShaderCompiler.SamplerDecl sampler) {
        Objects.requireNonNull(sampler, "sampler");
        return sampler.glslType().toLowerCase(Locale.ROOT).endsWith("shadow");
    }

    static boolean isShadowSamplerName(final String name) {
        return switch (name) {
            case "shadow", "watershadow",
                    "shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW",
                    "shadowtex0DH", "shadowtex1DH", "shadowcolor" -> true;
            default -> shadowColorIndex(name) >= 0;
        };
    }

    MetalRenderPass.@Nullable TextureViewAndSampler resolveShadowSampler(
            final String name,
            final boolean comparison,
            final BitSet readsFromAlt,
            final boolean waterShadowDeclared
    ) {
        ensureOpen();
        int depth = switch (name) {
            case "shadowtex0", "shadowtex0HW", "watershadow" -> 0;
            case "shadowtex1", "shadowtex1HW" -> 1;
            case "shadow" -> waterShadowDeclared ? 1 : 0;
            default -> -1;
        };
        if (depth >= 0) {
            return new MetalRenderPass.TextureViewAndSampler(
                    depth == 0 ? targets.shadowDepthView() : targets.shadowDepthNoTranslucentsView(),
                    targets.depthSampler(depth, comparison)
            );
        }
        int color = shadowColorIndex(name);
        if (color >= 0) {
            if (color >= targetCount) {
                throw new IllegalStateException(
                        "Pack declared " + name + " but this generation has only " + targetCount
                                + " shadowcolor targets"
                );
            }
            return new MetalRenderPass.TextureViewAndSampler(
                    targets.colorView(color, readsFromAlt),
                    targets.colorSampler(color, this.activeShadowMipTargets.contains(color))
            );
        }
        return null;
    }

    void resize(final int resolution) {
        requirePhase(Phase.READY, Phase.COMPLETE);
        targets.resize(resolution);
        fullClearRequired = true;
        phase = Phase.READY;
    }

    private void dispatchComputes(
            final List<ComputeSource> sources,
            @Nullable final ComputeDispatcher dispatcher
    ) {
        if (sources.isEmpty()) {
            return;
        }
        if (dispatcher == null) {
            throw new IllegalStateException(
                    "The pack declares shadow compute programs but no Metal compute dispatcher was connected"
            );
        }
        for (ComputeSource source : sources) {
            dispatcher.dispatch(source, computePrograms.computeIfAbsent(source, this::translateComputeProgram),
                    resolution(), resolution());
        }
    }

    private MetalIrisShaderCompiler.GlslProgram translateShadowProgram(
            final ShaderKey key,
            final ProgramSource source,
            final VertexFormat vertexFormat
    ) {
        rejectUnsupportedStages(source);
        String vertex = source.getVertexSource().orElseThrow(
                () -> translationFailure(source, MetalIrisShaderCompiler.StageKind.VERTEX, "missing vertex source"));
        String fragment = source.getFragmentSource().orElseThrow(
                () -> translationFailure(source, MetalIrisShaderCompiler.StageKind.FRAGMENT, "missing fragment source"));
        var alpha = source.getDirectives().getAlphaTestOverride().orElse(key.getAlphaTest());
        Map<PatchShaderType, String> patched;
        if (key.patch == Patch.SODIUM) {
            patched = TransformPatcher.patchSodium(
                    source.getName(), vertex, null, null, null, fragment,
                    alpha,
                    textureMap,
                    true
            );
        } else if (key.patch == Patch.VANILLA) {
            boolean isLines = key.getProgram() == ProgramId.Line && resolver.has(ProgramId.Line);
            ShaderAttributeInputs inputs = new ShaderAttributeInputs(
                    vertexFormat, key.shouldIgnoreLightmap(), isLines, false, key.isText(), false
            );
            patched = TransformPatcher.patchVanilla(
                    source.getName(), vertex, null, null, null, fragment,
                    alpha,
                    isLines, false, true, inputs, textureMap
            );
        } else {
            throw new IllegalStateException("Unsupported shadow patch family " + key.patch + " for " + key);
        }
        return linkPatchedPair(
                key,
                source,
                patched,
                shadowDrawBuffers(source.getDirectives()),
                OptionalDouble.of(alpha.reference())
        );
    }

    /** Mirrors Iris 1.11.2's shadow linker: Sodium keys inherit the live extended chunk format. */
    static VertexFormat resolveVertexFormat(final ShaderKey key) {
        VertexFormat explicit = key.getVertexFormat();
        if (explicit != null) {
            return explicit;
        }
        var chunkType = WorldRenderingSettings.INSTANCE.getVertexFormat();
        if (key.patch != Patch.SODIUM || chunkType == null) {
            throw new IllegalStateException(
                    "Iris shadow key " + key + " has no resolved vertex format for the Metal pipeline"
            );
        }
        return chunkType.getVertexFormat();
    }

    private MetalIrisShaderCompiler.GlslProgram translateCompositeProgram(final ProgramSource source) {
        if (source.getGeometrySource().isPresent()) {
            throw new MetalIrisShaderCompiler.TranslationException(
                    source.getName(), MetalIrisShaderCompiler.PHASE_UNSUPPORTED_STAGE, null,
                    "geometry shaders have no Metal equivalent"
            );
        }
        String vertex = source.getVertexSource().orElseThrow(
                () -> translationFailure(source, MetalIrisShaderCompiler.StageKind.VERTEX, "missing vertex source"));
        String fragment = source.getFragmentSource().orElseThrow(
                () -> translationFailure(source, MetalIrisShaderCompiler.StageKind.FRAGMENT, "missing fragment source"));
        Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(
                source.getName(), vertex, null, fragment, TextureStage.SHADOWCOMP, textureMap
        );
        return linkPatchedPair(source, patched, shadowDrawBuffers(source.getDirectives()));
    }

    private MetalIrisShaderCompiler.TranslatedProgram translateComputeProgram(final ComputeSource source) {
        String glsl = source.getSource().orElseThrow(() -> new IllegalStateException(
                "Compute source " + source.getName() + " has no shader text"));
        String patched = TransformPatcher.patchCompute(
                source.getName(), glsl, TextureStage.SHADOWCOMP, textureMap
        );
        MetalIrisShaderCompiler.TranslatedStage stage = MetalIrisShaderCompiler.translateStage(
                source.getName(), MetalIrisShaderCompiler.StageKind.COMPUTE, patched
        );
        return new MetalIrisShaderCompiler.TranslatedProgram(
                source.getName(), Optional.empty(), Optional.empty(), Optional.of(stage)
        );
    }

    private static MetalIrisShaderCompiler.GlslProgram linkPatchedPair(
            final ShaderKey key,
            final ProgramSource source,
            final Map<PatchShaderType, String> patched,
            final int[] drawBuffers,
            final OptionalDouble alphaTestReference
    ) {
        String vertex = patched.get(PatchShaderType.VERTEX);
        String fragment = patched.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            throw new MetalIrisShaderCompiler.TranslationException(
                    source.getName(), MetalIrisShaderCompiler.PHASE_PATCH, null,
                    "patcher returned stages " + patched.keySet() + " (need VERTEX+FRAGMENT)"
            );
        }
        return linkShadowPatchedPair(
                key, source.getName(), vertex, fragment, drawBuffers, alphaTestReference
        );
    }

    private static MetalIrisShaderCompiler.GlslProgram linkPatchedPair(
            final ProgramSource source,
            final Map<PatchShaderType, String> patched,
            final int[] drawBuffers
    ) {
        String vertex = patched.get(PatchShaderType.VERTEX);
        String fragment = patched.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            throw new MetalIrisShaderCompiler.TranslationException(
                    source.getName(), MetalIrisShaderCompiler.PHASE_PATCH, null,
                    "patcher returned stages " + patched.keySet() + " (need VERTEX+FRAGMENT)"
            );
        }
        return MetalIrisShaderCompiler.linkPatchedPair(
                source.getName(), vertex, fragment, drawBuffers
        );
    }

    static MetalIrisShaderCompiler.GlslProgram linkShadowPatchedPair(
            final ShaderKey key,
            final String name,
            final String vertex,
            final String fragment,
            final int[] drawBuffers
    ) {
        return linkShadowPatchedPair(
                key, name, vertex, fragment, drawBuffers, OptionalDouble.empty()
        );
    }

    static MetalIrisShaderCompiler.GlslProgram linkShadowPatchedPair(
            final ShaderKey key,
            final String name,
            final String vertex,
            final String fragment,
            final int[] drawBuffers,
            final OptionalDouble alphaTestReference
    ) {
        if (key.patch == Patch.VANILLA) {
            return MetalIrisShaderCompiler.linkVanillaPatchedPair(
                    name, vertex, fragment, drawBuffers, alphaTestReference
            );
        }
        return MetalIrisShaderCompiler.linkPatchedPair(
                name, vertex, fragment, drawBuffers, alphaTestReference
        );
    }

    private static void rejectUnsupportedStages(final ProgramSource source) {
        if (source.getGeometrySource().isPresent()) {
            throw new MetalIrisShaderCompiler.TranslationException(
                    source.getName(), MetalIrisShaderCompiler.PHASE_UNSUPPORTED_STAGE, null,
                    "geometry shaders have no Metal equivalent"
            );
        }
        if (source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
            throw new MetalIrisShaderCompiler.TranslationException(
                    source.getName(), MetalIrisShaderCompiler.PHASE_UNSUPPORTED_STAGE, null,
                    "tessellation shaders are not supported on the Metal backend"
            );
        }
    }

    private static MetalIrisShaderCompiler.TranslationException translationFailure(
            final ProgramSource source,
            final MetalIrisShaderCompiler.StageKind stage,
            final String message
    ) {
        return new MetalIrisShaderCompiler.TranslationException(
                source.getName(), MetalIrisShaderCompiler.PHASE_PATCH, stage, message
        );
    }

    private static CompositePlan buildCompositePlan(
            final ProgramSet programSet,
            final PackDirectives directives,
            final int targetCount
    ) {
        ProgramSource[] sources = programSet.getComposite(ProgramArrayId.ShadowComposite);
        ComputeSource[][] computes = programSet.getCompute(ProgramArrayId.ShadowComposite);
        List<ShadowCompositePass> passes = new ArrayList<>();
        BitSet flipped = new BitSet(targetCount);
        BitSet flippedAtLeastOnce = new BitSet(targetCount);
        directives.getExplicitFlips("shadowcomp_pre").forEach((target, shouldFlip) -> {
            checkTarget(target, targetCount, "shadowcomp_pre");
            if (shouldFlip) {
                flipped.flip(target);
            }
        });

        for (int index = 0; index < sources.length; index++) {
            ProgramSource source = sources[index];
            List<ComputeSource> passComputes = computes.length > index && computes[index] != null
                    ? nonNullComputes(computes[index])
                    : List.of();
            boolean validSource = source != null && source.isValid();
            if (!validSource && passComputes.isEmpty()) {
                continue;
            }
            BitSet reads = (BitSet) flipped.clone();
            BitSet ever = (BitSet) flippedAtLeastOnce.clone();
            int[] drawBuffers = validSource ? shadowDrawBuffers(source.getDirectives()) : new int[0];
            if (validSource) {
                validateDrawBuffers(drawBuffers, targetCount, source.getName());
            }
            String name = validSource ? source.getName() : "shadowcomp-compute-" + index;
            ViewportData viewport = validSource
                    ? source.getDirectives().getViewportScale()
                    : ViewportData.defaultValue();
            passes.add(new ShadowCompositePass(
                    index, name, validSource ? source : null, passComputes,
                    reads, ever, drawBuffers, viewport
            ));
            if (validSource) {
                applyPassFlips(
                        flipped,
                        flippedAtLeastOnce,
                        drawBuffers,
                        source.getDirectives().getExplicitFlips(),
                        targetCount
                );
            }
        }
        return new CompositePlan(List.copyOf(passes), flipped);
    }

    /** Package-visible for a focused regression test of Iris's two-step flip rule. */
    static void applyPassFlips(
            final BitSet flipped,
            final BitSet flippedAtLeastOnce,
            final int[] drawBuffers,
            final Map<Integer, Boolean> explicitFlips,
            final int targetCount
    ) {
        for (int target : drawBuffers) {
            checkTarget(target, targetCount, "shadow composite DRAWBUFFERS");
            if (explicitFlips.get(target) == Boolean.FALSE) {
                continue;
            }
            flipped.flip(target);
            flippedAtLeastOnce.set(target);
        }
        explicitFlips.forEach((target, shouldFlip) -> {
            checkTarget(target, targetCount, "shadow composite explicit flip");
            if (shouldFlip) {
                flipped.flip(target);
                flippedAtLeastOnce.set(target);
            }
        });
    }

    private static int[] shadowDrawBuffers(final ProgramDirectives directives) {
        return directives.hasUnknownDrawBuffers() ? new int[]{0, 1} : directives.getDrawBuffers().clone();
    }

    private static void validateDrawBuffers(final int[] drawBuffers, final int targetCount, final String label) {
        BitSet seen = new BitSet(targetCount);
        for (int target : drawBuffers) {
            checkTarget(target, targetCount, label + " DRAWBUFFERS");
            if (seen.get(target)) {
                throw new IllegalStateException(label + " repeats shadowcolor" + target + " in DRAWBUFFERS");
            }
            seen.set(target);
        }
    }

    private static void checkTarget(final int target, final int targetCount, final String label) {
        if (target < 0 || target >= targetCount) {
            throw new IllegalStateException(
                    label + " references shadowcolor" + target + " outside 0.." + (targetCount - 1)
            );
        }
    }

    private static List<ComputeSource> nonNullComputes(final ComputeSource[] sources) {
        if (sources == null || sources.length == 0) {
            return List.of();
        }
        List<ComputeSource> result = new ArrayList<>(sources.length);
        for (ComputeSource source : sources) {
            if (source != null && source.getSource().isPresent()) {
                result.add(source);
            }
        }
        return List.copyOf(result);
    }

    private static int shadowColorIndex(final String name) {
        if (name.equals("shadowcolor")) {
            return 0;
        }
        if (!name.startsWith("shadowcolor") || name.startsWith("shadowcolorimg")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("shadowcolor".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static GpuFormat formatForInternalName(final String name) {
        return switch (name) {
            case "R8" -> GpuFormat.R8_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            case "RGB8", "RGBA", "RGBA8" -> GpuFormat.RGBA8_UNORM;
            case "R8_SNORM" -> GpuFormat.R8_SNORM;
            case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
            case "RGB8_SNORM", "RGBA8_SNORM" -> GpuFormat.RGBA8_SNORM;
            case "R16" -> GpuFormat.R16_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RGB16", "RGBA16" -> GpuFormat.RGBA16_UNORM;
            case "R16_SNORM" -> GpuFormat.R16_SNORM;
            case "RG16_SNORM" -> GpuFormat.RG16_SNORM;
            case "RGB16_SNORM", "RGBA16_SNORM" -> GpuFormat.RGBA16_SNORM;
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "RGB16F", "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGB32F", "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
            case "R8I" -> GpuFormat.R8_SINT;
            case "RG8I" -> GpuFormat.RG8_SINT;
            case "RGB8I", "RGBA8I" -> GpuFormat.RGBA8_SINT;
            case "R8UI" -> GpuFormat.R8_UINT;
            case "RG8UI" -> GpuFormat.RG8_UINT;
            case "RGB8UI", "RGBA8UI" -> GpuFormat.RGBA8_UINT;
            case "R16I" -> GpuFormat.R16_SINT;
            case "RG16I" -> GpuFormat.RG16_SINT;
            case "RGB16I", "RGBA16I" -> GpuFormat.RGBA16_SINT;
            case "R16UI" -> GpuFormat.R16_UINT;
            case "RG16UI" -> GpuFormat.RG16_UINT;
            case "RGB16UI", "RGBA16UI" -> GpuFormat.RGBA16_UINT;
            case "R32I" -> GpuFormat.R32_SINT;
            case "RG32I" -> GpuFormat.RG32_SINT;
            case "RGB32I", "RGBA32I" -> GpuFormat.RGBA32_SINT;
            case "R32UI" -> GpuFormat.R32_UINT;
            case "RG32UI" -> GpuFormat.RG32_UINT;
            case "RGB32UI", "RGBA32UI" -> GpuFormat.RGBA32_UINT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            default -> throw new IllegalStateException(
                    "Iris shadowcolor format " + name + " has no exact Metal attachment mapping"
            );
        };
    }

    private void ensureExpectedCompositePass(final ShadowCompositePass pass) {
        requirePhase(Phase.COMPOSITE);
        if (nextCompositePass >= compositePasses.size() || compositePasses.get(nextCompositePass) != pass) {
            throw new IllegalStateException(
                    "Shadow composite passes must execute in plan order; expected index " + nextCompositePass
            );
        }
    }

    private void requirePhase(final Phase... allowed) {
        for (Phase candidate : allowed) {
            if (phase == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "Shadow pipeline phase " + phase + " is invalid here; expected " + List.of(allowed)
        );
    }

    private void ensureOpen() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("Iris Metal shadow pipeline is closed");
        }
    }

    @Override
    public void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        shadowPrograms.clear();
        compositePrograms.clear();
        computePrograms.clear();
        targets.close();
    }

    private record CompositePlan(List<ShadowCompositePass> passes, BitSet finalReadsFromAlt) {
        CompositePlan {
            passes = Collections.unmodifiableList(passes);
            finalReadsFromAlt = (BitSet) finalReadsFromAlt.clone();
        }

        @Override
        public BitSet finalReadsFromAlt() {
            return (BitSet) finalReadsFromAlt.clone();
        }
    }
}
