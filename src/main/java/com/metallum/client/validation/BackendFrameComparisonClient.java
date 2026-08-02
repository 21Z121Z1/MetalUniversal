package com.metallum.client.validation;

import com.metallum.Metallum;
import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.util.FlawlessFrames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in final-target capture shared by the Metal and Vulkan client paths.
 *
 * <p>This intentionally captures the backend-neutral Minecraft present target
 * through the Blaze3D API. It does not use a system screenshot, and it does
 * not claim that a frame is comparable until both runs have the same extent,
 * format, frame id and scene contract. The diagnostic blocks on a fence so
 * the bytes are known to belong to the submitted copy on both Vulkan and
 * Metal.</p>
 */
public final class BackendFrameComparisonClient {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.backend.compare.enabled");
    private static final boolean AUTO_STOP = Boolean.parseBoolean(
            System.getProperty("metallum.backend.compare.auto-stop", "true")
    );
    private static final Path ROOT = Path.of(System.getProperty(
            "metallum.backend.compare.output",
            "build/backend-compare"
    )).toAbsolutePath().normalize();
    private static final String SCENARIO_ID = System.getProperty(
            "metallum.backend.compare.scenario-id",
            ""
    ).trim();
    private static final String WORLD_NAME = System.getProperty(
            "metallum.backend.compare.world-name",
            ""
    ).trim();
    private static final String WORLD_SNAPSHOT_SHA256 = System.getProperty(
            "metallum.backend.compare.world-snapshot-sha256",
            ""
    ).trim().toLowerCase(Locale.ROOT);
    private static final String REQUESTED_GAME_DIRECTORY = canonicalGameDirectory(
            System.getProperty(
            "metallum.backend.compare.game-directory",
            ""
            )
    );
    private static final String REQUESTED_PLAYER_NAME = System.getProperty(
            "metallum.backend.compare.player-name",
            ""
    ).trim();
    private static final String REQUESTED_PLAYER_UUID = canonicalUuid(System.getProperty(
            "metallum.backend.compare.player-uuid",
            ""
    ));
    private static final Set<Integer> CAPTURE_FRAMES = parseFrames(
            System.getProperty("metallum.backend.compare.frames", "90")
    );
    private static final int IRIS_RELOAD_FRAME = Integer.getInteger(
            "metallum.backend.compare.iris-reload-frame",
            -1
    );
    private static final ResizeRequest RESIZE_REQUEST = parseResizeRequest(
            Integer.getInteger("metallum.backend.compare.resize-frame", -1),
            Integer.getInteger("metallum.backend.compare.resize-width", -1),
            Integer.getInteger("metallum.backend.compare.resize-height", -1)
    );
    private static final ShaderToggleRequest SHADER_TOGGLE_REQUEST = parseShaderToggleRequest(
            Integer.getInteger("metallum.backend.compare.shader-disable-frame", -1),
            Integer.getInteger("metallum.backend.compare.shader-enable-frame", -1)
    );
    private static final DimensionSwitchRequest DIMENSION_SWITCH_REQUEST =
            parseDimensionSwitchRequest(
                    Integer.getInteger("metallum.backend.compare.dimension-switch-frame", -1),
                    System.getProperty("metallum.backend.compare.dimension-switch-target", "")
            );
    /** Optional ordered lifecycle contract used by the repeated-transition receipt. */
    private static final List<DimensionSwitchRequest> DIMENSION_SWITCH_SEQUENCE =
            parseDimensionSwitchSequence(
                    System.getProperty("metallum.backend.compare.dimension-switch-sequence", "")
            );
    private static final long FIXED_CLOCK_TICKS = Long.getLong(
            "metallum.backend.compare.fixed-clock-ticks",
            Long.MIN_VALUE
    );
    private static final FixedCamera FIXED_CAMERA = parseFixedCamera(
            System.getProperty("metallum.backend.compare.fixed-camera", "")
    );
    private static final long FIXED_IRIS_FRAME_MILLIS = Long.getLong(
            "metallum.backend.compare.fixed-iris-frame-millis",
            -1L
    );
    private static final boolean FREEZE_SIMULATION = Boolean.getBoolean(
            "metallum.backend.compare.freeze-simulation"
    );
    private static final float FIXED_PARTIAL_TICK = parseFixedPartialTick(
            System.getProperty("metallum.backend.compare.fixed-partial-tick", "1.0")
    );
    /** Optional validation input used to remove player-ground-state timing drift. */
    private static final boolean FIXED_PLAYER_ON_GROUND = Boolean.getBoolean(
            "metallum.backend.compare.fixed-player-on-ground"
    );
    private static final FixedWeather FIXED_WEATHER = parseFixedWeather(
            System.getProperty("metallum.backend.compare.fixed-weather", "")
    );
    private static final int STABLE_SCENE_FRAMES = Math.max(
            0,
            Integer.getInteger("metallum.backend.compare.stable-scene-frames", 0)
    );
    private static final long STABLE_SCENE_MILLIS = Math.max(
            0L,
            Long.getLong("metallum.backend.compare.stable-scene-millis", 0L)
    );
    private static final SceneStabilityTracker SCENE_STABILITY = new SceneStabilityTracker(
            STABLE_SCENE_FRAMES,
            STABLE_SCENE_MILLIS
    );
    private static final List<Integer> COMPLETED_FRAMES = new ArrayList<>();
    private static int levelFrame = -1;
    private static int pendingCaptures;
    private static int failedCaptures;
    private static boolean sessionWritten;
    private static boolean stopRequested;
    private static boolean irisReloadAttempted;
    private static boolean irisReloadCompleted;
    private static boolean resizeAttempted;
    private static boolean resizeCompleted;
    private static int resizeObservedWidth = -1;
    private static int resizeObservedHeight = -1;
    private static boolean shaderDisableAttempted;
    private static boolean shaderDisableCompleted;
    private static boolean shaderEnableAttempted;
    private static boolean shaderEnableCompleted;
    private static int shaderDisableGeneration = -1;
    private static int shaderEnableGeneration = -1;
    private static boolean dimensionSwitchAttempted;
    private static boolean dimensionSwitchServerApplied;
    private static boolean dimensionSwitchCompleted;
    private static UUID dimensionSwitchPlayerUuid;
    private static String dimensionSwitchSource = "";
    private static String dimensionSwitchServerTarget = "";
    private static String dimensionSwitchClientTarget = "";
    private static String dimensionSwitchPipelineBefore;
    private static String dimensionSwitchPipelineAfter;
    private static int dimensionSwitchGenerationBefore = -1;
    private static int dimensionSwitchGenerationAfter = -1;
    private static int dimensionSwitchObservedFrame = -1;
    private static int dimensionSwitchServerTick = -1;
    private static String dimensionSwitchFailure = "";
    private static final Object DIMENSION_SWITCH_LOCK = new Object();
    private static int dimensionSequenceIndex;
    private static boolean dimensionSequenceAttempted;
    private static boolean dimensionSequenceServerApplied;
    private static UUID dimensionSequencePlayerUuid;
    private static String dimensionSequenceSource = "";
    private static String dimensionSequenceServerTarget = "";
    private static String dimensionSequenceClientTarget = "";
    private static String dimensionSequencePipelineBefore;
    private static String dimensionSequencePipelineAfter;
    private static int dimensionSequenceGenerationBefore = -1;
    private static int dimensionSequenceGenerationAfter = -1;
    private static int dimensionSequenceObservedFrame = -1;
    private static int dimensionSequenceServerTick = -1;
    private static String dimensionSequenceFailure = "";
    private static final List<DimensionSwitchReceipt> DIMENSION_SEQUENCE_RECEIPTS = new ArrayList<>();
    private static volatile boolean fixedClockApplied;
    private static volatile boolean integratedServerConfigured;
    private static boolean flawlessFramesAttempted;
    private static boolean sceneReady;
    private static boolean runtimeIdentityValidated;
    private static boolean runtimeIdentityValid = true;
    private static boolean sceneStartIrisResetAttempted;
    private static boolean sceneStartIrisResetCompleted;
    private static int sceneReadinessPolls;
    private static SceneReadinessSample sceneStartSample;

    private BackendFrameComparisonClient() {
    }

    public static void beforeFrame(final boolean renderLevel) {
        if (!ENABLED || !renderLevel) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!validateDirectories(minecraft)) {
            if (stopRequested && pendingCaptures == 0 && AUTO_STOP) {
                writeSession("failed", null);
                minecraft.stop();
            }
            return;
        }
        applyFixedClock(minecraft);
        applyFixedCamera(minecraft);
        applyFixedClientScene(minecraft);
        if (FIXED_PLAYER_ON_GROUND) {
            // This is deliberately a validation-only input. It keeps Iris's
            // own is_on_ground supplier identical across backend lanes without
            // changing the production uniform supplier or normal gameplay.
            minecraft.player.setOnGround(true);
        }
        if (minecraft.options != null) {
            minecraft.options.pauseOnLostFocus = false;
        }
        if (sceneReadinessRequested() && !sceneReady) {
            enableFlawlessFrames();
            SceneReadinessSample sample = sceneReadinessSample(minecraft);
            sceneReadinessPolls++;
            if (!SCENE_STABILITY.observe(sample, System.nanoTime())) {
                if (sceneReadinessPolls == 1 || sceneReadinessPolls % 120 == 0) {
                    Metallum.LOGGER.info(
                            "[metallum-backend-compare] scene readiness pending:"
                                    + " polls={}, stableFrames={}/{}, stableMillis={}/{},"
                                    + " loadedChunks={}, visibleChunks={}, terrainComplete={},"
                                    + " entities={}, entitySha={}",
                            sceneReadinessPolls,
                            SCENE_STABILITY.stableFrames(),
                            STABLE_SCENE_FRAMES,
                            SCENE_STABILITY.stableMillis(System.nanoTime()),
                            STABLE_SCENE_MILLIS,
                            sample.loadedChunks(),
                            sample.visibleChunks(),
                            sample.terrainComplete(),
                            sample.entityCount(),
                            sample.entitySha256()
                    );
                }
                return;
            }
            sceneStartSample = sample;
            if (!resetIrisAtSceneStart()) {
                if (AUTO_STOP) {
                    writeSession("failed", null);
                    minecraft.stop();
                }
                return;
            }
            sceneReady = true;
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] scene ready; logical timeline starts:"
                            + " polls={}, stableFrames={}, stableMillis={},"
                            + " loadedChunks={}, visibleChunks={}, entities={}, entitySha={}",
                    sceneReadinessPolls,
                    SCENE_STABILITY.stableFrames(),
                    SCENE_STABILITY.stableMillis(System.nanoTime()),
                    sample.loadedChunks(),
                    sample.visibleChunks(),
                    sample.entityCount(),
                    sample.entitySha256()
            );
        }
        levelFrame++;
        if (!sessionWritten) {
            sessionWritten = true;
            writeSession("running", null);
        }
        if (!irisReloadAttempted && levelFrame == IRIS_RELOAD_FRAME) {
            reloadIris();
        }
        applyScheduledResize(minecraft);
        applyScheduledShaderToggle(minecraft);
        if (dimensionSequenceEnabled()) {
            applyScheduledDimensionSwitchSequence(minecraft);
            observeDimensionSwitchSequence(minecraft);
        } else {
            applyScheduledDimensionSwitch(minecraft);
            observeDimensionSwitch(minecraft);
        }
        if (stopRequested && pendingCaptures == 0 && AUTO_STOP) {
            boolean lifecyclePassed = (RESIZE_REQUEST == null || resizeCompleted)
                    && (SHADER_TOGGLE_REQUEST == null
                    || shaderEnableCompleted)
                    && dimensionLifecyclePassed();
            writeSession(failedCaptures == 0 && lifecyclePassed ? "passed" : "failed", null);
            minecraft.stop();
        }
    }

    public static void afterFrame(final boolean renderLevel, final GameRenderer renderer) {
        if (!ENABLED || !renderLevel || levelFrame < 0 || CAPTURE_FRAMES.isEmpty()) {
            return;
        }
        if (!CAPTURE_FRAMES.contains(levelFrame) || COMPLETED_FRAMES.contains(levelFrame)) {
            return;
        }
        if (RESIZE_REQUEST != null && levelFrame >= RESIZE_REQUEST.frame() && !resizeCompleted) {
            failedCaptures++;
            stopRequested = true;
            writeFailure(
                    levelFrame,
                    new IllegalStateException(
                            "scheduled resize did not complete before capture frame " + levelFrame
                                    + ": expected " + RESIZE_REQUEST.width() + "x" + RESIZE_REQUEST.height()
                                    + ", observed " + currentWindowExtent()
                    )
            );
            return;
        }
        if (SHADER_TOGGLE_REQUEST != null) {
            if (levelFrame >= SHADER_TOGGLE_REQUEST.disableFrame() && !shaderDisableCompleted) {
                failedCaptures++;
                stopRequested = true;
                writeFailure(
                        levelFrame,
                        new IllegalStateException(
                                "scheduled shader disable did not complete before capture frame " + levelFrame
                        )
                );
                return;
            }
            if (levelFrame >= SHADER_TOGGLE_REQUEST.enableFrame() && !shaderEnableCompleted) {
                failedCaptures++;
                stopRequested = true;
                writeFailure(
                        levelFrame,
                        new IllegalStateException(
                                "scheduled shader enable did not complete before capture frame " + levelFrame
                        )
                );
                return;
            }
        }
        capture(renderer, levelFrame);
    }

    /**
     * Runs from {@code GameRenderer.renderLevel}, after Iris has advanced its
     * wall-clock timer at {@code GameRenderer.render} HEAD but before either
     * backend uploads pack uniforms.
     */
    public static void beforeLevelRender() {
        if (!ENABLED || levelFrame < 0 || FIXED_IRIS_FRAME_MILLIS < 0L) {
            return;
        }
        applyFixedIrisSystemTime(levelFrame, FIXED_IRIS_FRAME_MILLIS);
    }

    /**
     * Returns the render interpolation input for the deterministic comparison
     * harness.  A fixed value is an input contract only; normal clients never
     * enter this path because {@code metallum.backend.compare.enabled} is
     * false.
     */
    public static float fixedPartialTick() {
        return FIXED_PARTIAL_TICK;
    }

    static void applyFixedIrisSystemTime(final int frame, final long frameMillis) {
        if (frame < 0 || frameMillis < 0L) {
            throw new IllegalArgumentException("fixed Iris frame and duration must be non-negative");
        }
        long stepNanos = Math.multiplyExact(frameMillis, 1_000_000L);
        SystemTimeUniforms.TIMER.reset();
        SystemTimeUniforms.COUNTER.reset();
        for (int index = 0; index <= frame; index++) {
            SystemTimeUniforms.TIMER.beginFrame(Math.multiplyExact(index, stepNanos));
            SystemTimeUniforms.COUNTER.beginFrame();
        }
    }

    /**
     * Applies the comparison scenario on the integrated-server thread before
     * its first world tick. Freezing later from the render thread is too late:
     * Metal and OpenGL startup cost can otherwise advance entities, random
     * ticks and weather by different amounts before the first comparable
     * frame.
     */
    public static void configureIntegratedServer(final IntegratedServer server) {
        if (!ENABLED || integratedServerConfigured) {
            return;
        }
        if (FREEZE_SIMULATION) {
            server.tickRateManager().setFrozen(true);
        }
        applyFixedClockOnServer(server);
        if (FIXED_WEATHER == FixedWeather.CLEAR) {
            server.setWeatherParameters(Integer.MAX_VALUE, 0, false, false);
        }
        integratedServerConfigured = true;
        Metallum.LOGGER.info(
                "[metallum-backend-compare] integrated-server scenario configured:"
                        + " frozen={}, clock={}, weather={}",
                server.tickRateManager().isFrozen(),
                FIXED_CLOCK_TICKS == Long.MIN_VALUE ? "unchanged" : FIXED_CLOCK_TICKS,
                FIXED_WEATHER.propertyValue
        );
    }

    /**
     * Applies the one-shot dimension request on the integrated-server thread.
     * The client render thread only publishes the request; calling
     * {@code ServerPlayer.teleport} from that thread would make the receipt
     * meaningless and can race the server's player list. The server-side
     * request uses the canonical {@link TeleportTransition} path below so the
     * client receives the complete cross-dimension lifecycle.
     */
    public static void applyScheduledDimensionSwitch(final IntegratedServer server) {
        if (dimensionSequenceEnabled()) {
            applyScheduledDimensionSwitchSequence(server);
            return;
        }
        if (!ENABLED || DIMENSION_SWITCH_REQUEST == null || !dimensionSwitchAttempted
                || dimensionSwitchServerApplied || !dimensionSwitchFailure.isEmpty()) {
            return;
        }
        synchronized (DIMENSION_SWITCH_LOCK) {
            if (dimensionSwitchServerApplied || !dimensionSwitchFailure.isEmpty()) {
                return;
            }
            UUID playerUuid = dimensionSwitchPlayerUuid;
            if (playerUuid == null) {
                recordDimensionSwitchFailure("dimension switch request has no player identity");
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                recordDimensionSwitchFailure("integrated server could not find requested player " + playerUuid);
                return;
            }
            ServerLevel target = server.getLevel(DIMENSION_SWITCH_REQUEST.target().levelKey());
            if (target == null) {
                recordDimensionSwitchFailure(
                        "integrated server has no target dimension "
                                + DIMENSION_SWITCH_REQUEST.target().id()
                );
                return;
            }
            String source = player.level().dimension().identifier().toString();
            String targetId = target.dimension().identifier().toString();
            if (source.equals(targetId)) {
                recordDimensionSwitchFailure("requested target already active: " + targetId);
                return;
            }
            double scale = dimensionCoordinateScale(player.level().dimension(), target.dimension());
            ServerPlayer teleported = player.teleport(
                    new TeleportTransition(
                            target,
                            new Vec3(
                                    player.getX() * scale,
                                    player.getY(),
                                    player.getZ() * scale
                            ),
                            player.getDeltaMovement(),
                            player.getYRot(),
                            player.getXRot(),
                            false,
                            false,
                            Set.of(),
                            TeleportTransition.DO_NOTHING
                    )
            );
            if (teleported == null) {
                recordDimensionSwitchFailure(
                        "ServerPlayer.teleport rejected " + source + " -> " + targetId
                );
                return;
            }
            dimensionSwitchServerApplied = true;
            dimensionSwitchSource = source;
            dimensionSwitchServerTarget = targetId;
            dimensionSwitchServerTick = server.getTickCount();
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] dimension switch applied on server tick {}:"
                            + " player={} {} -> {}",
                    dimensionSwitchServerTick,
                    playerUuid,
                    source,
                    targetId
            );
            writeDimensionSwitchReceipt("server-applied");
        }
    }

    private static void applyScheduledDimensionSwitch(final Minecraft minecraft) {
        if (DIMENSION_SWITCH_REQUEST == null || dimensionSwitchAttempted
                || levelFrame < DIMENSION_SWITCH_REQUEST.frame()) {
            return;
        }
        dimensionSwitchAttempted = true;
        dimensionSwitchPlayerUuid = minecraft.player.getUUID();
        dimensionSwitchPipelineBefore = pipelineClass();
        dimensionSwitchGenerationBefore = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
        Metallum.LOGGER.info(
                "[metallum-backend-compare] scheduled dimension switch at level frame {}:"
                        + " player={} {} -> {} pipeline={} generation={}",
                levelFrame,
                dimensionSwitchPlayerUuid,
                currentDimension(minecraft),
                DIMENSION_SWITCH_REQUEST.target().id(),
                dimensionSwitchPipelineBefore,
                dimensionSwitchGenerationBefore
        );
        writeDimensionSwitchReceipt("requested");
    }

    private static boolean dimensionSequenceEnabled() {
        return !DIMENSION_SWITCH_SEQUENCE.isEmpty();
    }

    private static boolean dimensionLifecyclePassed() {
        if (dimensionSequenceEnabled()) {
            return dimensionSequenceIndex == DIMENSION_SWITCH_SEQUENCE.size()
                    && dimensionSequenceFailure.isEmpty();
        }
        return DIMENSION_SWITCH_REQUEST == null || dimensionSwitchCompleted;
    }

    private static DimensionSwitchRequest activeDimensionSequenceRequest() {
        return dimensionSequenceIndex >= DIMENSION_SWITCH_SEQUENCE.size()
                ? null
                : DIMENSION_SWITCH_SEQUENCE.get(dimensionSequenceIndex);
    }

    private static void applyScheduledDimensionSwitchSequence(final IntegratedServer server) {
        DimensionSwitchRequest request = activeDimensionSequenceRequest();
        if (!ENABLED || request == null || !dimensionSequenceAttempted
                || dimensionSequenceServerApplied || !dimensionSequenceFailure.isEmpty()) {
            return;
        }
        synchronized (DIMENSION_SWITCH_LOCK) {
            if (dimensionSequenceServerApplied || !dimensionSequenceFailure.isEmpty()) {
                return;
            }
            UUID playerUuid = dimensionSequencePlayerUuid;
            if (playerUuid == null) {
                recordDimensionSequenceFailure("dimension sequence step has no player identity");
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                recordDimensionSequenceFailure(
                        "integrated server could not find requested player " + playerUuid
                );
                return;
            }
            ServerLevel target = server.getLevel(request.target().levelKey());
            if (target == null) {
                recordDimensionSequenceFailure(
                        "integrated server has no target dimension " + request.target().id()
                );
                return;
            }
            String source = player.level().dimension().identifier().toString();
            String targetId = target.dimension().identifier().toString();
            if (source.equals(targetId)) {
                recordDimensionSequenceFailure(
                        "requested target already active at sequence step " + dimensionSequenceIndex
                                + ": " + targetId
                );
                return;
            }
            double scale = dimensionCoordinateScale(player.level().dimension(), target.dimension());
            ServerPlayer teleported = player.teleport(
                    new TeleportTransition(
                            target,
                            new Vec3(
                                    player.getX() * scale,
                                    player.getY(),
                                    player.getZ() * scale
                            ),
                            player.getDeltaMovement(),
                            player.getYRot(),
                            player.getXRot(),
                            false,
                            false,
                            Set.of(),
                            TeleportTransition.DO_NOTHING
                    )
            );
            if (teleported == null) {
                recordDimensionSequenceFailure(
                        "ServerPlayer.teleport rejected sequence step " + dimensionSequenceIndex
                                + ": " + source + " -> " + targetId
                );
                return;
            }
            dimensionSequenceServerApplied = true;
            dimensionSequenceSource = source;
            dimensionSequenceServerTarget = targetId;
            dimensionSequenceServerTick = server.getTickCount();
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] dimension sequence step {} applied on server tick {}:"
                            + " player={} {} -> {}",
                    dimensionSequenceIndex,
                    dimensionSequenceServerTick,
                    playerUuid,
                    source,
                    targetId
            );
            writeDimensionSequenceReceipt("server-applied");
        }
    }

    private static void applyScheduledDimensionSwitchSequence(final Minecraft minecraft) {
        DimensionSwitchRequest request = activeDimensionSequenceRequest();
        if (request == null || dimensionSequenceAttempted || levelFrame < request.frame()) {
            return;
        }
        dimensionSequenceAttempted = true;
        dimensionSequencePlayerUuid = minecraft.player.getUUID();
        dimensionSequencePipelineBefore = pipelineClass();
        dimensionSequenceGenerationBefore = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
        Metallum.LOGGER.info(
                "[metallum-backend-compare] scheduled dimension sequence step {} at level frame {}:"
                        + " player={} {} -> {} pipeline={} generation={}",
                dimensionSequenceIndex,
                levelFrame,
                dimensionSequencePlayerUuid,
                currentDimension(minecraft),
                request.target().id(),
                dimensionSequencePipelineBefore,
                dimensionSequenceGenerationBefore
        );
        writeDimensionSequenceReceipt("requested");
    }

    private static void observeDimensionSwitchSequence(final Minecraft minecraft) {
        DimensionSwitchRequest request = activeDimensionSequenceRequest();
        if (request == null || !dimensionSequenceAttempted || !dimensionSequenceServerApplied
                || minecraft.level == null) {
            return;
        }
        String observedDimension = currentDimension(minecraft);
        if (!request.target().id().equals(observedDimension)) {
            return;
        }
        String pipeline = pipelineClass();
        int generation = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
        boolean semanticPipeline =
                Iris.getIrisConfig().areShadersEnabled()
                        && Iris.getCurrentPack().isPresent()
                        && Iris.getPipelineManager().getPipelineNullable()
                        instanceof com.metallum.client.metal.render.MetalWorldRenderingPipeline;
        if (!semanticPipeline || generation < 0 || generation == dimensionSequenceGenerationBefore) {
            return;
        }
        dimensionSequenceClientTarget = observedDimension;
        dimensionSequencePipelineAfter = pipeline;
        dimensionSequenceGenerationAfter = generation;
        dimensionSequenceObservedFrame = levelFrame;
        DIMENSION_SEQUENCE_RECEIPTS.add(
                new DimensionSwitchReceipt(
                        dimensionSequenceIndex,
                        request.frame(),
                        request.target().id(),
                        dimensionSequenceSource,
                        dimensionSequenceServerTarget,
                        dimensionSequenceClientTarget,
                        dimensionSequenceServerTick,
                        dimensionSequenceObservedFrame,
                        dimensionSequenceGenerationBefore,
                        dimensionSequenceGenerationAfter,
                        dimensionSequencePipelineBefore,
                        dimensionSequencePipelineAfter,
                        "completed",
                        ""
                )
        );
        Metallum.LOGGER.info(
                "[metallum-backend-compare] dimension sequence step {} observed at level frame {}:"
                        + " {} generation {} -> {} pipeline={}",
                dimensionSequenceIndex,
                levelFrame,
                observedDimension,
                dimensionSequenceGenerationBefore,
                dimensionSequenceGenerationAfter,
                dimensionSequencePipelineAfter
        );
        dimensionSequenceIndex++;
        resetDimensionSequenceStep();
        writeDimensionSequenceReceipt("completed");
    }

    private static void resetDimensionSequenceStep() {
        dimensionSequenceAttempted = false;
        dimensionSequenceServerApplied = false;
        dimensionSequencePlayerUuid = null;
        dimensionSequenceSource = "";
        dimensionSequenceServerTarget = "";
        dimensionSequenceClientTarget = "";
        dimensionSequencePipelineBefore = null;
        dimensionSequencePipelineAfter = null;
        dimensionSequenceGenerationBefore = -1;
        dimensionSequenceGenerationAfter = -1;
        dimensionSequenceObservedFrame = -1;
        dimensionSequenceServerTick = -1;
    }

    private static void recordDimensionSequenceFailure(final String message) {
        dimensionSequenceFailure = message;
        failedCaptures++;
        stopRequested = true;
        DimensionSwitchRequest request = activeDimensionSequenceRequest();
        DIMENSION_SEQUENCE_RECEIPTS.add(
                new DimensionSwitchReceipt(
                        dimensionSequenceIndex,
                        request == null ? -1 : request.frame(),
                        request == null ? "" : request.target().id(),
                        dimensionSequenceSource,
                        dimensionSequenceServerTarget,
                        dimensionSequenceClientTarget,
                        dimensionSequenceServerTick,
                        dimensionSequenceObservedFrame,
                        dimensionSequenceGenerationBefore,
                        dimensionSequenceGenerationAfter,
                        dimensionSequencePipelineBefore,
                        dimensionSequencePipelineAfter,
                        "failed",
                        message
                )
        );
        Metallum.LOGGER.error("[metallum-backend-compare] {}", message);
        writeDimensionSequenceReceipt("failed");
    }

    private static void writeDimensionSequenceReceipt(final String status) {
        try {
            Path directory = ROOT.resolve(backendName());
            Files.createDirectories(directory);
            StringBuilder json = new StringBuilder("{\n")
                    .append("  \"schema\": 1,\n")
                    .append("  \"status\": \"").append(jsonEscape(status)).append("\",\n")
                    .append("  \"requestedSteps\": ").append(DIMENSION_SWITCH_SEQUENCE.size()).append(",\n")
                    .append("  \"completedSteps\": ").append(dimensionSequenceIndex).append(",\n")
                    .append("  \"failure\": ")
                    .append(jsonStringOrNull(dimensionSequenceFailure.isEmpty() ? null : dimensionSequenceFailure))
                    .append(",\n  \"steps\": [\n");
            for (int index = 0; index < DIMENSION_SEQUENCE_RECEIPTS.size(); index++) {
                DimensionSwitchReceipt receipt = DIMENSION_SEQUENCE_RECEIPTS.get(index);
                json.append("    {")
                        .append("\"index\": ").append(receipt.index())
                        .append(", \"requestedFrame\": ").append(receipt.requestedFrame())
                        .append(", \"target\": \"").append(jsonEscape(receipt.target())).append('\"')
                        .append(", \"source\": \"").append(jsonEscape(receipt.source())).append('\"')
                        .append(", \"serverTarget\": \"").append(jsonEscape(receipt.serverTarget())).append('\"')
                        .append(", \"clientTarget\": \"").append(jsonEscape(receipt.clientTarget())).append('\"')
                        .append(", \"serverTick\": ").append(receipt.serverTick())
                        .append(", \"observedFrame\": ").append(receipt.observedFrame())
                        .append(", \"generationBefore\": ").append(receipt.generationBefore())
                        .append(", \"generationAfter\": ").append(receipt.generationAfter())
                        .append(", \"pipelineBefore\": ")
                        .append(jsonStringOrNull(receipt.pipelineBefore()))
                        .append(", \"pipelineAfter\": ")
                        .append(jsonStringOrNull(receipt.pipelineAfter()))
                        .append(", \"status\": \"").append(jsonEscape(receipt.status())).append('\"')
                        .append(", \"failure\": ")
                        .append(jsonStringOrNull(receipt.failure().isEmpty() ? null : receipt.failure()))
                        .append("}");
                if (index + 1 < DIMENSION_SEQUENCE_RECEIPTS.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("  ]\n}\n");
            Files.writeString(
                    directory.resolve("dimension-switches.json"),
                    json,
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignoredException) {
            // The runtime log remains the source of the original failure.
        }
    }

    private static void observeDimensionSwitch(final Minecraft minecraft) {
        if (DIMENSION_SWITCH_REQUEST == null || !dimensionSwitchServerApplied
                || dimensionSwitchCompleted || minecraft.level == null) {
            return;
        }
        String observedDimension = currentDimension(minecraft);
        if (!DIMENSION_SWITCH_REQUEST.target().id().equals(observedDimension)) {
            return;
        }
        String pipeline = pipelineClass();
        int generation = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
        boolean semanticPipeline =
                minecraft.level != null
                        && Iris.getIrisConfig().areShadersEnabled()
                        && Iris.getCurrentPack().isPresent()
                        && Iris.getPipelineManager().getPipelineNullable()
                        instanceof com.metallum.client.metal.render.MetalWorldRenderingPipeline;
        if (!semanticPipeline || generation < 0 || generation == dimensionSwitchGenerationBefore) {
            return;
        }
        dimensionSwitchCompleted = true;
        dimensionSwitchClientTarget = observedDimension;
        dimensionSwitchPipelineAfter = pipeline;
        dimensionSwitchGenerationAfter = generation;
        dimensionSwitchObservedFrame = levelFrame;
        Metallum.LOGGER.info(
                "[metallum-backend-compare] dimension switch observed at level frame {}:"
                        + " {} generation {} -> {} pipeline={}",
                levelFrame,
                observedDimension,
                dimensionSwitchGenerationBefore,
                dimensionSwitchGenerationAfter,
                dimensionSwitchPipelineAfter
        );
        writeDimensionSwitchReceipt("completed");
    }

    private static double dimensionCoordinateScale(
            final net.minecraft.resources.ResourceKey<Level> source,
            final net.minecraft.resources.ResourceKey<Level> target
    ) {
        if (source.equals(Level.OVERWORLD) && target.equals(Level.NETHER)) {
            return 1.0 / 8.0;
        }
        if (source.equals(Level.NETHER) && target.equals(Level.OVERWORLD)) {
            return 8.0;
        }
        return 1.0;
    }

    private static String currentDimension(final Minecraft minecraft) {
        return minecraft == null || minecraft.level == null
                ? ""
                : minecraft.level.dimension().identifier().toString();
    }

    private static String pipelineClass() {
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline == null ? "" : pipeline.getClass().getName();
    }

    private static void recordDimensionSwitchFailure(final String message) {
        dimensionSwitchFailure = message;
        failedCaptures++;
        stopRequested = true;
        Metallum.LOGGER.error("[metallum-backend-compare] {}", message);
        writeDimensionSwitchReceipt("failed");
    }

    private static void writeDimensionSwitchReceipt(final String status) {
        try {
            Path directory = ROOT.resolve(backendName());
            Files.createDirectories(directory);
            String target = DIMENSION_SWITCH_REQUEST == null
                    ? ""
                    : DIMENSION_SWITCH_REQUEST.target().id();
            Files.writeString(
                    directory.resolve("dimension-switch.json"),
                    String.format(
                            Locale.ROOT,
                            "{\n"
                                    + "  \"schema\": 1,\n"
                                    + "  \"status\": \"%s\",\n"
                                    + "  \"requestedFrame\": %d,\n"
                                    + "  \"target\": \"%s\",\n"
                                    + "  \"playerUuid\": \"%s\",\n"
                                    + "  \"source\": \"%s\",\n"
                                    + "  \"serverTarget\": \"%s\",\n"
                                    + "  \"clientTarget\": \"%s\",\n"
                                    + "  \"serverApplied\": %s,\n"
                                    + "  \"clientObserved\": %s,\n"
                                    + "  \"completed\": %s,\n"
                                    + "  \"serverTick\": %d,\n"
                                    + "  \"observedFrame\": %d,\n"
                                    + "  \"generationBefore\": %d,\n"
                                    + "  \"generationAfter\": %d,\n"
                                    + "  \"pipelineBefore\": %s,\n"
                                    + "  \"pipelineAfter\": %s,\n"
                                    + "  \"failure\": %s\n"
                                    + "}\n",
                            jsonEscape(status),
                            DIMENSION_SWITCH_REQUEST == null ? -1 : DIMENSION_SWITCH_REQUEST.frame(),
                            jsonEscape(target),
                            dimensionSwitchPlayerUuid == null ? "" : dimensionSwitchPlayerUuid,
                            jsonEscape(dimensionSwitchSource),
                            jsonEscape(dimensionSwitchServerTarget),
                            jsonEscape(dimensionSwitchClientTarget),
                            dimensionSwitchServerApplied,
                            dimensionSwitchCompleted,
                            dimensionSwitchCompleted,
                            dimensionSwitchServerTick,
                            dimensionSwitchObservedFrame,
                            dimensionSwitchGenerationBefore,
                            dimensionSwitchGenerationAfter,
                            jsonStringOrNull(dimensionSwitchPipelineBefore),
                            jsonStringOrNull(dimensionSwitchPipelineAfter),
                            jsonStringOrNull(dimensionSwitchFailure.isEmpty() ? null : dimensionSwitchFailure)
                    ),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignoredException) {
            // The runtime log remains the source of the original failure.
        }
    }

    private static boolean sceneReadinessRequested() {
        return STABLE_SCENE_FRAMES > 0 || STABLE_SCENE_MILLIS > 0L;
    }

    private static void enableFlawlessFrames() {
        if (flawlessFramesAttempted) {
            return;
        }
        flawlessFramesAttempted = true;
        try {
            FlawlessFrames.getProvider()
                    .apply("metallum-backend-compare")
                    .accept(true);
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] FlawlessFrames enabled for scene readiness"
            );
        } catch (Throwable throwable) {
            Metallum.LOGGER.warn(
                    "[metallum-backend-compare] FlawlessFrames unavailable;"
                            + " readiness still requires idle Sodium terrain",
                    throwable
            );
        }
    }

    private static SceneReadinessSample sceneReadinessSample(final Minecraft minecraft) {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        EntityReceipt entities = entityReceipt(minecraft);
        return new SceneReadinessSample(
                minecraft.level == null
                        ? 0
                        : minecraft.level.getChunkSource().getLoadedChunksCount(),
                renderer == null ? 0 : renderer.getVisibleChunkCount(),
                renderer != null && renderer.isTerrainRenderComplete(),
                entities.count(),
                entities.sha256()
        );
    }

    /**
     * Discards pack history accumulated while chunks were arriving. The
     * logical A/B frame counter starts only after this synchronous reset, so
     * backend startup and shader compilation time cannot become temporal input.
     */
    private static boolean resetIrisAtSceneStart() {
        sceneStartIrisResetAttempted = true;
        String packBefore = Iris.getCurrentPackName();
        try {
            Iris.reload();
            sceneStartIrisResetCompleted = true;
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] Iris scene-start reset completed (pack {})",
                    packBefore
            );
            return true;
        } catch (IOException | RuntimeException exception) {
            failedCaptures++;
            stopRequested = true;
            Metallum.LOGGER.error(
                    "[metallum-backend-compare] Iris scene-start reset failed (pack {})",
                    packBefore,
                    exception
            );
            return false;
        }
    }

    private static void capture(final GameRenderer renderer, final int frame) {
        pendingCaptures++;
        GpuBuffer buffer = null;
        GpuFence fence = null;
        try {
            // Backend/Iris comparisons deliberately require MetalFX OFF, so
            // capture the renderer-owned target directly. Depending on
            // MetalFxManager here made the Iris regression harness part of the
            // optional temporal/presentation implementation it is meant to
            // exclude.
            RenderTarget target = renderer.mainRenderTarget();
            GpuTexture texture = target.getColorTexture();
            if (texture == null) {
                throw new IllegalStateException("present target has no color texture");
            }
            if (texture.getFormat() != GpuFormat.RGBA8_UNORM || texture.getFormat().blockSize() != 4) {
                throw new IllegalStateException(
                        "comparison requires RGBA8_UNORM, found " + texture.getFormat()
                );
            }
            int width = texture.getWidth(0);
            int height = texture.getHeight(0);
            int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            GpuDevice device = RenderSystem.getDevice();
            buffer = device.createBuffer(
                    () -> "backend comparison frame " + frame,
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    byteCount
            );
            CommandEncoder encoder = device.createCommandEncoder();
            fence = encoder.createFence();
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> { }, 0);
            encoder.submit();
            boolean completed = fence.awaitCompletion(10_000_000_000L);
            if (!completed) {
                throw new IllegalStateException("GPU readback fence timed out");
            }
            writeCapture(frame, target, texture, buffer);
            COMPLETED_FRAMES.add(frame);
            if (COMPLETED_FRAMES.size() == CAPTURE_FRAMES.size()) {
                stopRequested = true;
            }
        } catch (RuntimeException | IOException exception) {
            failedCaptures++;
            stopRequested = true;
            writeFailure(frame, exception);
        } finally {
            if (fence != null) {
                fence.close();
            }
            if (buffer != null) {
                buffer.close();
            }
            pendingCaptures--;
        }
    }

    /**
     * Pins every world clock in an isolated comparison save. Minecraft 26.2
     * decouples daylight from the legacy level game-time counter, so restoring
     * identical save bytes alone is insufficient when two backends reach the
     * same render frame at different wall-clock rates.
     */
    private static void applyFixedClock(final Minecraft minecraft) {
        if (fixedClockApplied || FIXED_CLOCK_TICKS == Long.MIN_VALUE) {
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.executeBlocking(() -> applyFixedClockOnServer(server));
        Metallum.LOGGER.info(
                "[metallum-backend-compare] fixed all integrated-server world clocks at {} ticks",
                FIXED_CLOCK_TICKS
        );
    }

    private static void applyFixedClockOnServer(final IntegratedServer server) {
        if (fixedClockApplied || FIXED_CLOCK_TICKS == Long.MIN_VALUE) {
            return;
        }
        var registry = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
        registry.stream().forEach(clock -> {
            var holder = registry.wrapAsHolder(clock);
            server.clockManager().setTotalTicks(holder, FIXED_CLOCK_TICKS);
            server.clockManager().setPaused(holder, true);
        });
        server.forceGameTimeSynchronization();
        fixedClockApplied = true;
    }

    /**
     * Keeps client interpolation state equal to the server-side fixed scene.
     * The server owns the durable weather choice; these assignments remove the
     * old/current rain fade that can otherwise depend on client tick count.
     */
    private static void applyFixedClientScene(final Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        if (FREEZE_SIMULATION) {
            minecraft.level.tickRateManager().setFrozen(true);
        }
        if (FIXED_WEATHER == FixedWeather.CLEAR) {
            minecraft.level.setRainLevel(0.0F);
            minecraft.level.setThunderLevel(0.0F);
        }
    }

    /**
     * Pins the client camera at render-frame granularity. A restored player
     * file is not sufficient for an A/B capture because native window mouse
     * events can alter yaw and pitch independently in the two launches.
     */
    private static void applyFixedCamera(final Minecraft minecraft) {
        if (FIXED_CAMERA == null) {
            return;
        }
        Vec3 position = new Vec3(FIXED_CAMERA.x(), FIXED_CAMERA.y(), FIXED_CAMERA.z());
        minecraft.player.setOldPosAndRot(position, FIXED_CAMERA.yaw(), FIXED_CAMERA.pitch());
        minecraft.player.setPos(position);
        minecraft.player.setYRot(FIXED_CAMERA.yaw());
        minecraft.player.setXRot(FIXED_CAMERA.pitch());
        minecraft.player.setYHeadRot(FIXED_CAMERA.yaw());
        minecraft.player.setYBodyRot(FIXED_CAMERA.yaw());
        minecraft.player.setDeltaMovement(Vec3.ZERO);
    }

    private static void reloadIris() {
        irisReloadAttempted = true;
        String packBefore = Iris.getCurrentPackName();
        try {
            Iris.reload();
            irisReloadCompleted = true;
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] Iris reload completed at level frame {} (pack {} -> {})",
                    levelFrame,
                    packBefore,
                    Iris.getCurrentPackName()
            );
        } catch (IOException | RuntimeException exception) {
            failedCaptures++;
            stopRequested = true;
            writeFailure(levelFrame, exception);
            Metallum.LOGGER.error(
                    "[metallum-backend-compare] Iris reload failed at level frame {}",
                    levelFrame,
                    exception
            );
        }
        writeSession("running", null);
    }

    private static void applyScheduledResize(final Minecraft minecraft) {
        if (RESIZE_REQUEST == null) {
            return;
        }
        if (!resizeAttempted && levelFrame >= RESIZE_REQUEST.frame()) {
            resizeAttempted = true;
            var window = minecraft.getWindow();
            int currentWidth = window.getWidth();
            int currentHeight = window.getHeight();
            int logicalWidth = logicalResizeDimension(
                    RESIZE_REQUEST.width(), currentWidth, window.getScreenWidth()
            );
            int logicalHeight = logicalResizeDimension(
                    RESIZE_REQUEST.height(), currentHeight, window.getScreenHeight()
            );
            window.setWindowed(logicalWidth, logicalHeight);
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] scheduled resize requested at level frame {}:"
                            + " framebuffer {}x{} -> {}x{} (windowed {}x{})",
                    levelFrame,
                    currentWidth,
                    currentHeight,
                    RESIZE_REQUEST.width(),
                    RESIZE_REQUEST.height(),
                    logicalWidth,
                    logicalHeight
            );
        }
        if (!resizeCompleted) {
            var window = minecraft.getWindow();
            if (window.getWidth() == RESIZE_REQUEST.width()
                    && window.getHeight() == RESIZE_REQUEST.height()) {
                resizeCompleted = true;
                resizeObservedWidth = window.getWidth();
                resizeObservedHeight = window.getHeight();
                Metallum.LOGGER.info(
                        "[metallum-backend-compare] scheduled resize completed at level frame {}: {}",
                        levelFrame,
                        currentWindowExtent()
                );
                writeSession("running", null);
            }
        }
    }

    private static int logicalResizeDimension(final int framebufferDimension,
                                              final int currentFramebufferDimension,
                                              final int currentLogicalDimension) {
        if (currentFramebufferDimension <= 0 || currentLogicalDimension <= 0) {
            return framebufferDimension;
        }
        double backingScale = (double) currentFramebufferDimension / currentLogicalDimension;
        if (!Double.isFinite(backingScale) || backingScale <= 0.0) {
            return framebufferDimension;
        }
        return Math.max(1, (int) Math.round(framebufferDimension / backingScale));
    }

    private static String currentWindowExtent() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return "<unavailable>";
        }
        return minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight();
    }

    private static void applyScheduledShaderToggle(final Minecraft minecraft) {
        if (SHADER_TOGGLE_REQUEST == null) {
            return;
        }
        if (!shaderDisableAttempted && levelFrame >= SHADER_TOGGLE_REQUEST.disableFrame()) {
            shaderDisableAttempted = true;
            int generationBefore = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
            try {
                Iris.toggleShaders(minecraft, false);
                shaderDisableGeneration = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
                shaderDisableCompleted = !Iris.getIrisConfig().areShadersEnabled()
                        && Iris.getCurrentPack().isEmpty()
                        && shaderDisableGeneration < 0
                        && Iris.getPipelineManager().getPipelineNullable()
                        instanceof net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
                Metallum.LOGGER.info(
                        "[metallum-backend-compare] scheduled shader disable at level frame {}:"
                                + " generation {} -> {}, completed={}",
                        levelFrame,
                        generationBefore,
                        shaderDisableGeneration,
                        shaderDisableCompleted
                );
            } catch (IOException | RuntimeException exception) {
                failedCaptures++;
                stopRequested = true;
                writeFailure(levelFrame, exception);
                Metallum.LOGGER.error(
                        "[metallum-backend-compare] scheduled shader disable failed at level frame {}",
                        levelFrame,
                        exception
                );
            }
            writeSession("running", null);
        }
        if (shaderDisableCompleted
                && !shaderEnableAttempted
                && levelFrame >= SHADER_TOGGLE_REQUEST.enableFrame()) {
            shaderEnableAttempted = true;
            int generationBefore = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
            try {
                Iris.toggleShaders(minecraft, true);
                shaderEnableGeneration = IrisMetalPipelineOverrides.activeGenerationForDiagnostics();
                shaderEnableCompleted = Iris.getIrisConfig().areShadersEnabled()
                        && Iris.getCurrentPack().isPresent()
                        && shaderEnableGeneration >= 0
                        && Iris.getPipelineManager().getPipelineNullable()
                        instanceof com.metallum.client.metal.render.MetalWorldRenderingPipeline;
                Metallum.LOGGER.info(
                        "[metallum-backend-compare] scheduled shader enable at level frame {}:"
                                + " generation {} -> {}, completed={}",
                        levelFrame,
                        generationBefore,
                        shaderEnableGeneration,
                        shaderEnableCompleted
                );
            } catch (IOException | RuntimeException exception) {
                failedCaptures++;
                stopRequested = true;
                writeFailure(levelFrame, exception);
                Metallum.LOGGER.error(
                        "[metallum-backend-compare] scheduled shader enable failed at level frame {}",
                        levelFrame,
                        exception
                );
            }
            writeSession("running", null);
        }
    }

    private static void writeCapture(
            final int frame,
            final RenderTarget target,
            final GpuTexture texture,
            final GpuBuffer buffer
    ) throws IOException {
        byte[] bytes;
        try (GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
            ByteBuffer data = mapped.data().duplicate();
            data.clear();
            bytes = new byte[data.remaining()];
            data.get(bytes);
        }
        String backend = backendName();
        Path directory = ROOT.resolve(backend);
        Files.createDirectories(directory);
        String stem = String.format(Locale.ROOT, "frame-%05d", frame);
        Files.write(directory.resolve(stem + ".bin"), bytes);
        writePng(directory.resolve(stem + ".png"), bytes, texture.getWidth(0), texture.getHeight(0));
        Files.writeString(
                directory.resolve(stem + ".json"),
                captureJson(frame, target, texture, bytes.length, backend),
                StandardCharsets.UTF_8
        );
        EntityReceipt entities = entityReceipt(Minecraft.getInstance());
        Files.write(
                directory.resolve(stem + "-entities.txt"),
                entities.states(),
                StandardCharsets.UTF_8
        );
    }

    private static void writePng(final Path path, final byte[] bytes, final int width, final int height)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = bytes[offset] & 0xff;
                int green = bytes[offset + 1] & 0xff;
                int blue = bytes[offset + 2] & 0xff;
                // MainTarget alpha is not part of the presented desktop image.
                // In particular, Iris/OpenGL leaves it non-opaque, which makes
                // image viewers composite an otherwise valid RGB readback
                // against their own background. Keep the exact RGBA bytes in
                // the sibling .bin, but make the inspection PNG unambiguously
                // represent the presented RGB channels.
                image.setRGB(x, y, 0xff000000 | (red << 16) | (green << 8) | blue);
                offset += 4;
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static String captureJson(
            final int frame,
            final RenderTarget target,
            final GpuTexture texture,
            final int byteCount,
            final String backend
        ) {
        Minecraft minecraft = Minecraft.getInstance();
        String observedOverworldClock = minecraft.level == null
                ? "null"
                : Long.toString(minecraft.level.getOverworldClockTime());
        String observedDefaultClock = minecraft.level == null
                ? "null"
                : Long.toString(minecraft.level.getDefaultClockTime());
        IntegratedServer server = minecraft.getSingleplayerServer();
        String serverTickCount = server == null ? "null" : Integer.toString(server.getTickCount());
        String serverSimulationFrozen = server == null
                ? "null"
                : Boolean.toString(server.tickRateManager().isFrozen());
        String clientSimulationFrozen = minecraft.level == null
                ? "null"
                : Boolean.toString(minecraft.level.tickRateManager().isFrozen());
        String observedRainLevel = minecraft.level == null
                ? "null"
                : String.format(Locale.ROOT, "%.9g", minecraft.level.getRainLevel(1.0F));
        String observedThunderLevel = minecraft.level == null
                ? "null"
                : String.format(Locale.ROOT, "%.9g", minecraft.level.getThunderLevel(1.0F));
        SceneReadinessSample scene = sceneReadinessSample(minecraft);
        String sceneStartLoadedChunks = sceneStartSample == null
                ? "null"
                : Integer.toString(sceneStartSample.loadedChunks());
        String sceneStartVisibleChunks = sceneStartSample == null
                ? "null"
                : Integer.toString(sceneStartSample.visibleChunks());
        String sceneStartEntityCount = sceneStartSample == null
                ? "null"
                : Integer.toString(sceneStartSample.entityCount());
        String sceneStartEntitySha = sceneStartSample == null
                ? ""
                : sceneStartSample.entitySha256();
        String fixedCamera = FIXED_CAMERA == null ? "null" : FIXED_CAMERA.json();
        String actualGameDirectory = actualGameDirectory(minecraft);
        String actualWorkingDirectory = actualWorkingDirectory();
        String actualPlayerName = actualPlayerName(minecraft);
        String actualPlayerUuid = actualPlayerUuid(minecraft);
        String observedPlayer = minecraft.player == null
                ? "null"
                : String.format(
                        Locale.ROOT,
                        "{\"x\":%.17g,\"y\":%.17g,\"z\":%.17g,\"yaw\":%.9g,\"pitch\":%.9g}",
                        minecraft.player.getX(),
                        minecraft.player.getY(),
                        minecraft.player.getZ(),
                        minecraft.player.getYRot(),
                        minecraft.player.getXRot()
                );
        IrisRuntimeReceipt iris = irisRuntimeReceipt();
        return String.format(
                Locale.ROOT,
                "{\n"
                        + "  \"schema\": 1,\n"
                        + "  \"backend\": \"%s\",\n"
                        + "  \"backendDescription\": \"%s\",\n"
                        + "  \"deviceBackend\": \"%s\",\n"
                        + "  \"scenarioId\": \"%s\",\n"
                        + "  \"worldName\": \"%s\",\n"
                        + "  \"worldSnapshotSha256\": \"%s\",\n"
                        + "  \"requestedGameDirectory\": \"%s\",\n"
                        + "  \"gameDirectory\": \"%s\",\n"
                        + "  \"workingDirectory\": \"%s\",\n"
                        + "  \"requestedPlayerName\": \"%s\",\n"
                        + "  \"requestedPlayerUuid\": \"%s\",\n"
                        + "  \"playerName\": \"%s\",\n"
                        + "  \"playerUuid\": \"%s\",\n"
                        + "  \"frame\": %d,\n"
                        + "  \"width\": %d,\n"
                        + "  \"height\": %d,\n"
                        + "  \"format\": \"%s\",\n"
                        + "  \"bytes\": %d,\n"
                        + "  \"targetLabel\": \"%s\",\n"
                        + "  \"rowOrder\": \"backend-native-copy-order\",\n"
                        + "  \"pngAlpha\": \"forced-opaque; raw RGBA retained in .bin\",\n"
                        + "  \"hudRequested\": %s,\n"
                        + "  \"irisSemanticRequested\": %s,\n"
                        + "  \"irisShadersEnabled\": %s,\n"
                        + "  \"irisPackPresent\": %s,\n"
                        + "  \"irisPackName\": %s,\n"
                        + "  \"irisPipelineClass\": %s,\n"
                        + "  \"irisMetalGeneration\": %d,\n"
                        + "  \"metalFxMode\": \"%s\",\n"
                        + "  \"frameGenerationRequested\": %s,\n"
                        + "  \"objectMotionProducerRequested\": %s,\n"
                        + "  \"fixedClockTicks\": %s,\n"
                        + "  \"observedOverworldClockTicks\": %s,\n"
                        + "  \"observedDefaultClockTicks\": %s,\n"
                        + "  \"freezeSimulationRequested\": %s,\n"
                        + "  \"integratedServerScenarioConfigured\": %s,\n"
                        + "  \"serverSimulationFrozen\": %s,\n"
                        + "  \"clientSimulationFrozen\": %s,\n"
                        + "  \"serverTickCount\": %s,\n"
                        + "  \"fixedWeather\": \"%s\",\n"
                        + "  \"observedRainLevel\": %s,\n"
                        + "  \"observedThunderLevel\": %s,\n"
                        + "  \"sceneReadinessRequested\": %s,\n"
                        + "  \"stableSceneFramesRequired\": %d,\n"
                        + "  \"stableSceneMillisRequired\": %d,\n"
                        + "  \"sceneReady\": %s,\n"
                        + "  \"sceneStartIrisResetAttempted\": %s,\n"
                        + "  \"sceneStartIrisResetCompleted\": %s,\n"
                        + "  \"loadedChunkCount\": %d,\n"
                        + "  \"visibleChunkCount\": %d,\n"
                        + "  \"terrainRenderComplete\": %s,\n"
                        + "  \"sceneStartLoadedChunkCount\": %s,\n"
                        + "  \"sceneStartVisibleChunkCount\": %s,\n"
                        + "  \"sceneStartEntityCount\": %s,\n"
                        + "  \"sceneStartEntityStateSha256\": \"%s\",\n"
                        + "  \"renderEntityCount\": %d,\n"
                        + "  \"renderEntityStateSha256\": \"%s\",\n"
                        + "  \"irisFrameCounter\": %d,\n"
                        + "  \"irisFrameTime\": %.9g,\n"
                        + "  \"irisFrameTimeCounter\": %.9g,\n"
                        + "  \"fixedIrisFrameMillis\": %s,\n"
                        + "  \"fixedCamera\": %s,\n"
                        + "  \"observedPlayer\": %s\n"
                        + "}\n",
                jsonEscape(backend),
                jsonEscape(RenderSystem.getBackendDescription()),
                jsonEscape(RenderSystem.getDevice().getDeviceInfo().backendName()),
                jsonEscape(SCENARIO_ID),
                jsonEscape(WORLD_NAME),
                jsonEscape(WORLD_SNAPSHOT_SHA256),
                jsonEscape(REQUESTED_GAME_DIRECTORY),
                jsonEscape(actualGameDirectory),
                jsonEscape(actualWorkingDirectory),
                jsonEscape(REQUESTED_PLAYER_NAME),
                jsonEscape(REQUESTED_PLAYER_UUID),
                jsonEscape(actualPlayerName),
                jsonEscape(actualPlayerUuid),
                frame,
                texture.getWidth(0),
                texture.getHeight(0),
                texture.getFormat(),
                byteCount,
                jsonEscape(target.getClass().getSimpleName()),
                Boolean.getBoolean("metallum.metal.hud"),
                Boolean.getBoolean("metallum.iris.semantic"),
                iris.shadersEnabled(),
                iris.packPresent(),
                jsonStringOrNull(iris.packName()),
                jsonStringOrNull(iris.pipelineClass()),
                iris.metalGeneration(),
                jsonEscape(System.getProperty("metallum.metalfx.mode", "unspecified")),
                Boolean.getBoolean("metallum.metalfx.frameGeneration"),
                Boolean.getBoolean("metallum.metalfx.objectMotionProducer"),
                FIXED_CLOCK_TICKS == Long.MIN_VALUE ? "null" : Long.toString(FIXED_CLOCK_TICKS),
                observedOverworldClock,
                observedDefaultClock,
                FREEZE_SIMULATION,
                integratedServerConfigured,
                serverSimulationFrozen,
                clientSimulationFrozen,
                serverTickCount,
                jsonEscape(FIXED_WEATHER.propertyValue),
                observedRainLevel,
                observedThunderLevel,
                sceneReadinessRequested(),
                STABLE_SCENE_FRAMES,
                STABLE_SCENE_MILLIS,
                !sceneReadinessRequested() || sceneReady,
                sceneStartIrisResetAttempted,
                sceneStartIrisResetCompleted,
                scene.loadedChunks(),
                scene.visibleChunks(),
                scene.terrainComplete(),
                sceneStartLoadedChunks,
                sceneStartVisibleChunks,
                sceneStartEntityCount,
                jsonEscape(sceneStartEntitySha),
                scene.entityCount(),
                scene.entitySha256(),
                SystemTimeUniforms.COUNTER.getAsInt(),
                SystemTimeUniforms.TIMER.getLastFrameTime(),
                SystemTimeUniforms.TIMER.getFrameTimeCounter(),
                FIXED_IRIS_FRAME_MILLIS < 0L
                        ? "null"
                        : Long.toString(FIXED_IRIS_FRAME_MILLIS),
                fixedCamera,
                observedPlayer
        );
    }

    private static void writeFailure(final int frame, final Exception exception) {
        try {
            Path directory = ROOT.resolve(backendName());
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve(String.format(Locale.ROOT, "frame-%05d.error.txt", frame)),
                    exception.toString() + "\n",
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignored) {
            // The original exception is already visible in the client log.
        }
    }

    private static void writeSession(final String status, final String ignored) {
        try {
            Path directory = ROOT.resolve(backendName());
            Files.createDirectories(directory);
            IrisRuntimeReceipt iris = irisRuntimeReceipt();
            String actualGameDirectory = actualGameDirectory(Minecraft.getInstance());
            String actualWorkingDirectory = actualWorkingDirectory();
            String actualPlayerName = actualPlayerName(Minecraft.getInstance());
            String actualPlayerUuid = actualPlayerUuid(Minecraft.getInstance());
            Files.writeString(
                    directory.resolve("session.json"),
                    String.format(
                            Locale.ROOT,
                            "{\n  \"schema\": 1,\n  \"status\": \"%s\",\n"
                                    + "  \"backend\": \"%s\",\n  \"requestedFrames\": %s,\n"
                                    + "  \"deviceBackend\": \"%s\",\n"
                                    + "  \"scenarioId\": \"%s\",\n"
                                    + "  \"worldName\": \"%s\",\n"
                                    + "  \"worldSnapshotSha256\": \"%s\",\n"
                                    + "  \"requestedGameDirectory\": \"%s\",\n"
                                    + "  \"gameDirectory\": \"%s\",\n"
                                    + "  \"workingDirectory\": \"%s\",\n"
                                    + "  \"requestedPlayerName\": \"%s\",\n"
                                    + "  \"requestedPlayerUuid\": \"%s\",\n"
                                    + "  \"playerName\": \"%s\",\n"
                                    + "  \"playerUuid\": \"%s\",\n"
                                    + "  \"irisSemanticRequested\": %s,\n"
                                    + "  \"irisShadersEnabled\": %s,\n"
                                    + "  \"irisPackPresent\": %s,\n"
                                    + "  \"irisPackName\": %s,\n"
                                    + "  \"irisPipelineClass\": %s,\n"
                                    + "  \"irisMetalGeneration\": %d,\n"
                                    + "  \"metalFxMode\": \"%s\",\n"
                                    + "  \"frameGenerationRequested\": %s,\n"
                                    + "  \"objectMotionProducerRequested\": %s,\n"
                                    + "  \"completedFrames\": %s,\n  \"failedCaptures\": %d,\n"
                                    + "  \"irisReloadFrame\": %d,\n"
                                    + "  \"irisReloadAttempted\": %s,\n"
                                    + "  \"irisReloadCompleted\": %s,\n"
                                    + "  \"resizeFrame\": %d,\n"
                                    + "  \"resizeWidth\": %d,\n"
                                    + "  \"resizeHeight\": %d,\n"
                                    + "  \"resizeAttempted\": %s,\n"
                                    + "  \"resizeCompleted\": %s,\n"
                                    + "  \"resizeObservedWidth\": %d,\n"
                                    + "  \"resizeObservedHeight\": %d,\n"
                                    + "  \"shaderDisableFrame\": %d,\n"
                                    + "  \"shaderEnableFrame\": %d,\n"
                                    + "  \"shaderDisableAttempted\": %s,\n"
                                    + "  \"shaderDisableCompleted\": %s,\n"
                                    + "  \"shaderEnableAttempted\": %s,\n"
                                    + "  \"shaderEnableCompleted\": %s,\n"
                                    + "  \"shaderDisableGeneration\": %d,\n"
                                    + "  \"shaderEnableGeneration\": %d,\n"
                                    + "  \"fixedClockTicks\": %s,\n"
                                    + "  \"fixedIrisFrameMillis\": %s,\n"
                                    + "  \"freezeSimulationRequested\": %s,\n"
                                    + "  \"fixedWeather\": \"%s\",\n"
                                    + "  \"sceneReadinessRequested\": %s,\n"
                                    + "  \"stableSceneFramesRequired\": %d,\n"
                                    + "  \"stableSceneMillisRequired\": %d,\n"
                                    + "  \"sceneReady\": %s,\n"
                                    + "  \"sceneStartIrisResetAttempted\": %s,\n"
                                    + "  \"sceneStartIrisResetCompleted\": %s,\n"
                                    + "  \"sceneStartLoadedChunkCount\": %s,\n"
                                    + "  \"sceneStartVisibleChunkCount\": %s,\n"
                                    + "  \"sceneStartEntityCount\": %s,\n"
                                    + "  \"sceneStartEntityStateSha256\": %s,\n"
                                    + "  \"fixedCamera\": %s\n}\n",
                            jsonEscape(status),
                            jsonEscape(backendName()),
                            CAPTURE_FRAMES,
                            jsonEscape(RenderSystem.getDevice().getDeviceInfo().backendName()),
                            jsonEscape(SCENARIO_ID),
                            jsonEscape(WORLD_NAME),
                            jsonEscape(WORLD_SNAPSHOT_SHA256),
                            jsonEscape(REQUESTED_GAME_DIRECTORY),
                            jsonEscape(actualGameDirectory),
                            jsonEscape(actualWorkingDirectory),
                            jsonEscape(REQUESTED_PLAYER_NAME),
                            jsonEscape(REQUESTED_PLAYER_UUID),
                            jsonEscape(actualPlayerName),
                            jsonEscape(actualPlayerUuid),
                            Boolean.getBoolean("metallum.iris.semantic"),
                            iris.shadersEnabled(),
                            iris.packPresent(),
                            jsonStringOrNull(iris.packName()),
                            jsonStringOrNull(iris.pipelineClass()),
                            iris.metalGeneration(),
                            jsonEscape(System.getProperty("metallum.metalfx.mode", "unspecified")),
                            Boolean.getBoolean("metallum.metalfx.frameGeneration"),
                            Boolean.getBoolean("metallum.metalfx.objectMotionProducer"),
                            COMPLETED_FRAMES,
                            failedCaptures,
                            IRIS_RELOAD_FRAME,
                            irisReloadAttempted,
                            irisReloadCompleted,
                            RESIZE_REQUEST == null ? -1 : RESIZE_REQUEST.frame(),
                            RESIZE_REQUEST == null ? -1 : RESIZE_REQUEST.width(),
                            RESIZE_REQUEST == null ? -1 : RESIZE_REQUEST.height(),
                            resizeAttempted,
                            resizeCompleted,
                            resizeObservedWidth,
                            resizeObservedHeight,
                            SHADER_TOGGLE_REQUEST == null ? -1 : SHADER_TOGGLE_REQUEST.disableFrame(),
                            SHADER_TOGGLE_REQUEST == null ? -1 : SHADER_TOGGLE_REQUEST.enableFrame(),
                            shaderDisableAttempted,
                            shaderDisableCompleted,
                            shaderEnableAttempted,
                            shaderEnableCompleted,
                            shaderDisableGeneration,
                            shaderEnableGeneration,
                            FIXED_CLOCK_TICKS == Long.MIN_VALUE
                                    ? "null"
                                    : Long.toString(FIXED_CLOCK_TICKS),
                            FIXED_IRIS_FRAME_MILLIS < 0L
                                    ? "null"
                                    : Long.toString(FIXED_IRIS_FRAME_MILLIS),
                            FREEZE_SIMULATION,
                            jsonEscape(FIXED_WEATHER.propertyValue),
                            sceneReadinessRequested(),
                            STABLE_SCENE_FRAMES,
                            STABLE_SCENE_MILLIS,
                            !sceneReadinessRequested() || sceneReady,
                            sceneStartIrisResetAttempted,
                            sceneStartIrisResetCompleted,
                            sceneStartSample == null
                                    ? "null"
                                    : Integer.toString(sceneStartSample.loadedChunks()),
                            sceneStartSample == null
                                    ? "null"
                                    : Integer.toString(sceneStartSample.visibleChunks()),
                            sceneStartSample == null
                                    ? "null"
                                    : Integer.toString(sceneStartSample.entityCount()),
                            sceneStartSample == null
                                    ? "null"
                                    : "\"" + jsonEscape(sceneStartSample.entitySha256()) + "\"",
                            FIXED_CAMERA == null ? "null" : FIXED_CAMERA.json()
                    ),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignoredException) {
            // Diagnostic metadata must not turn a rendered frame into a crash.
        }
    }

    private static boolean validateDirectories(final Minecraft minecraft) {
        if (runtimeIdentityValidated) {
            return runtimeIdentityValid;
        }
        runtimeIdentityValidated = true;
        String actualGame = actualGameDirectory(minecraft);
        String actualWorking = actualWorkingDirectory();
        String actualName = actualPlayerName(minecraft);
        String actualUuid = actualPlayerUuid(minecraft);
        boolean directoriesMatch = REQUESTED_GAME_DIRECTORY.isEmpty()
                || REQUESTED_GAME_DIRECTORY.equals(actualGame)
                && REQUESTED_GAME_DIRECTORY.equals(actualWorking);
        boolean playerMatches = (REQUESTED_PLAYER_NAME.isEmpty()
                || REQUESTED_PLAYER_NAME.equals(actualName))
                && (REQUESTED_PLAYER_UUID.isEmpty()
                || REQUESTED_PLAYER_UUID.equals(actualUuid));
        runtimeIdentityValid = directoriesMatch && playerMatches;
        if (runtimeIdentityValid) {
            Metallum.LOGGER.info(
                    "[metallum-backend-compare] runtime identity verified:"
                            + " requestedDir={}, gameDir={}, workingDir={},"
                            + " requestedPlayer={}/{}, player={}/{}",
                    REQUESTED_GAME_DIRECTORY.isEmpty() ? "<unspecified>" : REQUESTED_GAME_DIRECTORY,
                    actualGame,
                    actualWorking,
                    REQUESTED_PLAYER_NAME.isEmpty() ? "<unspecified>" : REQUESTED_PLAYER_NAME,
                    REQUESTED_PLAYER_UUID.isEmpty() ? "<unspecified>" : REQUESTED_PLAYER_UUID,
                    actualName,
                    actualUuid
            );
            return true;
        }

        IllegalStateException mismatch = new IllegalStateException(
                "Isolated runtime identity mismatch: requestedDir="
                        + REQUESTED_GAME_DIRECTORY + ", gameDir=" + actualGame
                        + ", workingDir=" + actualWorking + ", requestedPlayer="
                        + REQUESTED_PLAYER_NAME + "/" + REQUESTED_PLAYER_UUID
                        + ", player=" + actualName + "/" + actualUuid
        );
        failedCaptures++;
        stopRequested = true;
        writeFailure(-1, mismatch);
        Metallum.LOGGER.error("[metallum-backend-compare] {}", mismatch.getMessage());
        return false;
    }

    private static String actualGameDirectory(final Minecraft minecraft) {
        return canonicalGameDirectory(
                minecraft == null || minecraft.gameDirectory == null
                        ? ""
                        : minecraft.gameDirectory.getPath()
        );
    }

    private static String actualWorkingDirectory() {
        return canonicalGameDirectory(System.getProperty("user.dir", ""));
    }

    private static String actualPlayerName(final Minecraft minecraft) {
        return minecraft == null || minecraft.getUser() == null
                ? ""
                : minecraft.getUser().getName();
    }

    private static String actualPlayerUuid(final Minecraft minecraft) {
        return minecraft == null || minecraft.getUser() == null
                || minecraft.getUser().getProfileId() == null
                ? ""
                : minecraft.getUser().getProfileId().toString();
    }

    static String canonicalGameDirectory(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        try {
            return path.toRealPath().toString();
        } catch (IOException ignored) {
            return path.toString();
        }
    }

    static String canonicalUuid(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return UUID.fromString(value.trim()).toString();
    }

    private static String backendName() {
        String configured = System.getProperty("metallum.backend.compare.name", "").trim();
        if (!configured.isEmpty()) {
            return sanitize(configured);
        }
        String description = RenderSystem.getBackendDescription().toLowerCase(Locale.ROOT);
        if (description.contains("vulkan")) {
            return "vulkan";
        }
        if (description.contains("metal")) {
            return "metal";
        }
        return sanitize(description.isEmpty() ? "unknown" : description);
    }

    private static String sanitize(final String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }

    private static Set<Integer> parseFrames(final String value) {
        LinkedHashSet<Integer> frames = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            try {
                int frame = Integer.parseInt(token.trim());
                if (frame >= 0) {
                    frames.add(frame);
                }
            } catch (NumberFormatException ignored) {
                // A malformed diagnostic selector is ignored; an empty set
                // simply leaves the client running without capture.
            }
        }
        return Set.copyOf(frames);
    }

    static FixedCamera parseFixedCamera(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] components = value.split(",");
        if (components.length != 5) {
            throw new IllegalArgumentException(
                    "fixed-camera requires x,y,z,yaw,pitch, found " + value
            );
        }
        try {
            double x = Double.parseDouble(components[0].trim());
            double y = Double.parseDouble(components[1].trim());
            double z = Double.parseDouble(components[2].trim());
            float yaw = Float.parseFloat(components[3].trim());
            float pitch = Float.parseFloat(components[4].trim());
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("fixed-camera values must be finite: " + value);
            }
            return new FixedCamera(x, y, z, yaw, pitch);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "fixed-camera requires numeric x,y,z,yaw,pitch: " + value,
                    exception
            );
        }
    }

    static FixedWeather parseFixedWeather(final String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "" -> FixedWeather.UNCHANGED;
            case "clear" -> FixedWeather.CLEAR;
            default -> throw new IllegalArgumentException(
                    "fixed-weather must be empty or clear, found " + value
            );
        };
    }

    static float parseFixedPartialTick(final String value) {
        try {
            float parsed = Float.parseFloat(value == null ? "" : value.trim());
            if (!Float.isFinite(parsed) || parsed < 0.0F || parsed > 1.0F) {
                throw new IllegalArgumentException(
                        "fixed-partial-tick must be finite and within [0,1], found " + value
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "fixed-partial-tick must be a finite number within [0,1], found " + value,
                    exception
            );
        }
    }

    static ResizeRequest parseResizeRequest(final int frame, final int width, final int height) {
        if (frame == -1 && width == -1 && height == -1) {
            return null;
        }
        if (frame < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "resize requires frame >= 0 and positive width/height, found "
                            + frame + "," + width + "," + height
            );
        }
        return new ResizeRequest(frame, width, height);
    }

    static ShaderToggleRequest parseShaderToggleRequest(final int disableFrame, final int enableFrame) {
        if (disableFrame == -1 && enableFrame == -1) {
            return null;
        }
        if (disableFrame < 0 || enableFrame <= disableFrame) {
            throw new IllegalArgumentException(
                    "shader toggle requires disableFrame >= 0 and enableFrame > disableFrame, found "
                            + disableFrame + "," + enableFrame
            );
        }
        return new ShaderToggleRequest(disableFrame, enableFrame);
    }

    static DimensionSwitchRequest parseDimensionSwitchRequest(
            final int frame,
            final String target
    ) {
        String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        if (frame == -1 && normalized.isEmpty()) {
            return null;
        }
        if (frame < 0 || normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "dimension switch requires frame >= 0 and a target, found "
                            + frame + "," + target
            );
        }
        DimensionSwitchTarget parsedTarget = switch (normalized) {
            case "overworld", "minecraft:overworld" -> DimensionSwitchTarget.OVERWORLD;
            case "nether", "minecraft:the_nether" -> DimensionSwitchTarget.NETHER;
            case "end", "minecraft:the_end" -> DimensionSwitchTarget.END;
            default -> throw new IllegalArgumentException(
                    "dimension switch target must be overworld, nether or end, found " + target
            );
        };
        return new DimensionSwitchRequest(frame, parsedTarget);
    }

    static List<DimensionSwitchRequest> parseDimensionSwitchSequence(final String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<DimensionSwitchRequest> requests = new ArrayList<>();
        int previousFrame = -1;
        for (String entry : value.split(",")) {
            String token = entry.trim();
            int separator = token.indexOf(':');
            if (separator <= 0 || separator == token.length() - 1) {
                throw new IllegalArgumentException(
                        "dimension-switch-sequence entries require frame:target, found " + entry
                );
            }
            int frame;
            try {
                frame = Integer.parseInt(token.substring(0, separator).trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "dimension-switch-sequence frame must be an integer, found " + entry,
                        exception
                );
            }
            if (frame < 0 || frame <= previousFrame) {
                throw new IllegalArgumentException(
                        "dimension-switch-sequence frames must be strictly increasing, found " + entry
                );
            }
            requests.add(parseDimensionSwitchRequest(frame, token.substring(separator + 1).trim()));
            previousFrame = frame;
        }
        return List.copyOf(requests);
    }

    private static EntityReceipt entityReceipt(final Minecraft minecraft) {
        if (minecraft.level == null) {
            return new EntityReceipt(0, sha256(List.of()), List.of());
        }
        List<String> states = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            states.add(
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                            + "|" + entity.getUUID()
                            + "|" + Double.toHexString(entity.getX())
                            + "|" + Double.toHexString(entity.getY())
                            + "|" + Double.toHexString(entity.getZ())
                            + "|" + Double.toHexString(entity.xOld)
                            + "|" + Double.toHexString(entity.yOld)
                            + "|" + Double.toHexString(entity.zOld)
                            + "|" + Double.toHexString(entity.xo)
                            + "|" + Double.toHexString(entity.yo)
                            + "|" + Double.toHexString(entity.zo)
                            + "|" + Float.toHexString(entity.getYRot())
                            + "|" + Float.toHexString(entity.getXRot())
                            + "|" + Float.toHexString(entity.yRotO)
                            + "|" + Float.toHexString(entity.xRotO)
            );
        }
        states.sort(String::compareTo);
        return new EntityReceipt(states.size(), sha256(states), List.copyOf(states));
    }

    private static String sha256(final List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256 provider", impossible);
        }
    }

    private static String jsonEscape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String jsonStringOrNull(final String value) {
        return value == null ? "null" : "\"" + jsonEscape(value) + "\"";
    }

    private static IrisRuntimeReceipt irisRuntimeReceipt() {
        boolean shadersEnabled = Iris.getIrisConfig().areShadersEnabled();
        boolean packPresent = Iris.getCurrentPack().isPresent();
        // Iris reports the UI status sentinel "(off)" when shader packs are
        // disabled. The receipt describes an active pack identity, so a
        // non-present pack is canonically null in both dormant lanes.
        String packName = packPresent ? Iris.getCurrentPackName() : null;
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        return new IrisRuntimeReceipt(
                shadersEnabled,
                packPresent,
                packName,
                pipeline == null ? null : pipeline.getClass().getName(),
                IrisMetalPipelineOverrides.activeGenerationForDiagnostics()
        );
    }

    record SceneReadinessSample(
            int loadedChunks,
            int visibleChunks,
            boolean terrainComplete,
            int entityCount,
            String entitySha256
    ) {
        boolean eligible() {
            return this.loadedChunks > 0
                    && this.visibleChunks > 0
                    && this.terrainComplete
                    && this.entityCount > 0;
        }
    }

    static final class SceneStabilityTracker {
        private final int requiredFrames;
        private final long requiredNanos;
        private SceneReadinessSample lastSample;
        private int stableFrames;
        private long stableSinceNanos;

        SceneStabilityTracker(final int requiredFrames, final long requiredMillis) {
            if (requiredFrames < 0 || requiredMillis < 0L) {
                throw new IllegalArgumentException(
                        "scene-stability requirements must be non-negative"
                );
            }
            this.requiredFrames = requiredFrames;
            this.requiredNanos = Math.multiplyExact(requiredMillis, 1_000_000L);
        }

        boolean observe(final SceneReadinessSample sample, final long nowNanos) {
            if (!sample.eligible()) {
                this.lastSample = null;
                this.stableFrames = 0;
                this.stableSinceNanos = nowNanos;
                return false;
            }
            if (!sample.equals(this.lastSample)) {
                this.lastSample = sample;
                this.stableFrames = 1;
                this.stableSinceNanos = nowNanos;
            } else {
                this.stableFrames++;
            }
            return this.stableFrames >= Math.max(1, this.requiredFrames)
                    && nowNanos - this.stableSinceNanos >= this.requiredNanos;
        }

        int stableFrames() {
            return this.stableFrames;
        }

        long stableMillis(final long nowNanos) {
            if (this.lastSample == null) {
                return 0L;
            }
            return Math.max(0L, nowNanos - this.stableSinceNanos) / 1_000_000L;
        }
    }

    record FixedCamera(double x, double y, double z, float yaw, float pitch) {
        String json() {
            return String.format(
                    Locale.ROOT,
                    "{\"x\":%.17g,\"y\":%.17g,\"z\":%.17g,\"yaw\":%.9g,\"pitch\":%.9g}",
                    this.x,
                    this.y,
                    this.z,
                    this.yaw,
                    this.pitch
            );
        }
    }

    enum FixedWeather {
        UNCHANGED(""),
        CLEAR("clear");

        private final String propertyValue;

        FixedWeather(final String propertyValue) {
            this.propertyValue = propertyValue;
        }
    }

    record ResizeRequest(int frame, int width, int height) {
    }

    record ShaderToggleRequest(int disableFrame, int enableFrame) {
    }

    record DimensionSwitchRequest(int frame, DimensionSwitchTarget target) {
    }

    record DimensionSwitchReceipt(
            int index,
            int requestedFrame,
            String target,
            String source,
            String serverTarget,
            String clientTarget,
            int serverTick,
            int observedFrame,
            int generationBefore,
            int generationAfter,
            String pipelineBefore,
            String pipelineAfter,
            String status,
            String failure
    ) {
    }

    enum DimensionSwitchTarget {
        OVERWORLD("minecraft:overworld"),
        NETHER("minecraft:the_nether"),
        END("minecraft:the_end");

        private final String id;

        DimensionSwitchTarget(final String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        net.minecraft.resources.ResourceKey<Level> levelKey() {
            return switch (this) {
                case OVERWORLD -> Level.OVERWORLD;
                case NETHER -> Level.NETHER;
                case END -> Level.END;
            };
        }
    }

    private record EntityReceipt(int count, String sha256, List<String> states) {
    }

    private record IrisRuntimeReceipt(
            boolean shadersEnabled,
            boolean packPresent,
            String packName,
            String pipelineClass,
            int metalGeneration
    ) {
    }
}
