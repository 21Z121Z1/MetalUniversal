package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The Iris-on-Metal world rendering pipeline (B2-1 slice).
 *
 * <p>Iris's own {@code IrisRenderingPipeline} is a GL object graph: it builds
 * GL programs, framebuffers and samplers in its constructor. On the Metal
 * backend that is not adaptable, so {@code Iris.createPipeline} is redirected
 * to this class instead (see {@code IrisPipelineFactoryMixin}). This is the
 * "semantic layer" seam: Iris still owns pack parsing, option handling, the id
 * maps and the render-phase state machine, while the actual GPU work happens
 * through the Metal backend.</p>
 *
 * <p><b>Scope of B2-1.</b> This pipeline does exactly two things beyond the
 * vanilla behaviour it inherits:</p>
 * <ol>
 *   <li>mirrors the {@link WorldRenderingSettings} that
 *       {@code IrisRenderingPipeline}'s constructor sets, most importantly the
 *       extended chunk vertex format — sodium must build terrain meshes with
 *       the attributes the pack's {@code gbuffers_terrain} expects;</li>
 *   <li>activates {@link IrisMetalPipelineOverrides}, so sodium's terrain
 *       pipelines compile from the pack's translated programs.</li>
 * </ol>
 *
 * <p>Everything else — shadows, composite/final, the deferred chain, custom
 * uniforms, entity/particle programs — is inherited from
 * {@link VanillaRenderingPipeline} and therefore behaves exactly as it does
 * with shaders off. That is the honest state of B2-1: terrain is drawn with the
 * pack's gbuffer program and the raw gbuffer0 output goes to the screen; there
 * is no composite pass yet (B2-3).</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalWorldRenderingPipeline extends VanillaRenderingPipeline {
    private final ProgramSet programSet;
    private final ShaderPack pack;
    private final PackDirectives directives;
    private final OptionalInt forcedShadowRenderDistanceChunks;
    private final IrisMetalPipelineOverrides.Instance overrides;
    private final FrameState frameState = new FrameState();
    private final RenderBuffers shadowRenderBuffers;
    private final LevelRenderState shadowLevelRenderState = new LevelRenderState();
    private final SubmitNodeStorage shadowSubmitNodeStorage = new SubmitNodeStorage();
    private final FeatureRenderDispatcher shadowFeatureRenderDispatcher;
    private final HorizonRenderer horizonRenderer;
    private boolean initializedBlockIds;

    public MetalWorldRenderingPipeline(final ProgramSet programSet) {
        IrisMetalPackAdmission.requireSupported(
                programSet,
                Objects.requireNonNull(IrisVideoSettings.colorSpace, "Iris color space")
        );
        this.programSet = programSet;
        this.pack = programSet.getPack();
        this.directives = programSet.getPackDirectives();
        PackDirectives directives = this.directives;
        PackShadowDirectives shadowDirectives = directives.getShadowDirectives();
        if (shadowDirectives.isDistanceRenderMulExplicit()) {
            this.forcedShadowRenderDistanceChunks = shadowDirectives.getDistanceRenderMul() < 0.0F
                    ? OptionalInt.of(-1)
                    : OptionalInt.of((int) Math.ceil(
                            shadowDirectives.getDistance() * shadowDirectives.getDistanceRenderMul() / 16.0F
                    ));
        } else {
            this.forcedShadowRenderDistanceChunks = OptionalInt.empty();
        }

        // Build every CPU execution plan before mutating renderer-global state.
        // Unsupported declarations therefore fail admission without leaving
        // Sodium configured for a generation that was never published.
        IrisMetalPipelineOverrides.setExtendedTerrainTargets(true);
        this.overrides = IrisMetalPipelineOverrides.prepare(
                programSet,
                directives.getTextureMap(),
                this.frameState.updateNotifier(),
                () -> this.frameState.phase().ordinal()
        );

        RenderBuffers preparedShadowBuffers = null;
        FeatureRenderDispatcher preparedFeatureDispatcher = null;
        HorizonRenderer preparedHorizonRenderer = null;
        try {
            Minecraft client = Minecraft.getInstance();
            preparedShadowBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
            preparedFeatureDispatcher = new FeatureRenderDispatcher(
                    preparedShadowBuffers,
                    client.getModelManager(),
                    client.getAtlasManager(),
                    client.font,
                    client.gameRenderer.gameRenderState()
            );
            preparedHorizonRenderer = new HorizonRenderer();
            this.shadowRenderBuffers = preparedShadowBuffers;
            this.shadowFeatureRenderDispatcher = preparedFeatureDispatcher;
            this.horizonRenderer = preparedHorizonRenderer;

            // Mirrors IrisRenderingPipeline's constructor. The vertex format is the
            // load-bearing one: FormatAnalyzer.createFormat(true, true, true, true)
            // is the extended (XHFP) chunk format whose extra attributes Iris's own
            // sodium mesh mixins write, and which the patched terrain shader reads.
            WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
            settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
            settings.setEntityIds(this.pack.getIdMap().getEntityIdMap());
            settings.setItemIds(this.pack.getIdMap().getItemIdMap());
            settings.setAmbientOcclusionLevel(directives.getAmbientOcclusionLevel());
            settings.setDisableDirectionalShading(!directives.isOldLighting());
            settings.setUseSeparateAo(directives.shouldUseSeparateAo());
            settings.setBreaksAnisotropy(directives.breaksAnisotropy());
            settings.setVoxelizeLightBlocks(directives.shouldVoxelizeLightBlocks());
            settings.setSeparateEntityDraws(directives.shouldUseSeparateEntityDraws());

            // Publish only after the generation and its non-GPU renderer resources
            // are complete. Cached dimensions remain selected if construction fails.
            IrisMetalPipelineOverrides.select(this.overrides);
            IrisMetalPackLifecycle.onSemanticPipelineActivated(this.overrides.generation());
            Metallum.LOGGER.info(
                    "[metallum-iris] semantic pipeline generation {} online for pack program set {}",
                    this.overrides.generation(), this.pack.getProfileInfo()
            );
        } catch (RuntimeException | Error failure) {
            closeConstructionResources(
                    preparedHorizonRenderer,
                    preparedFeatureDispatcher,
                    preparedShadowBuffers,
                    failure
            );
            throw failure;
        }
    }

    private void closeConstructionResources(
            final HorizonRenderer preparedHorizonRenderer,
            final FeatureRenderDispatcher preparedFeatureDispatcher,
            final RenderBuffers preparedShadowBuffers,
            final Throwable failure
    ) {
        try {
            if (preparedHorizonRenderer != null) {
                preparedHorizonRenderer.destroy();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            if (preparedFeatureDispatcher != null) {
                preparedFeatureDispatcher.close();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            if (preparedShadowBuffers != null) {
                preparedShadowBuffers.close();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            IrisMetalPipelineOverrides.deactivate(this.overrides);
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Block/tag id maps are built lazily on the first frame, exactly as
     * {@code IrisRenderingPipeline} does — they need a loaded level, and
     * populating them invalidates every chunk mesh, so the rebuild is triggered
     * once here rather than at pack load.
     *
     * <p>{@code super.beginLevelRendering()} is deliberately not called: it
     * issues {@code glClipControl} and {@code glUseProgram} (see
     * {@code IrisVanillaPipelineCompatMixin}), which have no meaning on the
     * Metal backend.</p>
     */
    @Override
    public void beginLevelRendering() {
        IrisMetalPassTrace.observeLifecycle("begin_level_enter");
        activateDimensionGeneration();
        this.frameState.beginWorldRendering();
        // Iris's fixed-input uniform graph includes currentSelectedBlockId and
        // currentSelectedBlockData suppliers.  Populate the same world-owned
        // material maps before evaluating that graph; otherwise an initial
        // world load (especially a non-Overworld dimension) can observe the
        // maps as null and fail before the first draw.
        ensureBlockMaterialMappings();
        // Iris advances queued PBR resource aliases once per world frame
        // before any program asks their dynamic TextureWrapper suppliers.
        PBRTextureManager.INSTANCE.onNewFrame();
        IrisMetalPassTrace.observeLifecycle("pbr_frame_complete");
        // Refresh the pack's uniform block before sodium draws terrain.
        IrisMetalPipelineOverrides.updateFrame();
        IrisMetalPassTrace.observeLifecycle("begin_stage_enter");
        IrisMetalPipelineOverrides.executePostStage(IrisMetalPostChain.Stage.BEGIN);
        IrisMetalPassTrace.observeLifecycle("begin_stage_complete");
        IrisMetalPassTrace.observePhase("gbuffer", "executing");
    }

    private void ensureBlockMaterialMappings() {
        if (this.initializedBlockIds) {
            return;
        }
        this.initializedBlockIds = true;
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                this.pack.getIdMap().getBlockProperties(), this.pack.getIdMap().getTagEntries()
        ));
        settings.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
                this.pack.getIdMap().getBlockRenderTypeMap()
        ));
        // Publish the new map epoch before queuing builds. A worker that began
        // against the pre-map state retains the older immutable stamp and is
        // rejected when its result reaches Sodium's render-thread boundary.
        IrisMetalPipelineOverrides.markTerrainMaterialMappingsReady(this.overrides);
    }

    /**
     * Mirrors Iris's pre-sky horizon draw. This fan fills the area below
     * Mojang's sky disc through {@code gbuffers_skybasic}; omitting it exposes
     * the framebuffer clear colour along the horizon.
     */
    @Override
    public void onBeginClear() {
        this.frameState.setPhase(WorldRenderingPhase.SKY);
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !this.directives.shouldRenderSkyDisc()) {
            return;
        }
        DimensionType dimension = client.level.dimensionType();
        if (dimension.skybox() != DimensionType.Skybox.OVERWORLD && !dimension.hasSkyLight()) {
            return;
        }
        Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
        this.horizonRenderer.renderHorizon(
                CapturedRenderingState.INSTANCE.getGbufferModelView(),
                CapturedRenderingState.INSTANCE.getGbufferProjection(),
                new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F)
        );
    }

    /** Iris phase boundary used to freeze depthtex1 before translucents. */
    @Override
    public void beginTranslucents() {
        IrisMetalPipelineOverrides.captureNoTranslucentsDepth();
        IrisMetalPassTrace.observePhase("depthtex1", "captured");
        IrisMetalPipelineOverrides.executePostStage(IrisMetalPostChain.Stage.DEFERRED);
    }

    /** Iris phase boundary used to freeze depthtex2 before hand rendering. */
    @Override
    public void beginHand() {
        IrisMetalPipelineOverrides.sampleCenterDepth();
        IrisMetalPipelineOverrides.captureNoHandDepth();
        IrisMetalPassTrace.observePhase("depthtex2", "captured");
    }

    @Override
    public void renderShadows(
            final LevelRendererAccessor levelRenderer,
            final Camera camera,
            final CameraRenderState cameraRenderState
    ) {
        if (!IrisMetalPipelineOverrides.shadowsEnabled()
                || IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance) == 0) {
            IrisMetalPipelineOverrides.completeShadowFrame();
            IrisMetalPassTrace.observePhase("shadow", "empty");
            IrisMetalPipelineOverrides.executePostStage(IrisMetalPostChain.Stage.PREPARE);
            return;
        }
        PackShadowDirectives shadow = this.directives.getShadowDirectives();
        if (!(levelRenderer instanceof LevelRendererExtension extension)) {
            throw new IllegalStateException("Iris Metal shadows require Sodium's LevelRendererExtension");
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            throw new IllegalStateException("Iris Metal shadows require a loaded client level");
        }
        SodiumWorldRenderer sodium = extension.sodium$getWorldRenderer();
        ChunkRenderMatrices previousMatrices = extension.sodium$getMatrices();
        RenderBuffers previousRenderBuffers = levelRenderer.getRenderBuffers();
        Matrix4f previousView = new Matrix4f(cameraRenderState.viewRotationMatrix);
        Matrix4f previousProjection = new Matrix4f(cameraRenderState.projectionMatrix);
        boolean previousSmartCull = client.smartCull;
        CullingDataCache culling = levelRenderer instanceof CullingDataCache cache ? cache : null;
        ShadowRenderListAccess shadowLists = sodium instanceof ShadowRenderListAccess access ? access : null;
        boolean modelViewPushed = false;

        PoseStack shadowPose = ShadowRenderer.createShadowModelView(
                this.directives.getSunPathRotation(),
                shadow.getIntervalSize(),
                shadow.getNearPlane(),
                shadow.getFarPlane()
        );
        Matrix4f shadowView = new Matrix4f(shadowPose.last().pose());
        Matrix4f shadowProjection = shadow.getFov() == null
                ? ShadowMatrices.createOrthoMatrix(
                        shadow.getDistance(),
                        Mth.equal(shadow.getNearPlane(), -1.0F)
                                ? -DHCompat.getRenderDistance() * 16.0F : shadow.getNearPlane(),
                        Mth.equal(shadow.getFarPlane(), -1.0F)
                                ? DHCompat.getRenderDistance() * 16.0F : shadow.getFarPlane()
                )
                : ShadowMatrices.createPerspectiveMatrix(shadow.getFov());
        NonCullingFrustum shadowFrustum = new NonCullingFrustum(shadowProjection, shadowView);
        Vector3d cameraPosition = CameraUniforms.getUnshiftedCameraPosition();
        shadowFrustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);
        ChunkRenderMatrices shadowMatrices = new ChunkRenderMatrices(shadowProjection, shadowView);
        GpuSampler shadowSampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                true
        );

        try {
            if (culling != null) {
                culling.saveState();
            }
            if (shadowLists != null) {
                shadowLists.iris$beginShadowRenderListScope();
            }
            client.smartCull = false;
            levelRenderer.setRenderBuffers(this.shadowRenderBuffers);
            extension.sodium$setMatrices(shadowMatrices);
            cameraRenderState.viewRotationMatrix = shadowView;
            cameraRenderState.projectionMatrix = shadowProjection;
            this.shadowLevelRenderState.reset();
            camera.extractRenderState(
                    this.shadowLevelRenderState.cameraRenderState,
                    CapturedRenderingState.INSTANCE.getTickDelta()
            );
            this.shadowLevelRenderState.cameraRenderState.viewRotationMatrix = shadowView;
            this.shadowLevelRenderState.cameraRenderState.projectionMatrix = shadowProjection;
            RenderSystem.getModelViewStack().pushMatrix();
            modelViewPushed = true;
            RenderSystem.getModelViewStack().set(shadowView);

            ShadowRenderer.ACTIVE = true;
            ShadowRenderer.RESOLUTION = shadow.getResolution();
            ShadowRenderer.MODELVIEW = shadowView;
            ShadowRenderer.PROJECTION = shadowProjection;
            ShadowRenderer.FRUSTUM = shadowFrustum;
            ShadowRenderer.visibleBlockEntities = new ArrayList<>();
            ShadowRenderer.renderDistance = shadow.getDistanceRenderMul() < 0.0F
                    ? IrisVideoSettings.shadowDistance
                    : (int) (shadow.getDistance() * shadow.getDistanceRenderMul() / 16.0F);

            sodium.scheduleTerrainUpdate();
            sodium.setupTerrain(
                    camera,
                    ((ViewportProvider) shadowFrustum).sodium$createViewport(),
                    ((FogStorage) client.gameRenderer).sodium$getFogParameters(),
                    camera.entity() != null && camera.entity().isSpectator(),
                    false,
                    ((FrustumAccessor) shadowFrustum).sodium$getMatrix()
            );
            client.smartCull = previousSmartCull;

            ChunkSectionsToRender sections = new ChunkSectionsToRender(null, null, 0, null);
            ((SodiumChunkSection) (Object) sections).sodium$setRendering(
                    sodium, shadowMatrices, cameraPosition.x, cameraPosition.y, cameraPosition.z
            );
            IrisMetalPipelineOverrides.executeShadowFrame(new IrisMetalShadowPipeline.LevelRendererAdapter() {
                @Override
                public void renderOpaqueShadows() {
                    if (shadow.shouldRenderTerrain()) {
                        frameState.setPhase(WorldRenderingPhase.TERRAIN_SOLID);
                        sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, shadowSampler);
                        frameState.setPhase(WorldRenderingPhase.NONE);
                    }
                    if (needsShadowFeatureSubmission(shadow)) {
                        RenderSystem.getModelViewStack().identity();
                        try {
                            renderShadowFeatures(
                                    levelRenderer,
                                    sodium,
                                    camera,
                                    shadowPose,
                                    shadowFrustum,
                                    cameraPosition,
                                    shadow
                            );
                        } finally {
                            RenderSystem.getModelViewStack().set(shadowView);
                        }
                    }
                }

                @Override
                public void renderTranslucentShadows() {
                    if (shadow.shouldRenderTranslucent()) {
                        frameState.setPhase(WorldRenderingPhase.TERRAIN_TRANSLUCENT);
                        sections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, shadowSampler);
                        frameState.setPhase(WorldRenderingPhase.NONE);
                    }
                }
            });
        } finally {
            ShadowRenderer.ACTIVE = false;
            ShadowRenderer.visibleBlockEntities = null;
            client.smartCull = previousSmartCull;
            levelRenderer.setRenderBuffers(previousRenderBuffers);
            extension.sodium$setMatrices(previousMatrices);
            cameraRenderState.viewRotationMatrix = previousView;
            cameraRenderState.projectionMatrix = previousProjection;
            this.shadowLevelRenderState.reset();
            this.frameState.setPhase(WorldRenderingPhase.NONE);
            if (modelViewPushed) {
                RenderSystem.getModelViewStack().popMatrix();
            }
            if (shadowLists != null) {
                shadowLists.iris$endShadowRenderListScope();
            }
            if (culling != null) {
                culling.restoreState();
            }
        }
        IrisMetalPipelineOverrides.executePostStage(IrisMetalPostChain.Stage.PREPARE);
    }

    private void renderShadowFeatures(
            final LevelRendererAccessor levelRenderer,
            final SodiumWorldRenderer sodium,
            final Camera camera,
            final PoseStack shadowPose,
            final Frustum entityFrustum,
            final Vector3d cameraPosition,
            final PackShadowDirectives shadow
    ) {
        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher entityDispatcher = levelRenderer.getEntityRenderDispatcher();
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();

        this.frameState.setPhase(WorldRenderingPhase.ENTITIES);
        try {
            if (shadow.shouldRenderEntities()) {
                extractVisibleShadowEntities(client, camera, entityFrustum, this.shadowLevelRenderState);
            } else if (shadow.shouldRenderPlayer()) {
                extractShadowPlayer(
                        client,
                        entityDispatcher,
                        this.shadowLevelRenderState,
                        client.getDeltaTracker().getGameTimeDeltaPartialTick(false)
                );
            }

            for (EntityRenderState state : this.shadowLevelRenderState.entityRenderStates) {
                entityDispatcher.submit(
                        state,
                        this.shadowLevelRenderState.cameraRenderState,
                        state.x - cameraPosition.x,
                        state.y - cameraPosition.y,
                        state.z - cameraPosition.z,
                        shadowPose,
                        this.shadowSubmitNodeStorage
                );
            }

            if (shadow.shouldRenderBlockEntities() || shadow.shouldRenderLightBlockEntities()) {
                sodium.extractBlockEntities(
                        camera,
                        tickDelta,
                        client.level.destructionProgress(),
                        this.shadowLevelRenderState
                );
                if (!shadow.shouldRenderBlockEntities()) {
                    this.shadowLevelRenderState.blockEntityRenderStates.removeIf(
                            state -> !shouldRenderLightBlockEntity(
                                    client.level.getBlockState(state.blockPos).getLightEmission()
                            )
                    );
                }
                submitShadowBlockEntities(client, camera, shadowPose);
            }

            this.shadowFeatureRenderDispatcher.renderAllFeatures(this.shadowSubmitNodeStorage);
        } finally {
            this.shadowRenderBuffers.endFrame();
            this.frameState.setPhase(WorldRenderingPhase.NONE);
        }
    }

    private static void extractVisibleShadowEntities(
            final Minecraft client,
            final Camera camera,
            final Frustum frustum,
            final LevelRenderState output
    ) {
        if (client.level == null) {
            throw new IllegalStateException("Iris Metal entity shadows require a loaded client level");
        }
        Vec3 cameraPosition = camera.position();
        double cameraX = cameraPosition.x();
        double cameraY = cameraPosition.y();
        double cameraZ = cameraPosition.z();
        TickRateManager tickRateManager = client.level.tickRateManager();
        double viewScale = Mth.clamp(client.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5)
                * client.options.entityDistanceScaling().get();
        Entity.setViewScale(viewScale);
        DeltaTracker deltaTracker = client.getDeltaTracker();
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!shouldExtractGeneralShadowEntity(entity instanceof AbstractClientPlayer player && player.isSpectator())) {
                continue;
            }
            if (!dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)
                    && !entity.hasIndirectPassenger(client.player)) {
                continue;
            }
            BlockPos blockPos = entity.blockPosition();
            if (!client.level.isOutsideBuildHeight(blockPos.getY())
                    && !client.levelRenderer.isSectionCompiledAndVisible(blockPos)) {
                continue;
            }
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(
                    !tickRateManager.isEntityFrozen(entity)
            );
            output.entityRenderStates.add(dispatcher.extractEntity(entity, partialTick));
        }
    }

    private static void extractShadowPlayer(
            final Minecraft client,
            final EntityRenderDispatcher dispatcher,
            final LevelRenderState output,
            final float tickDelta
    ) {
        LocalPlayer player = client.player;
        if (player == null) {
            throw new IllegalStateException("Iris Metal player shadows require a local player");
        }
        if (shouldExtractShadowPlayer(player.isSpectator(), player.isInvisible())) {
            output.entityRenderStates.add(dispatcher.extractEntity(player, tickDelta));
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            output.entityRenderStates.add(dispatcher.extractEntity(vehicle, tickDelta));
        }
    }

    private void submitShadowBlockEntities(
            final Minecraft client,
            final Camera camera,
            final PoseStack shadowPose
    ) {
        Vec3 cameraPosition = camera.position();
        BlockEntityRenderDispatcher dispatcher = client.getBlockEntityRenderDispatcher();
        for (BlockEntityRenderState state : this.shadowLevelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = state.blockPos;
            shadowPose.pushPose();
            shadowPose.translate(
                    blockPos.getX() - cameraPosition.x,
                    blockPos.getY() - cameraPosition.y,
                    blockPos.getZ() - cameraPosition.z
            );
            dispatcher.submit(
                    state,
                    shadowPose,
                    this.shadowSubmitNodeStorage,
                    this.shadowLevelRenderState.cameraRenderState
            );
            shadowPose.popPose();
        }
    }

    static boolean needsShadowFeatureSubmission(final PackShadowDirectives shadow) {
        return shadow.shouldRenderEntities()
                || shadow.shouldRenderPlayer()
                || shadow.shouldRenderBlockEntities()
                || shadow.shouldRenderLightBlockEntities();
    }

    static boolean shouldExtractGeneralShadowEntity(final boolean spectatorClientPlayer) {
        return !spectatorClientPlayer;
    }

    static boolean shouldExtractShadowPlayer(final boolean spectator, final boolean invisible) {
        return !spectator && !invisible;
    }

    static boolean shouldRenderLightBlockEntity(final int lightEmission) {
        return lightEmission != 0;
    }

    @Override
    public void finalizeLevelRendering() {
        // Match Iris: core shader replacement ends before composite/final draw
        // into the Minecraft target, even though the pipeline remains active.
        this.frameState.endWorldRendering();
        IrisMetalPipelineOverrides.executePostStage(IrisMetalPostChain.Stage.COMPOSITE);
        IrisMetalPipelineOverrides.executeFinal();
        super.finalizeLevelRendering();
    }

    @Override
    public void finalizeGameRendering() {
        // Fixed Iris runs its output color-space converter after the pack final
        // pass, preserving the intermediate RGBA8 quantization before display.
        IrisMetalPipelineOverrides.executeColorSpace(
                Objects.requireNonNull(IrisVideoSettings.colorSpace, "Iris color space")
        );
        super.finalizeGameRendering();
    }

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return this.programSet.getPackDirectives().getTextureMap();
    }

    @Override
    public float getSunPathRotation() {
        return this.directives.getSunPathRotation();
    }

    @Override
    public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
        return this.forcedShadowRenderDistanceChunks;
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
    public boolean shouldDisableFrustumCulling() {
        return !this.directives.shouldUseFrustumCulling();
    }

    @Override
    public boolean shouldDisableOcclusionCulling() {
        return !this.directives.shouldUseOcclusionCulling();
    }

    @Override
    public boolean shouldDisableVanillaEntityShadows() {
        return IrisMetalPipelineOverrides.shadowsEnabled();
    }

    @Override
    public CloudSetting getCloudSetting() {
        return this.directives.getCloudSetting();
    }

    @Override
    public boolean supportsEndFlash() {
        return this.directives.supportsEndFlash();
    }

    /** Mirrors fixed Iris's concrete-pipeline-only skipAllRendering contract. */
    public boolean shouldSkipAllRendering() {
        return this.directives.skipAllRendering();
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

    /** Equivalent to Iris's {@code isRenderingWorld && isMainBound} gate. */
    boolean shouldOverrideCoreShaders(final boolean writesMainTarget) {
        return this.frameState.shouldOverrideShaders(writesMainTarget);
    }

    /**
     * {@code VanillaRenderingPipeline}'s constructor calls this before our own
     * fields are assigned (it seeds {@code WorldRenderingSettings} from it), so
     * the null check is load-bearing: during super construction it answers with
     * the vanilla default, and our constructor writes the pack's real value to
     * the settings straight afterwards.
     */
    @Override
    public boolean shouldDisableDirectionalShading() {
        return this.programSet != null && !this.programSet.getPackDirectives().isOldLighting();
    }

    @Override
    public void destroy() {
        this.frameState.endWorldRendering();
        IrisMetalPipelineOverrides.deactivate(this.overrides);
        this.horizonRenderer.destroy();
        this.shadowFeatureRenderDispatcher.close();
        this.shadowRenderBuffers.close();
        Metallum.LOGGER.info(
                "[metallum-iris] semantic pipeline generation {} destroyed", this.overrides.generation()
        );
        super.destroy();
    }

    /** Called by PipelineManager for both newly-created and cached dimensions. */
    public void activateDimensionGeneration() {
        IrisMetalPipelineOverrides.select(this.overrides);
    }

    /** Render-thread state kept independently of the GL-backed Iris pipeline. */
    static final class FrameState {
        private final FrameUpdateNotifier updateNotifier = new FrameUpdateNotifier();
        private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
        private WorldRenderingPhase overridePhase;
        private boolean removePhase;
        private boolean renderingWorld;
        private boolean mainBound;

        FrameUpdateNotifier updateNotifier() {
            return this.updateNotifier;
        }

        void beginWorldRendering() {
            this.renderingWorld = true;
            // Mojang GPU API draw calls initially target the main RenderTarget;
            // per-draw attachment identity provides the offscreen refinement.
            this.mainBound = true;
        }

        void endWorldRendering() {
            this.renderingWorld = false;
            removePhaseIfNeeded();
        }

        WorldRenderingPhase phase() {
            removePhaseIfNeeded();
            return this.overridePhase != null ? this.overridePhase : this.phase;
        }

        void setPhase(final WorldRenderingPhase next) {
            if (next == WorldRenderingPhase.NONE) {
                this.removePhase = true;
                return;
            }
            this.removePhase = false;
            this.phase = next;
        }

        void setOverridePhase(final WorldRenderingPhase overridePhase) {
            this.overridePhase = overridePhase;
        }

        void setMainBound(final boolean mainBound) {
            this.mainBound = mainBound;
        }

        boolean shouldOverrideShaders(final boolean writesMainTarget) {
            return this.renderingWorld && this.mainBound && writesMainTarget;
        }

        private void removePhaseIfNeeded() {
            if (this.removePhase) {
                this.phase = WorldRenderingPhase.NONE;
                this.removePhase = false;
            }
        }
    }
}
