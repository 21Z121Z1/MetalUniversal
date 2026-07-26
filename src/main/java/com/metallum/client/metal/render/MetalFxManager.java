package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns the per-device MetalFX resources and the frame-level history contract. */
@Environment(EnvType.CLIENT)
public final class MetalFxManager {
    public static final int USAGE_SHADER_WRITE = 1 << 5;
    private static final double SCENE_CUT_DISTANCE = 32.0;
    private static final float FOV_SCENE_CUT_DEGREES = 5.0F;
    // The current Minecraft/Sodium renderers do not expose previous object
    // transforms or a motion MRT writer. Keep frame generation disabled until
    // that producer is connected; an all-zero validity attachment is not a
    // valid substitute for object motion.
    private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;
    private static final Vector4f UI_CLEAR = new Vector4f(0.0F);
    private static MetalFxManager active;

    private final MetalDevice device;
    private final MetalFxConfig config;
    private final MetalFxConfig.Mode effectiveMode;
    private final boolean motionPipelineV2Available;
    private final boolean cutoutReactivePipelineAvailable;
    private final int phaseCount;
    private int phase;
    private boolean historyReset = true;
    private boolean previousMatrixValid;
    private final Matrix4f previousViewProjection = new Matrix4f();
    private final Matrix4f currentViewProjection = new Matrix4f();
    private final Matrix4f inverseCurrentViewProjection = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f currentProjection = new Matrix4f();
    private final Matrix4f jitteredViewProjection = new Matrix4f();
    private final Vector2f pixelJitter = new Vector2f();
    private final Vector2f clipJitter = new Vector2f();
    private final MetalMotionStateStore motionStateStore = new MetalMotionStateStore();
    private final Map<Entity, Long> entityGenerations = new IdentityHashMap<>();
    private long nextEntityGeneration = 1L;
    private int displayWidth;
    private int displayHeight;
    private int renderWidth;
    private int renderHeight;
    private boolean sceneFrame;
    private boolean frameUsesUpscaledTarget;
    private boolean frameGenerationEnabled;
    private boolean frameGenerationSuspendedForGui;
    private boolean runtimeDisabled;
    private boolean warnedInvalidFrame;
    private boolean previousCameraPositionValid;
    private boolean previousCameraProjectionValid;
    private float previousFieldOfView;
    private float previousFarPlane;
    private double previousCameraX;
    private double previousCameraY;
    private double previousCameraZ;
    private boolean loggedFirstSuccessfulFrame;
    private boolean reactiveMaskPrepared;
    private boolean cutoutReactivePassObserved;
    private boolean cutoutReactivePrepared;
    private boolean motionInputsPrepared;
    private boolean loggedTransparencyTargets;
    private boolean loggedCutoutReactive;
    private boolean frameResetForPresent = true;
    private float frameFieldOfView = 70.0F;
    private float frameFarPlane = 1000.0F;
    @Nullable
    private ValidationFrame validationFrame;
    private int validationCapturesPending;
    private int validationCapturesCompleted;
    private int validationCaptureFailures;
    @Nullable
    private String lastLoggedResetReason;
    @Nullable
    private TextureTarget uiTarget;
    @Nullable
    private TextureTarget sceneOutputTarget;
    @Nullable
    private MetalGpuTexture motionTexture;
    @Nullable
    private MetalGpuTexture cameraMotionTexture;
    @Nullable
    private MetalGpuTexture objectMotionTexture;
    @Nullable
    private MetalGpuTexture objectValidityTexture;
    @Nullable
    private GpuTextureView objectMotionView;
    @Nullable
    private GpuTextureView objectValidityView;
    @Nullable
    private MetalGpuTexture disocclusionTexture;
    @Nullable
    private MetalGpuTexture reactiveTexture;
    @Nullable
    private MetalGpuTexture cutoutReactiveTexture;
    @Nullable
    private GpuTextureView cutoutReactiveView;
    @Nullable
    private MetalGpuTexture sceneDepthTexture;
    @Nullable
    private MetalGpuTexture frameDepthTexture;

    private MetalFxManager(final MetalDevice device) {
        this.device = device;
        this.config = MetalFxConfig.load();
        this.motionPipelineV2Available = MetalNativeBridge.metallum_metalfx_supports_motion_v2(device.metalDeviceHandle());
        this.cutoutReactivePipelineAvailable =
                MetalNativeBridge.metallum_metalfx_supports_cutout_reactive(device.metalDeviceHandle());
        this.effectiveMode = chooseMode(device, this.config);
        this.phaseCount = MetalFxConfig.phaseCount(this.config.scale);
        this.frameGenerationEnabled = this.config.frameGeneration
                && this.effectiveMode == MetalFxConfig.Mode.TEMPORAL
                && OBJECT_MOTION_PRODUCER_CONNECTED
                && MetalNativeBridge.metallum_metalfx_supports_frame_generation(device.metalDeviceHandle());
        if (this.config.frameGeneration && !this.frameGenerationEnabled) {
            Metallum.LOGGER.warn("MetalFX frame generation disabled: complete object-motion producer is not connected");
        }
        if (this.effectiveMode != MetalFxConfig.Mode.OFF) {
            Metallum.LOGGER.info(
                    "MetalFX configured: requested={}, effective={}, scale={}, phases={}, motionPipelineV2={}, cutoutReactive={}, objectMotionProducer={}, frameGeneration={}",
                    this.config.requestedMode, this.effectiveMode, this.config.scale, this.phaseCount,
                    this.motionPipelineV2Available, this.cutoutReactivePipelineAvailable,
                    OBJECT_MOTION_PRODUCER_CONNECTED, this.frameGenerationEnabled
            );
        }
    }

    public static synchronized void initialize(final MetalDevice device) {
        if (active == null) {
            active = new MetalFxManager(device);
        }
    }

    public static int sceneWidth(final int displayWidth) {
        MetalFxManager manager = active;
        if (manager == null) return displayWidth;
        manager.displayWidth = displayWidth;
        return manager.sceneWidthInternal(displayWidth);
    }

    public static int sceneHeight(final int displayHeight) {
        MetalFxManager manager = active;
        if (manager == null) return displayHeight;
        manager.displayHeight = displayHeight;
        return manager.sceneHeightInternal(displayHeight);
    }

    public static int reportedWidth(final int fallback) {
        MetalFxManager manager = active;
        return manager == null || manager.effectiveMode == MetalFxConfig.Mode.OFF || manager.displayWidth <= 0
                ? fallback : manager.displayWidth;
    }

    public static int reportedHeight(final int fallback) {
        MetalFxManager manager = active;
        return manager == null || manager.effectiveMode == MetalFxConfig.Mode.OFF || manager.displayHeight <= 0
                ? fallback : manager.displayHeight;
    }

    public static void beginFrame() {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.beginFrameInternal();
        }
    }

    public static Matrix4f prepareSceneProjection(
            final CameraRenderState cameraState,
            final Matrix4f projectionMatrix,
            final int displayWidth,
            final int displayHeight
    ) {
        MetalFxManager manager = active;
        return manager == null
                ? projectionMatrix
                : manager.prepareSceneProjectionInternal(cameraState, projectionMatrix, displayWidth, displayHeight);
    }

    public static void beforeGui(final GameRenderer renderer) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.beforeGuiInternal(renderer);
        }
    }

    /**
     * Preserves the completed world depth before Minecraft clears the main
     * depth attachment for the first-person hand pass. The final scene color
     * contains both phases, but the hand projection cannot replace the world
     * depth consumed by Temporal reconstruction.
     */
    public static void preserveWorldDepthBeforeHand(final GameRenderer renderer) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.preserveWorldDepthBeforeHandInternal(renderer);
        }
    }

    /**
     * Captures the interpolated renderer position of one real Minecraft
     * entity. UUID identity is paired with an object-lifetime generation, so
     * entity integer-id reuse and same-UUID object replacement cannot inherit
     * unrelated history.
     */
    public static void captureEntityMotion(final Entity entity, final EntityRenderState state) {
        MetalFxManager manager = active;
        if (manager == null || entity == null || state == null) {
            return;
        }
        manager.captureEntityMotionInternal(entity, state);
    }

    /**
     * Replays the exact staged entity geometry into the object-motion and
     * validity MRT attachments. This is a second geometry pass sharing the
     * scene depth; it does not infer coverage from a bounding box.
     */
    public static void drawEntityMotion(
            final PreparedRenderType prepared,
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final MetalEntityMotionCapture.Sample sample
    ) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.drawEntityMotionInternal(prepared, executeInfo, sample);
        }
    }

    public static void setValidationFrame(
            final int frame,
            final String scenario,
            final double currentEntityX,
            final double currentEntityY,
            final double currentEntityZ,
            final double previousEntityX,
            final double previousEntityY,
            final double previousEntityZ
    ) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.validationFrame = new ValidationFrame(
                    frame,
                    scenario,
                    currentEntityX,
                    currentEntityY,
                    currentEntityZ,
                    previousEntityX,
                    previousEntityY,
                    previousEntityZ
            );
        }
    }

    public static int validationCapturesPending() {
        MetalFxManager manager = active;
        return manager == null ? 0 : manager.validationCapturesPending;
    }

    public static int validationCapturesCompleted() {
        MetalFxManager manager = active;
        return manager == null ? 0 : manager.validationCapturesCompleted;
    }

    public static int validationCaptureFailures() {
        MetalFxManager manager = active;
        return manager == null ? 0 : manager.validationCaptureFailures;
    }

    @Nullable
    static FrameGenerationInput frameGenerationInput(final MetalGpuTexture presentedUiTexture) {
        MetalFxManager manager = active;
        return manager == null ? null : manager.frameGenerationInputInternal(presentedUiTexture);
    }

    public static void addTransparencyReactivePass(final FrameGraphBuilder frame, final LevelTargetBundle targets) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.addTransparencyReactivePassInternal(frame, targets);
        }
    }

    public static RenderTarget guiTarget(final GameRenderer renderer) {
        MetalFxManager manager = active;
        if (manager == null || !manager.frameUsesUpscaledTarget || manager.uiTarget == null) {
            return renderer.mainRenderTarget();
        }
        return manager.uiTarget;
    }

    public static RenderTarget presentTarget(final GameRenderer renderer) {
        MetalFxManager manager = active;
        if (manager == null || !manager.frameUsesUpscaledTarget || manager.uiTarget == null) {
            return renderer.mainRenderTarget();
        }
        return manager.uiTarget;
    }

    public static RenderTarget blurTarget(final RenderTarget mainTarget) {
        MetalFxManager manager = active;
        if (manager == null || !manager.frameUsesUpscaledTarget || manager.uiTarget == null) {
            return mainTarget;
        }
        return manager.uiTarget;
    }

    public static void resetHistory(final String reason) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.resetHistoryInternal(reason);
        }
    }

    static void disableFrameGeneration(final String reason) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.disableFrameGenerationInternal(reason);
        }
    }

    public static void close() {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.closeInternal();
            active = null;
        }
    }

    public static boolean usesSceneScaling() {
        MetalFxManager manager = active;
        return manager != null && manager.effectiveMode != MetalFxConfig.Mode.OFF && !manager.runtimeDisabled;
    }

    public static boolean usesTransparencyTargets() {
        MetalFxManager manager = active;
        return manager != null && manager.effectiveMode == MetalFxConfig.Mode.TEMPORAL
                && manager.config.transparencyReactiveMask && !manager.runtimeDisabled;
    }

    public static boolean usesCutoutReactiveTerrain() {
        MetalFxManager manager = active;
        return manager != null
                && manager.effectiveMode == MetalFxConfig.Mode.TEMPORAL
                && manager.cutoutReactivePipelineAvailable
                && manager.sceneFrame
                && manager.motionInputsPrepared
                && manager.cutoutReactiveView != null
                && !manager.runtimeDisabled;
    }

    @Nullable
    public static GpuTextureView cutoutReactiveAttachment() {
        MetalFxManager manager = active;
        if (!usesCutoutReactiveTerrain() || manager == null) {
            return null;
        }
        manager.cutoutReactivePassObserved = true;
        return manager.cutoutReactiveView;
    }

    private static MetalFxConfig.Mode chooseMode(final MetalDevice device, final MetalFxConfig config) {
        if (config.requestedMode == MetalFxConfig.Mode.OFF || MetalNativeBridge.isIOS()) {
            return MetalFxConfig.Mode.OFF;
        }

        boolean spatial = MetalNativeBridge.metallum_metalfx_supports_spatial(device.metalDeviceHandle());
        boolean temporal = MetalNativeBridge.metallum_metalfx_supports_temporal(device.metalDeviceHandle())
                && MetalNativeBridge.metallum_metalfx_supports_motion_v2(device.metalDeviceHandle());
        MetalFxConfig.Mode selected = selectMode(config.requestedMode, spatial, temporal);
        if (selected != config.requestedMode && config.requestedMode != MetalFxConfig.Mode.AUTO) {
            Metallum.LOGGER.warn("MetalFX {} unavailable; falling back to {}", config.requestedMode, selected);
        }
        if (selected == MetalFxConfig.Mode.OFF && config.requestedMode != MetalFxConfig.Mode.OFF) {
            Metallum.LOGGER.warn("MetalFX unavailable on this device; keeping native present path");
        }
        return selected;
    }

    static MetalFxConfig.Mode selectMode(
            final MetalFxConfig.Mode requested,
            final boolean spatialSupported,
            final boolean temporalSupported
    ) {
        return switch (requested) {
            case TEMPORAL -> temporalSupported ? MetalFxConfig.Mode.TEMPORAL : spatialSupported ? MetalFxConfig.Mode.SPATIAL : MetalFxConfig.Mode.OFF;
            case SPATIAL -> spatialSupported ? MetalFxConfig.Mode.SPATIAL : MetalFxConfig.Mode.OFF;
            case AUTO -> temporalSupported ? MetalFxConfig.Mode.TEMPORAL : spatialSupported ? MetalFxConfig.Mode.SPATIAL : MetalFxConfig.Mode.OFF;
            case OFF -> MetalFxConfig.Mode.OFF;
        };
    }

    private int sceneWidthInternal(final int width) {
        return effectiveMode == MetalFxConfig.Mode.OFF || runtimeDisabled
                ? width : MetalFxConfig.scaledDimension(width, config.scale);
    }

    private int sceneHeightInternal(final int height) {
        return effectiveMode == MetalFxConfig.Mode.OFF || runtimeDisabled
                ? height : MetalFxConfig.scaledDimension(height, config.scale);
    }

    private void beginFrameInternal() {
        if (frameGenerationSuspendedForGui && !runtimeDisabled && !hasActiveGui()) {
            frameGenerationSuspendedForGui = false;
            frameGenerationEnabled = true;
            resetHistoryInternal("GUI closed; frame generation resumed");
        }
        this.sceneFrame = false;
        this.reactiveMaskPrepared = false;
        this.cutoutReactivePassObserved = false;
        this.cutoutReactivePrepared = false;
        this.motionInputsPrepared = false;
        this.frameDepthTexture = null;
        this.frameUsesUpscaledTarget = false;
        this.motionStateStore.beginFrame();
        MetalEntityMotionCapture.beginFrame();
    }

    private void captureEntityMotionInternal(final Entity entity, final EntityRenderState state) {
        if (effectiveMode != MetalFxConfig.Mode.TEMPORAL || runtimeDisabled) {
            return;
        }
        UUID uuid = entity.getUUID();
        long generation = entityGenerations.computeIfAbsent(entity, ignored -> nextEntityGeneration++);
        long objectId = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 1);
        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(objectId, generation);
        Matrix4f currentObject = new Matrix4f().translation(
                (float) state.x,
                (float) state.y,
                (float) state.z
        );
        Matrix4f previousObject = motionStateStore.previous(key);
        motionStateStore.observe(key, currentObject);
        MetalEntityMotionCapture.attachState(
                state,
                new MetalEntityMotionCapture.Sample(
                        objectId,
                        generation,
                        currentObject,
                        previousObject
                )
        );
    }

    private void drawEntityMotionInternal(
            final PreparedRenderType prepared,
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final MetalEntityMotionCapture.Sample sample
    ) {
        if (!sceneFrame) {
            MetalEntityMotionCapture.recordMotionDrawSkip("scene-frame-inactive");
            return;
        }
        if (!motionInputsPrepared) {
            MetalEntityMotionCapture.recordMotionDrawSkip("motion-inputs-unprepared");
            return;
        }
        if (historyReset) {
            MetalEntityMotionCapture.recordMotionDrawSkip("history-reset");
            return;
        }
        if (!sample.hasPrevious()) {
            MetalEntityMotionCapture.recordMotionDrawSkip("no-previous-object-state");
            return;
        }
        if (objectMotionView == null || objectValidityView == null) {
            MetalEntityMotionCapture.recordMotionDrawSkip("attachments-unavailable");
            return;
        }
        if (!MetalEntityMotionPipeline.supports(prepared.pipeline())) {
            MetalEntityMotionCapture.recordMotionDrawSkip("pipeline-unsupported");
            return;
        }
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (depthView == null) {
            MetalEntityMotionCapture.recordMotionDrawSkip("depth-unavailable");
            return;
        }

        Matrix4f currentUnjitteredFromRaster =
                new Matrix4f(currentViewProjection).mul(inverseCurrentViewProjection);
        Matrix4f previousFromRaster = new Matrix4f(previousViewProjection)
                .mul(MetalEntityMotionCapture.objectCurrentToPrevious(sample))
                .mul(inverseCurrentViewProjection);
        if (!MetalFxMath.isFinite(currentUnjitteredFromRaster)
                || !MetalFxMath.isFinite(previousFromRaster)) {
            MetalEntityMotionCapture.recordMotionDrawSkip("non-finite-transform");
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice motionUniform;
        try (GpuBufferSlice.MappedView mapped = encoder.transientMemory()
                .allocateGpuMapped(128L, 256L, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer bytes = mapped.data().order(ByteOrder.nativeOrder());
            currentUnjitteredFromRaster.get(0, bytes);
            previousFromRaster.get(64, bytes);
            motionUniform = mapped.slice();
        }

        RenderPassDescriptor descriptor = RenderPassDescriptor
                .create(() -> "Metallum ordinary entity object motion")
                .withColorAttachment(objectMotionView)
                .withColorAttachment(objectValidityView)
                .withDepthAttachment(depthView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, renderWidth, renderHeight));
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(MetalEntityMotionPipeline.forSource(prepared.pipeline()));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
            pass.setUniform("MetallumMotion", motionUniform);
            pass.setVertexBuffer(0, executeInfo.vertexBuffer().slice());
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
            }
            pass.setIndexBuffer(executeInfo.indexBuffer(), executeInfo.indexType());
            pass.drawIndexed(
                    executeInfo.indexCount(),
                    1,
                    executeInfo.firstIndex(),
                    executeInfo.baseVertex(),
                    0
            );
            MetalEntityMotionCapture.recordMotionDrawEncoded();
        }
    }

    private Matrix4f prepareSceneProjectionInternal(
            final CameraRenderState cameraState,
            final Matrix4f projectionMatrix,
            final int displayWidth,
            final int displayHeight
    ) {
        // MinecraftMetalFxMixin.renderFrame(HEAD) is the sole whole-frame owner
        // and runs before GameRenderer.extract() in Minecraft 26.2.
        // Re-entering beginFrame here would clear object-motion observations
        // made by an earlier renderer hook in the same frame.
        boolean dimensionsChanged = this.displayWidth != displayWidth || this.displayHeight != displayHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.renderWidth = sceneWidthInternal(displayWidth);
        this.renderHeight = sceneHeightInternal(displayHeight);
        dimensionsChanged |= ensureAuxiliaryTextures();
        if (dimensionsChanged) {
            resetHistoryInternal("display or render size changed");
        }
        if (effectiveMode == MetalFxConfig.Mode.OFF || runtimeDisabled
                || !cameraState.initialized || displayWidth <= 0 || displayHeight <= 0) {
            return projectionMatrix;
        }

        float displayAspect = (float) displayWidth / displayHeight;
        float renderAspect = (float) renderWidth / renderHeight;
        // This matrix is the final world projection assembled by Mojang. It
        // contains view bobbing, hurt tilt, and screen-effect distortion,
        // unlike CameraRenderState.projectionMatrix. Keep it for motion
        // reconstruction; normal camera changes must not reset history.
        this.currentProjection.set(projectionMatrix);
        // Frame interpolation needs the camera FOV used to build the base
        // perspective matrix. Screen-effect transforms can legitimately alter
        // m11 and are already represented by the motion reconstruction matrix.
        this.frameFieldOfView = MetalFxMath.verticalFieldOfViewDegrees(cameraState.projectionMatrix, 70.0F);
        this.frameFarPlane = cameraState.depthFar > 0.0F && Float.isFinite(cameraState.depthFar)
                ? cameraState.depthFar : 1000.0F;
        MetalFxMath.adjustPerspectiveAspect(this.currentProjection, displayAspect, renderAspect);
        if (previousCameraProjectionValid
                && (Math.abs(this.frameFieldOfView - previousFieldOfView) > FOV_SCENE_CUT_DEGREES
                || Math.abs(this.frameFarPlane - previousFarPlane) > Math.max(1.0F, previousFarPlane * 0.01F))) {
            resetHistoryInternal("projection changed");
        }
        if (previousCameraPositionValid
                && MetalFxMath.exceedsSceneCutDistance(
                previousCameraX, previousCameraY, previousCameraZ,
                cameraState.pos.x, cameraState.pos.y, cameraState.pos.z,
                SCENE_CUT_DISTANCE
        )) {
            resetHistoryInternal("camera teleport");
        }
        this.previousFieldOfView = this.frameFieldOfView;
        this.previousFarPlane = this.frameFarPlane;
        this.previousCameraProjectionValid = true;
        this.previousCameraX = cameraState.pos.x;
        this.previousCameraY = cameraState.pos.y;
        this.previousCameraZ = cameraState.pos.z;
        this.previousCameraPositionValid = true;

        MetalFxMath.viewMatrix(
                this.viewMatrix,
                cameraState.viewRotationMatrix,
                cameraState.pos.x,
                cameraState.pos.y,
                cameraState.pos.z
        );
        MetalFxMath.viewProjection(this.currentViewProjection, this.currentProjection, this.viewMatrix);
        if (!MetalFxMath.isFinite(this.currentViewProjection)) {
            if (!warnedInvalidFrame) {
                Metallum.LOGGER.warn("MetalFX skipped a frame because the camera matrices were invalid");
                warnedInvalidFrame = true;
            }
            resetHistoryInternal("invalid camera matrix");
            return projectionMatrix;
        }
        warnedInvalidFrame = false;
        this.sceneFrame = true;

        if (effectiveMode == MetalFxConfig.Mode.TEMPORAL) {
            MetalFxMath.pixelJitter(this.pixelJitter, phase, phaseCount);
            MetalFxMath.clipJitter(this.clipJitter, this.pixelJitter, renderWidth, renderHeight);
            projectionMatrix.set(this.currentProjection);
            MetalFxMath.applyProjectionJitter(projectionMatrix, clipJitter);
            // The depth buffer was produced with the jittered projection, so
            // reconstruction uses its inverse. The motion pass then projects
            // the reconstructed world position through current and previous
            // unjittered matrices, keeping camera jitter out of object motion.
            MetalFxMath.viewProjection(this.jitteredViewProjection, projectionMatrix, this.viewMatrix);
            if (!MetalFxMath.isFinite(this.jitteredViewProjection)
                    || !this.jitteredViewProjection.invert(this.inverseCurrentViewProjection).isFinite()) {
                if (!warnedInvalidFrame) {
                    Metallum.LOGGER.warn("MetalFX skipped a frame because the jittered camera matrices were invalid");
                    warnedInvalidFrame = true;
                }
                resetHistoryInternal("invalid jittered camera matrix");
                this.sceneFrame = false;
                return projectionMatrix;
            }
            if (!previousMatrixValid) {
                previousViewProjection.set(currentViewProjection);
                previousMatrixValid = true;
                historyReset = true;
            }
        } else {
            pixelJitter.zero();
            clipJitter.zero();
            projectionMatrix.set(this.currentProjection);
        }
        if (effectiveMode == MetalFxConfig.Mode.TEMPORAL && !motionInputsPrepared) {
            motionInputsPrepared = prepareMotionInputs();
            if (!motionInputsPrepared && config.debug) {
                Metallum.LOGGER.warn("MetalFX temporal frame will fail closed: motion input initialization failed");
            }
        }
        return projectionMatrix;
    }

    private void beforeGuiInternal(final GameRenderer renderer) {
        this.frameUsesUpscaledTarget = false;
        if (effectiveMode == MetalFxConfig.Mode.OFF || runtimeDisabled) {
            return;
        }
        int width = renderer.gameRenderState().windowRenderState.width;
        int height = renderer.gameRenderState().windowRenderState.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        ensureTargets(width, height);
        if (uiTarget == null) {
            return;
        }

        // Menus and loading screens can render a GUI frame without a world
        // scene. They still need the native-resolution UI target; otherwise
        // Minecraft's window-sized scissor rectangles are submitted to the
        // low-resolution scene target and fail validation (or crash).
        if (!sceneFrame) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    uiTarget.getColorTexture(), UI_CLEAR, uiTarget.getDepthTexture(), 0.0
            );
            this.frameResetForPresent = true;
            this.frameUsesUpscaledTarget = true;
            return;
        }

        MetalCommandEncoder encoder = device.commandEncoder();
        if (effectiveMode == MetalFxConfig.Mode.TEMPORAL
                && cutoutReactivePipelineAvailable
                && cutoutReactiveTexture != null
                && reactiveTexture != null) {
            int radius = MetalFxMath.cutoutReactiveRadius(config.scale, pixelJitter);
            boolean combined = encoder.encodeCutoutReactiveMask(
                    cutoutReactiveTexture,
                    reactiveTexture,
                    renderWidth,
                    renderHeight,
                    radius
            );
            this.cutoutReactivePrepared = this.cutoutReactivePassObserved && combined;
            if (config.debug && this.cutoutReactivePrepared && !loggedCutoutReactive) {
                loggedCutoutReactive = true;
                Metallum.LOGGER.info(
                        "MetalFX CUTOUT reactive coverage prepared from Sodium terrain MRT: radius={} inputPixels",
                        radius
                );
            } else if (this.cutoutReactivePassObserved && !combined) {
                Metallum.LOGGER.warn(
                        "MetalFX CUTOUT reactive coverage failed closed; using depth-edge fallback"
                );
            }
        }
        boolean encoded = false;
        boolean historyTransactionEncoded = false;
        if (sceneFrame && renderer.mainRenderTarget().getColorTexture() != null) {
            MetalGpuTexture color = (MetalGpuTexture) renderer.mainRenderTarget().getColorTexture();
            MetalGpuTexture depth = this.frameDepthTexture;
            this.frameDepthTexture = depth;
            MetalGpuTexture output = frameGenerationEnabled && sceneOutputTarget != null
                    ? (MetalGpuTexture) sceneOutputTarget.getColorTexture()
                    : (MetalGpuTexture) uiTarget.getColorTexture();
            this.frameResetForPresent = historyReset;
            if (effectiveMode == MetalFxConfig.Mode.TEMPORAL && depth != null && motionInputsPrepared
                    && cameraMotionTexture != null && objectMotionTexture != null
                    && objectValidityTexture != null && disocclusionTexture != null
                    && motionTexture != null && reactiveTexture != null) {
                encoded = encoder.encodeMetalFxV2(
                        color,
                        depth,
                        cameraMotionTexture,
                        objectMotionTexture,
                        objectValidityTexture,
                        disocclusionTexture,
                        motionTexture,
                        reactiveTexture,
                        output,
                        currentViewProjection,
                        inverseCurrentViewProjection,
                        previousViewProjection,
                        pixelJitter,
                        renderWidth,
                        renderHeight,
                        historyReset,
                        true,
                        (config.transparencyReactiveMask && reactiveMaskPrepared)
                                || cutoutReactivePrepared
                );
            } else if (effectiveMode == MetalFxConfig.Mode.SPATIAL) {
                encoded = encoder.encodeMetalFx(
                        effectiveMode,
                        color,
                        null,
                        null,
                        null,
                        output,
                        null,
                        null,
                        null,
                        new Vector2f(),
                        renderWidth,
                        renderHeight,
                        false,
                        true,
                        false
                );
            }
            if (encoded && frameGenerationEnabled) {
                // Keep the pre-composited full-resolution scene for the frame
                // interpolator, then seed the GUI target with the same scene.
                encoded = encoder.encodeTextureCopy(output, (MetalGpuTexture) uiTarget.getColorTexture(), false);
                if (!encoded) {
                    disableFrameGenerationInternal("scene/UI composition copy failed");
                }
            }
            historyTransactionEncoded = encoded && effectiveMode == MetalFxConfig.Mode.TEMPORAL;
            if (historyTransactionEncoded && depth != null) {
                captureValidationFrameIfRequested(color, depth, output);
            }
        }

        if (!encoded) {
            this.motionStateStore.discardFrame();
            if (frameGenerationEnabled) {
                disableFrameGenerationInternal("MetalFX scene encode failed while preparing frame generation");
            }
            if (sceneFrame && renderer.mainRenderTarget().getColorTexture() != null) {
                encoded = encoder.encodeTextureCopy(
                        (MetalGpuTexture) renderer.mainRenderTarget().getColorTexture(),
                        (MetalGpuTexture) uiTarget.getColorTexture(),
                        true
                );
            }
            if (!encoded) {
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        uiTarget.getColorTexture(), UI_CLEAR, uiTarget.getDepthTexture(), 0.0
                );
                disableForSession(renderer, "MetalFX encode and fullscreen copy fallback both failed");
                return;
            }
            Metallum.LOGGER.warn("MetalFX encode failed; using fullscreen copy fallback for this frame");
        } else if (config.debug && !loggedFirstSuccessfulFrame) {
            loggedFirstSuccessfulFrame = true;
            Metallum.LOGGER.info("MetalFX encode succeeded: mode={}, input={}x{}, output={}x{}, reactiveMask={}",
                    effectiveMode, renderWidth, renderHeight, width, height, reactiveMaskPrepared);
            if (effectiveMode == MetalFxConfig.Mode.TEMPORAL) {
                Metallum.LOGGER.info(
                        "MetalFX temporal state: jitterPixels=({}, {}), motionVectorScale=({}, {}), inputContent={}x{}, fieldOfView={}deg, depthReversed=true, motion=previousScreen-currentScreen",
                        pixelJitter.x, pixelJitter.y, renderWidth * 0.5F, renderHeight * 0.5F,
                        renderWidth, renderHeight, frameFieldOfView
                );
            }
        }

        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(uiTarget.getDepthTexture(), 0.0);
        this.frameUsesUpscaledTarget = true;
        if (historyTransactionEncoded) {
            Matrix4f submittedViewProjection = new Matrix4f(this.currentViewProjection);
            int submittedNextPhase = (phase + 1) % phaseCount;
            encoder.onCurrentSubmit(
                    () -> {
                        this.historyReset = false;
                        this.previousViewProjection.set(submittedViewProjection);
                        this.previousMatrixValid = true;
                        this.motionStateStore.commitSubmittedFrame();
                        this.phase = submittedNextPhase;
                    },
                    () -> {
                        this.motionStateStore.discardFrame();
                        resetHistoryInternal("Metal command buffer failed after temporal encode");
                    }
            );
        } else {
            this.motionStateStore.discardFrame();
        }
    }

    private void preserveWorldDepthBeforeHandInternal(final GameRenderer renderer) {
        if (effectiveMode != MetalFxConfig.Mode.TEMPORAL || runtimeDisabled
                || !sceneFrame || sceneDepthTexture == null) {
            return;
        }
        GpuTexture sourceTexture = renderer.mainRenderTarget().getDepthTexture();
        if (!(sourceTexture instanceof MetalGpuTexture source)
                || source.getFormat() != sceneDepthTexture.getFormat()
                || source.getWidth(0) != renderWidth
                || source.getHeight(0) != renderHeight) {
            this.frameDepthTexture = null;
            resetHistoryInternal("world depth snapshot incompatible");
            return;
        }
        device.commandEncoder().copyTextureToTexture(
                source,
                sceneDepthTexture,
                0,
                0,
                0,
                0,
                0,
                renderWidth,
                renderHeight
        );
        this.frameDepthTexture = sceneDepthTexture;
    }

    private void captureValidationFrameIfRequested(
            final MetalGpuTexture inputColor,
            final MetalGpuTexture depth,
            final MetalGpuTexture temporalOutput
    ) {
        ValidationFrame requested = this.validationFrame;
        this.validationFrame = null;
        if (requested == null || !requested.shouldCapture()
                || cameraMotionTexture == null || objectMotionTexture == null
                || objectValidityTexture == null || motionTexture == null
                || disocclusionTexture == null || reactiveTexture == null
                || cutoutReactiveTexture == null) {
            return;
        }

        List<ValidationReadback> readbacks = new ArrayList<>();
        readbacks.add(validationReadback("input-color", inputColor));
        readbacks.add(validationReadback("depth", depth));
        readbacks.add(validationReadback("camera-motion", cameraMotionTexture));
        readbacks.add(validationReadback("object-motion", objectMotionTexture));
        readbacks.add(validationReadback("object-validity", objectValidityTexture));
        readbacks.add(validationReadback("merged-motion", motionTexture));
        readbacks.add(validationReadback("disocclusion", disocclusionTexture));
        readbacks.add(validationReadback("cutout-coverage", cutoutReactiveTexture));
        readbacks.add(validationReadback("reactive", reactiveTexture));
        readbacks.add(validationReadback("temporal-output", temporalOutput));

        Matrix4f submittedCurrent = new Matrix4f(currentViewProjection);
        Matrix4f submittedPrevious = new Matrix4f(previousViewProjection);
        int submittedCutoutRadius = MetalFxMath.cutoutReactiveRadius(config.scale, pixelJitter);
        MetalEntityMotionCapture.Diagnostics producerDiagnostics =
                MetalEntityMotionCapture.diagnostics();
        Path root = Path.of(System.getProperty(
                "metallum.validation.output",
                "build/metal-validation/minecraft-client-current"
        )).toAbsolutePath().normalize();
        this.validationCapturesPending++;
        for (int index = 0; index < readbacks.size(); index++) {
            ValidationReadback readback = readbacks.get(index);
            boolean last = index == readbacks.size() - 1;
            device.commandEncoder().copyTextureToBuffer(
                    readback.texture,
                    readback.buffer,
                    0L,
                    last
                            ? () -> finishValidationCapture(
                                    root,
                                    requested,
                                    readbacks,
                                    submittedCurrent,
                                    submittedPrevious,
                                    submittedCutoutRadius,
                                    producerDiagnostics
                            )
                            : () -> {
                            },
                    0
            );
        }
    }

    private ValidationReadback validationReadback(final String name, final MetalGpuTexture texture) {
        int bytes = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "MetalFX validation readback " + name,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bytes
        );
        return new ValidationReadback(name, texture, buffer, bytes);
    }

    private void finishValidationCapture(
            final Path root,
            final ValidationFrame requested,
            final List<ValidationReadback> readbacks,
            final Matrix4f submittedCurrent,
            final Matrix4f submittedPrevious,
            final int submittedCutoutRadius,
            final MetalEntityMotionCapture.Diagnostics producerDiagnostics
    ) {
        try {
            Path frameDirectory = root.resolve(String.format(
                    java.util.Locale.ROOT,
                    "frame-%03d-%s",
                    requested.frame,
                    requested.scenario
            ));
            Files.createDirectories(frameDirectory);
            Map<String, byte[]> bytesByName = new java.util.HashMap<>();
            for (ValidationReadback readback : readbacks) {
                ByteBuffer source = readback.buffer.currentStorage()
                        .limit(readback.byteCount)
                        .slice()
                        .order(ByteOrder.nativeOrder());
                byte[] bytes = new byte[readback.byteCount];
                source.get(bytes);
                bytesByName.put(readback.name, bytes);
                Files.write(frameDirectory.resolve(readback.name + ".bin"), bytes);
            }

            MotionMetrics metrics = measureObjectMotion(
                    requested,
                    bytesByName.get("depth"),
                    bytesByName.get("object-motion"),
                    bytesByName.get("object-validity"),
                    bytesByName.get("disocclusion"),
                    bytesByName.get("cutout-coverage"),
                    bytesByName.get("reactive"),
                    submittedCurrent,
                    submittedPrevious,
                    submittedCutoutRadius
            );
            Files.writeString(
                    frameDirectory.resolve("metrics.json"),
                    metrics.toJson(requested, renderWidth, renderHeight),
                    StandardCharsets.UTF_8
            );
            Metallum.LOGGER.info(
                    "Minecraft validation GPU readback frame={} scenario={} validPixels={} "
                            + "depthValidPixels={} disocclusionPixels={} objectDisocclusionPixels={} "
                            + "cutoutCoveragePixels={} coveredCutoutReactivePixels={} "
                            + "dilatedCutoutReactivePixels={} cutoutRadius={} "
                            + "motionMean=({}, {}) expected=({}, {}) error={} producer={}",
                    requested.frame,
                    requested.scenario,
                    metrics.validPixels,
                    metrics.depthValidPixels,
                    metrics.disocclusionPixels,
                    metrics.objectDisocclusionPixels,
                    metrics.cutoutCoveragePixels,
                    metrics.coveredCutoutReactivePixels,
                    metrics.dilatedCutoutReactivePixels,
                    metrics.cutoutRadius,
                    metrics.meanX,
                    metrics.meanY,
                    metrics.expectedX,
                    metrics.expectedY,
                    metrics.error,
                    producerDiagnostics
            );
            this.validationCapturesCompleted++;
            if (!metrics.passed) {
                this.validationCaptureFailures++;
            }
        } catch (IOException | RuntimeException exception) {
            this.validationCapturesCompleted++;
            this.validationCaptureFailures++;
            Metallum.LOGGER.error(
                    "Minecraft validation GPU readback failed for frame {} ({})",
                    requested.frame,
                    requested.scenario,
                    exception
            );
        } finally {
            for (ValidationReadback readback : readbacks) {
                readback.buffer.close();
            }
            this.validationCapturesPending--;
        }
    }

    private MotionMetrics measureObjectMotion(
            final ValidationFrame requested,
            final byte[] depth,
            final byte[] objectMotion,
            final byte[] validity,
            final byte[] disocclusion,
            final byte[] cutoutCoverage,
            final byte[] reactive,
            final Matrix4f submittedCurrent,
            final Matrix4f submittedPrevious,
            final int cutoutRadius
    ) {
        int pixelCount = renderWidth * renderHeight;
        if (depth == null || depth.length != pixelCount * Float.BYTES
                || objectMotion == null || objectMotion.length != pixelCount * 4
                || validity == null || validity.length != pixelCount) {
            throw new IllegalStateException("Object motion validation readback size mismatch");
        }
        if (disocclusion == null || disocclusion.length != pixelCount) {
            throw new IllegalStateException("Disocclusion validation readback size mismatch");
        }
        if (cutoutCoverage == null || cutoutCoverage.length != pixelCount
                || reactive == null || reactive.length != pixelCount) {
            throw new IllegalStateException("CUTOUT reactive validation readback size mismatch");
        }
        double sumX = 0.0;
        double sumY = 0.0;
        int validPixels = 0;
        ByteBuffer motion = ByteBuffer.wrap(objectMotion).order(ByteOrder.nativeOrder());
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            if (Byte.toUnsignedInt(validity[pixel]) < 128) {
                continue;
            }
            float x = Float.float16ToFloat(motion.getShort(pixel * 4));
            float y = Float.float16ToFloat(motion.getShort(pixel * 4 + 2));
            if (Float.isFinite(x) && Float.isFinite(y)) {
                sumX += x;
                sumY += y;
                validPixels++;
            }
        }

        Vector4f currentClip = new Vector4f(
                (float) requested.currentEntityX,
                (float) (requested.currentEntityY + 1.0),
                (float) requested.currentEntityZ,
                1.0F
        ).mul(submittedCurrent);
        Vector4f previousClip = new Vector4f(
                (float) requested.previousEntityX,
                (float) (requested.previousEntityY + 1.0),
                (float) requested.previousEntityZ,
                1.0F
        ).mul(submittedPrevious);
        if (!MetalMotionContract.validHomogeneousW(currentClip.w)
                || !MetalMotionContract.validHomogeneousW(previousClip.w)) {
            throw new IllegalStateException("Validation entity center was outside the valid clip half-space");
        }
        double expectedX = previousClip.x / previousClip.w - currentClip.x / currentClip.w;
        double expectedY = currentClip.y / currentClip.w - previousClip.y / previousClip.w;
        double meanX = validPixels == 0 ? Double.NaN : sumX / validPixels;
        double meanY = validPixels == 0 ? Double.NaN : sumY / validPixels;
        double error = Math.hypot(meanX - expectedX, meanY - expectedY);
        int disocclusionPixels = 0;
        int objectDisocclusionPixels = 0;
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            if (Byte.toUnsignedInt(disocclusion[pixel]) >= 128) {
                disocclusionPixels++;
                if (Byte.toUnsignedInt(validity[pixel]) >= 128) {
                    objectDisocclusionPixels++;
                }
            }
        }
        int depthValidPixels = 0;
        ByteBuffer depths = ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder());
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            float value = depths.getFloat(pixel * Float.BYTES);
            if (Float.isFinite(value) && value > 0.00001F && value <= 1.00001F) {
                depthValidPixels++;
            }
        }
        boolean depthContractPassed = depthValidPixels > 0 && disocclusionPixels < pixelCount;
        int cutoutCoveragePixels = 0;
        int coveredCutoutReactivePixels = 0;
        int dilatedCutoutReactivePixels = 0;
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            boolean covered = Byte.toUnsignedInt(cutoutCoverage[pixel]) >= 128;
            boolean markedReactive = Byte.toUnsignedInt(reactive[pixel]) >= 128;
            if (covered) {
                cutoutCoveragePixels++;
                if (markedReactive) {
                    coveredCutoutReactivePixels++;
                }
            } else if (markedReactive && hasCutoutCoverageNeighbor(
                    cutoutCoverage,
                    pixel % renderWidth,
                    pixel / renderWidth,
                    renderWidth,
                    renderHeight,
                    cutoutRadius
            )) {
                dilatedCutoutReactivePixels++;
            }
        }
        boolean passed = switch (requested.scenario) {
            case "occluded_entity" -> depthContractPassed && validPixels < 2_500;
            case "revealed_entity" -> validPixels > 2_000
                    && depthContractPassed
                    && objectDisocclusionPixels > 1_000
                    && Double.isFinite(error)
                    && error <= 0.03;
            case "scene_reset" -> depthContractPassed
                    && validPixels == 0
                    && objectDisocclusionPixels == 0;
            case "cutout_leaves", "cutout_grass" -> depthContractPassed
                    && cutoutCoveragePixels > 32
                    && coveredCutoutReactivePixels == cutoutCoveragePixels
                    && (cutoutRadius == 0 || dilatedCutoutReactivePixels > 0);
            default -> depthContractPassed
                    && validPixels > 0
                    && Double.isFinite(error)
                    && error <= 0.03;
        };
        return new MotionMetrics(
                validPixels,
                depthValidPixels,
                disocclusionPixels,
                objectDisocclusionPixels,
                cutoutCoveragePixels,
                coveredCutoutReactivePixels,
                dilatedCutoutReactivePixels,
                cutoutRadius,
                meanX,
                meanY,
                expectedX,
                expectedY,
                error,
                passed
        );
    }

    private static boolean hasCutoutCoverageNeighbor(
            final byte[] coverage,
            final int x,
            final int y,
            final int width,
            final int height,
            final int radius
    ) {
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int sampleY = y + offsetY;
            if (sampleY < 0 || sampleY >= height) {
                continue;
            }
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                int sampleX = x + offsetX;
                if (sampleX < 0 || sampleX >= width) {
                    continue;
                }
                if (Byte.toUnsignedInt(coverage[sampleY * width + sampleX]) >= 128) {
                    return true;
                }
            }
        }
        return false;
    }

    private record ValidationReadback(
            String name,
            MetalGpuTexture texture,
            MetalGpuBuffer buffer,
            int byteCount
    ) {
    }

    private record ValidationFrame(
            int frame,
            String scenario,
            double currentEntityX,
            double currentEntityY,
            double currentEntityZ,
            double previousEntityX,
            double previousEntityY,
            double previousEntityZ
    ) {
        private boolean shouldCapture() {
            return frame == 6 || frame == 12 || frame == 22 || frame == 32
                    || frame == 42 || frame == 47 || frame == 54 || frame == 62
                    || frame == 74 || frame == 82;
        }
    }

    private record MotionMetrics(
            int validPixels,
            int depthValidPixels,
            int disocclusionPixels,
            int objectDisocclusionPixels,
            int cutoutCoveragePixels,
            int coveredCutoutReactivePixels,
            int dilatedCutoutReactivePixels,
            int cutoutRadius,
            double meanX,
            double meanY,
            double expectedX,
            double expectedY,
            double error,
            boolean passed
    ) {
        private String toJson(
                final ValidationFrame requested,
                final int width,
                final int height
        ) {
            return String.format(
                    java.util.Locale.ROOT,
                    """
                    {
                      "frame": %d,
                      "scenario": "%s",
                      "width": %d,
                      "height": %d,
                      "validPixels": %d,
                      "depthValidPixels": %d,
                      "disocclusionPixels": %d,
                      "objectDisocclusionPixels": %d,
                      "cutoutCoveragePixels": %d,
                      "coveredCutoutReactivePixels": %d,
                      "dilatedCutoutReactivePixels": %d,
                      "cutoutReactiveRadius": %d,
                      "meanObjectMotionNdc": [%.9f, %.9f],
                      "expectedObjectMotionNdc": [%.9f, %.9f],
                      "error": %.9f,
                      "tolerance": 0.03,
                      "historyResetExpected": %s,
                      "passed": %s,
                      "capturePoint": "after temporal encode, before present",
                      "usedSystemScreenshot": false
                    }
                    """,
                    requested.frame,
                    requested.scenario,
                    width,
                    height,
                    validPixels,
                    depthValidPixels,
                    disocclusionPixels,
                    objectDisocclusionPixels,
                    cutoutCoveragePixels,
                    coveredCutoutReactivePixels,
                    dilatedCutoutReactivePixels,
                    cutoutRadius,
                    meanX,
                    meanY,
                    expectedX,
                    expectedY,
                    error,
                    requested.scenario.equals("scene_reset"),
                    passed
            );
        }
    }

    private void addTransparencyReactivePassInternal(final FrameGraphBuilder frame, final LevelTargetBundle targets) {
        if (effectiveMode != MetalFxConfig.Mode.TEMPORAL || runtimeDisabled
                || !config.transparencyReactiveMask || reactiveTexture == null) {
            return;
        }

        ResourceHandle<RenderTarget> translucent = targets.translucent;
        ResourceHandle<RenderTarget> itemEntity = targets.itemEntity;
        ResourceHandle<RenderTarget> particles = targets.particles;
        ResourceHandle<RenderTarget> weather = targets.weather;
        ResourceHandle<RenderTarget> clouds = targets.clouds;
        // The pass is created below so all optional handles can be registered
        // before its callback is installed.
        var pass = frame.addPass("metallum_reactive_mask_layers");
        if (translucent != null) pass.reads(translucent);
        if (itemEntity != null) pass.reads(itemEntity);
        if (particles != null) pass.reads(particles);
        if (weather != null) pass.reads(weather);
        if (clouds != null) pass.reads(clouds);
        pass.disableCulling();
        pass.executes(() -> {
            MetalGpuTexture translucentTexture = colorTexture(translucent);
            MetalGpuTexture itemEntityTexture = colorTexture(itemEntity);
            MetalGpuTexture particlesTexture = colorTexture(particles);
            MetalGpuTexture weatherTexture = colorTexture(weather);
            MetalGpuTexture cloudsTexture = colorTexture(clouds);
            boolean encoded = device.commandEncoder().encodeTransparencyReactiveMask(
                    translucentTexture,
                    itemEntityTexture,
                    particlesTexture,
                    weatherTexture,
                    cloudsTexture,
                    reactiveTexture,
                    renderWidth,
                    renderHeight
            );
            this.reactiveMaskPrepared = encoded;
            if (config.debug && encoded && !loggedTransparencyTargets) {
                loggedTransparencyTargets = true;
                Metallum.LOGGER.info(
                        "MetalFX reactive mask prepared from transparency targets: translucent={}, itemEntity={}, particles={}, weather={}, clouds={}",
                        translucentTexture != null,
                        itemEntityTexture != null,
                        particlesTexture != null,
                        weatherTexture != null,
                        cloudsTexture != null
                );
            }
        });
    }

    @Nullable
    private static MetalGpuTexture colorTexture(@Nullable final ResourceHandle<RenderTarget> handle) {
        if (handle == null) {
            return null;
        }
        GpuTexture color = handle.get().getColorTexture();
        return color instanceof MetalGpuTexture value ? value : null;
    }

    private void ensureTargets(final int width, final int height) {
        int targetRenderWidth = sceneWidthInternal(width);
        int targetRenderHeight = sceneHeightInternal(height);
        boolean dimensionsChanged = this.displayWidth != width || this.displayHeight != height
                || this.renderWidth != targetRenderWidth || this.renderHeight != targetRenderHeight;
        this.displayWidth = width;
        this.displayHeight = height;
        this.renderWidth = targetRenderWidth;
        this.renderHeight = targetRenderHeight;
        if (uiTarget == null || uiTarget.width != width || uiTarget.height != height) {
            if (uiTarget != null) uiTarget.destroyBuffers();
            uiTarget = new TextureTarget("MetalFX Native Resolution UI", width, height, true, GpuFormat.RGBA8_UNORM);
            dimensionsChanged = true;
        }
        if (frameGenerationEnabled) {
            if (sceneOutputTarget == null || sceneOutputTarget.width != width || sceneOutputTarget.height != height) {
                if (sceneOutputTarget != null) sceneOutputTarget.destroyBuffers();
                sceneOutputTarget = new TextureTarget("MetalFX Scene Output", width, height, false, GpuFormat.RGBA8_UNORM);
                dimensionsChanged = true;
            }
        } else if (sceneOutputTarget != null) {
            sceneOutputTarget.destroyBuffers();
            sceneOutputTarget = null;
            dimensionsChanged = true;
        }
        dimensionsChanged |= ensureAuxiliaryTextures();
        if (dimensionsChanged) {
            resetHistoryInternal("display or render size changed");
        }
    }

    private boolean ensureAuxiliaryTextures() {
        if (effectiveMode != MetalFxConfig.Mode.TEMPORAL || runtimeDisabled
                || renderWidth <= 0 || renderHeight <= 0
                || (motionTexture != null && motionTexture.getWidth(0) == renderWidth
                && motionTexture.getHeight(0) == renderHeight
                && cameraMotionTexture != null && cameraMotionTexture.getWidth(0) == renderWidth
                && cameraMotionTexture.getHeight(0) == renderHeight
                && objectMotionTexture != null && objectMotionTexture.getWidth(0) == renderWidth
                && objectMotionTexture.getHeight(0) == renderHeight
                && objectValidityTexture != null && objectValidityTexture.getWidth(0) == renderWidth
                && objectValidityTexture.getHeight(0) == renderHeight
                && disocclusionTexture != null && disocclusionTexture.getWidth(0) == renderWidth
                && disocclusionTexture.getHeight(0) == renderHeight
                && reactiveTexture != null && reactiveTexture.getWidth(0) == renderWidth
                && reactiveTexture.getHeight(0) == renderHeight
                && cutoutReactiveTexture != null && cutoutReactiveTexture.getWidth(0) == renderWidth
                && cutoutReactiveTexture.getHeight(0) == renderHeight
                && sceneDepthTexture != null && sceneDepthTexture.getWidth(0) == renderWidth
                && sceneDepthTexture.getHeight(0) == renderHeight)) {
            return false;
        }

        closeAuxiliaryTextures();
        // The motion reconstruction pass always owns this texture because it
        // writes depth-edge reactivity for alpha-cutout leaves/grass. The
        // Sodium toggle only controls the additional transparent-target mask.
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | USAGE_SHADER_WRITE;
        motionTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Motion RG16F", usage, GpuFormat.RG16_FLOAT, renderWidth, renderHeight, 1, 1
        );
        cameraMotionTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Camera Motion RG16F", usage, GpuFormat.RG16_FLOAT, renderWidth, renderHeight, 1, 1
        );
        int objectUsage = usage | GpuTexture.USAGE_RENDER_ATTACHMENT;
        objectMotionTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Object Motion RG16F", objectUsage, GpuFormat.RG16_FLOAT, renderWidth, renderHeight, 1, 1
        );
        objectValidityTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Object Motion Validity R8", objectUsage, GpuFormat.R8_UNORM, renderWidth, renderHeight, 1, 1
        );
        objectMotionView = RenderSystem.getDevice().createTextureView(objectMotionTexture);
        objectValidityView = RenderSystem.getDevice().createTextureView(objectValidityTexture);
        disocclusionTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Disocclusion R8", usage, GpuFormat.R8_UNORM, renderWidth, renderHeight, 1, 1
        );
        // Cleared through clearColorTexture (deferred-clear materialization
        // attaches it as a color target), so RenderTarget usage is required —
        // Metal API validation aborts otherwise.
        reactiveTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Reactive R8", usage | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.R8_UNORM, renderWidth, renderHeight, 1, 1
        );
        cutoutReactiveTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX CUTOUT Coverage R8",
                usage | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.R8_UNORM,
                renderWidth,
                renderHeight,
                1,
                1
        );
        cutoutReactiveView = RenderSystem.getDevice().createTextureView(cutoutReactiveTexture);
        sceneDepthTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Preserved World Depth",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.D32_FLOAT,
                renderWidth,
                renderHeight,
                1,
                1
        );
        return true;
    }

    private boolean prepareMotionInputs() {
        if (objectMotionTexture == null || objectValidityTexture == null
                || reactiveTexture == null || cutoutReactiveTexture == null
                || renderWidth <= 0 || renderHeight <= 0) {
            return false;
        }
        // These clears happen before world draws. Entity motion and Sodium's
        // CUTOUT MRT overwrite exact covered pixels afterward. Keeping CUTOUT
        // coverage separate lets the later dilation pass merge it with the
        // transparent-target mask without a read/write race.
        device.commandEncoder().clearColorTexture(reactiveTexture, UI_CLEAR);
        device.commandEncoder().clearColorTexture(cutoutReactiveTexture, UI_CLEAR);
        return device.commandEncoder().clearMotionInputs(
                objectMotionTexture,
                objectValidityTexture,
                renderWidth,
                renderHeight
        );
    }

    private void resetHistoryInternal(final String reason) {
        historyReset = true;
        previousMatrixValid = false;
        previousCameraProjectionValid = false;
        previousCameraPositionValid = false;
        entityGenerations.clear();
        phase = 0;
        motionInputsPrepared = false;
        motionStateStore.reset();
        if (config.debug && !reason.equals(lastLoggedResetReason)) {
            Metallum.LOGGER.info("MetalFX history reset: {}", reason);
            lastLoggedResetReason = reason;
        }
    }

    private void disableForSession(final GameRenderer renderer, final String reason) {
        if (runtimeDisabled) {
            return;
        }
        runtimeDisabled = true;
        frameUsesUpscaledTarget = false;
        disableFrameGenerationInternal(reason);
        Metallum.LOGGER.warn("MetalFX disabled for this session: {}; reverting to native render targets", reason);
        if (uiTarget != null) {
            uiTarget.destroyBuffers();
            uiTarget = null;
        }
        if (sceneOutputTarget != null) {
            sceneOutputTarget.destroyBuffers();
            sceneOutputTarget = null;
        }
        closeAuxiliaryTextures();
        MetalNativeBridge.metallum_metalfx_shutdown();

        RenderTarget mainTarget = renderer.mainRenderTarget();
        if (displayWidth > 0 && displayHeight > 0
                && (mainTarget.width != displayWidth || mainTarget.height != displayHeight)) {
            mainTarget.resize(displayWidth, displayHeight);
        }
    }

    private void disableFrameGenerationInternal(final String reason) {
        if (!frameGenerationEnabled) {
            return;
        }
        frameGenerationEnabled = false;
        if (sceneOutputTarget != null) {
            sceneOutputTarget.destroyBuffers();
            sceneOutputTarget = null;
        }
        MetalNativeBridge.metallum_metalfx_stop_frame_generation();
        if (config.debug) {
            Metallum.LOGGER.warn("MetalFX frame generation disabled: {}", reason);
        }
    }

    private void closeAuxiliaryTextures() {
        if (objectMotionView != null) objectMotionView.close();
        if (objectValidityView != null) objectValidityView.close();
        if (cutoutReactiveView != null) cutoutReactiveView.close();
        objectMotionView = null;
        objectValidityView = null;
        cutoutReactiveView = null;
        if (motionTexture != null) motionTexture.close();
        if (cameraMotionTexture != null) cameraMotionTexture.close();
        if (objectMotionTexture != null) objectMotionTexture.close();
        if (objectValidityTexture != null) objectValidityTexture.close();
        if (disocclusionTexture != null) disocclusionTexture.close();
        if (reactiveTexture != null) reactiveTexture.close();
        if (cutoutReactiveTexture != null) cutoutReactiveTexture.close();
        if (sceneDepthTexture != null) sceneDepthTexture.close();
        motionTexture = null;
        cameraMotionTexture = null;
        objectMotionTexture = null;
        objectValidityTexture = null;
        disocclusionTexture = null;
        reactiveTexture = null;
        cutoutReactiveTexture = null;
        sceneDepthTexture = null;
        frameDepthTexture = null;
        reactiveMaskPrepared = false;
        cutoutReactivePassObserved = false;
        cutoutReactivePrepared = false;
        motionInputsPrepared = false;
    }

    private void closeInternal() {
        motionStateStore.reset();
        entityGenerations.clear();
        MetalEntityMotionPipeline.clear();
        MetalCutoutReactivePipeline.clear();
        closeAuxiliaryTextures();
        if (uiTarget != null) {
            uiTarget.destroyBuffers();
            uiTarget = null;
        }
        if (sceneOutputTarget != null) {
            sceneOutputTarget.destroyBuffers();
            sceneOutputTarget = null;
        }
        MetalNativeBridge.metallum_metalfx_shutdown();
    }

    @Nullable
    private FrameGenerationInput frameGenerationInputInternal(final MetalGpuTexture presentedUiTexture) {
        // Do not let an experimental interpolated frame race a Minecraft screen
        // or overlay. A screen can change every frame while the presenter still
        // owns pending drawables, which produces whole-window flashes and GUI
        // ghosting. Stop it at the transition and use the single-present path.
        if (frameGenerationEnabled && hasActiveGui()) {
            suspendFrameGenerationForGuiInternal();
        }
        if (!frameGenerationEnabled || runtimeDisabled || !frameUsesUpscaledTarget
                || sceneOutputTarget == null || uiTarget == null
                || uiTarget.getColorTexture() != presentedUiTexture
                || frameDepthTexture == null || motionTexture == null || !motionInputsPrepared) {
            return null;
        }
        GpuTexture sceneTexture = sceneOutputTarget.getColorTexture();
        if (!(sceneTexture instanceof MetalGpuTexture sceneColor)) {
            return null;
        }
        return new FrameGenerationInput(
                sceneColor,
                presentedUiTexture,
                frameDepthTexture,
                motionTexture,
                renderWidth,
                renderHeight,
                pixelJitter.x,
                pixelJitter.y,
                frameFieldOfView,
                0.05F,
                frameFarPlane,
                displayHeight > 0 ? (float) displayWidth / displayHeight : 1.0F,
                frameResetForPresent
        );
    }

    private static boolean hasActiveGui() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gui.screen() != null || minecraft.gui.overlay() != null;
    }

    private void suspendFrameGenerationForGuiInternal() {
        if (!frameGenerationEnabled) {
            return;
        }
        frameGenerationEnabled = false;
        frameGenerationSuspendedForGui = true;
        // Keep sceneOutputTarget alive until this frame is submitted. The
        // current frame may already contain an encoded MetalFX write to it.
        MetalNativeBridge.metallum_metalfx_stop_frame_generation();
        if (config.debug) {
            Metallum.LOGGER.info("MetalFX frame generation paused while GUI screen or overlay is active");
        }
    }

    record FrameGenerationInput(
            MetalGpuTexture sceneColor,
            MetalGpuTexture uiColor,
            MetalGpuTexture depth,
            MetalGpuTexture motion,
            int inputWidth,
            int inputHeight,
            float jitterX,
            float jitterY,
            float fieldOfView,
            float nearPlane,
            float farPlane,
            float aspectRatio,
            boolean reset
    ) {
    }
}
