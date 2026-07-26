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
import java.util.HashSet;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns the per-device MetalFX resources and the frame-level history contract. */
@Environment(EnvType.CLIENT)
public final class MetalFxManager {
    public static final int USAGE_SHADER_WRITE = 1 << 5;
    private static final double SCENE_CUT_DISTANCE = 32.0;
    private static final float FOV_SCENE_CUT_DEGREES = 5.0F;
    // Ordinary entities, dropped items, minecarts, boats and arrows now carry a
    // reconstructed root object transform (MetalEntityObjectPose) through a
    // split motion draw. Falling blocks and display entities still ride the
    // core/block path and reach the interpolator with translation-only or no
    // object motion, so the shipped default stays off until the attended
    // visual QA in the audit's 13.4 matrix has signed the gate off.
    private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;
    // QA escape hatch for that matrix: it enables frame generation without
    // changing what ships, and is the switch the acceptance run flips.
    private static final boolean OBJECT_MOTION_PRODUCER_OVERRIDE =
            Boolean.getBoolean("metallum.metalfx.objectMotionProducer");
    private static final Vector4f UI_CLEAR = new Vector4f(0.0F);
    private static MetalFxManager active;
    // CAMetalDisplayLink is a vsync-on-only present loop, and every pacing
    // acceptance run measured it with displaySyncEnabled true. Minecraft can
    // switch the surface to MAILBOX at any time from the video settings, so the
    // present mode is tracked here and frame generation suspends while it is
    // immediate instead of presenting off the refresh boundary.
    private static volatile boolean immediatePresentMode;

    private final MetalDevice device;
    private final MetalFxConfig config;
    private final MetalFxConfig.Mode effectiveMode;
    // Reactive weight for first-person overlay pixels: zero motion handles
    // camera movement exactly; the residual swing/bob animation relies on a
    // moderate history bias instead of per-vertex motion.
    private static final float HAND_OVERLAY_REACTIVE_BOOST = 0.35F;
    // Validation thresholds for the CUTOUT reactive policy (see
    // docs/cutout-shimmer-remediation-2026-07-27.md). Interior CUTOUT pixels
    // may only carry residual reactivity (depth gradients read ~0-0.06
    // there); 48/255 ≈ 0.19 leaves margin while catching any interior flood.
    // The edge band must reach at least 72/255 ≈ 0.28 (< default edge weight
    // 0.35 and < depth-edge cap 0.5).
    private static final int INTERIOR_REACTIVE_MAX = 48;
    private static final int EDGE_REACTIVE_MIN = 72;
    // Object-motion acceptance thresholds (item_spin / vehicle_turn). Both
    // scenarios hold the object at a fixed world position under a static
    // camera, so the expected motion is a per-pixel field rather than one
    // vector and the single-mean model does not apply. A dropped item makes
    // that concrete: it carries a hover bob (a genuine vertical translation,
    // ItemEntityRenderer's `sin(ageInTicks/10 + bobOffset)` term) on top of its
    // Y spin, and the two land on different axes.
    //
    //   - the bob is a near-uniform vertical shift, so it dominates mean Y and
    //     contributes almost nothing to horizontal spread;
    //   - the spin moves points by an amount proportional to their offset from
    //     the axis, so it shows up as horizontal peak-to-peak spread.
    //
    // Separating them by axis is what makes the spin assertable. Measured at
    // frame 164: spreadX 0.028 with meanY 0.021, and the mean-vector error was
    // 0.024 — inside the 0.03 tolerance, i.e. the old model would have passed
    // this frame while proving nothing about the rotation. Dropping the
    // rotateY term from MetalEntityObjectPose.droppedItem collapses the field
    // to that uniform translation and takes spreadX to ~0, which this floor
    // catches. The boat's yaw is also a Y rotation and measures 0.061.
    private static final int OBJECT_MIN_VALID_PIXELS = 2_000;
    private static final double OBJECT_MIN_SPIN_SPREAD_X = 0.012;
    private static final double OBJECT_MAX_MOTION = 0.5;
    private final boolean motionPipelineV2Available;
    private final boolean cutoutReactivePipelineAvailable;
    private final boolean handOverlayPipelineAvailable;
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
    // Set while a recoverable condition (an open GUI, an immediate present mode)
    // holds frame generation off. Unlike runtimeDisabled this is reversible and
    // beginFrameInternal re-enables the presenter once every gate clears.
    private boolean frameGenerationSuspended;
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
    private boolean loggedHandOverlay;
    private boolean frameResetForPresent = true;
    private float frameFieldOfView = 70.0F;
    private float frameFarPlane = 1000.0F;
    // Frame interpolation wants the interval between the two source frames it
    // interpolates between, anchored on the render timeline. Scene-frame start
    // is a far more stable anchor than the native encode-enqueue wall clock.
    private long lastSceneFrameStartNanos;
    private float sceneFrameDeltaSeconds;
    @Nullable
    private ValidationFrame validationFrame;
    private int validationCapturesPending;
    private int validationCapturesCompleted;
    private int validationCaptureFailures;
    // Temporal-flicker measurement series (static-camera hold): consecutive
    // upscaled-output frames are folded into per-pixel |delta luma|
    // histograms, split by the CUTOUT coverage mask captured on the first
    // series frame. See docs/cutout-shimmer-remediation-2026-07-27.md §8.
    @Nullable
    private FlickerRequest flickerRequest;
    private boolean flickerCapturePending;
    private final Set<String> flickerCompletedScenarios = new HashSet<>();
    private int flickerFramesAccumulated;
    private int flickerDisplayWidth;
    private int flickerDisplayHeight;
    @Nullable
    private boolean[] flickerMask;
    private int flickerMaskPixels;
    // Sky-edge subset of the mask: CUTOUT coverage *and* cleared far-plane
    // depth in the same render neighbourhood, i.e. the foliage/sky silhouette
    // band. Reported alongside the mask so a scene with no sky in view is
    // visible as skyPixels=0 instead of silently measuring nothing.
    @Nullable
    private boolean[] flickerSkyEdgeMask;
    private int flickerSkyEdgePixels;
    private int flickerSkyPixels;
    // Open sky with no CUTOUT coverage anywhere near it. Under a static camera
    // with frozen time, clouds off and no weather, this region is perfectly
    // static geometry-free content: any delta here is the temporal pipeline's
    // own instability on the sky itself, isolated from any silhouette.
    @Nullable
    private boolean[] flickerSkyInteriorMask;
    private int flickerSkyInteriorPixels;
    @Nullable
    private byte[] flickerPreviousLuma;
    private final long[] flickerMaskedHistogram = new long[256];
    private final long[] flickerControlHistogram = new long[256];
    private final long[] flickerSkyEdgeHistogram = new long[256];
    private final long[] flickerSkyInteriorHistogram = new long[256];
    // 16 buckets of 16 reactive levels each, over the render-space silhouette
    // band. Identifies which policy writer owns the band's reactivity.
    private final long[] flickerSkyEdgeReactiveBuckets = new long[16];
    private int flickerSkyEdgeRenderPixels;
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
        MetalNativeBridge.metallum_metalfx_set_reactive_tuning(
                this.config.cutoutReactiveEdgeWeight,
                this.config.cutoutReactiveInteriorWeight,
                this.config.depthEdgeReactiveCap,
                this.config.transparencyReactiveValue,
                this.config.skyFarPlaneMotion ? 1.0F : 0.0F,
                this.config.disocclusionReactiveCap,
                this.config.mergeDepthDilation ? 1.0F : 0.0F
        );
        this.motionPipelineV2Available = MetalNativeBridge.metallum_metalfx_supports_motion_v2(device.metalDeviceHandle());
        this.cutoutReactivePipelineAvailable =
                MetalNativeBridge.metallum_metalfx_supports_cutout_reactive(device.metalDeviceHandle());
        this.handOverlayPipelineAvailable =
                MetalNativeBridge.metallum_metalfx_supports_hand_overlay(device.metalDeviceHandle());
        this.effectiveMode = chooseMode(device, this.config);
        this.phaseCount = MetalFxConfig.phaseCount(this.config.scale);
        this.frameGenerationEnabled = this.config.frameGeneration
                && this.effectiveMode == MetalFxConfig.Mode.TEMPORAL
                && objectMotionProducerConnected()
                && MetalNativeBridge.metallum_metalfx_supports_frame_generation(device.metalDeviceHandle());
        if (this.config.frameGeneration && !this.frameGenerationEnabled) {
            Metallum.LOGGER.warn("MetalFX frame generation disabled: complete object-motion producer is not connected");
        }
        if (this.effectiveMode != MetalFxConfig.Mode.OFF) {
            Metallum.LOGGER.info(
                    "MetalFX configured: requested={}, effective={}, scale={}, phases={}, motionPipelineV2={}, cutoutReactive={}, objectMotionProducer={}, frameGeneration={}, reactiveTuning=(edge={}, interior={}, depthCap={}, transparency={}, skyFarPlaneMotion={}, disocclusionCap={}, depthDilation={})",
                    this.config.requestedMode, this.effectiveMode, this.config.scale, this.phaseCount,
                    this.motionPipelineV2Available, this.cutoutReactivePipelineAvailable,
                    objectMotionProducerConnected(), this.frameGenerationEnabled,
                    this.config.cutoutReactiveEdgeWeight, this.config.cutoutReactiveInteriorWeight,
                    this.config.depthEdgeReactiveCap, this.config.transparencyReactiveValue,
                    this.config.skyFarPlaneMotion, this.config.disocclusionReactiveCap,
                    this.config.mergeDepthDilation
            );
        }
    }

    public static synchronized void initialize(final MetalDevice device) {
        if (active == null) {
            active = new MetalFxManager(device);
        }
    }

    private static boolean objectMotionProducerConnected() {
        return OBJECT_MOTION_PRODUCER_CONNECTED || OBJECT_MOTION_PRODUCER_OVERRIDE;
    }

    /**
     * Records the present mode the surface was last configured with. The
     * surface can be reconfigured at any time — a video-settings VSync toggle
     * or a resize both go through it — so this is the only place frame
     * generation can learn that it no longer presents on the refresh boundary.
     */
    public static void observePresentMode(final boolean immediate) {
        immediatePresentMode = immediate;
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

    /**
     * Negative texture LOD bias for material sampling while the scene renders
     * below display resolution. Follows the Game Porting Toolkit formula
     * {@code log2(renderRes / displayRes) - 1.0}; without it, mipmapped
     * textures (the block atlas) select mips for the low render resolution
     * and the upscaled image looks soft. Applied by the shader cross compiler
     * to plain fragment sample calls; single-mip textures are unaffected by
     * construction, so GUI/text sampling stays exact.
     */
    public static float shaderSampleLodBias() {
        MetalFxManager manager = active;
        if (manager == null || manager.effectiveMode == MetalFxConfig.Mode.OFF
                || manager.runtimeDisabled) {
            return 0.0F;
        }
        float scale = manager.config.scale;
        if (!(scale > 0.0F) || scale >= 1.0F) {
            return 0.0F;
        }
        return (float) (Math.log(scale) / Math.log(2.0)) - 1.0F;
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

    public static void setFlickerCaptureFrame(
            final int frame,
            final String scenario,
            final boolean first,
            final boolean last
    ) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.flickerRequest = new FlickerRequest(frame, scenario, first, last);
            if (first) {
                // Pin the Halton phase to the start of the sequence so the
                // series samples the same jitter offsets in every run. Without
                // this the phase at the series start depends on how many
                // frames warm-up and terrain settling happened to render,
                // which moves the metric run to run. Called from the timeline
                // tick on the render thread, before this frame's encode.
                manager.phase = 0;
            }
        }
    }

    public static boolean flickerSeriesPending() {
        MetalFxManager manager = active;
        return manager != null && manager.flickerCapturePending;
    }

    public static boolean flickerMetricCompleted(final String scenario) {
        MetalFxManager manager = active;
        return manager != null && manager.flickerCompletedScenarios.contains(scenario);
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
        if (frameGenerationSuspended && !runtimeDisabled && !hasActiveGui() && !immediatePresentMode) {
            frameGenerationSuspended = false;
            frameGenerationEnabled = true;
            resetHistoryInternal("frame generation resumed; suspend condition cleared");
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
        Matrix4f currentObject = MetalEntityObjectPose.compose(state);
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
            MetalEntityMotionCapture.recordMotionDrawEncoded(prepared.pipeline());
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
        long sceneFrameStartNanos = System.nanoTime();
        this.sceneFrameDeltaSeconds = lastSceneFrameStartNanos > 0
                ? (float) ((sceneFrameStartNanos - lastSceneFrameStartNanos) / 1_000_000_000.0)
                : 0.0F;
        this.lastSceneFrameStartNanos = sceneFrameStartNanos;

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
        if (effectiveMode == MetalFxConfig.Mode.TEMPORAL && sceneFrame
                && handOverlayPipelineAvailable && motionInputsPrepared
                && objectMotionTexture != null && objectValidityTexture != null
                && reactiveTexture != null
                && renderer.mainRenderTarget().getDepthTexture() instanceof MetalGpuTexture handDepth
                && handDepth.getWidth(0) == renderWidth
                && handDepth.getHeight(0) == renderHeight) {
            // Vanilla clears the reversed-Z depth buffer right before the
            // first-person pass, so at this point it contains only hand,
            // held-item, and screen-effect coverage. Those pixels are
            // camera-locked: stamp zero object motion with full validity so
            // the merge pass does not apply world reprojection to them.
            boolean handEncoded = encoder.encodeHandOverlayMotion(
                    handDepth,
                    objectMotionTexture,
                    objectValidityTexture,
                    reactiveTexture,
                    renderWidth,
                    renderHeight,
                    HAND_OVERLAY_REACTIVE_BOOST
            );
            if (config.debug && handEncoded && !loggedHandOverlay) {
                loggedHandOverlay = true;
                Metallum.LOGGER.info(
                        "MetalFX first-person overlay motion prepared: zero-motion validity plus reactive boost {}",
                        HAND_OVERLAY_REACTIVE_BOOST
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
                captureFlickerFrameIfRequested(output, depth);
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

    private void captureFlickerFrameIfRequested(
            final MetalGpuTexture temporalOutput,
            final MetalGpuTexture depth
    ) {
        FlickerRequest requested = this.flickerRequest;
        this.flickerRequest = null;
        if (requested == null || cutoutReactiveTexture == null
                || flickerCompletedScenarios.contains(requested.scenario)) {
            return;
        }
        this.flickerCapturePending = true;
        ValidationReadback outputReadback = validationReadback("flicker-output", temporalOutput);
        ValidationReadback coverageReadback = requested.first
                ? validationReadback("flicker-coverage", cutoutReactiveTexture)
                : null;
        // The sky class comes from the same cleared far-plane test the motion
        // kernels use, so the mask and the shader agree on what "sky" means.
        ValidationReadback depthReadback = requested.first
                ? validationReadback("flicker-depth", depth)
                : null;
        // Attribution: the final reactive mask on the silhouette band, bucketed
        // so each policy writer is identifiable by its value (0.35 cutout edge
        // band, 0.5 depth-edge cap, 0.85 disocclusion cap, 0.9 transparency).
        ValidationReadback reactiveReadback = requested.first && reactiveTexture != null
                ? validationReadback("flicker-reactive", reactiveTexture)
                : null;
        if (coverageReadback != null) {
            device.commandEncoder().copyTextureToBuffer(
                    coverageReadback.texture, coverageReadback.buffer, 0L, () -> { }, 0);
        }
        if (depthReadback != null) {
            device.commandEncoder().copyTextureToBuffer(
                    depthReadback.texture, depthReadback.buffer, 0L, () -> { }, 0);
        }
        if (reactiveReadback != null) {
            device.commandEncoder().copyTextureToBuffer(
                    reactiveReadback.texture, reactiveReadback.buffer, 0L, () -> { }, 0);
        }
        device.commandEncoder().copyTextureToBuffer(
                outputReadback.texture,
                outputReadback.buffer,
                0L,
                () -> finishFlickerCapture(
                        requested, outputReadback, coverageReadback, depthReadback, reactiveReadback),
                0
        );
    }

    private void finishFlickerCapture(
            final FlickerRequest requested,
            final ValidationReadback outputReadback,
            @Nullable final ValidationReadback coverageReadback,
            @Nullable final ValidationReadback depthReadback,
            @Nullable final ValidationReadback reactiveReadback
    ) {
        try {
            byte[] output = readbackBytes(outputReadback);
            int width = outputReadback.texture.getWidth(0);
            int height = outputReadback.texture.getHeight(0);
            if (requested.first) {
                byte[] coverage = readbackBytes(coverageReadback);
                byte[] depth = depthReadback == null ? null : readbackBytes(depthReadback);
                byte[] reactive = reactiveReadback == null ? null : readbackBytes(reactiveReadback);
                beginFlickerSeries(width, height, coverage, depth, reactive);
            }
            accumulateFlickerFrame(output, width, height);
            // Requests already in flight when the series closes must not
            // rewrite the metric: the JSON is final on the first close.
            if (requested.last && flickerCompletedScenarios.add(requested.scenario)) {
                writeFlickerMetrics(requested.scenario);
            }
        } catch (IOException | RuntimeException exception) {
            Metallum.LOGGER.error(
                    "MetalFX flicker capture failed for frame {} ({})",
                    requested.frame,
                    requested.scenario,
                    exception
            );
            // Fail open: the timeline still finishes and the missing JSON (or
            // this log line) makes the failed measurement obvious in A/B runs.
            this.flickerCompletedScenarios.add(requested.scenario);
        } finally {
            outputReadback.buffer.close();
            if (coverageReadback != null) {
                coverageReadback.buffer.close();
            }
            if (depthReadback != null) {
                depthReadback.buffer.close();
            }
            if (reactiveReadback != null) {
                reactiveReadback.buffer.close();
            }
            this.flickerCapturePending = false;
        }
    }

    private static byte[] readbackBytes(final ValidationReadback readback) {
        ByteBuffer source = readback.buffer.currentStorage()
                .limit(readback.byteCount)
                .slice()
                .order(ByteOrder.nativeOrder());
        byte[] bytes = new byte[readback.byteCount];
        source.get(bytes);
        return bytes;
    }

    private void beginFlickerSeries(
            final int width,
            final int height,
            final byte[] coverage,
            @Nullable final byte[] depth,
            @Nullable final byte[] reactive
    ) {
        this.flickerDisplayWidth = width;
        this.flickerDisplayHeight = height;
        this.flickerFramesAccumulated = 0;
        this.flickerPreviousLuma = null;
        java.util.Arrays.fill(this.flickerMaskedHistogram, 0L);
        java.util.Arrays.fill(this.flickerControlHistogram, 0L);
        java.util.Arrays.fill(this.flickerSkyEdgeHistogram, 0L);
        java.util.Arrays.fill(this.flickerSkyInteriorHistogram, 0L);
        // Reversed-Z: the cleared far plane is zero, so an untouched depth
        // pixel is sky. Same threshold as validDepth() in the motion kernels.
        boolean[] sky = null;
        int skyPixels = 0;
        if (depth != null && depth.length >= renderWidth * renderHeight * 4) {
            ByteBuffer depthValues = ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder());
            sky = new boolean[renderWidth * renderHeight];
            for (int pixel = 0; pixel < renderWidth * renderHeight; pixel++) {
                float value = depthValues.getFloat(pixel * 4);
                if (Float.isFinite(value) && value >= 0.0F && value <= 0.00001F) {
                    sky[pixel] = true;
                    skyPixels++;
                }
            }
        }
        // Display pixel -> render pixel (integer scale), masked when any
        // CUTOUT coverage exists in the 3x3 render neighborhood: this covers
        // the upscale footprint plus the reactive edge band. The sky-edge
        // submask additionally requires sky in the same neighborhood.
        // The sky-interior submask is the complement: sky with no CUTOUT
        // coverage within a wider radius, so no silhouette contaminates it.
        boolean[] mask = new boolean[width * height];
        boolean[] skyEdge = new boolean[width * height];
        boolean[] skyInterior = new boolean[width * height];
        int maskPixels = 0;
        int skyEdgePixels = 0;
        int skyInteriorPixels = 0;
        for (int y = 0; y < height; y++) {
            int renderY = Math.min(renderHeight - 1, y * renderHeight / height);
            for (int x = 0; x < width; x++) {
                int renderX = Math.min(renderWidth - 1, x * renderWidth / width);
                boolean skyNear = sky != null
                        && hasSkyNeighbor(sky, renderX, renderY, renderWidth, renderHeight, 1);
                if (hasCutoutCoverageNeighbor(coverage, renderX, renderY, renderWidth, renderHeight, 1)) {
                    mask[y * width + x] = true;
                    maskPixels++;
                    if (skyNear) {
                        skyEdge[y * width + x] = true;
                        skyEdgePixels++;
                    }
                } else if (skyNear && sky[renderY * renderWidth + renderX]
                        && !hasCutoutCoverageNeighbor(
                                coverage, renderX, renderY, renderWidth, renderHeight, 3)) {
                    skyInterior[y * width + x] = true;
                    skyInteriorPixels++;
                }
            }
        }
        this.flickerMask = mask;
        this.flickerMaskPixels = maskPixels;
        this.flickerSkyEdgeMask = skyEdge;
        this.flickerSkyEdgePixels = skyEdgePixels;
        this.flickerSkyPixels = skyPixels;
        this.flickerSkyInteriorMask = skyInterior;
        this.flickerSkyInteriorPixels = skyInteriorPixels;
        buildReactiveAttribution(coverage, sky, reactive);
    }

    /**
     * Bucketed reactive values on the silhouette band, in render space. Each
     * policy writer lands on its own value, so the distribution says which one
     * is responsible for the residual flicker instead of requiring one full
     * validation run per knob:
     * 0 interior, ~89 cutout edge band (0.35), ~128 depth-edge cap (0.5),
     * ~217 disocclusion cap (0.85), ~230 transparency (0.9), 255 full.
     */
    private void buildReactiveAttribution(
            final byte[] coverage,
            @Nullable final boolean[] sky,
            @Nullable final byte[] reactive
    ) {
        java.util.Arrays.fill(this.flickerSkyEdgeReactiveBuckets, 0L);
        this.flickerSkyEdgeRenderPixels = 0;
        if (reactive == null || sky == null || reactive.length < renderWidth * renderHeight) {
            return;
        }
        for (int y = 0; y < renderHeight; y++) {
            for (int x = 0; x < renderWidth; x++) {
                if (!hasCutoutCoverageNeighbor(coverage, x, y, renderWidth, renderHeight, 1)
                        || !hasSkyNeighbor(sky, x, y, renderWidth, renderHeight, 1)) {
                    continue;
                }
                int value = Byte.toUnsignedInt(reactive[y * renderWidth + x]);
                flickerSkyEdgeReactiveBuckets[value >> 4]++;
                flickerSkyEdgeRenderPixels++;
            }
        }
    }

    private static boolean hasSkyNeighbor(
            final boolean[] sky,
            final int x,
            final int y,
            final int width,
            final int height,
            final int radius
    ) {
        for (int dy = -radius; dy <= radius; dy++) {
            int sampleY = y + dy;
            if (sampleY < 0 || sampleY >= height) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                int sampleX = x + dx;
                if (sampleX < 0 || sampleX >= width) {
                    continue;
                }
                if (sky[sampleY * width + sampleX]) {
                    return true;
                }
            }
        }
        return false;
    }

    private void accumulateFlickerFrame(final byte[] rgba, final int width, final int height) {
        boolean[] mask = this.flickerMask;
        boolean[] skyEdge = this.flickerSkyEdgeMask;
        boolean[] skyInterior = this.flickerSkyInteriorMask;
        if (mask == null || width != flickerDisplayWidth || height != flickerDisplayHeight
                || rgba.length < width * height * 4) {
            throw new IllegalStateException("Flicker capture dimensions changed mid-series");
        }
        byte[] luma = new byte[width * height];
        for (int pixel = 0; pixel < width * height; pixel++) {
            int r = Byte.toUnsignedInt(rgba[pixel * 4]);
            int g = Byte.toUnsignedInt(rgba[pixel * 4 + 1]);
            int b = Byte.toUnsignedInt(rgba[pixel * 4 + 2]);
            // Integer Rec.709 luma; a channel-order swap would affect both A/B
            // runs identically and cancel out of the comparison.
            luma[pixel] = (byte) ((54 * r + 183 * g + 19 * b) >> 8);
        }
        byte[] previous = this.flickerPreviousLuma;
        if (previous != null) {
            for (int pixel = 0; pixel < width * height; pixel++) {
                int delta = Math.abs(
                        Byte.toUnsignedInt(luma[pixel]) - Byte.toUnsignedInt(previous[pixel]));
                if (mask[pixel]) {
                    flickerMaskedHistogram[delta]++;
                    // Sky-edge is a subset of the mask, not a fourth class:
                    // maskedMeanDelta stays comparable with earlier A/B runs.
                    if (skyEdge != null && skyEdge[pixel]) {
                        flickerSkyEdgeHistogram[delta]++;
                    }
                } else {
                    flickerControlHistogram[delta]++;
                    if (skyInterior != null && skyInterior[pixel]) {
                        flickerSkyInteriorHistogram[delta]++;
                    }
                }
            }
        }
        this.flickerPreviousLuma = luma;
        this.flickerFramesAccumulated++;
    }

    private void writeFlickerMetrics(final String scenario) throws IOException {
        Path root = Path.of(System.getProperty(
                "metallum.validation.output",
                "build/metal-validation/minecraft-client-current"
        )).toAbsolutePath().normalize();
        Files.createDirectories(root);
        double maskedMean = histogramMean(flickerMaskedHistogram);
        int maskedP95 = histogramPercentile(flickerMaskedHistogram, 0.95);
        double controlMean = histogramMean(flickerControlHistogram);
        int controlP95 = histogramPercentile(flickerControlHistogram, 0.95);
        double skyEdgeMean = histogramMean(flickerSkyEdgeHistogram);
        int skyEdgeP95 = histogramPercentile(flickerSkyEdgeHistogram, 0.95);
        double skyInteriorMean = histogramMean(flickerSkyInteriorHistogram);
        int skyInteriorP95 = histogramPercentile(flickerSkyInteriorHistogram, 0.95);
        String json = String.format(
                java.util.Locale.ROOT,
                """
                {
                  "scenario": "%s",
                  "frames": %d,
                  "displayWidth": %d,
                  "displayHeight": %d,
                  "maskPixels": %d,
                  "maskedMeanDelta": %.6f,
                  "maskedP95Delta": %d,
                  "controlMeanDelta": %.6f,
                  "controlP95Delta": %d,
                  "skyPixels": %d,
                  "skyEdgePixels": %d,
                  "skyEdgeMeanDelta": %.6f,
                  "skyEdgeP95Delta": %d,
                  "skyInteriorPixels": %d,
                  "skyInteriorMeanDelta": %.6f,
                  "skyInteriorP95Delta": %d,
                  "skyEdgeRenderPixels": %d,
                  "skyEdgeReactiveBuckets": [%s]
                }
                """,
                scenario, flickerFramesAccumulated, flickerDisplayWidth, flickerDisplayHeight,
                flickerMaskPixels, maskedMean, maskedP95, controlMean, controlP95,
                flickerSkyPixels, flickerSkyEdgePixels, skyEdgeMean, skyEdgeP95,
                flickerSkyInteriorPixels, skyInteriorMean, skyInteriorP95,
                flickerSkyEdgeRenderPixels, bucketList(flickerSkyEdgeReactiveBuckets)
        );
        Files.writeString(root.resolve("flicker-" + scenario + ".json"), json, StandardCharsets.UTF_8);
        Metallum.LOGGER.info(
                "MetalFX flicker metric: scenario={} frames={} maskPixels={} maskedMeanDelta={} maskedP95={} controlMeanDelta={} controlP95={} skyPixels={} skyEdgePixels={} skyEdgeMeanDelta={} skyEdgeP95={} skyInteriorPixels={} skyInteriorMeanDelta={} skyInteriorP95={}",
                scenario, flickerFramesAccumulated, flickerMaskPixels,
                String.format(java.util.Locale.ROOT, "%.4f", maskedMean), maskedP95,
                String.format(java.util.Locale.ROOT, "%.4f", controlMean), controlP95,
                flickerSkyPixels, flickerSkyEdgePixels,
                String.format(java.util.Locale.ROOT, "%.4f", skyEdgeMean), skyEdgeP95,
                flickerSkyInteriorPixels,
                String.format(java.util.Locale.ROOT, "%.4f", skyInteriorMean), skyInteriorP95
        );
    }

    private static String bucketList(final long[] buckets) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < buckets.length; index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(buckets[index]);
        }
        return text.toString();
    }

    /** Mean of an empty histogram is 0, not NaN: the JSON must stay parseable. */
    private static double histogramMean(final long[] histogram) {
        long total = 0L;
        long weighted = 0L;
        for (int value = 0; value < histogram.length; value++) {
            total += histogram[value];
            weighted += histogram[value] * value;
        }
        return total == 0L ? 0.0 : (double) weighted / total;
    }

    private static int histogramPercentile(final long[] histogram, final double percentile) {
        long total = 0L;
        for (long count : histogram) {
            total += count;
        }
        if (total == 0L) {
            return 0;
        }
        long threshold = (long) Math.ceil(total * percentile);
        long cumulative = 0L;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative >= threshold) {
                return value;
            }
        }
        return histogram.length - 1;
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
                    submittedCutoutRadius,
                    producerDiagnostics
            );
            Files.writeString(
                    frameDirectory.resolve("metrics.json"),
                    metrics.toJson(requested, renderWidth, renderHeight),
                    StandardCharsets.UTF_8
            );
            Metallum.LOGGER.info(
                    "Minecraft validation GPU readback frame={} scenario={} validPixels={} "
                            + "depthValidPixels={} disocclusionPixels={} objectDisocclusionPixels={} "
                            + "cutoutCoveragePixels={} cutoutInteriorPixels={} "
                            + "cutoutInteriorViolations={} cutoutEdgeBandReactivePixels={} cutoutRadius={} "
                            + "motionMean=({}, {}) expected=({}, {}) error={} "
                            + "motionSpread=({}, {}) maxAbsMotion={} producer={}",
                    requested.frame,
                    requested.scenario,
                    metrics.validPixels,
                    metrics.depthValidPixels,
                    metrics.disocclusionPixels,
                    metrics.objectDisocclusionPixels,
                    metrics.cutoutCoveragePixels,
                    metrics.cutoutInteriorPixels,
                    metrics.cutoutInteriorViolations,
                    metrics.cutoutEdgeBandReactivePixels,
                    metrics.cutoutRadius,
                    metrics.meanX,
                    metrics.meanY,
                    metrics.expectedX,
                    metrics.expectedY,
                    metrics.error,
                    metrics.motionSpreadX,
                    metrics.motionSpreadY,
                    metrics.maxAbsMotion,
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
            final int cutoutRadius,
            final MetalEntityMotionCapture.Diagnostics producerDiagnostics
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
        // Peak-to-peak extent of the object-motion field over the silhouette.
        // A rigid translation projects to a near-uniform field, so its spread
        // stays near zero; a rotation about the object's own axis moves points
        // by an amount proportional to their offset from that axis, so the
        // spread is what actually carries the rotation. See the item_spin case
        // in the pass switch below.
        double minMotionX = Double.POSITIVE_INFINITY;
        double maxMotionX = Double.NEGATIVE_INFINITY;
        double minMotionY = Double.POSITIVE_INFINITY;
        double maxMotionY = Double.NEGATIVE_INFINITY;
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
                minMotionX = Math.min(minMotionX, x);
                maxMotionX = Math.max(maxMotionX, x);
                minMotionY = Math.min(minMotionY, y);
                maxMotionY = Math.max(maxMotionY, y);
            }
        }
        double motionSpreadX = validPixels == 0 ? Double.NaN : maxMotionX - minMotionX;
        double motionSpreadY = validPixels == 0 ? Double.NaN : maxMotionY - minMotionY;
        double maxAbsMotion = validPixels == 0 ? Double.NaN : Math.max(
                Math.max(Math.abs(minMotionX), Math.abs(maxMotionX)),
                Math.max(Math.abs(minMotionY), Math.abs(maxMotionY))
        );
        int itemMotionDraws = producerDiagnostics == null
                ? 0
                : producerDiagnostics.itemMotionDrawsEncoded();

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
        // Policy invariants (docs/cutout-shimmer-remediation-2026-07-27.md):
        // interior CUTOUT pixels must KEEP temporal accumulation (low
        // reactive) while the edge band still carries a protective bias.
        // Interior = every in-bounds neighbor within the submitted dilation
        // radius is covered, mirroring the kernel's window classification.
        int cutoutCoveragePixels = 0;
        int cutoutInteriorPixels = 0;
        int cutoutInteriorViolations = 0;
        int cutoutEdgeBandReactivePixels = 0;
        int effectiveRadius = Math.clamp(cutoutRadius, 1, 3);
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            boolean covered = Byte.toUnsignedInt(cutoutCoverage[pixel]) >= 128;
            int reactiveValue = Byte.toUnsignedInt(reactive[pixel]);
            int x = pixel % renderWidth;
            int y = pixel / renderWidth;
            if (covered) {
                cutoutCoveragePixels++;
                if (allCutoutNeighborsCovered(cutoutCoverage, x, y, renderWidth, renderHeight, effectiveRadius)) {
                    cutoutInteriorPixels++;
                    // Disoccluded pixels are legitimately fully reactive for
                    // one frame (the capture frames sit a few frames after a
                    // scripted scene mutation); the invariant targets the
                    // standing policy, so those transients are excluded.
                    if (reactiveValue > INTERIOR_REACTIVE_MAX
                            && Byte.toUnsignedInt(disocclusion[pixel]) < 128) {
                        cutoutInteriorViolations++;
                    }
                } else if (reactiveValue >= EDGE_REACTIVE_MIN) {
                    cutoutEdgeBandReactivePixels++;
                }
            } else if (reactiveValue >= EDGE_REACTIVE_MIN && hasCutoutCoverageNeighbor(
                    cutoutCoverage, x, y, renderWidth, renderHeight, effectiveRadius)) {
                cutoutEdgeBandReactivePixels++;
            }
        }
        boolean passed = switch (requested.scenario) {
            case "occluded_entity" -> depthContractPassed && validPixels < 2_500;
            // The 3x3 occlusion wall two blocks ahead spans the whole
            // viewport, so a frame-exact removal legitimately disoccludes
            // every pixel; requiring disocclusionPixels < pixelCount here
            // (depthContractPassed) only passed while the prioritized rebuild
            // raced and landed a frame late with a partial reveal.
            case "revealed_entity" -> validPixels > 2_000
                    && depthValidPixels > 0
                    && objectDisocclusionPixels > 1_000
                    && Double.isFinite(error)
                    && error <= 0.03;
            case "scene_reset" -> depthContractPassed
                    && validPixels == 0
                    && objectDisocclusionPixels == 0;
            // A dropped item spinning in place. itemMotionDrawsEncoded is the
            // core/item subset of motionDrawsEncoded: asserting it is non-zero
            // is what catches a future regression that drops the core/item
            // vertex-shader family back out of the motion pipeline, which would
            // otherwise show up only as a silently unvalidated path.
            case "item_spin" -> depthContractPassed
                    && validPixels > OBJECT_MIN_VALID_PIXELS
                    && itemMotionDraws > 0
                    && Double.isFinite(motionSpreadX)
                    && motionSpreadX >= OBJECT_MIN_SPIN_SPREAD_X
                    && maxAbsMotion <= OBJECT_MAX_MOTION;
            // A boat turning on the spot. Same rotational envelope, but the
            // vehicle renders through core/entity, so no core/item assertion.
            case "vehicle_turn" -> depthContractPassed
                    && validPixels > OBJECT_MIN_VALID_PIXELS
                    && Double.isFinite(motionSpreadX)
                    && motionSpreadX >= OBJECT_MIN_SPIN_SPREAD_X
                    && maxAbsMotion <= OBJECT_MAX_MOTION;
            case "cutout_leaves", "cutout_grass" -> depthContractPassed
                    && cutoutCoveragePixels > 32
                    && cutoutInteriorPixels > 0
                    && cutoutInteriorViolations == 0
                    && cutoutEdgeBandReactivePixels > 0;
            default -> depthContractPassed
                    && validPixels > 0
                    && Double.isFinite(error)
                    && error <= 0.03;
        };
        if (Boolean.getBoolean("metallum.validation.lenient")) {
            // A/B baseline runs with the legacy reactive policy record the
            // same metrics but must not abort the timeline.
            passed = true;
        }
        return new MotionMetrics(
                validPixels,
                depthValidPixels,
                disocclusionPixels,
                objectDisocclusionPixels,
                cutoutCoveragePixels,
                cutoutInteriorPixels,
                cutoutInteriorViolations,
                cutoutEdgeBandReactivePixels,
                cutoutRadius,
                meanX,
                meanY,
                expectedX,
                expectedY,
                error,
                motionSpreadX,
                motionSpreadY,
                maxAbsMotion,
                itemMotionDraws,
                passed
        );
    }

    private static boolean allCutoutNeighborsCovered(
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
                if (Byte.toUnsignedInt(coverage[sampleY * width + sampleX]) < 128) {
                    return false;
                }
            }
        }
        return true;
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

    /** One frame of the static-camera flicker series (§8 of the remediation doc). */
    private record FlickerRequest(
            int frame,
            String scenario,
            boolean first,
            boolean last
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
            // Frame 46 is the occlusion-wall removal frame: with prioritized
            // synchronous section rebuilds the reveal happens on exactly this
            // frame, and its one-frame disocclusion transient is the signal
            // being validated.
            // 164 and 176 are the object-motion acceptance captures, placed 8
            // frames after their scenario starts so temporal history has
            // settled; see MetalValidationClient's OBJECT_SCENE_FRAME block.
            return frame == 6 || frame == 12 || frame == 22 || frame == 32
                    || frame == 42 || frame == 46 || frame == 54 || frame == 62
                    || frame == 74 || frame == 82
                    || frame == 164 || frame == 176;
        }
    }

    private record MotionMetrics(
            int validPixels,
            int depthValidPixels,
            int disocclusionPixels,
            int objectDisocclusionPixels,
            int cutoutCoveragePixels,
            int cutoutInteriorPixels,
            int cutoutInteriorViolations,
            int cutoutEdgeBandReactivePixels,
            int cutoutRadius,
            double meanX,
            double meanY,
            double expectedX,
            double expectedY,
            double error,
            double motionSpreadX,
            double motionSpreadY,
            double maxAbsMotion,
            int itemMotionDraws,
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
                      "cutoutInteriorPixels": %d,
                      "cutoutInteriorViolations": %d,
                      "cutoutEdgeBandReactivePixels": %d,
                      "cutoutReactiveRadius": %d,
                      "meanObjectMotionNdc": [%.9f, %.9f],
                      "expectedObjectMotionNdc": [%.9f, %.9f],
                      "error": %.9f,
                      "tolerance": 0.03,
                      "objectMotionSpreadNdc": [%.9f, %.9f],
                      "maxAbsObjectMotionNdc": %.9f,
                      "itemMotionDrawsEncoded": %d,
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
                    cutoutInteriorPixels,
                    cutoutInteriorViolations,
                    cutoutEdgeBandReactivePixels,
                    cutoutRadius,
                    meanX,
                    meanY,
                    expectedX,
                    expectedY,
                    error,
                    motionSpreadX,
                    motionSpreadY,
                    maxAbsMotion,
                    itemMotionDraws,
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
            // Upscaler/frame-generation output targets are the only vanilla
            // TextureTargets that need MTLTextureUsage.ShaderWrite (MetalFX
            // writes them from compute). Route the backend-only usage bit
            // through the creation scope so every other color target keeps
            // lossless bandwidth compression.
            device.withExtraTextureUsage(MetalGpuTexture.USAGE_SHADER_WRITE, () ->
                    uiTarget = new TextureTarget("MetalFX Native Resolution UI", width, height, true, GpuFormat.RGBA8_UNORM)
            );
            dimensionsChanged = true;
        }
        if (frameGenerationEnabled) {
            if (sceneOutputTarget == null || sceneOutputTarget.width != width || sceneOutputTarget.height != height) {
                if (sceneOutputTarget != null) sceneOutputTarget.destroyBuffers();
                device.withExtraTextureUsage(MetalGpuTexture.USAGE_SHADER_WRITE, () ->
                        sceneOutputTarget = new TextureTarget("MetalFX Scene Output", width, height, false, GpuFormat.RGBA8_UNORM)
                );
                dimensionsChanged = true;
            }
        } else if (sceneOutputTarget != null) {
            sceneOutputTarget.destroyBuffers();
            sceneOutputTarget = null;
            dimensionsChanged = true;
        }
        dimensionsChanged |= ensureAuxiliaryTextures();
        if (dimensionsChanged) {
            // The native scaler cache is keyed by input/output dimensions, so
            // the entries for the previous size are unreachable from here on.
            // Dropping them keeps a drag-resize from stranding one fully
            // initialized MTLFXTemporalScaler (plus its depth history) per
            // intermediate size for the rest of the session. The next encode
            // rebuilds the scaler for the new size, which the history reset
            // below already accounts for.
            MetalNativeBridge.metallum_metalfx_release_scalers();
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
        // The reactive mask is pre-cleared through a render-pass load action
        // before producers max-merge into it, so it must be a render target.
        reactiveTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Reactive R8",
                usage | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.R8_UNORM,
                renderWidth,
                renderHeight,
                1,
                1
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
            suspendFrameGenerationInternal("a GUI screen or overlay is active");
        }
        // The presenter drives presents from CAMetalDisplayLink, which only
        // schedules updates on the refresh boundary. With vsync off the layer
        // no longer honours that boundary, so the generated/real pair loses the
        // spacing every pacing acceptance run measured; fall back to the
        // single-present path until VSync is on again.
        if (frameGenerationEnabled && immediatePresentMode) {
            suspendFrameGenerationInternal("the surface presents in immediate mode (VSync off)");
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
                sceneFrameDeltaSeconds,
                frameResetForPresent
        );
    }

    private static boolean hasActiveGui() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gui.screen() != null || minecraft.gui.overlay() != null;
    }

    private void suspendFrameGenerationInternal(final String reason) {
        if (!frameGenerationEnabled) {
            return;
        }
        frameGenerationEnabled = false;
        frameGenerationSuspended = true;
        // Keep sceneOutputTarget alive until this frame is submitted. The
        // current frame may already contain an encoded MetalFX write to it.
        MetalNativeBridge.metallum_metalfx_stop_frame_generation();
        if (config.debug) {
            Metallum.LOGGER.info("MetalFX frame generation paused while {}", reason);
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
            float deltaSeconds,
            boolean reset
    ) {
    }
}
