package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Captures every world/Sodium/static shadow value changed by a shadow scene. */
final class IrisMetalShadowStateSnapshot {
    private final LevelRendererAccessor levelRenderer;
    private final LevelRendererExtension sodiumExtension;
    private final Minecraft client;
    private final CameraRenderState cameraRenderState;
    private final RenderBuffers renderBuffers;
    private final ChunkRenderMatrices sodiumMatrices;
    private final Matrix4f cameraView;
    private final Matrix4f cameraProjection;
    private final Matrix4f modelView;
    private final boolean smartCull;
    private final @Nullable CullingDataCache cullingData;
    private final @Nullable ShadowRenderListAccess shadowRenderLists;
    private final boolean shadowActive;
    private final int shadowResolution;
    private final @Nullable List<BlockEntity> visibleBlockEntities;
    private final int shadowRenderDistance;
    private final @Nullable Matrix4f shadowModelView;
    private final @Nullable Matrix4f shadowProjection;
    private final @Nullable Frustum shadowFrustum;
    private boolean entered;
    private boolean cullingSaved;
    private boolean shadowListScope;
    private boolean modelViewPushed;
    private boolean restored;

    private IrisMetalShadowStateSnapshot(
            final LevelRendererAccessor levelRenderer,
            final LevelRendererExtension sodiumExtension,
            final Minecraft client,
            final CameraRenderState cameraRenderState,
            final RenderBuffers renderBuffers,
            final ChunkRenderMatrices sodiumMatrices,
            final Matrix4f cameraView,
            final Matrix4f cameraProjection,
            final Matrix4f modelView,
            final boolean smartCull,
            final @Nullable CullingDataCache cullingData,
            final @Nullable ShadowRenderListAccess shadowRenderLists
    ) {
        this.levelRenderer = levelRenderer;
        this.sodiumExtension = sodiumExtension;
        this.client = client;
        this.cameraRenderState = cameraRenderState;
        this.renderBuffers = renderBuffers;
        this.sodiumMatrices = sodiumMatrices;
        this.cameraView = cameraView;
        this.cameraProjection = cameraProjection;
        this.modelView = modelView;
        this.smartCull = smartCull;
        this.cullingData = cullingData;
        this.shadowRenderLists = shadowRenderLists;
        this.shadowActive = ShadowRenderer.ACTIVE;
        this.shadowResolution = ShadowRenderer.RESOLUTION;
        this.visibleBlockEntities = ShadowRenderer.visibleBlockEntities;
        this.shadowRenderDistance = ShadowRenderer.renderDistance;
        this.shadowModelView = ShadowRenderer.MODELVIEW == null
                ? null : new Matrix4f(ShadowRenderer.MODELVIEW);
        this.shadowProjection = ShadowRenderer.PROJECTION == null
                ? null : new Matrix4f(ShadowRenderer.PROJECTION);
        this.shadowFrustum = ShadowRenderer.FRUSTUM;
    }

    static IrisMetalShadowStateSnapshot capture(
            final LevelRendererAccessor levelRenderer,
            final LevelRendererExtension sodiumExtension,
            final Minecraft client,
            final CameraRenderState cameraRenderState,
            final @Nullable CullingDataCache cullingData,
            final @Nullable ShadowRenderListAccess shadowRenderLists
    ) {
        RenderBuffers renderBuffers = levelRenderer.getRenderBuffers();
        ChunkRenderMatrices sodiumMatrices = sodiumExtension.sodium$getMatrices();
        return new IrisMetalShadowStateSnapshot(
                levelRenderer,
                sodiumExtension,
                client,
                cameraRenderState,
                renderBuffers,
                sodiumMatrices,
                new Matrix4f(cameraRenderState.viewRotationMatrix),
                new Matrix4f(cameraRenderState.projectionMatrix),
                RenderSystem.getModelViewMatrixCopy(),
                client.smartCull,
                cullingData,
                shadowRenderLists
        );
    }

    void enter(
            final RenderBuffers shadowBuffers,
            final ChunkRenderMatrices shadowMatrices,
            final Matrix4f shadowView,
            final Matrix4f shadowProjection
    ) {
        if (this.entered) {
            throw new IllegalStateException("Iris shadow state was entered twice");
        }
        this.entered = true;
        if (this.cullingData != null) {
            this.cullingData.saveState();
            this.cullingSaved = true;
        }
        if (this.shadowRenderLists != null) {
            this.shadowRenderLists.iris$beginShadowRenderListScope();
            this.shadowListScope = true;
        }
        this.client.smartCull = false;
        this.levelRenderer.setRenderBuffers(shadowBuffers);
        this.sodiumExtension.sodium$setMatrices(shadowMatrices);
        this.cameraRenderState.viewRotationMatrix = shadowView;
        this.cameraRenderState.projectionMatrix = shadowProjection;
        RenderSystem.getModelViewStack().pushMatrix();
        this.modelViewPushed = true;
        RenderSystem.getModelViewStack().set(shadowView);
    }

    void setShadowStatics(
            final int resolution,
            final int renderDistance,
            final Matrix4f shadowView,
            final Matrix4f shadowProjection,
            final Frustum shadowFrustum,
            final List<BlockEntity> visibleBlockEntities
    ) {
        ShadowRenderer.ACTIVE = true;
        ShadowRenderer.RESOLUTION = resolution;
        ShadowRenderer.MODELVIEW = shadowView;
        ShadowRenderer.PROJECTION = shadowProjection;
        ShadowRenderer.FRUSTUM = shadowFrustum;
        ShadowRenderer.visibleBlockEntities = visibleBlockEntities;
        ShadowRenderer.renderDistance = renderDistance;
    }

    boolean previousSmartCull() {
        return this.smartCull;
    }

    void restore() {
        if (!this.entered || this.restored) {
            return;
        }
        this.restored = true;
        List<Runnable> steps = new ArrayList<>(14);
        steps.add(() -> ShadowRenderer.ACTIVE = this.shadowActive);
        steps.add(() -> ShadowRenderer.RESOLUTION = this.shadowResolution);
        steps.add(() -> ShadowRenderer.MODELVIEW = this.shadowModelView);
        steps.add(() -> ShadowRenderer.PROJECTION = this.shadowProjection);
        steps.add(() -> ShadowRenderer.FRUSTUM = this.shadowFrustum);
        steps.add(() -> ShadowRenderer.visibleBlockEntities = this.visibleBlockEntities);
        steps.add(() -> ShadowRenderer.renderDistance = this.shadowRenderDistance);
        steps.add(() -> this.client.smartCull = this.smartCull);
        steps.add(() -> this.levelRenderer.setRenderBuffers(this.renderBuffers));
        steps.add(() -> this.sodiumExtension.sodium$setMatrices(this.sodiumMatrices));
        steps.add(() -> this.cameraRenderState.viewRotationMatrix = this.cameraView);
        steps.add(() -> this.cameraRenderState.projectionMatrix = this.cameraProjection);
        if (this.modelViewPushed) {
            steps.add(() -> {
                RenderSystem.getModelViewStack().popMatrix();
                this.modelViewPushed = false;
            });
        }
        steps.add(() -> RenderSystem.getModelViewStack().set(this.modelView));
        if (this.shadowListScope) {
            steps.add(() -> {
                this.shadowRenderLists.iris$endShadowRenderListScope();
                this.shadowListScope = false;
            });
        }
        if (this.cullingSaved) {
            steps.add(() -> {
                this.cullingData.restoreState();
                this.cullingSaved = false;
            });
        }
        Throwable failure = runRestoreSteps(steps);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new IllegalStateException("Iris shadow state restoration failed", failure);
        }
    }

    /** Runs every restoration step so one failing integration hook cannot skip the rest. */
    static @Nullable Throwable runRestoreSteps(final List<? extends Runnable> steps) {
        Throwable firstFailure = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        return firstFailure;
    }
}
