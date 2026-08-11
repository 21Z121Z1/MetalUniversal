package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Backend-owned Iris world-pipeline generation.
 *
 * <p>Iris remains the source of truth for pack parsing and dimension program
 * selection. This object mirrors the CPU-visible world semantics without
 * constructing {@code IrisRenderingPipeline}'s OpenGL programs, framebuffers,
 * samplers, or images. GPU program/resource ownership is connected in later
 * focused commits before the Iris factory is redirected here.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalWorldRenderingPipeline extends VanillaRenderingPipeline {
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final Object WORLD_SETTINGS_LOCK = new Object();
    private static @Nullable MetalWorldRenderingPipeline worldSettingsOwner;

    private final int generation;
    private final ProgramSet programSet;
    private final ShaderPack pack;
    private final PackDirectives directives;
    private final ColorSpace outputColorSpace;
    private final OptionalInt forcedShadowRenderDistanceChunks;
    private final IrisMetalFrameState frameState = new IrisMetalFrameState();
    private final IrisMetalUniformValues uniformValues;
    private final IrisMetalWorldPrograms programs;
    private IrisMetalExecutionGraph executionGraph;
    private final IrisMetalRuntimeReceipts receipts;
    private IrisMetalCompiledPrograms compiledPrograms;
    private IrisMetalWorldResources resources;
    private @Nullable IrisMetalCenterDepthSampler centerDepthSampler;
    private @Nullable IrisMetalShadowSceneExecutor shadowSceneExecutor;
    private MetalRenderPass.@Nullable TextureViewAndSampler mojangExternalOverlay;
    private MetalDevice centerDepthDevice;
    private boolean initializedBlockMaterialMappings;
    private int receiptWidth = -1;
    private int receiptHeight = -1;
    private boolean published;
    private boolean worldSettingsPublished;
    private @Nullable MetalWorldRenderingPipeline previousWorldSettingsOwner;
    private long shadowTerrainPasses;
    private long shadowTerrainDrawCalls;
    private long shadowTerrainIndexCount;
    private long shadowCoreDrawCalls;

    public MetalWorldRenderingPipeline(final ProgramSet programSet) {
        // Admission must run before generation IDs, Iris world settings, or GPU
        // resources become observable. A failed pack must not partially mutate
        // the currently selected Metal generation.
        this.outputColorSpace = Objects.requireNonNull(IrisVideoSettings.colorSpace, "Iris color space");
        IrisMetalPackAdmission.requireSupported(programSet, this.outputColorSpace);
        this.generation = GENERATIONS.incrementAndGet();
        this.programSet = Objects.requireNonNull(programSet, "programSet");
        this.programs = new IrisMetalWorldPrograms(this.generation, this.programSet);
        this.executionGraph = new IrisMetalExecutionGraph(
                this.generation,
                this.programSet,
                this.programs,
                IrisMetalRenderTargetFormats.from(this.programSet.getPackDirectives()).length
        );
        this.receipts = IrisMetalRuntimeReceipts.open(this.generation);
        this.pack = programSet.getPack();
        this.directives = programSet.getPackDirectives();
        this.forcedShadowRenderDistanceChunks = forcedShadowDistance(
                this.directives.getShadowDirectives()
        );
        CustomUniformFixedInputUniformsHolder.Builder fixedInputs =
                new CustomUniformFixedInputUniformsHolder.Builder();
        CommonUniforms.addNonDynamicUniforms(
                fixedInputs,
                this.pack.getIdMap(),
                this.directives,
                this.frameState.updateNotifier()
        );
        CustomUniformFixedInputUniformsHolder fixedInputGraph = fixedInputs.build();
        CustomUniforms customUniforms = this.pack.customUniforms.build(fixedInputGraph);
        IrisMetalDynamicUniforms dynamicUniformGraph = IrisMetalDynamicUniforms.create(
                () -> this.frameState.phase().ordinal()
        );
        this.uniformValues = new IrisMetalUniformValues(
                this.directives.getSunPathRotation(),
                customUniforms,
                fixedInputGraph,
                dynamicUniformGraph,
                this.frameState.updateNotifier(),
                () -> this.frameState.phase().ordinal()
        );
        this.executionGraph.attachUniformValues(this.uniformValues);
    }

    /**
     * Prepares the complete GPU candidate before Iris's PipelineManager makes
     * it current. Pipeline creation is the last rollback point available to
     * the backend; a failure here leaves the previous dimension generation
     * selected by Iris.
     */
    public void prepareForPublication() {
        prepareResources();
        publishWorldSettings();
    }

    /**
     * Releases a candidate that failed before Iris published it as current.
     * The failure is kept as a receipt event before the writer is closed.
     */
    public void discardUnpublished(final Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        try {
            this.receipts.recordFailure("publication-prewarm", failure);
        } catch (RuntimeException | Error receiptFailure) {
            failure.addSuppressed(receiptFailure);
        }
        restoreWorldSettingsAfterFailedPublication();
        try {
            this.destroy();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static OptionalInt forcedShadowDistance(final PackShadowDirectives shadow) {
        if (!shadow.isDistanceRenderMulExplicit()) {
            return OptionalInt.empty();
        }
        if (shadow.getDistanceRenderMul() < 0.0F) {
            return OptionalInt.of(-1);
        }
        return OptionalInt.of((int) Math.ceil(
                shadow.getDistance() * shadow.getDistanceRenderMul() / 16.0F
        ));
    }

    static boolean requiresExecutionGraphRebuild(
            final boolean deviceChanged,
            final boolean resizing
    ) {
        return deviceChanged || resizing;
    }

    private void publishWorldSettings() {
        synchronized (WORLD_SETTINGS_LOCK) {
            if (worldSettingsOwner == this) {
                this.worldSettingsPublished = true;
                return;
            }
            MetalWorldRenderingPipeline previousOwner = worldSettingsOwner;
            try {
                applyWorldSettings();
                this.previousWorldSettingsOwner = previousOwner;
                this.worldSettingsPublished = true;
                worldSettingsOwner = this;
            } catch (RuntimeException | Error failure) {
                if (previousOwner != null) {
                    previousOwner.applyWorldSettings();
                } else {
                    resetWorldSettings();
                }
                throw failure;
            }
        }
    }

    private void applyWorldSettings() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
        settings.setEntityIds(this.pack.getIdMap().getEntityIdMap());
        settings.setItemIds(this.pack.getIdMap().getItemIdMap());
        settings.setAmbientOcclusionLevel(this.directives.getAmbientOcclusionLevel());
        settings.setDisableDirectionalShading(!this.directives.isOldLighting());
        settings.setUseSeparateAo(this.directives.shouldUseSeparateAo());
        settings.setBreaksAnisotropy(this.directives.breaksAnisotropy());
        settings.setVoxelizeLightBlocks(this.directives.shouldVoxelizeLightBlocks());
        settings.setSeparateEntityDraws(this.directives.shouldUseSeparateEntityDraws());
    }

    private static void resetWorldSettings() {
        WorldRenderingSettings.INSTANCE.setVertexFormat(ChunkMeshFormats.COMPACT);
    }

    private void restoreWorldSettingsAfterFailedPublication() {
        synchronized (WORLD_SETTINGS_LOCK) {
            if (worldSettingsOwner != this) {
                this.worldSettingsPublished = false;
                this.previousWorldSettingsOwner = null;
                return;
            }
            if (this.previousWorldSettingsOwner != null) {
                this.previousWorldSettingsOwner.applyWorldSettings();
                worldSettingsOwner = this.previousWorldSettingsOwner;
            } else {
                resetWorldSettings();
                worldSettingsOwner = null;
            }
            this.worldSettingsPublished = false;
            this.previousWorldSettingsOwner = null;
        }
    }

    private void retireWorldSettings() {
        synchronized (WORLD_SETTINGS_LOCK) {
            if (worldSettingsOwner == this) {
                resetWorldSettings();
                worldSettingsOwner = null;
            }
            this.worldSettingsPublished = false;
            this.previousWorldSettingsOwner = null;
        }
    }

    ProgramSet programSet() {
        return this.programSet;
    }

    int generation() {
        return this.generation;
    }

    IrisMetalWorldPrograms programs() {
        return this.programs;
    }

    IrisMetalCompiledPrograms compiledPrograms() {
        if (this.compiledPrograms == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation + " has not prepared compiled programs"
            );
        }
        return this.compiledPrograms;
    }

    IrisMetalWorldResources resources() {
        if (this.resources == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation + " has not prepared GPU resources"
            );
        }
        return this.resources;
    }

    MetalRenderPass.@Nullable TextureViewAndSampler mojangExternalOverlay() {
        return this.mojangExternalOverlay;
    }

    /** Returns the generation-owned pack uniform block for a terrain shader key. */
    GpuBufferSlice uniformSlice(final ShaderKey key) {
        GpuBufferSlice slice = this.uniformValues.slice(key);
        if (slice == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation
                            + " has no prepared uniform block for " + key
            );
        }
        return slice;
    }

    boolean shouldOverrideCoreShaders(final boolean writesMainTarget) {
        return this.frameState.shouldOverrideShaders(writesMainTarget);
    }

    int coreDrawBlockSize(final ShaderKey key) {
        return this.uniformValues.coreDrawBlockSize(key);
    }

    void materializeCoreDrawUniforms(
            final ShaderKey key,
            final java.nio.ByteBuffer output,
            final java.nio.@Nullable ByteBuffer dynamicTransforms,
            final java.nio.@Nullable ByteBuffer projection,
            final IrisMetalUniformValues.DrawUniformContext context
    ) {
        this.uniformValues.materializeCoreDraw(key, output, dynamicTransforms, projection, context);
    }

    IrisMetalCoreDrawBridge.CoreDrawOverride prepareCoreDraw(
            final RenderPipeline source,
            final ShaderKey key,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<org.joml.Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(sceneColor, "sceneColor");
        Objects.requireNonNull(clearColor, "clearColor");
        Objects.requireNonNull(sceneDepth, "sceneDepth");
        Objects.requireNonNull(clearDepth, "clearDepth");
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris core draw has no active Metal device");
        }
        IrisMetalWorldResources generationResources = resources();
        IrisMetalCompiledPrograms generationPrograms = compiledPrograms();
        IrisMetalGlslLinker.LinkedRasterProgram linked = this.programs.core(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Iris core draw " + key + " has no resolved fixed-version program"
                ));
        this.uniformValues.register(key, "core_" + key.getName(), linked);
        this.uniformValues.prewarm(device);
        GpuFormat[] attachmentFormats = null;
        if (key.isShadow()) {
            IrisMetalShadowTargets shadowTargets = generationResources.shadowTargets();
            if (shadowTargets == null) {
                throw new IllegalStateException(
                        "Iris shadow core draw has no generation-owned shadow targets"
                );
            }
            attachmentFormats = shadowTargets.colorFormats();
        }
        MetalCompiledRenderPipeline compiled = generationPrograms.core(
                key, source, linked, attachmentFormats
        );
        IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor = key.isShadow()
                ? IrisMetalCorePipelineDescriptor.shadow(
                        generationResources,
                        label,
                        linked,
                        clearColor,
                        clearDepth
                )
                : IrisMetalCorePipelineDescriptor.main(
                        generationResources,
                        label,
                        linked,
                        sceneColor,
                        clearColor,
                        sceneDepth,
                        clearDepth
                );
        return new IrisMetalCoreDrawBridge.CoreDrawOverride(
                this, source, key, linked, compiled, descriptor
        );
    }

    BitSet shadowReadSnapshot() {
        return this.executionGraph.shadowReadSnapshot();
    }

    void beginShadowDrawMetrics() {
        this.shadowTerrainPasses = 0L;
        this.shadowTerrainDrawCalls = 0L;
        this.shadowTerrainIndexCount = 0L;
        this.shadowCoreDrawCalls = 0L;
    }

    void recordShadowTerrainPass(final ShaderKey key) {
        if (key.isShadow()) {
            this.shadowTerrainPasses++;
        }
    }

    void recordShadowTerrainDraw(final int primitiveCount) {
        this.shadowTerrainDrawCalls++;
        this.shadowTerrainIndexCount += Math.max(0, primitiveCount);
    }

    void recordShadowCoreDraw() {
        this.shadowCoreDrawCalls++;
    }

    @Override
    public void beginLevelRendering() {
        this.receipts.recordEvent("frame.begin");
        prepareResources();
        ensureBlockMaterialMappings();
        // Iris advances queued PBR aliases at the world-frame boundary. The
        // Metal resolver reads the resulting live texture wrappers, so this
        // must happen before any terrain/core draw asks for ResourceData.
        PBRTextureManager.INSTANCE.onNewFrame();
        prepareTerrainUniforms();
        if (!this.worldSettingsPublished) {
            publishWorldSettings();
        }
        if (!this.published) {
            IrisMetalPackLifecycle.onSemanticPipelineActivated();
            this.published = true;
            this.receipts.recordEvent("generation.publish");
        }
        Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
        this.executionGraph.beginFrame(
                this.resources(), new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F)
        );
        this.frameState.beginWorldRendering();
        this.receipts.recordEvent("setup");
        this.executionGraph.executeSetup(this.resources());
        this.receipts.recordEvent("begin");
        this.executionGraph.executeBegin(this.resources());
    }

    private void ensureBlockMaterialMappings() {
        if (this.initializedBlockMaterialMappings) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // The first beginLevelRendering call is expected to have a loaded
            // world. Refuse to continue rather than evaluating Iris fixed
            // inputs against an uninitialized material map.
            throw new IllegalStateException(
                    "Iris Metal material mappings require a loaded client level"
            );
        }
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                this.pack.getIdMap().getBlockProperties(),
                this.pack.getIdMap().getTagEntries()
        ));
        settings.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
                this.pack.getIdMap().getBlockRenderTypeMap()
        ));
        minecraft.levelExtractor.allChanged();
        this.initializedBlockMaterialMappings = true;
    }

    private void prepareTerrainUniforms() {
        for (ShaderKey key : new ShaderKey[]{
                ShaderKey.SODIUM_TERRAIN_SOLID,
                ShaderKey.SODIUM_TERRAIN_CUTOUT,
                ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
                ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
        }) {
            this.programs.sodium(key.getProgram(), key.getAlphaTest()).ifPresent(
                    linked -> this.uniformValues.register(key, "sodium_" + key.getName(), linked)
            );
        }
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal terrain uniforms have no active Metal device");
        }
        this.uniformValues.prewarm(device);
        this.uniformValues.updateFrame();
    }

    private void prepareResources() {
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal world pipeline has no active Metal device");
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            throw new IllegalStateException("Iris Metal world pipeline has no game renderer");
        }
        var mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (mainTarget.width <= 0 || mainTarget.height <= 0) {
            throw new IllegalStateException(
                    "Iris Metal main target has invalid extent "
                            + mainTarget.width + "x" + mainTarget.height
            );
        }
        boolean firstAllocation = this.compiledPrograms == null;
        boolean resizing = this.receiptWidth >= 0
                && (this.receiptWidth != mainTarget.width || this.receiptHeight != mainTarget.height);
        IrisMetalCompiledPrograms previousCompiled = this.compiledPrograms;
        IrisMetalWorldResources previousResources = this.resources;
        IrisMetalCenterDepthSampler previousCenterDepth = this.centerDepthSampler;
        IrisMetalExecutionGraph previousGraph = this.executionGraph;
        boolean deviceChanged = (previousCompiled != null && !previousCompiled.isOwnedBy(device))
                || (previousResources != null && !previousResources.isOwnedBy(device))
                || (previousCenterDepth != null && this.centerDepthDevice != device);
        boolean rebuildGraph = requiresExecutionGraphRebuild(deviceChanged, resizing);
        IrisMetalCompiledPrograms candidateCompiled = previousCompiled;
        IrisMetalWorldResources candidateResources = previousResources;
        IrisMetalCenterDepthSampler candidateCenterDepth = previousCenterDepth;
        IrisMetalExecutionGraph candidateGraph = previousGraph;
        MetalRenderPass.@Nullable TextureViewAndSampler candidateMojangExternalOverlay =
                this.mojangExternalOverlay;
        boolean ownsCompiled = false;
        boolean ownsResources = false;
        boolean ownsCenterDepth = false;
        boolean ownsGraph = false;
        boolean committed = false;
        IrisMetalUniformValues.BackingTransaction uniformTransaction =
                (firstAllocation || deviceChanged)
                        ? this.uniformValues.beginBackingTransaction(device)
                        : null;
        IrisMetalUniformValues.RegistrationCheckpoint uniformCheckpoint = this.uniformValues.checkpoint();
        try {
            // Snapshot borrowed bindings inside the transaction. A stale view
            // must reject only this candidate, never the active generation.
            candidateMojangExternalOverlay = snapshotMojangExternalOverlay(device);
            if (candidateCompiled == null || !candidateCompiled.isOwnedBy(device)) {
                candidateCompiled = new IrisMetalCompiledPrograms(
                        device,
                        this.generation,
                        this.programs,
                        IrisMetalRenderTargetFormats.from(this.directives)
                );
                ownsCompiled = true;
            }
            if (candidateResources == null || !candidateResources.isOwnedBy(device)) {
                candidateResources = new IrisMetalWorldResources(
                        device,
                        this.generation,
                        this.programSet,
                        mainTarget.width,
                        mainTarget.height
                );
                ownsResources = true;
            } else {
                if (resizing) {
                    // Build a complete replacement before touching the active
                    // generation. A failed resize must leave its resources,
                    // history and bindings usable for the current frame.
                    candidateResources = new IrisMetalWorldResources(
                            device,
                            this.generation,
                            this.programSet,
                            mainTarget.width,
                            mainTarget.height
                    );
                    ownsResources = true;
                }
            }
            if (candidateCenterDepth == null || this.centerDepthDevice != device) {
                ShaderSource fallback = (identifier, type) -> {
                    throw new IllegalStateException(
                            "Unexpected fallback shader lookup while creating Iris center-depth sampler: "
                                    + identifier + " / " + type
                    );
                };
                candidateCenterDepth = new IrisMetalCenterDepthSampler(
                        device,
                        this.generation,
                        Math.max(0.001F, this.directives.getCenterDepthHalfLife()),
                        fallback
                );
                ownsCenterDepth = true;
            }
            if (rebuildGraph) {
                // A resize replaces generation-owned targets as well as a
                // device replacement invalidating every compiled graph
                // object. Re-plan the same fixed Iris program set into a new
                // graph so the old generation remains untouched until the
                // complete candidate is ready. The new graph also restores
                // first-use clear and flip/history state for fresh textures.
                candidateGraph = new IrisMetalExecutionGraph(
                        this.generation,
                        this.programSet,
                        this.programs,
                        IrisMetalRenderTargetFormats.from(this.directives).length
                );
                candidateGraph.attachUniformValues(this.uniformValues);
                ownsGraph = true;
            }
            candidateGraph.setCenterDepthSampler(candidateCenterDepth);
            GpuFormat mainColorFormat = mainTarget.getColorTexture().getFormat();
            candidateGraph.prepare(
                    device,
                    candidateResources,
                    this.uniformValues,
                    mainColorFormat
            );
            prewarmCoreCatalog(
                    device, candidateCompiled, candidateResources, uniformTransaction
            );

            // Receipt identity and lifecycle transitions are part of the
            // candidate transaction. If validation output cannot be written,
            // do not expose a partially identified generation.
            this.receipts.recordDeviceIdentity(device);
            if (firstAllocation) {
                this.receipts.recordEvent("generation.allocate");
            }
            if (resizing) {
                this.receipts.recordEvent("resize");
            }
            if (deviceChanged) {
                this.receipts.recordEvent("generation.device-replacement");
            }

            // Uniform buffers are generation bindings too. Keep the active
            // device's backing untouched until every candidate validation and
            // receipt write has succeeded.
            if (uniformTransaction != null) {
                uniformTransaction.commit();
            }

            // Publish all candidate-owned objects only after graph preparation
            // and receipt identity have completed. A failed candidate remains
            // invisible to terrain and cannot replace the active generation.
            this.compiledPrograms = candidateCompiled;
            this.resources = candidateResources;
            this.centerDepthSampler = candidateCenterDepth;
            this.executionGraph = candidateGraph;
            this.mojangExternalOverlay = candidateMojangExternalOverlay;
            this.centerDepthDevice = device;
            this.receiptWidth = mainTarget.width;
            this.receiptHeight = mainTarget.height;
            committed = true;
            if (previousGraph != candidateGraph) {
                retireAfterCommit(previousGraph, "graph");
            }
            if (previousCompiled != null && previousCompiled != candidateCompiled) {
                retireAfterCommit(previousCompiled, "compiled");
            }
            if (previousCenterDepth != null && previousCenterDepth != candidateCenterDepth) {
                retireAfterCommit(previousCenterDepth, "center-depth");
            }
            if (previousResources != null && previousResources != candidateResources) {
                retireAfterCommit(previousResources, "resources");
            }
        } catch (RuntimeException | Error failure) {
            if (!committed) {
                try {
                    this.receipts.recordGenerationCandidateFailure(
                            firstAllocation ? "generation.allocate"
                            : deviceChanged ? "generation.device-replacement"
                                    : resizing ? "resize" : "generation.prepare",
                            failure,
                            mainTarget.width,
                            mainTarget.height,
                            resizing,
                            deviceChanged
                    );
                } catch (RuntimeException | Error receiptFailure) {
                    failure.addSuppressed(receiptFailure);
                }
                if (ownsGraph && candidateGraph != null) {
                    candidateGraph.close();
                }
                if (ownsCenterDepth && candidateCenterDepth != null) {
                    candidateCenterDepth.close();
                    if (candidateGraph == this.executionGraph) {
                        this.executionGraph.setCenterDepthSampler(null);
                    }
                }
                if (ownsResources && candidateResources != null) {
                    candidateResources.close();
                }
                if (ownsCompiled && candidateCompiled != null) {
                    candidateCompiled.close();
                }
                if (uniformTransaction != null) {
                    uniformTransaction.close();
                }
                this.uniformValues.rollback(uniformCheckpoint);
            }
            throw failure;
        }
    }

    /**
     * Builds at least one real Metal PSO for every fixed-Iris core route before
     * the candidate can become visible. Dynamic hand/block-entity selectors
     * are enumerated so a route cannot first fail in a live draw.
     */
    private void prewarmCoreCatalog(
            final MetalDevice device,
            final IrisMetalCompiledPrograms compiled,
            final IrisMetalWorldResources generationResources,
            final IrisMetalUniformValues.@Nullable BackingTransaction uniformTransaction
    ) {
        List<RenderPipeline> mainPipelines = new ArrayList<>(
                IrisMetalCoreGbufferPipelines.mappedPipelines(false)
        );
        List<RenderPipeline> shadowPipelines = new ArrayList<>(
                IrisMetalCoreGbufferPipelines.mappedPipelines(true)
        );
        Comparator<RenderPipeline> byLocation = Comparator.comparing(
                pipeline -> pipeline.getLocation().toString()
        );
        mainPipelines.sort(byLocation);
        shadowPipelines.sort(byLocation);
        prewarmCorePipelines(
                device, compiled, generationResources, mainPipelines, false
        );
        if (generationResources.shadowTargets() != null) {
            prewarmCorePipelines(
                    device, compiled, generationResources, shadowPipelines, true
            );
        }
        this.uniformValues.prewarm(device, uniformTransaction);
    }

    private void prewarmCorePipelines(
            final MetalDevice device,
            final IrisMetalCompiledPrograms compiled,
            final IrisMetalWorldResources generationResources,
            final List<RenderPipeline> pipelines,
            final boolean shadow
    ) {
        for (RenderPipeline source : pipelines) {
            for (boolean handActive : new boolean[]{false, true}) {
                for (boolean handSolid : new boolean[]{false, true}) {
                    for (boolean blockEntities : new boolean[]{false, true}) {
                        ShaderKey key = IrisMetalCoreGbufferPipelines.resolve(
                                source,
                                new IrisMetalCoreGbufferPipelines.RenderState(
                                        shadow, handActive, handSolid, blockEntities
                                )
                        );
                        if (key == null) {
                            throw new IllegalStateException(
                                    "Fixed Iris catalog has no route for " + source.getLocation()
                                            + " shadow=" + shadow
                            );
                        }
                        IrisMetalGlslLinker.LinkedRasterProgram linked = this.programs.core(key)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Fixed Iris core route " + key.getName()
                                                + " has no resolved program for " + source.getLocation()
                                ));
                        this.uniformValues.register(key, "core_" + key.getName(), linked);
                        GpuFormat[] shadowFormats = key.isShadow()
                                ? requireShadowFormats(generationResources, key)
                                : null;
                        compiled.core(key, source, linked, shadowFormats);
                    }
                }
            }
        }
    }

    private static GpuFormat[] requireShadowFormats(
            final IrisMetalWorldResources generationResources,
            final ShaderKey key
    ) {
        IrisMetalShadowTargets shadowTargets = generationResources.shadowTargets();
        if (shadowTargets == null) {
            throw new IllegalStateException(
                    "Fixed Iris shadow route " + key.getName()
                            + " has no generation-owned shadow targets"
            );
        }
        return shadowTargets.colorFormats();
    }

    private void retireAfterCommit(
            final AutoCloseable retired,
            final String label
    ) {
        try {
            retired.close();
        } catch (Throwable failure) {
            try {
                this.receipts.recordFailure("retire-" + label, failure);
            } catch (Throwable receiptFailure) {
                failure.addSuppressed(receiptFailure);
            }
        }
    }

    /**
     * Captures the real Mojang overlay binding before any render encoder is
     * active. The pair is borrowed, never closed by this generation, and is
     * refreshed at each frame/resource-preparation boundary.
     */
    private MetalRenderPass.@Nullable TextureViewAndSampler snapshotMojangExternalOverlay(
            final MetalDevice device
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        GpuTextureView view = minecraft.gameRenderer.overlayTexture().getTextureView();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        return IrisMetalCoreDrawBridge.checkedMojangExternalOverlayBinding(device, view, sampler);
    }

    @Override
    public void beginTranslucents() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = target.getDepthTexture();
        if (depth == null) {
            throw new IllegalStateException("Iris translucent boundary has no main depth texture");
        }
        this.receipts.recordEvent("depthtex1.capture");
        this.executionGraph.captureNoTranslucentsDepth(this.resources(), depth);
        this.receipts.recordEvent("deferred");
        this.executionGraph.executeDeferred(this.resources());
    }

    @Override
    public void beginHand() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = target.getDepthTexture();
        GpuTextureView depthView = target.getDepthTextureView();
        if (depth == null || depthView == null) {
            throw new IllegalStateException("Iris hand boundary has no main depth texture view");
        }
        this.receipts.recordEvent("center-depth.sample");
        this.executionGraph.sampleCenterDepth(depthView, 1.0F / 60.0F);
        this.receipts.recordEvent("depthtex2.capture");
        this.executionGraph.captureNoHandDepth(this.resources(), depth);
    }

    @Override
    public void renderShadows(
            final LevelRendererAccessor levelRenderer,
            final Camera camera,
            final CameraRenderState cameraRenderState
    ) {
        if (this.directives.isPrepareBeforeShadow()) {
            this.receipts.recordEvent("prepare");
            this.executionGraph.executePrepare(this.resources());
        }
        if (shouldRenderShadowScene()) {
            if (this.shadowSceneExecutor == null) {
                this.shadowSceneExecutor = new IrisMetalShadowSceneExecutor(
                        Minecraft.getInstance(), this.frameState, this.uniformValues, this.receipts
                );
            }
            this.beginShadowDrawMetrics();
            this.receipts.recordEvent("shadow.render.begin");
            this.shadowSceneExecutor.render(
                    this.resources(),
                    this.executionGraph,
                    this.directives.getShadowDirectives(),
                    this.directives.getSunPathRotation(),
                    levelRenderer,
                    camera,
                    cameraRenderState
            );
            this.receipts.recordEvent("shadow.render.end");
            MetalDevice shadowDevice = MetalDeviceRegistry.getActiveDevice();
            if (shadowDevice == null) {
                throw new IllegalStateException(
                        "Iris shadow scene completed without an active Metal device"
                );
            }
            this.receipts.recordEvent("shadow.state.restored");
            this.receipts.captureShadowTargets(
                    shadowDevice,
                    shadowDevice.createCommandEncoder(),
                    this.resources().shadowTargets(),
                    new IrisMetalRuntimeReceipts.ShadowDrawMetrics(
                            this.shadowTerrainPasses,
                            this.shadowTerrainDrawCalls,
                            this.shadowTerrainIndexCount,
                            this.shadowCoreDrawCalls
                    )
            );
        } else {
            this.receipts.recordEvent("shadow.render.empty");
        }
        if (!this.directives.isPrepareBeforeShadow()) {
            this.receipts.recordEvent("prepare");
            this.executionGraph.executePrepare(this.resources());
        }
        this.receipts.recordEvent("shadow.composite");
        this.executionGraph.executeShadowComposite(this.resources());
    }

    private boolean shouldRenderShadowScene() {
        if (this.resources == null || this.resources.shadowTargets() == null) {
            return false;
        }
        PackShadowDirectives shadow = this.directives.getShadowDirectives();
        return shadow.isShadowEnabled().orElse(true)
                && IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance) != 0;
    }

    @Override
    public void finalizeLevelRendering() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = target.getDepthTexture();
        GpuTextureView colorView = target.getColorTextureView();
        if (depth == null || colorView == null) {
            throw new IllegalStateException("Iris final boundary has no main target textures");
        }
        this.receipts.recordEvent("depthtex0.capture");
        this.executionGraph.captureFinalDepth(this.resources(), depth);
        this.receipts.recordEvent("composite");
        this.executionGraph.executeComposite(this.resources());
        this.receipts.recordEvent("final");
        this.executionGraph.executeFinal(this.resources(), colorView);
        this.frameState.endWorldRendering();
    }

    @Override
    public void finalizeGameRendering() {
        if (this.resources == null) {
            throw new IllegalStateException("Iris color-space finalization has no generation resources");
        }
        GpuTextureView colorView = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
        if (colorView == null) {
            throw new IllegalStateException("Iris color-space finalization has no main color view");
        }
        boolean conversionExecuted = this.executionGraph.executeColorSpace(
                colorView, this.outputColorSpace
        );
        this.receipts.recordColorSpaceFinalization(
                this.outputColorSpace,
                conversionExecuted,
                this.directives.supportsColorCorrection()
        );
        // The receipt is intentionally after Iris's final color-space stage.
        // Capturing in finalizeLevelRendering() observes the pre-presentation
        // target and cannot prove DCI-P3/Display-P3/Rec.2020/Adobe RGB output.
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris final readback has no active Metal device");
        }
        this.receipts.captureFinalTarget(
                device,
                device.createCommandEncoder(),
                colorView
        );
        super.finalizeGameRendering();
    }

    @Override
    public void destroy() {
        retireWorldSettings();
        if (this.published) {
            IrisMetalPackLifecycle.onSemanticPipelineDestroyed();
            this.published = false;
        }
        this.frameState.endWorldRendering();
        this.receipts.recordEvent("generation.destroy");
        if (this.shadowSceneExecutor != null) {
            this.shadowSceneExecutor.close();
            this.shadowSceneExecutor = null;
        }
        if (this.compiledPrograms != null) {
            this.compiledPrograms.close();
            this.compiledPrograms = null;
        }
        this.executionGraph.close();
        if (this.centerDepthSampler != null) {
            this.centerDepthSampler.close();
            this.centerDepthSampler = null;
        }
        this.centerDepthDevice = null;
        this.programs.close();
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.mojangExternalOverlay = null;
        this.uniformValues.close();
        this.receipts.close();
        super.destroy();
    }

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return this.directives.getTextureMap();
    }

    @Override
    public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
        return this.forcedShadowRenderDistanceChunks;
    }

    @Override
    public WorldRenderingPhase getPhase() {
        return this.frameState.phase();
    }

    @Override
    public void setPhase(final WorldRenderingPhase phase) {
        this.frameState.setPhase(phase);
    }

    @Override
    public void setOverridePhase(final WorldRenderingPhase phase) {
        this.frameState.setOverridePhase(phase);
    }

    @Override
    public FrameUpdateNotifier getFrameUpdateNotifier() {
        return this.frameState.updateNotifier();
    }

    @Override
    public void setIsMainBound(final boolean mainBound) {
        this.frameState.setMainBound(mainBound);
    }

    @Override
    public void onBeginClear() {
        this.frameState.setPhase(WorldRenderingPhase.SKY);
    }

    @Override
    public float getSunPathRotation() {
        return this.directives.getSunPathRotation();
    }

    @Override
    public boolean shouldRenderUnderwaterOverlay() {
        return this.directives.underwaterOverlay();
    }

    @Override
    public boolean shouldRenderVignette() {
        return this.directives.vignette();
    }

    @Override
    public boolean shouldRenderSun() {
        return this.directives.shouldRenderSun();
    }

    @Override
    public boolean shouldRenderWeather() {
        return this.directives.shouldRenderWeather();
    }

    @Override
    public boolean shouldRenderWeatherParticles() {
        return this.directives.shouldRenderWeatherParticles();
    }

    @Override
    public boolean shouldRenderMoon() {
        return this.directives.shouldRenderMoon();
    }

    @Override
    public boolean shouldRenderStars() {
        return this.directives.shouldRenderStars();
    }

    @Override
    public boolean shouldRenderSkyDisc() {
        return this.directives.shouldRenderSkyDisc();
    }

    @Override
    public boolean shouldWriteRainAndSnowToDepthBuffer() {
        return this.directives.rainDepth();
    }

    @Override
    public ParticleRenderingSettings getParticleRenderingSettings() {
        return this.directives.getParticleRenderingSettings();
    }

    @Override
    public boolean allowConcurrentCompute() {
        return this.directives.getConcurrentCompute();
    }

    @Override
    public boolean hasFeature(final FeatureFlags feature) {
        return this.pack.hasFeature(feature);
    }

    @Override
    public boolean shouldDisableDirectionalShading() {
        return this.programSet != null && !this.programSet.getPackDirectives().isOldLighting();
    }

    @Override
    public boolean shouldDisableFrustumCulling() {
        return !this.directives.shouldUseFrustumCulling();
    }

    @Override
    public boolean shouldDisableOcclusionCulling() {
        return !this.directives.shouldUseOcclusionCulling();
    }

    @Override
    public CloudSetting getCloudSetting() {
        return this.directives.getCloudSetting();
    }

    @Override
    public boolean supportsEndFlash() {
        return this.directives.supportsEndFlash();
    }
}
