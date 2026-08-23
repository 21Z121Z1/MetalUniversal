package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.properties.IndirectPointer;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3i;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Metal executor for Iris's setup, begin, prepare, deferred, composite and
 * final full-screen passes.
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

    private static final String COLOR_SPACE_VERTEX = """
            #version 450 core

            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 0) out vec2 uv;

            void main() {
                gl_Position = vec4(Position.xy * 2.0 - 1.0, Position.z, 1.0);
                uv = UV0;
            }
            """;

    private static final Pattern COLORTEX_NAME = Pattern.compile("colortex(\\d+)");
    private static final Pattern COLOR_IMAGE_NAME = Pattern.compile("colorimg(\\d+)");
    private static final Pattern FRAGMENT_OUTPUT_DECLARATION = Pattern.compile(
            "(?m)^(\\h*)((?:layout\\h*\\([^\\r\\n)]*\\)\\h*)?)"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\h+)*"
                    + "out\\h+(float|int|uint|vec[234]|ivec[234]|uvec[234])\\h+([A-Za-z_]\\w*)\\h*;"
    );
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\h+main\\h*\\(\\h*\\)\\h*\\{");
    private static final Pattern VOID_RETURN = Pattern.compile("\\breturn\\h*;");

    private static final class PlannedColorSpacePass {
        private final ColorSpace colorSpace;
        private final PassInfo info;
        private final MetalIrisShaderCompiler.GlslProgram program;
        private final Identifier vertexId;
        private final Identifier fragmentId;
        private @Nullable RenderPipeline pipeline;

        private PlannedColorSpacePass(
                final ColorSpace colorSpace,
                final MetalIrisShaderCompiler.GlslProgram program,
                final Identifier vertexId,
                final Identifier fragmentId
        ) {
            this.colorSpace = colorSpace;
            this.info = new PassInfo(
                    Stage.FINAL,
                    "iris-color-space-" + colorSpace.name().toLowerCase(Locale.ROOT),
                    new int[]{0},
                    new BitSet(),
                    new BitSet(),
                    new BitSet(),
                    samplerNames(program)
            );
            this.program = program;
            this.vertexId = vertexId;
            this.fragmentId = fragmentId;
        }
    }

    enum Stage {
        SETUP(null, TextureStage.SETUP, null),
        BEGIN(ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre"),
        SHADOW_COMPOSITE(null, TextureStage.SHADOWCOMP, null),
        PREPARE(ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre"),
        DEFERRED(ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre"),
        COMPOSITE(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre"),
        /** The standalone Iris final renderer and final compute queue. */
        FINAL(null, TextureStage.COMPOSITE_AND_FINAL, null);

        final @Nullable ProgramArrayId arrayId;
        final TextureStage textureStage;
        final @Nullable String preFlipDirective;

        Stage(
                final @Nullable ProgramArrayId arrayId,
                final TextureStage textureStage,
                final @Nullable String preFlipDirective
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

    /** A typed Iris samplerBuffer range. The format is part of the binding ABI. */
    record TexelBufferBinding(GpuBufferSlice slice, GpuFormat format) {
        TexelBufferBinding {
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(format, "format");
        }
    }

    private record TargetBlendState(
            Optional<BlendFunction> global,
            Map<Integer, Optional<BlendFunction>> perTarget
    ) {
        TargetBlendState {
            Objects.requireNonNull(global, "global");
            perTarget = Map.copyOf(perTarget);
        }

        Optional<BlendFunction> forTarget(final int logicalTarget) {
            return perTarget.getOrDefault(logicalTarget, global);
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

        default @Nullable GpuBufferSlice uniform(
                final PassInfo pass,
                final String blockName,
                final Object token
        ) {
            return uniform(pass, blockName);
        }

        default @Nullable GpuBufferSlice uniform(
                final PassInfo pass,
                final String blockName,
                final Object token,
                final IrisMetalUniformValues.DrawUniformContext context
        ) {
            return uniform(pass, blockName, token);
        }

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

        default @Nullable GpuTextureView storageImage(final PassInfo pass, final String imageName) {
            return null;
        }

        default @Nullable GpuBufferSlice storageBuffer(final int binding) {
            return null;
        }

        /**
         * Resolves a raster samplerBuffer before its render PSO is built. Iris
         * has no samplerBuffer format in the GLSL type, so providers must
         * supply the exact Metal/GpuFormat alongside the byte range.
         */
        default @Nullable TexelBufferBinding texelBuffer(
                final PassInfo pass,
                final MetalIrisShaderCompiler.SamplerDecl sampler
        ) {
            return null;
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
        private final int arrayIndex;
        private final PassInfo info;
        private final MetalIrisShaderCompiler.GlslProgram program;
        private final ViewportData viewport;
        private final Set<Integer> mipmappedBuffers;
        private final TargetBlendState blendState;
        private final Identifier vertexId;
        private final Identifier fragmentId;
        private @Nullable RenderPipeline pipeline;

        private PlannedPass(
                final int arrayIndex,
                final PassInfo info,
                final MetalIrisShaderCompiler.GlslProgram program,
                final ViewportData viewport,
                final Set<Integer> mipmappedBuffers,
                final TargetBlendState blendState,
                final Identifier vertexId,
                final Identifier fragmentId
        ) {
            this.arrayIndex = arrayIndex;
            this.info = info;
            this.program = program;
            this.viewport = viewport;
            this.mipmappedBuffers = Set.copyOf(mipmappedBuffers);
            this.blendState = blendState;
            this.vertexId = vertexId;
            this.fragmentId = fragmentId;
        }
    }

    private static final class PlannedCompute {
        private final String token;
        private final PassInfo info;
        private final ComputeSource source;
        private final MetalIrisShaderCompiler.TranslatedStage translated;
        private final MetalIrisShaderCompiler.ComputeReflection reflection;
        private @Nullable MetalComputePipeline pipeline;

        private PlannedCompute(
                final String token,
                final PassInfo info,
                final ComputeSource source,
                final MetalIrisShaderCompiler.TranslatedStage translated
        ) {
            this.token = token;
            this.info = info;
            this.source = source;
            this.translated = translated;
            this.reflection = Objects.requireNonNull(
                    translated.computeReflection(), "compute reflection for " + source.getName()
            );
        }
    }

    private record PlannedComputeGroup(int arrayIndex, List<PlannedCompute> computes) {
        PlannedComputeGroup {
            computes = List.copyOf(computes);
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
                    Stage.FINAL,
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
    private final EnumMap<Stage, List<PlannedComputeGroup>> computeGroups;
    private final List<PlannedCompute> setupComputes;
    private final List<PlannedCompute> finalComputes;
    private final EnumMap<Stage, BitSet> stageInputs;
    private final EnumMap<Stage, BitSet> stageOutputs;
    private final BitSet finalSnapshot;
    private final Set<Integer> finalHistoryTargets;
    private final Set<Integer> mipmappedTargets;
    private final Set<Integer> storageImageTargets;
    private final Map<Identifier, String> generatedSources;
    private final boolean concurrentCompute;
    private final boolean packOwnsColorCorrection;
    private final EnumMap<ColorSpace, PlannedColorSpacePass> colorSpacePasses;
    private final @Nullable PlannedFinal finalPass;
    private @Nullable MetalGpuTexture colorSpaceSwap;
    private @Nullable MetalGpuTextureView colorSpaceSwapView;
    private @Nullable MetalGpuSampler colorSpaceSampler;
    private boolean prepared;
    private @Nullable GpuFormat preparedFinalFormat;
    private boolean closed;

    private IrisMetalPostChain(
            final int generation,
            final int targetCount,
            final EnumMap<Stage, List<PlannedPass>> passes,
            final EnumMap<Stage, List<PlannedComputeGroup>> computeGroups,
            final List<PlannedCompute> setupComputes,
            final List<PlannedCompute> finalComputes,
            final EnumMap<Stage, BitSet> stageInputs,
            final EnumMap<Stage, BitSet> stageOutputs,
            final BitSet finalSnapshot,
            final Set<Integer> finalHistoryTargets,
            final Set<Integer> mipmappedTargets,
            final Set<Integer> storageImageTargets,
            final Map<Identifier, String> generatedSources,
            final boolean concurrentCompute,
            final boolean packOwnsColorCorrection,
            final EnumMap<ColorSpace, PlannedColorSpacePass> colorSpacePasses,
            final @Nullable PlannedFinal finalPass
    ) {
        this.generation = generation;
        this.targetCount = targetCount;
        this.passes = passes;
        this.computeGroups = computeGroups;
        this.setupComputes = List.copyOf(setupComputes);
        this.finalComputes = List.copyOf(finalComputes);
        this.stageInputs = stageInputs;
        this.stageOutputs = stageOutputs;
        this.finalSnapshot = copy(finalSnapshot);
        this.finalHistoryTargets = Set.copyOf(finalHistoryTargets);
        this.mipmappedTargets = Set.copyOf(mipmappedTargets);
        this.storageImageTargets = Set.copyOf(storageImageTargets);
        this.generatedSources = Map.copyOf(generatedSources);
        this.concurrentCompute = concurrentCompute;
        this.packOwnsColorCorrection = packOwnsColorCorrection;
        this.colorSpacePasses = colorSpacePasses;
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
        EnumMap<Stage, List<PlannedComputeGroup>> computeStages = new EnumMap<>(Stage.class);
        EnumMap<Stage, BitSet> inputs = new EnumMap<>(Stage.class);
        EnumMap<Stage, BitSet> outputs = new EnumMap<>(Stage.class);
        Map<Identifier, String> generated = new LinkedHashMap<>();
        BitSet state = copy(initialFlipState);
        BitSet compositeFlippedAtLeastOnce = new BitSet(targetCount);
        int ordinal = 0;
        int computeOrdinal = 0;
        List<PlannedCompute> setupComputes = planComputes(
                programSet.getSetup(),
                Stage.SETUP,
                -1,
                state,
                textureMap,
                targetCount,
                computeOrdinal
        );
        computeOrdinal += setupComputes.size();

        for (Stage stage : Stage.values()) {
            if (stage.preFlipDirective != null) {
                state = applyPreFlips(
                        state,
                        packDirectives.getExplicitFlips(stage.preFlipDirective),
                        targetCount
                );
            }
            inputs.put(stage, copy(state));
            BitSet flippedAtLeastOnce = new BitSet(targetCount);
            List<PlannedPass> stagePasses = new ArrayList<>();
            List<PlannedComputeGroup> stageComputes = new ArrayList<>();
            ProgramSource[] sources = stage.arrayId == null
                    ? new ProgramSource[0]
                    : programSet.getComposite(stage.arrayId);
            ComputeSource[][] computes = stage.arrayId == null
                    ? new ComputeSource[0][]
                    : programSet.getCompute(stage.arrayId);

            int slotCount = Math.max(sources.length, computes.length);
            for (int index = 0; index < slotCount; index++) {
                ComputeSource[] computeGroup = index < computes.length ? computes[index] : null;
                List<PlannedCompute> plannedComputes = planComputes(
                        computeGroup,
                        stage,
                        index,
                        state,
                        textureMap,
                        targetCount,
                        computeOrdinal
                );
                computeOrdinal += plannedComputes.size();
                if (!plannedComputes.isEmpty()) {
                    stageComputes.add(new PlannedComputeGroup(index, plannedComputes));
                }
                ProgramSource source = index < sources.length ? sources[index] : null;
                if (source == null || !source.isValid()) {
                    continue;
                }

                ProgramDirectives directives = source.getDirectives();
                TargetBlendState blendState = blendState(directives, targetCount);
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
                        index,
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
                        blendState,
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
            computeStages.put(stage, List.copyOf(stageComputes));
            outputs.put(stage, copy(state));
        }

        List<PlannedCompute> finalComputes = planComputes(
                programSet.getFinalCompute(),
                Stage.FINAL,
                -1,
                state,
                textureMap,
                targetCount,
                computeOrdinal
        );
        PlannedFinal finalPass = null;
        Optional<ProgramSource> maybeFinal = programSet.get(ProgramId.Final);
        if (maybeFinal.isPresent() && maybeFinal.get().isValid()) {
            ProgramSource source = maybeFinal.get();
            ProgramDirectives directives = source.getDirectives();
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
        Set<Integer> storageImageTargets = collectStorageImageTargets(
                computeStages, setupComputes, finalComputes, targetCount
        );
        EnumMap<ColorSpace, PlannedColorSpacePass> colorSpacePasses = new EnumMap<>(ColorSpace.class);
        if (!packDirectives.supportsColorCorrection()) {
            for (ColorSpace colorSpace : ColorSpace.values()) {
                if (colorSpace == ColorSpace.SRGB) {
                    continue;
                }
                String base = "iris/gen" + generation + "/presentation/"
                        + colorSpace.name().toLowerCase(Locale.ROOT);
                Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
                Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
                MetalIrisShaderCompiler.GlslProgram program = colorSpaceProgram(colorSpace);
                generated.put(vertexId, program.vertexGlsl());
                generated.put(fragmentId, program.fragmentGlsl());
                colorSpacePasses.put(
                        colorSpace,
                        new PlannedColorSpacePass(colorSpace, program, vertexId, fragmentId)
                );
            }
        }
        return new IrisMetalPostChain(
                generation,
                targetCount,
                stages,
                computeStages,
                setupComputes,
                finalComputes,
                inputs,
                outputs,
                state,
                histories,
                mipmappedTargets,
                storageImageTargets,
                generated,
                packDirectives.getConcurrentCompute(),
                packDirectives.supportsColorCorrection(),
                colorSpacePasses,
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
        prepare(device, targets, finalColorFormat, fallback, null);
    }

    void prepare(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final GpuFormat finalColorFormat,
            final ShaderSource fallback,
            final @Nullable ResourceProvider resources
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
        for (PlannedCompute compute : allComputes()) {
            if (compute.pipeline == null) {
                compute.pipeline = MetalComputePipeline.compileTranslated(
                        device,
                        "iris/gen" + this.generation + "/compute/" + compute.source.getName(),
                        compute.translated
                );
            }
        }
        for (Stage stage : Stage.values()) {
            for (PlannedPass pass : this.passes.get(stage)) {
                if (pass.pipeline == null) {
                    pass.pipeline = buildPipeline(pass, targets, resources);
                }
                verifyPrecompile(device, device.precompilePipeline(pass.pipeline, source), pass.info.name());
            }
        }
        if (this.finalPass != null) {
            if (this.finalPass.pipeline == null) {
                this.finalPass.pipeline = buildFinalPipeline(this.finalPass, finalColorFormat, resources);
            }
            verifyPrecompile(
                    device,
                    device.precompilePipeline(this.finalPass.pipeline, source),
                    this.finalPass.name
            );
        }
        if (!this.colorSpacePasses.isEmpty()) {
            if (finalColorFormat != GpuFormat.RGBA8_UNORM) {
                throw new UnsupportedOperationException(
                        "Iris color-space conversion requires the fixed-Iris RGBA8 MainTarget contract, got "
                                + finalColorFormat
                );
            }
            for (PlannedColorSpacePass pass : this.colorSpacePasses.values()) {
                if (pass.pipeline == null) {
                    pass.pipeline = buildColorSpacePipeline(pass, finalColorFormat);
                }
                verifyPrecompile(
                        device,
                        device.precompilePipeline(pass.pipeline, source),
                        pass.info.name()
                );
            }
            ensureColorSpaceResources(device, targets.width(), targets.height());
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
        if (stage == Stage.SETUP) {
            executeComputeGroup(device, targets, resources, this.setupComputes, executed);
        } else {
            List<PlannedPass> raster = this.passes.get(stage);
            List<PlannedComputeGroup> computes = this.computeGroups.get(stage);
            int rasterCursor = 0;
            int computeCursor = 0;
            while (rasterCursor < raster.size() || computeCursor < computes.size()) {
                int rasterIndex = rasterCursor < raster.size()
                        ? raster.get(rasterCursor).arrayIndex
                        : Integer.MAX_VALUE;
                int computeIndex = computeCursor < computes.size()
                        ? computes.get(computeCursor).arrayIndex()
                        : Integer.MAX_VALUE;
                int index = Math.min(rasterIndex, computeIndex);
                if (computeIndex == index) {
                    executeComputeGroup(
                            device, targets, resources,
                            computes.get(computeCursor++).computes(), executed
                    );
                }
                if (rasterIndex == index) {
                    PlannedPass pass = raster.get(rasterCursor++);
                    executePass(device, targets, resources, pass);
                    colors.restore(pass.info.stateAfter());
                    executed.add(pass.info.name());
                }
            }
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
            executeComputeGroup(device, targets, resources, this.finalComputes, null);
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
                            Optional.empty(),
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
                        true,
                        com.metallum.client.validation.contract.ProducerType.RESOLVE,
                        "iris/final/resolve"
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

    boolean executeColorSpace(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final GpuTextureView mainColor,
            final ColorSpace colorSpace
    ) {
        ensurePrepared();
        Objects.requireNonNull(device, "device");
        validateTargets(targets);
        Objects.requireNonNull(mainColor, "mainColor");
        Objects.requireNonNull(colorSpace, "colorSpace");
        if (this.packOwnsColorCorrection || colorSpace == ColorSpace.SRGB) {
            return false;
        }
        PlannedColorSpacePass pass = this.colorSpacePasses.get(colorSpace);
        if (pass == null) {
            throw new IllegalStateException("No fixed-Iris Metal color-space lowering for " + colorSpace);
        }
        if (mainColor.texture().getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException(
                    "Iris color-space conversion requires RGBA8 MainTarget, got "
                            + mainColor.texture().getFormat()
            );
        }
        ensureColorSpaceResources(device, mainColor.getWidth(0), mainColor.getHeight(0));
        MetalGpuTexture swap = Objects.requireNonNull(this.colorSpaceSwap, "color-space swap texture");
        MetalGpuTextureView swapView = Objects.requireNonNull(this.colorSpaceSwapView, "color-space swap view");
        MetalGpuSampler sampler = Objects.requireNonNull(this.colorSpaceSampler, "color-space sampler");
        RenderPassDescriptor descriptor = RenderPassDescriptor
                .create(() -> "Iris color space: " + colorSpace)
                .withColorAttachment(swapView, Optional.empty())
                .withRenderArea(new RenderPass.RenderArea(
                        0, 0, mainColor.getWidth(0), mainColor.getHeight(0)
                ));
        MetalCommandEncoder encoder = device.commandEncoder();
        MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(descriptor);
        try {
            renderFullscreen(
                    renderPass,
                    Objects.requireNonNull(pass.pipeline, "color-space pipeline"),
                    pass.info,
                    pass.program,
                    Optional.empty(),
                    targets,
                    new ResourceProvider() {
                        @Override
                        public @Nullable GpuBufferSlice uniform(
                                final PassInfo ignoredPass,
                                final String blockName
                        ) {
                            return null;
                        }

                        @Override
                        public @Nullable TextureBinding texture(
                                final PassInfo ignoredPass,
                                final String samplerName
                        ) {
                            return "readImage".equals(samplerName)
                                    ? new TextureBinding(mainColor, sampler)
                                    : null;
                        }
                    }
            );
        } finally {
            encoder.submitRenderPass();
        }
        encoder.copyTextureToTexture(
                swap,
                mainColor.texture(),
                0,
                0,
                0,
                0,
                0,
                mainColor.getWidth(0),
                mainColor.getHeight(0)
        );
        return true;
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

    /** Logical colortex targets that need Metal shader-write usage. */
    Set<Integer> storageImageTargets() {
        return this.storageImageTargets;
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
        for (PlannedCompute compute : allComputes()) {
            if (compute.reflection.resources().stream().anyMatch(resource ->
                    resource.name().equals(samplerName)
                            && (resource.kind() == MetalIrisShaderCompiler.ComputeResourceKind.SAMPLED_IMAGE
                            || resource.kind() == MetalIrisShaderCompiler.ComputeResourceKind.SEPARATE_SAMPLER))) {
                return true;
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
        for (PlannedCompute compute : allComputes()) {
            compute.reflection.resources().stream()
                    .filter(resource -> resource.name().equals(samplerName))
                    .filter(resource -> resource.kind() == MetalIrisShaderCompiler.ComputeResourceKind.SAMPLED_IMAGE
                            || resource.kind() == MetalIrisShaderCompiler.ComputeResourceKind.SEPARATE_SAMPLER)
                    .forEach(resource -> result.add("sampler2D"));
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
        for (PlannedCompute compute : allComputes()) {
            values.registerCompute(compute.token, "post_compute_" + compute.info.name(), compute.reflection);
        }
    }

    @Nullable GpuBufferSlice uniformSlice(
            final IrisMetalUniformValues values,
            final PassInfo pass
    ) {
        return values.slice(uniformToken(pass));
    }

    static String uniformToken(final PassInfo pass) {
        return "post:" + pass.stage().name() + ':' + pass.name();
    }

    private List<PlannedCompute> allComputes() {
        List<PlannedCompute> result = new ArrayList<>(this.setupComputes.size() + this.finalComputes.size());
        result.addAll(this.setupComputes);
        for (Stage stage : Stage.values()) {
            for (PlannedComputeGroup group : this.computeGroups.get(stage)) {
                result.addAll(group.computes());
            }
        }
        result.addAll(this.finalComputes);
        return result;
    }

    private void executeComputeGroup(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources,
            final List<PlannedCompute> computes,
            final @Nullable List<String> executed
    ) {
        if (computes.isEmpty()) {
            return;
        }
        if (this.concurrentCompute) {
            try (MetalComputePass pass = device.commandEncoder().createComputePass("iris/compute")) {
                for (PlannedCompute compute : computes) {
                    executeCompute(pass, compute, targets, resources, executed);
                }
            }
            return;
        }

        // Fixed Iris issues image/texture-fetch/SSBO barriers before every
        // dispatch unless the pack explicitly opts into concurrent compute.
        // An encoder boundary on the shared Metal fence is the conservative
        // native equivalent for hazard-untracked resources.
        for (PlannedCompute compute : computes) {
            try (MetalComputePass pass = device.commandEncoder().createComputePass("iris/compute")) {
                executeCompute(pass, compute, targets, resources, executed);
            }
        }
    }

    private void executeCompute(
            final MetalComputePass pass,
            final PlannedCompute compute,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources,
            final @Nullable List<String> executed
    ) {
        pass.setPipeline(Objects.requireNonNull(compute.pipeline, "compute pipeline"));
        bindComputeResources(pass, compute, targets, resources);
        dispatchCompute(pass, compute, targets, resources);
        if (executed != null) {
            executed.add(compute.info.name());
        }
    }

    private void bindComputeResources(
            final MetalComputePass pass,
            final PlannedCompute compute,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        IrisMetalUniformValues.DrawUniformContext uniformContext =
                IrisMetalUniformValues.requiresDrawContext(compute.reflection.uniformLayout())
                        ? fullscreenUniformContext(compute.info, targets, resources, Optional.empty())
                        : IrisMetalUniformValues.DrawUniformContext.empty();
        for (MetalIrisShaderCompiler.ComputeResource resource : compute.reflection.resources()) {
            switch (resource.kind()) {
                case UNIFORM_BUFFER -> bindComputeBuffer(
                        pass,
                        resource.binding(),
                        requireBuffer(
                                resources.uniform(
                                        compute.info, resource.name(), compute.token, uniformContext
                                ),
                                compute, "uniform block", resource.name()
                        )
                );
                case STORAGE_BUFFER -> bindComputeBuffer(
                        pass,
                        resource.binding(),
                        requireBuffer(
                                resources.storageBuffer(resource.binding()),
                                compute, "SSBO binding", Integer.toString(resource.binding())
                        )
                );
                case SAMPLED_IMAGE -> {
                    TextureBinding binding = requireComputeTexture(compute, resource.name(), targets, resources);
                    pass.bindTextureView(resource.binding(), metalView(binding.view(), compute, resource.name()));
                    pass.bindSampler(resource.binding(), metalSampler(binding.sampler(), compute, resource.name()).nativeHandle());
                }
                case SEPARATE_SAMPLER -> {
                    TextureBinding binding = requireComputeTexture(compute, resource.name(), targets, resources);
                    pass.bindSampler(resource.binding(), metalSampler(binding.sampler(), compute, resource.name()).nativeHandle());
                }
                case STORAGE_IMAGE -> {
                    GpuTextureView view = storageImage(compute, resource.name(), targets, resources);
                    MetalGpuTextureView metalView = metalView(view, compute, resource.name());
                    ((MetalGpuTexture) metalView.texture()).markContentsDirty();
                    pass.bindTextureView(resource.binding(), metalView);
                }
                case TEXEL_BUFFER, STORAGE_TEXEL_BUFFER, ATOMIC_COUNTER -> throw new IllegalStateException(
                        "Unsupported compute resource survived admission: " + resource.kind() + " " + resource.name()
                );
            }
        }
    }

    private static GpuBufferSlice requireBuffer(
            final @Nullable GpuBufferSlice slice,
            final PlannedCompute compute,
            final String kind,
            final String identity
    ) {
        if (slice == null) {
            throw new IllegalStateException(
                    "Iris compute " + compute.info.name() + " is missing required " + kind + " '" + identity + "'"
            );
        }
        return slice;
    }

    private static void bindComputeBuffer(
            final MetalComputePass pass,
            final int binding,
            final GpuBufferSlice slice
    ) {
        if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
            throw new IllegalStateException("Iris compute resource is not backed by a Metal buffer");
        }
        pass.bindBuffer(binding, buffer, slice.offset());
    }

    private static TextureBinding requireComputeTexture(
            final PlannedCompute compute,
            final String name,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        MetalIrisShaderCompiler.SamplerDecl sampler =
                new MetalIrisShaderCompiler.SamplerDecl(name, "sampler2D");
        TextureBinding binding = externalTexture(resources, compute.info, sampler);
        if (binding == null) {
            binding = standardTexture(compute.info, name, targets);
        }
        if (binding == null) {
            throw new IllegalStateException(
                    "Iris compute " + compute.info.name() + " is missing required sampled texture '" + name + "'"
            );
        }
        return binding;
    }

    private static GpuTextureView storageImage(
            final PlannedCompute compute,
            final String name,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        int target = colorImageIndex(name);
        if (target >= 0) {
            return targets.colorTargets().sampleReadView(target);
        }
        GpuTextureView view = resources.storageImage(compute.info, name);
        if (view == null) {
            throw new IllegalStateException(
                    "Iris compute " + compute.info.name() + " is missing required storage image '" + name + "'"
            );
        }
        return view;
    }

    private static MetalGpuTextureView metalView(
            final GpuTextureView view,
            final PlannedCompute compute,
            final String name
    ) {
        if (!(view instanceof MetalGpuTextureView metalView)) {
            throw new IllegalStateException(
                    "Iris compute " + compute.info.name() + " resource '" + name + "' is not a Metal texture view"
            );
        }
        return metalView;
    }

    private static MetalGpuSampler metalSampler(
            final GpuSampler sampler,
            final PlannedCompute compute,
            final String name
    ) {
        if (!(sampler instanceof MetalGpuSampler metalSampler)) {
            throw new IllegalStateException(
                    "Iris compute " + compute.info.name() + " resource '" + name + "' is not a Metal sampler"
            );
        }
        return metalSampler;
    }

    private static void dispatchCompute(
            final MetalComputePass pass,
            final PlannedCompute compute,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        IndirectPointer indirect = compute.source.getIndirectPointer();
        if (indirect != null) {
            GpuBufferSlice slice = requireBuffer(
                    resources.storageBuffer(indirect.buffer()),
                    compute,
                    "indirect SSBO binding",
                    Integer.toString(indirect.buffer())
            );
            long relativeOffset = indirect.offset();
            if (relativeOffset < 0 || relativeOffset > slice.length() - 12L) {
                throw new IllegalStateException(
                        "Iris compute " + compute.info.name() + " indirect range " + relativeOffset + "+12 exceeds "
                                + slice.length() + " bytes at SSBO binding " + indirect.buffer()
                );
            }
            if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
                throw new IllegalStateException("Iris indirect dispatch buffer is not backed by Metal");
            }
            pass.dispatchIndirect(buffer, Math.addExact(slice.offset(), relativeOffset));
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
        int threadsX = Math.max(1, (int) Math.ceil(targets.width() * scaleX));
        int threadsY = Math.max(1, (int) Math.ceil(targets.height() * scaleY));
        pass.dispatchThreadsCovering(threadsX, threadsY, 1);
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
                        pass.blendState.global(),
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
            final Optional<BlendFunction> globalBlend,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        renderPass.setPipeline(pipeline);
        bindResources(renderPass, info, program, globalBlend, targets, resources);
        GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
        renderPass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
        renderPass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
        renderPass.drawIndexed(6, 1, 0, 0, 0);
    }

    private static void bindResources(
            final MetalRenderPass renderPass,
            final PassInfo info,
            final MetalIrisShaderCompiler.GlslProgram program,
            final Optional<BlendFunction> globalBlend,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        IrisMetalUniformValues.DrawUniformContext uniformContext =
                IrisMetalUniformValues.requiresDrawContext(program.uniformLayout())
                        ? fullscreenUniformContext(info, targets, resources, globalBlend)
                        : IrisMetalUniformValues.DrawUniformContext.empty();
        for (MetalIrisShaderCompiler.StorageBufferDecl storage : program.storageBuffers()) {
            GpuBufferSlice slice = resources.storageBuffer(storage.binding());
            if (slice == null) {
                throw new IllegalStateException(
                        "Iris pass " + info.name() + " is missing required SSBO binding "
                                + storage.binding()
                );
            }
            renderPass.bindStorageBuffer(storage.binding(), slice);
        }
        for (String block : program.uniformBlockNames()) {
            GpuBufferSlice slice = resources.uniform(
                    info, block, uniformToken(info), uniformContext
            );
            if (slice == null) {
                throw new IllegalStateException(
                        "Iris pass " + info.name() + " is missing required uniform block '" + block + "'"
                );
            }
            renderPass.setUniform(block, slice);
        }
        for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
            if (sampler.isStorageImage()) {
                GpuTextureView image = rasterStorageImage(info, sampler.name(), targets, resources);
                renderPass.bindStorageImage(sampler.name(), image);
                continue;
            }
            if (sampler.isTexelBuffer()) {
                TexelBufferBinding binding = resources.texelBuffer(info, sampler);
                if (binding == null) {
                    throw new IllegalStateException(
                            "Iris pass " + info.name() + " is missing required typed texel buffer '"
                                    + sampler.name() + "'"
                    );
                }
                renderPass.setUniform(sampler.name(), binding.slice());
                continue;
            }
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

    private static IrisMetalUniformValues.DrawUniformContext fullscreenUniformContext(
            final PassInfo info,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources,
            final Optional<BlendFunction> globalBlend
    ) {
        MetalIrisShaderCompiler.SamplerDecl sampler =
                new MetalIrisShaderCompiler.SamplerDecl("colortex0", "sampler2D");
        TextureBinding primary = externalTexture(resources, info, sampler);
        if (primary == null) {
            primary = standardTexture(info, "colortex0", targets);
        }
        if (primary == null) {
            throw new IllegalStateException(
                    "Iris pass " + info.name() + " has no logical texture-unit-0 colortex0 binding"
            );
        }
        return new IrisMetalUniformValues.DrawUniformContext(
                primary.view(), 0, 0, globalBlend
        );
    }

    private static GpuTextureView rasterStorageImage(
            final PassInfo info,
            final String name,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources
    ) {
        int target = colorImageIndex(name);
        if (target >= 0) {
            if (target >= targets.colorTargets().targetCount()) {
                throw new IllegalStateException(
                        "Iris pass " + info.name() + " storage image '" + name
                                + "' exceeds generation target count"
                );
            }
            return targets.colorTargets().sampleReadView(target);
        }
        GpuTextureView view = resources.storageImage(info, name);
        if (view == null) {
            throw new IllegalStateException(
                    "Iris pass " + info.name() + " is missing required storage image '" + name + "'"
            );
        }
        return view;
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
                    targets.colorTargets().sampleReadView(target),
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

    static int colorImageIndex(final String name) {
        Matcher matcher = COLOR_IMAGE_NAME.matcher(name);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
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
            final IrisMetalRenderTargets targets,
            final @Nullable ResourceProvider resources
    ) {
        RenderPipeline.Builder builder = basePipeline(
                pass.vertexId,
                pass.fragmentId,
                Identifier.fromNamespaceAndPath(
                        "metallum", pass.vertexId.getPath().substring(0, pass.vertexId.getPath().length() - 2)
                ),
                pass.program,
                texelBufferFormats(pass.info, pass.program, resources)
        );
        int[] drawBuffers = pass.info.drawBuffers();
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            builder.withColorTargetState(slot, new ColorTargetState(
                    pass.blendState.forTarget(drawBuffers[slot]),
                    targets.colorTargets().format(drawBuffers[slot]),
                    ColorTargetState.WRITE_ALL
            ));
        }
        return builder.build();
    }

    private static RenderPipeline buildFinalPipeline(
            final PlannedFinal pass,
            final GpuFormat finalColorFormat,
            final @Nullable ResourceProvider resources
    ) {
        return basePipeline(
                pass.vertexId,
                pass.fragmentId,
                Identifier.fromNamespaceAndPath("metallum", "iris/gen/post/final"),
                pass.program,
                texelBufferFormats(pass.info(), pass.program, resources)
        ).withColorTargetState(new ColorTargetState(
                Optional.empty(), finalColorFormat, ColorTargetState.WRITE_ALL
        )).build();
    }

    private static RenderPipeline buildColorSpacePipeline(
            final PlannedColorSpacePass pass,
            final GpuFormat finalColorFormat
    ) {
        return basePipeline(
                pass.vertexId,
                pass.fragmentId,
                Identifier.fromNamespaceAndPath(
                        "metallum",
                        "iris/presentation/" + pass.colorSpace.name().toLowerCase(Locale.ROOT)
                ),
                pass.program,
                Map.of()
        ).withColorTargetState(new ColorTargetState(
                Optional.empty(), finalColorFormat, ColorTargetState.WRITE_ALL
        )).build();
    }

    private static RenderPipeline.Builder basePipeline(
            final Identifier vertexId,
            final Identifier fragmentId,
            final Identifier location,
            final MetalIrisShaderCompiler.GlslProgram program,
            final Map<String, GpuFormat> texelBufferFormats
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
            if (sampler.isStorageImage()) {
                continue;
            }
            if (sampler.isTexelBuffer()) {
                GpuFormat format = texelBufferFormats.get(sampler.name());
                if (format == null) {
                    throw new IllegalStateException(
                            "Iris post samplerBuffer '" + sampler.name()
                                    + "' has no typed format binding"
                    );
                }
                bindings.withUniform(sampler.name(), UniformType.UNIFORM_BUFFER, format);
                continue;
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

    private static Map<String, GpuFormat> texelBufferFormats(
            final PassInfo pass,
            final MetalIrisShaderCompiler.GlslProgram program,
            final @Nullable ResourceProvider resources
    ) {
        Map<String, GpuFormat> formats = new LinkedHashMap<>();
        for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
            if (!sampler.isTexelBuffer()) {
                continue;
            }
            if (resources == null) {
                throw new IllegalStateException(
                        "Iris pass " + pass.name() + " declares samplerBuffer '" + sampler.name()
                                + "' but no typed texel-buffer provider was supplied"
                );
            }
            TexelBufferBinding binding = resources.texelBuffer(pass, sampler);
            if (binding == null) {
                throw new IllegalStateException(
                        "Iris pass " + pass.name() + " is missing typed texel-buffer admission for '"
                                + sampler.name() + "'"
                );
            }
            formats.put(sampler.name(), binding.format());
        }
        return Map.copyOf(formats);
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

    private static MetalIrisShaderCompiler.GlslProgram colorSpaceProgram(final ColorSpace colorSpace) {
        List<StringPair> defines = new ArrayList<>();
        defines.add(new StringPair("CURRENT_COLOR_SPACE", Integer.toString(colorSpace.ordinal())));
        for (ColorSpace value : ColorSpace.values()) {
            defines.add(new StringPair(value.name(), Integer.toString(value.ordinal())));
        }
        String fragment = JcppProcessor.glslPreprocessSource(colorSpaceFragmentSource(), defines)
                .replaceFirst("(?m)^\\s*#version\\s+330(?:\\s+core)?", "#version 450 core")
                .replace("in vec2 uv;", "layout(location = 0) in vec2 uv;")
                .replace("out vec4 outColor;", "layout(location = 0) out vec4 outColor;");
        return MetalIrisShaderCompiler.linkPatchedPair(
                "iris-color-space-" + colorSpace.name().toLowerCase(Locale.ROOT),
                COLOR_SPACE_VERTEX,
                fragment,
                new int[]{0}
        );
    }

    private static String colorSpaceFragmentSource() {
        try (InputStream stream = Objects.requireNonNull(
                net.irisshaders.iris.pathways.colorspace.ColorSpaceFragmentConverter.class
                        .getResourceAsStream("/colorSpace.csh"),
                "Iris 1.11.2 colorSpace.csh"
        )) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixed-Iris color-space shader", e);
        }
    }

    private void ensureColorSpaceResources(
            final MetalDevice device,
            final int width,
            final int height
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid Iris color-space extent " + width + 'x' + height);
        }
        if (this.colorSpaceSwap != null
                && this.colorSpaceSwap.getWidth(0) == width
                && this.colorSpaceSwap.getHeight(0) == height) {
            return;
        }
        closeColorSpaceSwap();
        this.colorSpaceSwap = (MetalGpuTexture) device.createTexture(
                () -> "metallum:iris_color_space_swap",
                GpuTexture.USAGE_RENDER_ATTACHMENT
                        | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_SRC
                        | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
        );
        this.colorSpaceSwap.registerValidationIdentity();
        this.colorSpaceSwapView = new MetalGpuTextureView(this.colorSpaceSwap, 0, 1);
        if (this.colorSpaceSampler == null) {
            this.colorSpaceSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    java.util.OptionalDouble.empty()
            );
        }
    }

    private void closeColorSpaceSwap() {
        if (this.colorSpaceSwapView != null) {
            this.colorSpaceSwapView.close();
            this.colorSpaceSwapView = null;
        }
        if (this.colorSpaceSwap != null) {
            this.colorSpaceSwap.close();
            this.colorSpaceSwap = null;
        }
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

    private static List<PlannedCompute> planComputes(
            final ComputeSource @Nullable [] sources,
            final Stage stage,
            final int arrayIndex,
            final BitSet readsFromAlt,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final int targetCount,
            final int firstOrdinal
    ) {
        if (sources == null || sources.length == 0) {
            return List.of();
        }
        List<PlannedCompute> result = new ArrayList<>();
        int ordinal = firstOrdinal;
        for (ComputeSource source : sources) {
            if (source == null || !source.isValid()) {
                continue;
            }
            String patched = TransformPatcher.patchCompute(
                    source.getName(),
                    source.getSource().orElseThrow(),
                    stage.textureStage,
                    textureMap
            );
            MetalIrisShaderCompiler.TranslatedStage translated = MetalIrisShaderCompiler.translateStage(
                    source.getName(), MetalIrisShaderCompiler.StageKind.COMPUTE, patched
            );
            MetalIrisShaderCompiler.ComputeReflection reflection = Objects.requireNonNull(
                    translated.computeReflection(), "compute reflection for " + source.getName()
            );
            validateComputeResources(source.getName(), reflection, targetCount);
            Set<String> sampledNames = reflection.resources().stream()
                    .filter(resource -> resource.kind() == MetalIrisShaderCompiler.ComputeResourceKind.SAMPLED_IMAGE
                            || resource.kind() == MetalIrisShaderCompiler.ComputeResourceKind.SEPARATE_SAMPLER)
                    .map(MetalIrisShaderCompiler.ComputeResource::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            String token = "compute:" + stage.name() + ':' + arrayIndex + ':' + ordinal++;
            result.add(new PlannedCompute(
                    token,
                    new PassInfo(
                            stage,
                            source.getName(),
                            new int[0],
                            readsFromAlt,
                            readsFromAlt,
                            new BitSet(targetCount),
                            sampledNames
                    ),
                    source,
                    translated
            ));
        }
        return List.copyOf(result);
    }

    private static void validateComputeResources(
            final String programName,
            final MetalIrisShaderCompiler.ComputeReflection reflection,
            final int targetCount
    ) {
        for (MetalIrisShaderCompiler.ComputeResource resource : reflection.resources()) {
            switch (resource.kind()) {
                case UNIFORM_BUFFER -> {
                    if (!MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(resource.name())) {
                        throw new UnsupportedOperationException(
                                "Iris compute program " + programName + " declares unmanaged UBO '"
                                        + resource.name() + "'"
                        );
                    }
                }
                case STORAGE_IMAGE -> {
                    if (resource.imageDimension() != org.lwjgl.util.spvc.Spv.SpvDim2D) {
                        throw new UnsupportedOperationException(
                                "Iris compute image '" + resource.name() + "' in " + programName
                                        + " is not 2D (SPIR-V dim=" + resource.imageDimension() + ')'
                        );
                    }
                    int target = colorImageIndex(resource.name());
                    if (target >= targetCount) {
                        throw new IllegalArgumentException(
                                "Iris compute image '" + resource.name() + "' references colortex" + target
                                        + " but this generation has only " + targetCount + " targets"
                        );
                    }
                }
                case TEXEL_BUFFER, STORAGE_TEXEL_BUFFER -> throw new UnsupportedOperationException(
                        "Iris compute program " + programName + " declares typed texel buffer '"
                                + resource.name() + "'; the Metal compute texel-buffer binding is not connected"
                );
                case ATOMIC_COUNTER -> throw new UnsupportedOperationException(
                        "Iris compute program " + programName + " declares atomic counter '"
                                + resource.name() + "'; no Iris Metal atomic-counter resource exists"
                );
                default -> {
                }
            }
        }
    }

    private static Set<Integer> collectStorageImageTargets(
            final EnumMap<Stage, List<PlannedComputeGroup>> stages,
            final List<PlannedCompute> setup,
            final List<PlannedCompute> finals,
            final int targetCount
    ) {
        Set<Integer> result = new LinkedHashSet<>();
        List<PlannedCompute> computes = new ArrayList<>(setup.size() + finals.size());
        computes.addAll(setup);
        stages.values().forEach(groups -> groups.forEach(group -> computes.addAll(group.computes())));
        computes.addAll(finals);
        for (PlannedCompute compute : computes) {
            for (MetalIrisShaderCompiler.ComputeResource resource : compute.reflection.resources()) {
                if (resource.kind() != MetalIrisShaderCompiler.ComputeResourceKind.STORAGE_IMAGE) {
                    continue;
                }
                int target = colorImageIndex(resource.name());
                if (target >= 0) {
                    validateTarget(target, targetCount, "compute storage image " + resource.name());
                    result.add(target);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static TargetBlendState blendState(
            final ProgramDirectives directives,
            final int targetCount
    ) {
        Optional<BlendFunction> global = directives.getBlendModeOverride()
                .flatMap(IrisMetalPipelineOverrides::irisBlendFunction);
        Map<Integer, Optional<BlendFunction>> perTarget = new LinkedHashMap<>();
        for (var override : directives.getBufferBlendOverrides()) {
            if (override.index() < 0 || override.index() >= targetCount) {
                throw new IllegalArgumentException(
                        "Iris post directives declare blend target " + override.index()
                                + " outside 0.." + (targetCount - 1)
                );
            }
            Optional<BlendFunction> blend = override.blendMode() == null
                    ? Optional.empty()
                    : Optional.of(IrisMetalPipelineOverrides.irisBlendFunction(override.blendMode()));
            Optional<BlendFunction> previous = perTarget.put(override.index(), blend);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Iris post directives repeat blend override for colortex" + override.index()
                );
            }
        }
        return new TargetBlendState(global, perTarget);
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
        if (this.closed) {
            return;
        }
        this.closed = true;
        closeColorSpaceSwap();
        if (this.colorSpaceSampler != null) {
            this.colorSpaceSampler.close();
            this.colorSpaceSampler = null;
        }
        for (PlannedCompute compute : allComputes()) {
            if (compute.pipeline != null) {
                compute.pipeline.close();
                compute.pipeline = null;
            }
        }
    }
}
