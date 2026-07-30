package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metal executor for Iris's deferred, composite and final full-screen passes.
 *
 * <p>The state transition is intentionally the one used by Iris 1.11.2's
 * {@code CompositeRenderer}, {@code FinalPassRenderer} and
 * {@code BufferFlipper}: a pass snapshots the flip set before it is built,
 * samples that side, writes the opposite side, then applies the implicit
 * DRAWBUFFERS flips followed by explicit flips. The final pass samples the
 * final snapshot, resolves into Minecraft's main color target, and copies
 * persistent flipped histories back to each target's main side.</p>
 *
 * <p>Oracle: Iris commit
 * {@code 20e226b14fd2c3ba192e16ae2c8af4a27987767c}, specifically
 * {@code CompositeRenderer}, {@code FinalPassRenderer},
 * {@code BufferFlipper}, and {@code RenderTargets}. Source:
 * https://github.com/IrisShaders/Iris/tree/20e226b14fd2c3ba192e16ae2c8af4a27987767c
 * This implementation is independent code against those observable
 * contracts; no upstream implementation is copied.</p>
 *
 * <p>External resources are fail-closed. Colortex aliases and depthtex0/1/2
 * are resolved here from generation-owned targets. Noise, shadow, custom
 * textures and uniform blocks must be supplied by {@link ResourceProvider}; a
 * missing binding aborts the pass instead of substituting a placeholder.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalPostChain implements AutoCloseable {
    static final String IRIS_ORACLE_COMMIT = "20e226b14fd2c3ba192e16ae2c8af4a27987767c";

    private static final Pattern COLORTEX_NAME = Pattern.compile("colortex(\\d+)");
    private static final Pattern FRAGMENT_OUTPUT_DECLARATION = Pattern.compile(
            "(?m)^(\\h*)((?:layout\\h*\\([^\\r\\n)]*\\)\\h*)?)"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\h+)*"
                    + "out\\h+(float|int|uint|vec[234]|ivec[234]|uvec[234])\\h+([A-Za-z_]\\w*)\\h*;"
    );
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\h+main\\h*\\(\\h*\\)\\h*\\{");
    private static final Pattern VOID_RETURN = Pattern.compile("\\breturn\\h*;");

    enum Stage {
        DEFERRED(ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre"),
        COMPOSITE(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre");

        final ProgramArrayId arrayId;
        final TextureStage textureStage;
        final String preFlipDirective;

        Stage(
                final ProgramArrayId arrayId,
                final TextureStage textureStage,
                final String preFlipDirective
        ) {
            this.arrayId = arrayId;
            this.textureStage = textureStage;
            this.preFlipDirective = preFlipDirective;
        }
    }

    /** Immutable public identity of a pass, suitable for resource lookup and tracing. */
    record PassInfo(
            Stage stage,
            String name,
            int[] drawBuffers,
            BitSet readsFromAlt,
            BitSet stateAfter,
            BitSet flippedAtLeastOnceBefore,
            Set<String> declaredSamplers
    ) {
        PassInfo {
            drawBuffers = drawBuffers.clone();
            readsFromAlt = copy(readsFromAlt);
            stateAfter = copy(stateAfter);
            flippedAtLeastOnceBefore = copy(flippedAtLeastOnceBefore);
            declaredSamplers = Set.copyOf(declaredSamplers);
        }

        PassInfo(
                final Stage stage,
                final String name,
                final int[] drawBuffers,
                final BitSet readsFromAlt,
                final BitSet stateAfter,
                final BitSet flippedAtLeastOnceBefore
        ) {
            this(
                    stage, name, drawBuffers, readsFromAlt, stateAfter,
                    flippedAtLeastOnceBefore, Set.of()
            );
        }

        @Override
        public int[] drawBuffers() {
            return drawBuffers.clone();
        }

        @Override
        public BitSet readsFromAlt() {
            return copy(readsFromAlt);
        }

        @Override
        public BitSet stateAfter() {
            return copy(stateAfter);
        }

        @Override
        public BitSet flippedAtLeastOnceBefore() {
            return copy(flippedAtLeastOnceBefore);
        }

        boolean declaresSampler(final String samplerName) {
            return declaredSamplers.contains(samplerName);
        }

        /**
         * Iris custom colortex overrides apply only until that logical target
         * has been written by an earlier pass in the same composite array.
         * Pre-flips intentionally do not deactivate an override. Legacy names
         * ({@code gcolor}, {@code gdepth}, ... ) follow the same target index.
         */
        boolean allowsCustomTextureOverride(final String samplerName) {
            int target = renderTargetIndex(samplerName);
            return target < 0 || !this.flippedAtLeastOnceBefore.get(target);
        }
    }

    record TextureBinding(GpuTextureView view, GpuSampler sampler) {
        TextureBinding {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(sampler, "sampler");
        }
    }

    /**
     * Supplies resources that are not owned by {@link IrisMetalRenderTargets}.
     * A provider may intentionally override a standard sampler name to honor
     * Iris custom-texture directives; returning {@code null} delegates standard
     * colortex/depth names back to this class.
     */
    interface ResourceProvider {
        @Nullable GpuBufferSlice uniform(PassInfo pass, String blockName);

        @Nullable TextureBinding texture(PassInfo pass, String samplerName);

        /**
         * Type-aware texture lookup used by the post executor. Shadow depth
         * resources can legally use the same name as either {@code sampler2D}
         * or {@code sampler2DShadow}; Metal must select a comparison sampler
         * only for the latter. The name-only method remains the compatibility
         * fallback for providers whose resources do not depend on GLSL type.
         */
        default @Nullable TextureBinding texture(
                final PassInfo pass,
                final MetalIrisShaderCompiler.SamplerDecl sampler
        ) {
            return texture(pass, sampler.name());
        }
    }

    record ExecutionReceipt(
            Stage stage,
            List<String> passes,
            BitSet stateAfter
    ) {
        ExecutionReceipt {
            passes = List.copyOf(passes);
            stateAfter = copy(stateAfter);
        }

        @Override
        public BitSet stateAfter() {
            return copy(stateAfter);
        }
    }

    record FinalReceipt(
            boolean shaderExecuted,
            boolean mainTargetResolved,
            Set<Integer> historyTargetsCopied,
            BitSet finalSnapshot
    ) {
        FinalReceipt {
            historyTargetsCopied = Set.copyOf(historyTargetsCopied);
            finalSnapshot = copy(finalSnapshot);
        }

        @Override
        public BitSet finalSnapshot() {
            return copy(finalSnapshot);
        }
    }

    /** Pure transition result used by the planner and focused tests. */
    record FlipTransition(BitSet readsFromAlt, BitSet stateAfter, BitSet flippedAtLeastOnceAfter) {
        FlipTransition {
            readsFromAlt = copy(readsFromAlt);
            stateAfter = copy(stateAfter);
            flippedAtLeastOnceAfter = copy(flippedAtLeastOnceAfter);
        }

        @Override
        public BitSet readsFromAlt() {
            return copy(readsFromAlt);
        }

        @Override
        public BitSet stateAfter() {
            return copy(stateAfter);
        }

        @Override
        public BitSet flippedAtLeastOnceAfter() {
            return copy(flippedAtLeastOnceAfter);
        }
    }

    private static final class PlannedPass {
        private final PassInfo info;
        private final MetalIrisShaderCompiler.GlslProgram program;
        private final ViewportData viewport;
        private final Set<Integer> mipmappedBuffers;
        private final Identifier vertexId;
        private final Identifier fragmentId;
        private @Nullable RenderPipeline pipeline;

        private PlannedPass(
                final PassInfo info,
                final MetalIrisShaderCompiler.GlslProgram program,
                final ViewportData viewport,
                final Set<Integer> mipmappedBuffers,
                final Identifier vertexId,
                final Identifier fragmentId
        ) {
            this.info = info;
            this.program = program;
            this.viewport = viewport;
            this.mipmappedBuffers = Set.copyOf(mipmappedBuffers);
            this.vertexId = vertexId;
            this.fragmentId = fragmentId;
        }
    }

    private static final class PlannedFinal {
        private final String name;
        private final BitSet readsFromAlt;
        private final BitSet flippedAtLeastOnce;
        private final MetalIrisShaderCompiler.GlslProgram program;
        private final Set<Integer> mipmappedBuffers;
        private final Identifier vertexId;
        private final Identifier fragmentId;
        private @Nullable RenderPipeline pipeline;

        private PlannedFinal(
                final String name,
                final BitSet readsFromAlt,
                final BitSet flippedAtLeastOnce,
                final MetalIrisShaderCompiler.GlslProgram program,
                final Set<Integer> mipmappedBuffers,
                final Identifier vertexId,
                final Identifier fragmentId
        ) {
            this.name = name;
            this.readsFromAlt = copy(readsFromAlt);
            this.flippedAtLeastOnce = copy(flippedAtLeastOnce);
            this.program = program;
            this.mipmappedBuffers = Set.copyOf(mipmappedBuffers);
            this.vertexId = vertexId;
            this.fragmentId = fragmentId;
        }

        private PassInfo info() {
            return new PassInfo(
                    Stage.COMPOSITE,
                    this.name,
                    new int[]{0},
                    this.readsFromAlt,
                    this.readsFromAlt,
                    this.flippedAtLeastOnce,
                    samplerNames(this.program)
            );
        }
    }

    private final int generation;
    private final int targetCount;
    private final EnumMap<Stage, List<PlannedPass>> passes;
    private final EnumMap<Stage, BitSet> stageInputs;
    private final EnumMap<Stage, BitSet> stageOutputs;
    private final BitSet finalSnapshot;
    private final Set<Integer> finalHistoryTargets;
    private final Set<Integer> mipmappedTargets;
    private final Map<Identifier, String> generatedSources;
    private final @Nullable PlannedFinal finalPass;
    private boolean prepared;
    private @Nullable GpuFormat preparedFinalFormat;
    private boolean closed;

    private IrisMetalPostChain(
            final int generation,
            final int targetCount,
            final EnumMap<Stage, List<PlannedPass>> passes,
            final EnumMap<Stage, BitSet> stageInputs,
            final EnumMap<Stage, BitSet> stageOutputs,
            final BitSet finalSnapshot,
            final Set<Integer> finalHistoryTargets,
            final Set<Integer> mipmappedTargets,
            final Map<Identifier, String> generatedSources,
            final @Nullable PlannedFinal finalPass
    ) {
        this.generation = generation;
        this.targetCount = targetCount;
        this.passes = passes;
        this.stageInputs = stageInputs;
        this.stageOutputs = stageOutputs;
        this.finalSnapshot = copy(finalSnapshot);
        this.finalHistoryTargets = Set.copyOf(finalHistoryTargets);
        this.mipmappedTargets = Set.copyOf(mipmappedTargets);
        this.generatedSources = Map.copyOf(generatedSources);
        this.finalPass = finalPass;
    }

    static IrisMetalPostChain create(
            final int generation,
            final ProgramSet programSet,
            final int targetCount,
            final BitSet initialFlipState
    ) {
        Objects.requireNonNull(programSet, "programSet");
        Objects.requireNonNull(initialFlipState, "initialFlipState");
        if (targetCount <= 0) {
            throw new IllegalArgumentException("Iris post chain needs at least one color target");
        }
        validateBits(initialFlipState, targetCount, "initial flip state");

        PackDirectives packDirectives = programSet.getPackDirectives();
        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap =
                packDirectives.getTextureMap();
        EnumMap<Stage, List<PlannedPass>> stages = new EnumMap<>(Stage.class);
        EnumMap<Stage, BitSet> inputs = new EnumMap<>(Stage.class);
        EnumMap<Stage, BitSet> outputs = new EnumMap<>(Stage.class);
        Map<Identifier, String> generated = new LinkedHashMap<>();
        BitSet state = copy(initialFlipState);
        BitSet compositeFlippedAtLeastOnce = new BitSet(targetCount);
        int ordinal = 0;

        for (Stage stage : Stage.values()) {
            state = applyPreFlips(
                    state,
                    packDirectives.getExplicitFlips(stage.preFlipDirective),
                    targetCount
            );
            inputs.put(stage, copy(state));
            BitSet flippedAtLeastOnce = new BitSet(targetCount);
            List<PlannedPass> stagePasses = new ArrayList<>();
            ProgramSource[] sources = programSet.getComposite(stage.arrayId);
            ComputeSource[][] computes = programSet.getCompute(stage.arrayId);

            for (int index = 0; index < sources.length; index++) {
                ComputeSource[] computeGroup = index < computes.length ? computes[index] : null;
                rejectComputes(stage.name().toLowerCase(Locale.ROOT), computeGroup);
                ProgramSource source = sources[index];
                if (source == null || !source.isValid()) {
                    continue;
                }

                ProgramDirectives directives = source.getDirectives();
                rejectUnsupportedBlend(source.getName(), directives);
                int[] drawBuffers = validatedDrawBuffers(
                        source.getName(), directives.getDrawBuffers(), targetCount
                );
                FlipTransition transition = transition(
                        state,
                        flippedAtLeastOnce,
                        drawBuffers,
                        directives.getExplicitFlips(),
                        targetCount
                );
                MetalIrisShaderCompiler.GlslProgram program = translate(
                        source, stage.textureStage, textureMap, drawBuffers
                );
                String base = "iris/gen" + generation + "/post/"
                        + stage.name().toLowerCase(Locale.ROOT) + "/" + ordinal++;
                Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
                Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
                generated.put(vertexId, program.vertexGlsl());
                generated.put(fragmentId, program.fragmentGlsl());
                stagePasses.add(new PlannedPass(
                        new PassInfo(
                                stage,
                                source.getName(),
                                drawBuffers,
                                transition.readsFromAlt(),
                                transition.stateAfter(),
                                flippedAtLeastOnce,
                                samplerNames(program)
                        ),
                        program,
                        directives.getViewportScale(),
                        directives.getMipmappedBuffers(),
                        vertexId,
                        fragmentId
                ));
                state = transition.stateAfter();
                flippedAtLeastOnce = transition.flippedAtLeastOnceAfter();
            }
            if (stage == Stage.COMPOSITE) {
                compositeFlippedAtLeastOnce = copy(flippedAtLeastOnce);
            }
            stages.put(stage, List.copyOf(stagePasses));
            outputs.put(stage, copy(state));
        }

        rejectComputes("final", programSet.getFinalCompute());
        PlannedFinal finalPass = null;
        Optional<ProgramSource> maybeFinal = programSet.get(ProgramId.Final);
        if (maybeFinal.isPresent() && maybeFinal.get().isValid()) {
            ProgramSource source = maybeFinal.get();
            ProgramDirectives directives = source.getDirectives();
            rejectUnsupportedBlend(source.getName(), directives);
            int[] declared = validatedDrawBuffers(source.getName(), directives.getDrawBuffers(), targetCount);
            MetalIrisShaderCompiler.GlslProgram program = translate(
                    source, TextureStage.COMPOSITE_AND_FINAL, textureMap, declared
            );
            String base = "iris/gen" + generation + "/post/final";
            Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
            Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
            generated.put(vertexId, program.vertexGlsl());
            generated.put(fragmentId, program.fragmentGlsl());
            finalPass = new PlannedFinal(
                    source.getName(),
                    state,
                    compositeFlippedAtLeastOnce,
                    program,
                    directives.getMipmappedBuffers(),
                    vertexId,
                    fragmentId
            );
        }

        Set<Integer> cleared = new HashSet<>();
        packDirectives.getRenderTargetDirectives().getBuffersToBeCleared().forEach(
                (int target) -> cleared.add(target)
        );
        Set<Integer> histories = finalHistoryTargets(state, cleared, targetCount);
        Set<Integer> mipmappedTargets = collectMipmappedTargets(stages, finalPass, targetCount);
        return new IrisMetalPostChain(
                generation,
                targetCount,
                stages,
                inputs,
                outputs,
                state,
                histories,
                mipmappedTargets,
                generated,
                finalPass
        );
    }

    /**
     * Composes generated pack sources with the normal game source provider.
     * The fallback is required in production because MetalDevice retains the
     * most recent precompile source for later cache misses.
     */
    ShaderSource shaderSource(final ShaderSource fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return (identifier, type) -> {
            String generated = this.generatedSources.get(identifier);
            return generated != null ? generated : fallback.get(identifier, type);
        };
    }

    /**
     * Builds and precompiles every render PSO. This must run before execution;
     * the supplied fallback keeps unrelated Mojang pipelines compilable after
     * MetalDevice installs the composed source provider.
     */
    void prepare(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final GpuFormat finalColorFormat,
            final ShaderSource fallback
    ) {
        ensureOpen();
        validateTargets(targets);
        Objects.requireNonNull(finalColorFormat, "finalColorFormat");
        if (this.prepared && this.preparedFinalFormat != finalColorFormat) {
            throw new IllegalStateException(
                    "Final target format changed from " + this.preparedFinalFormat
                            + " to " + finalColorFormat + "; rebuild the post-chain generation"
            );
        }
        ShaderSource source = shaderSource(fallback);
        for (Stage stage : Stage.values()) {
            for (PlannedPass pass : this.passes.get(stage)) {
                if (pass.pipeline == null) {
                    pass.pipeline = buildPipeline(pass, targets);
                }
                verifyPrecompile(device, device.precompilePipeline(pass.pipeline, source), pass.info.name());
            }
        }
        if (this.finalPass != null) {
            if (this.finalPass.pipeline == null) {
                this.finalPass.pipeline = buildFinalPipeline(this.finalPass, finalColorFormat);
            }
            verifyPrecompile(
                    device,
                    device.precompilePipeline(this.finalPass.pipeline, source),
                    this.finalPass.name
            );
        }
        this.preparedFinalFormat = finalColorFormat;
        this.prepared = true;
    }

    ExecutionReceipt executeStage(
            final Stage stage,
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        ensurePrepared();
        validateTargets(targets);
        Objects.requireNonNull(resources, "resources");
        IrisMetalPingPongTargets colors = targets.colorTargets();
        colors.restore(this.stageInputs.get(stage));
        List<String> executed = new ArrayList<>();
        for (PlannedPass pass : this.passes.get(stage)) {
            executePass(device, targets, resources, pass);
            colors.restore(pass.info.stateAfter());
            executed.add(pass.info.name());
        }
        BitSet expected = this.stageOutputs.get(stage);
        colors.restore(expected);
        return new ExecutionReceipt(stage, executed, expected);
    }

    FinalReceipt executeFinal(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final GpuTextureView mainColor,
            final ResourceProvider resources
    ) {
        ensurePrepared();
        validateTargets(targets);
        Objects.requireNonNull(mainColor, "mainColor");
        Objects.requireNonNull(resources, "resources");
        if (mainColor.texture().getFormat() != this.preparedFinalFormat) {
            throw new IllegalStateException(
                    "Prepared final format " + this.preparedFinalFormat
                            + " does not match live MainTarget " + mainColor.texture().getFormat()
            );
        }
        if (mainColor.getWidth(0) != targets.width() || mainColor.getHeight(0) != targets.height()) {
            throw new IllegalArgumentException(
                    "MainTarget extent " + mainColor.getWidth(0) + "x" + mainColor.getHeight(0)
                            + " does not match Iris targets " + targets.width() + "x" + targets.height()
            );
        }

        try {
            IrisMetalPingPongTargets colors = targets.colorTargets();
            colors.restore(this.finalSnapshot);
            MetalCommandEncoder encoder = device.commandEncoder();
            boolean shaderExecuted = this.finalPass != null;
            boolean resolved;
            if (this.finalPass != null) {
                generateMipmaps(encoder, targets, this.finalPass.mipmappedBuffers);
                RenderPassDescriptor descriptor = RenderPassDescriptor
                        .create(() -> "Iris final: " + this.finalPass.name)
                        .withColorAttachment(mainColor, Optional.empty())
                        .withRenderArea(new RenderPass.RenderArea(0, 0, targets.width(), targets.height()));
                MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(descriptor);
                try {
                    renderFullscreen(
                            renderPass,
                            Objects.requireNonNull(this.finalPass.pipeline, "final pipeline"),
                            this.finalPass.info(),
                            this.finalPass.program,
                            targets,
                            resources
                    );
                } finally {
                    encoder.submitRenderPass();
                }
                resolved = true;
            } else {
                resolved = encoder.encodeTextureCopy(
                        colors.readTexture(0),
                        (MetalGpuTexture) mainColor.texture(),
                        true
                );
                if (!resolved) {
                    throw new IllegalStateException("Metal final colortex0 -> MainTarget resolve failed");
                }
            }

            // Iris turns both physical-side mip sampler modes off before its
            // final history copies. The copies themselves are level-zero only.
            targets.resetMipmaps();
            Set<Integer> copied = new LinkedHashSet<>();
            for (int target : this.finalHistoryTargets) {
                MetalGpuTexture source = colors.readTexture(target);
                MetalGpuTexture destination = colors.mainTexture(target);
                if (source != destination) {
                    encoder.copyTextureToTexture(
                            source, destination, 0, 0, 0, 0, 0,
                            source.getWidth(0), source.getHeight(0)
                    );
                    copied.add(target);
                }
            }
            colors.restore(this.finalSnapshot);
            return new FinalReceipt(shaderExecuted, resolved, copied, this.finalSnapshot);
        } finally {
            // A failed final pass must not leak mip sampling into a later frame.
            targets.resetMipmaps();
        }
    }

    BitSet stageInput(final Stage stage) {
        return copy(this.stageInputs.get(stage));
    }

    BitSet stageOutput(final Stage stage) {
        return copy(this.stageOutputs.get(stage));
    }

    BitSet finalSnapshot() {
        return copy(this.finalSnapshot);
    }

    Set<Integer> finalHistoryTargets() {
        return this.finalHistoryTargets;
    }

    /** Logical targets requiring a full mip chain in this immutable generation. */
    Set<Integer> mipmappedTargets() {
        return this.mipmappedTargets;
    }

    List<PassInfo> passInfos(final Stage stage) {
        return this.passes.get(stage).stream().map(pass -> pass.info).toList();
    }

    boolean hasFinalShader() {
        return this.finalPass != null;
    }

    /** Whether any executable post/final program declares the named sampler. */
    boolean requiresSampler(final String samplerName) {
        Objects.requireNonNull(samplerName, "samplerName");
        for (Stage stage : Stage.values()) {
            for (PlannedPass pass : this.passes.get(stage)) {
                if (declaresSampler(pass.program, samplerName)) {
                    return true;
                }
            }
        }
        return this.finalPass != null && declaresSampler(this.finalPass.program, samplerName);
    }

    /** GLSL sampler kinds requested under this name anywhere in the generation. */
    Set<String> samplerTypes(final String samplerName) {
        Objects.requireNonNull(samplerName, "samplerName");
        Set<String> result = new LinkedHashSet<>();
        for (Stage stage : Stage.values()) {
            for (PlannedPass pass : this.passes.get(stage)) {
                collectSamplerTypes(pass.program, samplerName, result);
            }
        }
        if (this.finalPass != null) {
            collectSamplerTypes(this.finalPass.program, samplerName, result);
        }
        return Set.copyOf(result);
    }

    private static void collectSamplerTypes(
            final MetalIrisShaderCompiler.GlslProgram program,
            final String samplerName,
            final Set<String> result
    ) {
        program.samplers().stream()
                .filter(sampler -> sampler.name().equals(samplerName))
                .map(MetalIrisShaderCompiler.SamplerDecl::glslType)
                .forEach(result::add);
    }

    private static boolean declaresSampler(
            final MetalIrisShaderCompiler.GlslProgram program,
            final String samplerName
    ) {
        return program.samplers().stream().anyMatch(sampler -> sampler.name().equals(samplerName));
    }

    private static Set<String> samplerNames(final MetalIrisShaderCompiler.GlslProgram program) {
        return program.samplers().stream()
                .map(MetalIrisShaderCompiler.SamplerDecl::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Registers every post-pass loose-uniform layout in the generation block store. */
    void registerUniforms(final IrisMetalUniformValues values) {
        Objects.requireNonNull(values, "values");
        for (Stage stage : Stage.values()) {
            for (PlannedPass pass : this.passes.get(stage)) {
                values.register(uniformToken(pass.info), "post_" + stage.name().toLowerCase(Locale.ROOT)
                        + "_" + pass.info.name(), pass.program);
            }
        }
        if (this.finalPass != null) {
            PassInfo info = this.finalPass.info();
            values.register(uniformToken(info), "post_final_" + info.name(), this.finalPass.program);
        }
    }

    @Nullable GpuBufferSlice uniformSlice(
            final IrisMetalUniformValues values,
            final PassInfo pass
    ) {
        return values.slice(uniformToken(pass));
    }

    private static String uniformToken(final PassInfo pass) {
        return "post:" + pass.stage().name() + ':' + pass.name();
    }

    private void executePass(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources,
            final PlannedPass pass
    ) {
        IrisMetalPingPongTargets colors = targets.colorTargets();
        colors.restore(pass.info.readsFromAlt());
        generateMipmaps(device.commandEncoder(), targets, pass.mipmappedBuffers);
        RenderPass.RenderArea area = renderArea(pass.viewport, targets.width(), targets.height());
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor = targets.createWriteDescriptor(
                "Iris " + pass.info.stage().name().toLowerCase(Locale.ROOT) + ": " + pass.info.name(),
                pass.info.drawBuffers(),
                null,
                false,
                null,
                null
        )) {
            descriptor.descriptor().withRenderArea(area);
            MetalCommandEncoder encoder = device.commandEncoder();
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            try {
                renderFullscreen(
                        renderPass,
                        Objects.requireNonNull(pass.pipeline, "post pipeline"),
                        pass.info,
                        pass.program,
                        targets,
                        resources
                );
            } finally {
                encoder.submitRenderPass();
            }
        }
    }

    private static void renderFullscreen(
            final MetalRenderPass renderPass,
            final RenderPipeline pipeline,
            final PassInfo info,
            final MetalIrisShaderCompiler.GlslProgram program,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        renderPass.setPipeline(pipeline);
        bindResources(renderPass, info, program, targets, resources);
        GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
        renderPass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
        renderPass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
        renderPass.drawIndexed(6, 1, 0, 0, 0);
    }

    private static void bindResources(
            final MetalRenderPass renderPass,
            final PassInfo info,
            final MetalIrisShaderCompiler.GlslProgram program,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        for (String block : program.uniformBlockNames()) {
            GpuBufferSlice slice = resources.uniform(info, block);
            if (slice == null) {
                throw new IllegalStateException(
                        "Iris pass " + info.name() + " is missing required uniform block '" + block + "'"
                );
            }
            renderPass.setUniform(block, slice);
        }
        for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
            TextureBinding binding = externalTexture(resources, info, sampler);
            if (binding == null) {
                binding = standardTexture(info, sampler.name(), targets);
            }
            if (binding == null) {
                throw new IllegalStateException(
                        "Iris pass " + info.name() + " is missing required sampler '" + sampler.name()
                                + "' (" + sampler.glslType() + ")"
                );
            }
            renderPass.bindTexture(sampler.name(), binding.view(), binding.sampler());
        }
    }

    static @Nullable TextureBinding externalTexture(
            final ResourceProvider resources,
            final PassInfo pass,
            final MetalIrisShaderCompiler.SamplerDecl sampler
    ) {
        return resources.texture(pass, sampler);
    }

    private static @Nullable TextureBinding standardTexture(
            final PassInfo info,
            final String name,
            final IrisMetalRenderTargets targets
    ) {
        int target = renderTargetIndex(name);
        if (target >= 0) {
            if (target >= targets.colorTargets().targetCount()) {
                throw new IllegalStateException(
                        "Sampler '" + name + "' resolves to colortex" + target
                                + " but this generation has only " + targets.colorTargets().targetCount() + " targets"
                );
            }
            return new TextureBinding(
                    targets.colorTargets().readView(target),
                    targets.colorSampler(target)
            );
        }
        return switch (name) {
            case "depthtex0" -> new TextureBinding(targets.mainDepthView(), targets.depthSampler());
            case "depthtex1" -> new TextureBinding(targets.noTranslucentsDepthView(), targets.depthSampler());
            case "depthtex2" -> new TextureBinding(targets.noHandDepthView(), targets.depthSampler());
            default -> null;
        };
    }

    static int renderTargetIndex(final String name) {
        Matcher matcher = COLORTEX_NAME.matcher(name);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(name);
    }

    private static void generateMipmaps(
            final MetalCommandEncoder encoder,
            final IrisMetalRenderTargets targets,
            final Set<Integer> mipmappedBuffers
    ) {
        for (int target : mipmappedBuffers) {
            MetalGpuTexture texture = targets.colorTargets().readTexture(target);
            if (texture.getMipLevels() <= 1) {
                throw new IllegalStateException(
                        "Iris pass requests mipmaps for colortex" + target
                                + " but the generation allocated only one mip level"
                );
            }
            encoder.generateMipmaps(texture);
            targets.enableReadMipmaps(target);
        }
    }

    private static RenderPipeline buildPipeline(
            final PlannedPass pass,
            final IrisMetalRenderTargets targets
    ) {
        RenderPipeline.Builder builder = basePipeline(
                pass.vertexId,
                pass.fragmentId,
                Identifier.fromNamespaceAndPath(
                        "metallum", pass.vertexId.getPath().substring(0, pass.vertexId.getPath().length() - 2)
                ),
                pass.program
        );
        int[] drawBuffers = pass.info.drawBuffers();
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            builder.withColorTargetState(slot, new ColorTargetState(
                    Optional.empty(),
                    targets.colorTargets().format(drawBuffers[slot]),
                    ColorTargetState.WRITE_ALL
            ));
        }
        return builder.build();
    }

    private static RenderPipeline buildFinalPipeline(
            final PlannedFinal pass,
            final GpuFormat finalColorFormat
    ) {
        return basePipeline(
                pass.vertexId,
                pass.fragmentId,
                Identifier.fromNamespaceAndPath("metallum", "iris/gen/post/final"),
                pass.program
        ).withColorTargetState(new ColorTargetState(
                Optional.empty(), finalColorFormat, ColorTargetState.WRITE_ALL
        )).build();
    }

    private static RenderPipeline.Builder basePipeline(
            final Identifier vertexId,
            final Identifier fragmentId,
            final Identifier location,
            final MetalIrisShaderCompiler.GlslProgram program
    ) {
        BindGroupLayout.Builder bindings = BindGroupLayout.builder();
        Set<String> names = new HashSet<>();
        for (String block : program.uniformBlockNames()) {
            if (!names.add(block)) {
                throw new IllegalStateException("Duplicate post resource '" + block + "'");
            }
            bindings.withUniform(block, UniformType.UNIFORM_BUFFER);
        }
        for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
            if (!names.add(sampler.name())) {
                throw new IllegalStateException("Duplicate post resource '" + sampler.name() + "'");
            }
            if (sampler.glslType().toLowerCase(Locale.ROOT).contains("samplerbuffer")) {
                throw new UnsupportedOperationException(
                        "Post sampler buffer '" + sampler.name() + "' needs a typed texel-buffer binding"
                );
            }
            bindings.withSampler(sampler.name());
        }
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
        if (!names.isEmpty()) {
            builder.withBindGroupLayout(bindings.build());
        }
        return builder;
    }

    private static RenderPass.RenderArea renderArea(
            final ViewportData viewport,
            final int width,
            final int height
    ) {
        int x = (int) (width * viewport.viewportX());
        int y = (int) (height * viewport.viewportY());
        int scaledWidth = (int) (width * viewport.scale());
        int scaledHeight = (int) (height * viewport.scale());
        if (scaledWidth <= 0 || scaledHeight <= 0 || x < 0 || y < 0
                || x + scaledWidth > width || y + scaledHeight > height) {
            throw new IllegalArgumentException(
                    "Invalid Iris viewport " + viewport + " for " + width + "x" + height
            );
        }
        return new RenderPass.RenderArea(x, y, scaledWidth, scaledHeight);
    }

    private static MetalIrisShaderCompiler.GlslProgram translate(
            final ProgramSource source,
            final TextureStage textureStage,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final int[] drawBuffers
    ) {
        if (source.getGeometrySource().isPresent()
                || source.getTessControlSource().isPresent()
                || source.getTessEvalSource().isPresent()) {
            throw new UnsupportedOperationException(
                    "Iris post program " + source.getName()
                            + " uses geometry/tessellation stages unsupported by the Metal path"
            );
        }
        Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(
                source.getName(),
                source.getVertexSource().orElseThrow(),
                null,
                source.getFragmentSource().orElseThrow(),
                textureStage,
                textureMap
        );
        String vertex = Objects.requireNonNull(patched.get(PatchShaderType.VERTEX), "patched vertex");
        String fragment = widenFragmentOutputsForMetal(
                Objects.requireNonNull(patched.get(PatchShaderType.FRAGMENT), "patched fragment")
        );
        return MetalIrisShaderCompiler.linkPatchedPair(
                source.getName(), vertex, fragment, drawBuffers
        );
    }

    /**
     * Metal has no renderable RGB attachment formats and requires a color
     * result to provide every component present in the attachment. GLSL/OpenGL
     * permits a {@code vec3} output to an RGBA target, so keep the pack's
     * original variable as private shader state and export a four-component
     * value at every exit from {@code main}. This is an ABI adaptation based on
     * declared output types, not shader-pack text or names.
     */
    static String widenFragmentOutputsForMetal(final String source) {
        Matcher declarations = FRAGMENT_OUTPUT_DECLARATION.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length() + 256);
        List<FragmentOutput> widened = new ArrayList<>();
        while (declarations.find()) {
            String type = declarations.group(3);
            int components = vectorComponents(type);
            if (components == 4) {
                declarations.appendReplacement(rewritten, Matcher.quoteReplacement(declarations.group()));
                continue;
            }
            String name = declarations.group(4);
            String exportName = "metallum_FragColor_" + name;
            String exportType = vectorPrefix(type) + "vec4";
            String replacement = declarations.group(1) + type + " " + name + ";\n"
                    + declarations.group(1) + declarations.group(2)
                    + "out " + exportType + " " + exportName + ";";
            declarations.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
            widened.add(new FragmentOutput(name, exportName, type, exportType, components));
        }
        declarations.appendTail(rewritten);
        if (widened.isEmpty()) {
            return source;
        }

        String result = rewritten.toString();
        Matcher main = MAIN_FUNCTION.matcher(result);
        if (!main.find()) {
            throw new IllegalArgumentException("Fragment shader declares color outputs but has no void main()");
        }
        int closingBrace = matchingBrace(result, main.end() - 1);
        String flush = renderFragmentOutputFlush(widened);
        String body = result.substring(main.end(), closingBrace);
        Matcher returns = VOID_RETURN.matcher(body);
        StringBuffer rewrittenBody = new StringBuffer(body.length() + flush.length());
        while (returns.find()) {
            returns.appendReplacement(
                    rewrittenBody,
                    Matcher.quoteReplacement(flush + "\n    return;")
            );
        }
        returns.appendTail(rewrittenBody);
        return result.substring(0, main.end())
                + rewrittenBody
                + flush
                + result.substring(closingBrace);
    }

    private record FragmentOutput(
            String sourceName,
            String exportName,
            String sourceType,
            String exportType,
            int components
    ) {
    }

    private static String renderFragmentOutputFlush(final List<FragmentOutput> outputs) {
        StringBuilder result = new StringBuilder();
        for (FragmentOutput output : outputs) {
            String zero = output.exportType().startsWith("u") ? "0u" : "0";
            String one = output.exportType().startsWith("u") ? "1u" : "1";
            result.append("\n    ").append(output.exportName()).append(" = ")
                    .append(output.exportType()).append('(').append(output.sourceName());
            for (int component = output.components(); component < 3; component++) {
                result.append(", ").append(zero);
            }
            result.append(", ").append(one).append(");");
        }
        return result.toString();
    }

    private static int vectorComponents(final String type) {
        char last = type.charAt(type.length() - 1);
        return Character.isDigit(last) ? last - '0' : 1;
    }

    private static String vectorPrefix(final String type) {
        return type.startsWith("ivec") || type.equals("int")
                ? "i"
                : type.startsWith("uvec") || type.equals("uint") ? "u" : "";
    }

    private static int matchingBrace(final String source, final int openingBrace) {
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unbalanced fragment main() braces");
    }

    private static void verifyPrecompile(
            final MetalDevice device,
            final CompiledRenderPipeline compiled,
            final String passName
    ) {
        if (!device.asyncPrewarmEnabled() && !compiled.isValid()) {
            throw new IllegalStateException(
                    "Metal render pipeline state is invalid for Iris pass " + passName
            );
        }
    }

    private static void rejectComputes(final String group, final ComputeSource @Nullable [] computes) {
        if (computes == null) {
            return;
        }
        for (ComputeSource compute : computes) {
            if (compute != null && compute.isValid()) {
                throw new UnsupportedOperationException(
                        "Iris " + group + " compute program " + compute.getName()
                                + " has no Metal post-chain executor yet"
                );
            }
        }
    }

    private static void rejectUnsupportedBlend(
            final String name,
            final ProgramDirectives directives
    ) {
        if (directives.getBlendModeOverride().isPresent()) {
            throw new UnsupportedOperationException(
                    "Iris post program " + name + " declares a blend override;"
                            + " Metal post blending must be mapped before this pass can execute"
            );
        }
        if (!directives.getBufferBlendOverrides().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Iris post program " + name + " declares per-buffer blend overrides;"
                            + " Metal post blending must be mapped before this pass can execute"
            );
        }
    }

    private static int[] validatedDrawBuffers(
            final String name,
            final int[] drawBuffers,
            final int targetCount
    ) {
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("Iris post program " + name + " has no DRAWBUFFERS");
        }
        int[] result = drawBuffers.clone();
        BitSet seen = new BitSet(targetCount);
        for (int target : result) {
            validateTarget(target, targetCount, "DRAWBUFFERS of " + name);
            if (seen.get(target)) {
                throw new IllegalArgumentException(
                        "Iris post program " + name + " repeats DRAWBUFFERS target " + target
                );
            }
            seen.set(target);
        }
        return result;
    }

    private static Set<Integer> collectMipmappedTargets(
            final EnumMap<Stage, List<PlannedPass>> passes,
            final @Nullable PlannedFinal finalPass,
            final int targetCount
    ) {
        Set<Integer> result = new LinkedHashSet<>();
        for (Stage stage : Stage.values()) {
            for (PlannedPass pass : passes.get(stage)) {
                for (int target : pass.mipmappedBuffers) {
                    validateTarget(target, targetCount, "mipmap directive of " + pass.info.name());
                    result.add(target);
                }
            }
        }
        if (finalPass != null) {
            for (int target : finalPass.mipmappedBuffers) {
                validateTarget(target, targetCount, "mipmap directive of " + finalPass.name);
                result.add(target);
            }
        }
        return Set.copyOf(result);
    }

    static BitSet applyPreFlips(
            final BitSet before,
            final Map<Integer, Boolean> explicitPreFlips,
            final int targetCount
    ) {
        validateBits(before, targetCount, "pre-flip input");
        BitSet after = copy(before);
        explicitPreFlips.forEach((target, shouldFlip) -> {
            validateTarget(target, targetCount, "explicit pre-flip");
            if (Boolean.TRUE.equals(shouldFlip)) {
                after.flip(target);
            }
        });
        return after;
    }

    /**
     * Iris transition order, including the intentional double toggle when an
     * explicitly-true target also appears in DRAWBUFFERS.
     */
    static FlipTransition transition(
            final BitSet before,
            final BitSet flippedAtLeastOnceBefore,
            final int[] drawBuffers,
            final Map<Integer, Boolean> explicitFlips,
            final int targetCount
    ) {
        validateBits(before, targetCount, "pass input");
        validateBits(flippedAtLeastOnceBefore, targetCount, "flip history input");
        BitSet snapshot = copy(before);
        BitSet after = copy(before);
        BitSet history = copy(flippedAtLeastOnceBefore);
        for (int target : drawBuffers) {
            validateTarget(target, targetCount, "DRAWBUFFERS transition");
            if (explicitFlips.get(target) == Boolean.FALSE) {
                continue;
            }
            after.flip(target);
            history.set(target);
        }
        explicitFlips.forEach((target, shouldFlip) -> {
            validateTarget(target, targetCount, "explicit flip");
            if (Boolean.TRUE.equals(shouldFlip)) {
                after.flip(target);
                history.set(target);
            }
        });
        return new FlipTransition(snapshot, after, history);
    }

    static Set<Integer> finalHistoryTargets(
            final BitSet finalSnapshot,
            final Set<Integer> buffersClearedEveryFrame,
            final int targetCount
    ) {
        validateBits(finalSnapshot, targetCount, "final snapshot");
        Set<Integer> result = new LinkedHashSet<>();
        for (int target = finalSnapshot.nextSetBit(0);
             target >= 0;
             target = finalSnapshot.nextSetBit(target + 1)) {
            if (!buffersClearedEveryFrame.contains(target)) {
                result.add(target);
            }
        }
        return Set.copyOf(result);
    }

    private static BitSet copy(final BitSet source) {
        return (BitSet) source.clone();
    }

    private static void validateBits(
            final BitSet bits,
            final int targetCount,
            final String description
    ) {
        if (bits.length() > targetCount) {
            throw new IllegalArgumentException(
                    description + " contains target " + (bits.length() - 1)
                            + " but target count is " + targetCount
            );
        }
    }

    private static void validateTarget(
            final int target,
            final int targetCount,
            final String description
    ) {
        if (target < 0 || target >= targetCount) {
            throw new IllegalArgumentException(
                    description + " target out of range: " + target
                            + " (count=" + targetCount + ")"
            );
        }
    }

    private void validateTargets(final IrisMetalRenderTargets targets) {
        if (targets.colorTargets().targetCount() != this.targetCount) {
            throw new IllegalArgumentException(
                    "Post-chain generation expects " + this.targetCount
                            + " color targets, got " + targets.colorTargets().targetCount()
            );
        }
    }

    private void ensurePrepared() {
        ensureOpen();
        if (!this.prepared) {
            throw new IllegalStateException("Iris Metal post chain has not been prepared");
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal post chain is closed");
        }
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
