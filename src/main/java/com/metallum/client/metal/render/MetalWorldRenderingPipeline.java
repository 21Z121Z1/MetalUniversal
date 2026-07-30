package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final int generation;
    private final ProgramSet programSet;
    private final ShaderPack pack;
    private final PackDirectives directives;
    private final OptionalInt forcedShadowRenderDistanceChunks;
    private final IrisMetalFrameState frameState = new IrisMetalFrameState();
    private IrisMetalWorldResources resources;

    public MetalWorldRenderingPipeline(final ProgramSet programSet) {
        this.generation = GENERATIONS.incrementAndGet();
        this.programSet = Objects.requireNonNull(programSet, "programSet");
        this.pack = programSet.getPack();
        this.directives = programSet.getPackDirectives();
        this.forcedShadowRenderDistanceChunks = forcedShadowDistance(
                this.directives.getShadowDirectives()
        );
        publishWorldSettings();
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

    private void publishWorldSettings() {
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

    ProgramSet programSet() {
        return this.programSet;
    }

    int generation() {
        return this.generation;
    }

    IrisMetalWorldResources resources() {
        if (this.resources == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation + " has not prepared GPU resources"
            );
        }
        return this.resources;
    }

    boolean shouldOverrideCoreShaders(final boolean writesMainTarget) {
        return this.frameState.shouldOverrideShaders(writesMainTarget);
    }

    @Override
    public void beginLevelRendering() {
        prepareResources();
        this.frameState.beginWorldRendering();
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
        if (this.resources == null) {
            this.resources = new IrisMetalWorldResources(
                    device,
                    this.generation,
                    this.programSet,
                    mainTarget.width,
                    mainTarget.height
            );
            return;
        }
        if (!this.resources.isOwnedBy(device)) {
            throw new IllegalStateException("Iris Metal generation crossed Metal device ownership");
        }
        this.resources.resize(mainTarget.width, mainTarget.height);
    }

    @Override
    public void finalizeLevelRendering() {
        this.frameState.endWorldRendering();
    }

    @Override
    public void destroy() {
        this.frameState.endWorldRendering();
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
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
