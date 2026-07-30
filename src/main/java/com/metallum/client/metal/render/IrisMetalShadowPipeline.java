package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
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
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.joml.Vector4f;
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
    private final List<ComputeSource> shadowComputes;
    private final List<ShadowCompositePass> compositePasses;
    private final BitSet finalReadsFromAlt;
    private final int targetCount;
    private final boolean enabled;
    private boolean fullClearRequired = true;
    private int nextCompositePass;
    private Phase phase = Phase.READY;

    IrisMetalShadowPipeline(final MetalDevice device, final ProgramSet programSet) {
        PackDirectives packDirectives = programSet.getPackDirectives();
        this.shadowDirectives = packDirectives.getShadowDirectives();
        this.resolver = new ProgramFallbackResolver(programSet);
        this.enabled = shadowDirectives.isShadowEnabled().orElse(true)
                && this.resolver.resolveNullable(ProgramId.ShadowSolid) != null;
        this.textureMap = packDirectives.getTextureMap();
        this.targetCount = programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;

        boolean[] nearestColor = new boolean[targetCount];
        GpuFormat[] colorFormats = new GpuFormat[targetCount];
        for (int index = 0; index < targetCount; index++) {
            PackShadowDirectives.SamplingSettings settings =
                    shadowDirectives.getColorSamplingSettings().computeIfAbsent(
                            index, ignored -> new PackShadowDirectives.SamplingSettings());
            if (settings.getMipmap()) {
                throw new IllegalStateException(
                        "Metal shadowcolor mipmaps are not available without mipmapped ping-pong targets"
                );
            }
            nearestColor[index] = settings.getNearest();
            colorFormats[index] = formatForInternalName(settings.getFormat().name());
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
                nearestDepth,
                mipmappedDepth
        );
        this.shadowComputes = nonNullComputes(programSet.getShadowCompute());
        CompositePlan plan = buildCompositePlan(programSet, packDirectives, targetCount);
        this.compositePasses = plan.passes();
        this.finalReadsFromAlt = plan.finalReadsFromAlt();
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
    void executeFrame(final MetalDevice device, final LevelRendererAdapter adapter) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(adapter, "adapter");
        if (!enabled) {
            return;
        }
        if (!shadowComputes.isEmpty()) {
            throw new IllegalStateException(
                    "The pack declares standalone shadow compute programs but no Metal compute dispatcher is connected"
            );
        }
        if (!compositePasses.isEmpty()) {
            throw new IllegalStateException(
                    "The pack declares " + compositePasses.size()
                            + " shadow composite pass(es), but Metal shadowcomp execution is not connected"
            );
        }
        MetalCommandEncoder encoder = device.commandEncoder();
        beginFrame(encoder, null);
        renderGeometry(encoder, adapter);
        finishComposites();
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
        phase = Phase.COMPOSITE;
    }

    MetalIrisShaderCompiler.GlslProgram compositeProgram(final ShadowCompositePass pass) {
        ensureExpectedCompositePass(pass);
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
        if (!pass.source().getDirectives().getMipmappedBuffers().isEmpty()) {
            throw new IllegalStateException(
                    "Shadow composite pass " + pass.name()
                            + " requests shadowcolor mipmaps, but its ping-pong targets are not mipmapped"
            );
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
                    targets.colorView(color, readsFromAlt), targets.colorSampler(color)
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
        Map<PatchShaderType, String> patched;
        if (key.patch == Patch.SODIUM) {
            patched = TransformPatcher.patchSodium(
                    source.getName(), vertex, null, null, null, fragment,
                    source.getDirectives().getAlphaTestOverride().orElse(key.getAlphaTest()),
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
                    source.getDirectives().getAlphaTestOverride().orElse(key.getAlphaTest()),
                    isLines, false, true, inputs, textureMap
            );
        } else {
            throw new IllegalStateException("Unsupported shadow patch family " + key.patch + " for " + key);
        }
        return linkPatchedPair(source, patched, shadowDrawBuffers(source.getDirectives()));
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
        return MetalIrisShaderCompiler.linkPatchedPair(source.getName(), vertex, fragment, drawBuffers);
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
