package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Drives Iris shadow scene ownership while leaving pass scheduling to the graph. */
final class IrisMetalShadowSceneExecutor implements AutoCloseable {
    private final IrisMetalFrameState frameState;
    private final IrisMetalShadowFeatureSubmitter featureSubmitter;
    private boolean closed;

    IrisMetalShadowSceneExecutor(final Minecraft client, final IrisMetalFrameState frameState) {
        this.frameState = frameState;
        this.featureSubmitter = new IrisMetalShadowFeatureSubmitter(client);
    }

    void render(
            final IrisMetalWorldResources resources,
            final IrisMetalExecutionGraph graph,
            final PackShadowDirectives shadow,
            final float sunPathRotation,
            final LevelRendererAccessor levelRenderer,
            final Camera camera,
            final CameraRenderState cameraRenderState
    ) {
        ensureOpen();
        IrisMetalShadowTargets targets = resources.shadowTargets();
        if (targets == null) {
            throw new IllegalStateException("Iris shadow scene has no generation-owned shadow targets");
        }
        if (!(levelRenderer instanceof LevelRendererExtension extension)) {
            throw new IllegalStateException("Iris Metal shadows require Sodium's LevelRendererExtension");
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            throw new IllegalStateException("Iris Metal shadows require a loaded client level");
        }
        SodiumWorldRenderer sodium = extension.sodium$getWorldRenderer();
        Vector3d cameraPosition = CameraUniforms.getUnshiftedCameraPosition();
        PoseStack shadowPose = ShadowRenderer.createShadowModelView(
                sunPathRotation,
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
        shadowFrustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);
        ChunkRenderMatrices shadowMatrices = new ChunkRenderMatrices(shadowProjection, shadowView);
        GpuSampler shadowSampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                true
        );

        CullingDataCache cullingData = levelRenderer instanceof CullingDataCache cache ? cache : null;
        ShadowRenderListAccess shadowRenderLists = sodium instanceof ShadowRenderListAccess access ? access : null;
        IrisMetalShadowStateSnapshot snapshot = IrisMetalShadowStateSnapshot.capture(
                levelRenderer,
                extension,
                client,
                cameraRenderState,
                cullingData,
                shadowRenderLists
        );
        try {
            snapshot.enter(
                    this.featureSubmitter.renderBuffers(),
                    shadowMatrices,
                    shadowView,
                    shadowProjection
            );
            snapshot.setShadowStatics(
                    shadow.getResolution(),
                    effectiveRenderDistance(shadow),
                    shadowView,
                    shadowProjection,
                    shadowFrustum,
                    new java.util.ArrayList<>()
            );
            this.featureSubmitter.prepareCamera(
                    camera,
                    CapturedRenderingState.INSTANCE.getTickDelta(),
                    shadowView,
                    shadowProjection
            );
            graph.beginShadowScene(resources, shadow);

            sodium.scheduleTerrainUpdate();
            sodium.setupTerrain(
                    camera,
                    ((ViewportProvider) shadowFrustum).sodium$createViewport(),
                    ((FogStorage) client.gameRenderer).sodium$getFogParameters(),
                    camera.entity() != null && camera.entity().isSpectator(),
                    false,
                    ((FrustumAccessor) shadowFrustum).sodium$getMatrix()
            );
            client.smartCull = snapshot.previousSmartCull();

            ChunkSectionsToRender sections = new ChunkSectionsToRender(null, null, 0, null);
            ((SodiumChunkSection) (Object) sections).sodium$setRendering(
                    sodium,
                    shadowMatrices,
                    cameraPosition.x,
                    cameraPosition.y,
                    cameraPosition.z
            );
            if (shadow.shouldRenderTerrain()) {
                this.frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.TERRAIN_SOLID);
                sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, shadowSampler);
                this.frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
            }
            if (needsFeatureSubmission(shadow)) {
                RenderSystem.getModelViewStack().identity();
                try {
                    this.featureSubmitter.submit(
                            levelRenderer,
                            sodium,
                            camera,
                            shadowPose,
                            shadowFrustum,
                            cameraPosition,
                            shadow,
                            this.frameState
                    );
                } finally {
                    RenderSystem.getModelViewStack().set(shadowView);
                }
            }
            if (shadow.shouldRenderTranslucent()) {
                this.frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.TERRAIN_TRANSLUCENT);
                sections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, shadowSampler);
                this.frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
            }
        } finally {
            this.frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
            snapshot.restore();
        }
    }

    static boolean needsFeatureSubmission(final PackShadowDirectives shadow) {
        return shadow.shouldRenderEntities()
                || shadow.shouldRenderPlayer()
                || shadow.shouldRenderBlockEntities()
                || shadow.shouldRenderLightBlockEntities();
    }

    private static int effectiveRenderDistance(final PackShadowDirectives shadow) {
        return shadow.getDistanceRenderMul() < 0.0F
                ? IrisVideoSettings.shadowDistance
                : (int) (shadow.getDistance() * shadow.getDistanceRenderMul() / 16.0F);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.featureSubmitter.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris shadow scene executor is closed");
        }
    }
}
