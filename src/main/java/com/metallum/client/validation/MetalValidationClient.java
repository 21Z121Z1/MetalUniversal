package com.metallum.client.validation;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalFxManager;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in, input-free Minecraft renderer validation driver.
 *
 * <p>The driver is inactive in normal play. A dedicated Gradle verification
 * task enables it together with Quick Play, controls a client-rendered entity
 * and camera on frame boundaries, writes machine-readable state, and exits
 * without keyboard, mouse, screenshot or Computer Use automation.</p>
 */
public final class MetalValidationClient implements ClientModInitializer {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.validation.enabled");
    private static final int CONTROLLED_ENTITY_ID = -2_147_000_001;
    private static final UUID CONTROLLED_ENTITY_UUID =
            UUID.fromString("7a294d59-ecbe-4b47-b864-66c57a3dbf01");
    // The scripted timeline only starts after the initial chunk meshes and the
    // controlled entity's render section have settled; captures are frame-exact
    // afterwards. Section compilation runs on worker threads, so warm-up frames
    // yield wall-clock time instead of only render-loop iterations.
    private static final int WARMUP_FRAMES = 40;
    private static final long WARMUP_FRAME_SLEEP_MILLIS = 50L;
    // Static-camera hold on the cutout grass scene: only the Halton jitter
    // varies between these frames, so any output delta is temporal
    // instability. 24 consecutive frames cover the full 18-phase cycle.
    // See docs/cutout-shimmer-remediation-2026-07-27.md §8.
    private static final int FLICKER_START_FRAME = 92;
    private static final int FLICKER_END_FRAME = 115;
    // Second hold, against the sky. The sealed validation room has no cleared
    // far plane anywhere, so the grass hold cannot see the foliage/sky
    // silhouette band at all — it measured the sky far-plane motion change as
    // a bit-for-bit no-op. This scene opens the ceiling and suspends a sparse
    // leaf/vine cluster in open sky. See §14 of the remediation doc.
    private static final int SKY_SCENE_FRAME = 118;
    private static final int SKY_FLICKER_START_FRAME = 128;
    private static final int SKY_FLICKER_END_FRAME = 151;
    private static final float SKY_SCENE_PITCH = -50.0F;
    // Pinned FRAMEBUFFER size. All metric thresholds and golden baselines
    // are calibrated at this capture size (the 2x-backing framebuffer of the
    // 854x480 logical window the Gradle task requests via --width/--height).
    private static final int FRAMEBUFFER_WIDTH = 1708;
    private static final int FRAMEBUFFER_HEIGHT = 960;
    private static int warmupFrames;
    private static int frame;
    private static int heldFrames;
    private static int windowResizeAttempts;
    private static int requestedLogicalWidth = FRAMEBUFFER_WIDTH / 2;
    private static int requestedLogicalHeight = FRAMEBUFFER_HEIGHT / 2;
    private static boolean timelineAnchored;
    private static ArmorStand controlledEntity;
    private static Vec3 cameraOrigin;
    private static float cameraYaw;
    private static float cameraPitch;
    private static Path outputDirectory;
    private static Vec3 previousEntityPosition;
    private static final Map<BlockPos, BlockState> OCCLUSION_WALL = new LinkedHashMap<>();
    private static final Map<BlockPos, BlockState> CUTOUT_SCENE = new LinkedHashMap<>();
    private static final StringBuilder FRAME_JSON = new StringBuilder("[\n");

    @Override
    public void onInitializeClient() {
        if (!ENABLED) {
            return;
        }
        outputDirectory = Path.of(
                System.getProperty(
                        "metallum.validation.output",
                        "build/metal-validation/minecraft-client-current"
                )
        ).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Minecraft validation output directory", exception);
        }
        Metallum.LOGGER.info("Automated Minecraft MetalFX validation enabled: {}", outputDirectory);
        try {
            // ReplayMod's FlawlessFrames protocol, implemented by Sodium:
            // while active, every frame builds all pending chunk sections
            // with an unlimited upload budget and blocks until they land.
            // This removes the upload-budget race that otherwise makes scene
            // mutations (occlusion wall, cutout scenes) mesh a frame late
            // depending on the estimator state — the last source of
            // frame-timing nondeterminism in golden captures.
            net.caffeinemc.mods.sodium.client.util.FlawlessFrames.getProvider()
                    .apply("metallum-validation")
                    .accept(true);
            Metallum.LOGGER.info("FlawlessFrames enabled for deterministic chunk building");
        } catch (Throwable t) {
            Metallum.LOGGER.warn("FlawlessFrames unavailable; scene mutations may mesh a frame late", t);
        }
    }

    public static void beforeFrame(final GameRenderer renderer) {
        if (!ENABLED) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (controlledEntity == null || controlledEntity.isRemoved()) {
            installControlledScene(minecraft);
        }
        if (warmupFrames < WARMUP_FRAMES) {
            warmupFrames++;
            holdInitialPose(minecraft);
            sleepForAsyncWork(WARMUP_FRAME_SLEEP_MILLIS);
            return;
        }
        if (!timelineAnchored) {
            // Hold the timeline until the FRAMEBUFFER is the pinned size.
            // The Gradle run passes --width/--height, but macOS window
            // management can zoom or tile the window afterwards, and the
            // backing scale differs by which display the window lands on
            // (built-in Retina 2x vs external 1x) — both change the capture
            // size and make golden runs incomparable. setWindowed takes the
            // LOGICAL size, so on a 2x display the request is halved to land
            // the framebuffer on the target.
            int framebufferWidth = minecraft.getWindow().getWidth();
            int framebufferHeight = minecraft.getWindow().getHeight();
            if (framebufferWidth != FRAMEBUFFER_WIDTH || framebufferHeight != FRAMEBUFFER_HEIGHT) {
                windowResizeAttempts++;
                if (windowResizeAttempts > 200) {
                    throw new IllegalStateException(
                            "Validation framebuffer stuck at " + framebufferWidth + "x" + framebufferHeight
                                    + "; expected " + FRAMEBUFFER_WIDTH + "x" + FRAMEBUFFER_HEIGHT
                    );
                }
                if (windowResizeAttempts % 40 == 1) {
                    boolean retinaBacking = framebufferWidth == requestedLogicalWidth * 2
                            && framebufferHeight == requestedLogicalHeight * 2;
                    requestedLogicalWidth = retinaBacking ? FRAMEBUFFER_WIDTH / 2 : FRAMEBUFFER_WIDTH;
                    requestedLogicalHeight = retinaBacking ? FRAMEBUFFER_HEIGHT / 2 : FRAMEBUFFER_HEIGHT;
                    minecraft.getWindow().setWindowed(requestedLogicalWidth, requestedLogicalHeight);
                }
                holdInitialPose(minecraft);
                sleepForAsyncWork(25L);
                return;
            }
            timelineAnchored = true;
            // A pause screen may already be open if focus was lost before
            // pauseOnLostFocus was cleared; the timeline must start unpaused
            // and unblurred.
            minecraft.gui.setScreen(null);
            // The MetalFX jitter phase and history lineage advance with every
            // rendered frame since the last reset, and the number of frames
            // spent in loading screens and warm-up varies run to run. Anchor
            // both to the timeline start so frame N carries the same subpixel
            // jitter and accumulation depth in every run — a prerequisite for
            // byte-identical golden captures.
            MetalFxManager.resetHistory("validation timeline start");
        }

        // Scene mutations must land in the frame that triggers them (the
        // prioritized Sodium rebuild is only reliably synchronous when the
        // builder is otherwise idle), so the timeline holds — repeating the
        // previous pose without advancing — until pending section builds
        // drain. This keeps transitions frame-exact regardless of how fast
        // startup compilation left the builder queue.
        if (isSceneMutationFrame(frame) && !terrainSettled()) {
            heldFrames++;
            if (heldFrames > 400) {
                throw new IllegalStateException(
                        "Sodium terrain never settled before scene mutation frame " + frame
                                + " (held " + heldFrames + " frames total)"
                );
            }
            applyScenarioPose(minecraft, scenarioPoseFor(frame - 1));
            return;
        }

        if (frame == 38) {
            installOcclusionWall(minecraft);
        } else if (frame == 46) {
            removeOcclusionWall(minecraft);
        } else if (frame == 54) {
            minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
        } else if (frame == 62) {
            minecraft.gui.setScreen(null);
            MetalFxManager.resetHistory("automated validation scene reset");
        } else if (frame == 66) {
            // Deterministic Sodium CUTOUT terrain coverage: an alpha-tested
            // leaves wall fills the view. The prioritized synchronous rebuild
            // meshes it on this frame; the frames before the frame 74 capture
            // let temporal history settle on the new scene.
            installCutoutLeavesScene(minecraft);
        } else if (frame == 75) {
            installCutoutGrassScene(minecraft);
        } else if (frame == SKY_SCENE_FRAME) {
            installCutoutSkyScene(minecraft);
        }

        ScenarioPose pose = scenarioPoseFor(frame);
        String scenario = pose.scenario();
        Vec3 entityPosition = applyScenarioPose(minecraft, pose);
        Vec3 cameraPosition = cameraOrigin.add(horizontalRight(cameraYaw).scale(pose.cameraOffset()));
        double entityOffset = pose.entityOffset();
        double cameraOffset = pose.cameraOffset();

        Vec3 previous = previousEntityPosition == null ? entityPosition : previousEntityPosition;
        MetalFxManager.setValidationFrame(
                frame,
                scenario,
                entityPosition.x,
                entityPosition.y,
                entityPosition.z,
                previous.x,
                previous.y,
                previous.z
        );
        requestFlickerFrameIfDue(
                frame, "cutout_grass_hold",
                FLICKER_START_FRAME, FLICKER_END_FRAME, SKY_SCENE_FRAME - 1);
        requestFlickerFrameIfDue(
                frame, "cutout_sky_hold",
                SKY_FLICKER_START_FRAME, SKY_FLICKER_END_FRAME, 200);
        if (frame < 90) {
            appendFrameState(scenario, cameraPosition, entityPosition, entityOffset, cameraOffset);
        }
        previousEntityPosition = entityPosition;
        frame++;
        if (frame >= SKY_FLICKER_END_FRAME + 3
                && MetalFxManager.validationCapturesPending() == 0
                && !MetalFxManager.flickerSeriesPending()
                && MetalFxManager.flickerMetricCompleted("cutout_grass_hold")
                && MetalFxManager.flickerMetricCompleted("cutout_sky_hold")) {
            int completed = MetalFxManager.validationCapturesCompleted();
            int failures = MetalFxManager.validationCaptureFailures();
            if (completed != 10 || failures != 0) {
                removeOcclusionWall(minecraft);
                removeCutoutScene(minecraft);
                applyPlayerPose(minecraft, cameraOrigin, cameraYaw, cameraPitch);
                finishRunState("failed", completed, failures);
                throw new IllegalStateException(
                        "Automated Minecraft GPU validation failed: completed="
                                + completed + "/10, failures=" + failures
                );
            }
            finishAndStop(minecraft, completed, failures);
        } else if (frame >= 220) {
            throw new IllegalStateException(
                    "Timed out waiting for automated Minecraft GPU readbacks: pending="
                            + MetalFxManager.validationCapturesPending()
            );
        }
    }

    public static void afterFrame(final GameRenderer renderer) {
        // GPU attachment capture is intentionally connected separately in the
        // MetalFX manager after temporal encoding and before present.
    }

    /** Camera/entity placement for one timeline frame, pure in the frame index. */
    private record ScenarioPose(String scenario, double entityOffset, double cameraOffset) {
    }

    private static ScenarioPose scenarioPoseFor(final int timelineFrame) {
        if (timelineFrame < 8) {
            return new ScenarioPose("static_entity_static_camera", 0.0, 0.0);
        }
        if (timelineFrame < 18) {
            return new ScenarioPose("moving_entity_static_camera", (timelineFrame - 7) * 0.04, 0.0);
        }
        if (timelineFrame < 28) {
            return new ScenarioPose("static_entity_moving_camera", 0.40, (timelineFrame - 17) * 0.02);
        }
        if (timelineFrame < 38) {
            return new ScenarioPose(
                    "moving_entity_moving_camera",
                    0.40 + (timelineFrame - 27) * 0.04,
                    0.20 + (timelineFrame - 27) * 0.02
            );
        }
        if (timelineFrame < 46) {
            return new ScenarioPose("occluded_entity", 0.80, 0.40);
        }
        if (timelineFrame < 54) {
            return new ScenarioPose("revealed_entity", 0.80, 0.40);
        }
        if (timelineFrame < 62) {
            return new ScenarioPose("gui_open", 0.80, 0.40);
        }
        if (timelineFrame < 66) {
            return new ScenarioPose("scene_reset", 0.80, 0.40);
        }
        if (timelineFrame < 75) {
            return new ScenarioPose("cutout_leaves", 0.80, 0.40);
        }
        if (timelineFrame < 90) {
            return new ScenarioPose("cutout_grass", 0.80, 0.40);
        }
        // Identical pose to cutout_grass: nothing moves during the hold, so
        // the flicker metric isolates jitter-driven temporal instability.
        if (timelineFrame < SKY_SCENE_FRAME) {
            return new ScenarioPose("cutout_grass_hold", 0.80, 0.40);
        }
        // Pitched up at the opened ceiling. The frames between the scene swap
        // and the hold let temporal history settle after the rotation.
        if (timelineFrame < SKY_FLICKER_START_FRAME) {
            return new ScenarioPose("cutout_sky", 0.80, 0.40);
        }
        return new ScenarioPose("cutout_sky_hold", 0.80, 0.40);
    }

    /**
     * Queues one flicker-series frame. Past {@code endFrame} the closing
     * request repeats until the metric lands, so a single dropped encode
     * cannot hang the finish gate; {@code retryDeadline} bounds that retry so
     * a series that never closes cannot bleed into the next one.
     */
    private static void requestFlickerFrameIfDue(
            final int timelineFrame,
            final String scenario,
            final int startFrame,
            final int endFrame,
            final int retryDeadline
    ) {
        if (timelineFrame < startFrame || timelineFrame > retryDeadline) {
            return;
        }
        if (timelineFrame > endFrame
                && (MetalFxManager.flickerMetricCompleted(scenario)
                || MetalFxManager.flickerSeriesPending())) {
            return;
        }
        MetalFxManager.setFlickerCaptureFrame(
                timelineFrame,
                scenario,
                timelineFrame == startFrame,
                timelineFrame >= endFrame
        );
    }

    /** Frames whose handler mutates terrain and needs the section builder idle. */
    private static boolean isSceneMutationFrame(final int timelineFrame) {
        return timelineFrame == 38 || timelineFrame == 46 || timelineFrame == 66
                || timelineFrame == 75 || timelineFrame == SKY_SCENE_FRAME;
    }

    private static boolean terrainSettled() {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        return renderer == null || renderer.isTerrainRenderComplete();
    }

    /** Applies the pose's camera and entity placement; returns the entity position. */
    private static Vec3 applyScenarioPose(final Minecraft minecraft, final ScenarioPose pose) {
        // The grass plants sit on a ground platform; pitch the camera downward
        // by a fixed amount so their alpha-tested cross models fill the view.
        // The sky scene hangs above the opened ceiling, so it pitches up far
        // enough (70-degree vertical FOV, half-angle 35) to keep the horizon
        // and any distant terrain out of frame: only sky backs the foliage.
        float pitch = cameraPitch;
        if (pose.scenario().startsWith("cutout_grass")) {
            pitch = 15.0F;
        } else if (pose.scenario().startsWith("cutout_sky")) {
            pitch = SKY_SCENE_PITCH;
        }
        Vec3 right = horizontalRight(cameraYaw);
        Vec3 cameraPosition = cameraOrigin.add(right.scale(pose.cameraOffset()));
        minecraft.player.setOldPosAndRot(cameraPosition, cameraYaw, pitch);
        minecraft.player.setPos(cameraPosition);
        minecraft.player.setYRot(cameraYaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.setYHeadRot(cameraYaw);
        minecraft.player.setYBodyRot(cameraYaw);
        Vec3 baseEntity = cameraOrigin.add(horizontalLook(cameraYaw).scale(4.0));
        Vec3 entityPosition = baseEntity.add(right.scale(pose.entityOffset()));
        // old == new: the renderer lerps old→new by partialTick, and the
        // wall-clock partialTick would smear the entity's rendered position
        // nondeterministically between runs. The motion producer keeps the
        // previous frame's captured model matrix, so per-frame deltas — and
        // the expected-motion assertions — are unaffected.
        controlledEntity.setOldPosAndRot(entityPosition, controlledEntity.getYRot(), controlledEntity.getXRot());
        controlledEntity.setPos(entityPosition);
        // The living-entity body yaw converges toward the head with a damped
        // step every tick, so its float value at a given timeline frame
        // encodes the tick count since spawn — which varies run to run and
        // shifts the rendered model by sub-pixel amounts. Pin every lerped
        // rotation channel both current and old.
        controlledEntity.setYRot(0.0F);
        controlledEntity.setXRot(0.0F);
        controlledEntity.yRotO = 0.0F;
        controlledEntity.xRotO = 0.0F;
        controlledEntity.yBodyRot = 0.0F;
        controlledEntity.yBodyRotO = 0.0F;
        controlledEntity.yHeadRot = 0.0F;
        controlledEntity.yHeadRotO = 0.0F;
        return entityPosition;
    }

    /**
     * Pins every world-state source of cross-run variance the captures can
     * see: day/weather cycles (persisted gamerules — after the first run the
     * save carries a frozen sky), random ticks (plant growth in view), mob
     * spawning plus existing strays (silhouettes wandering through frames),
     * and client-side cloud drift (advances with wall-clock ticks). Golden
     * frame comparison requires byte-identical planes, not just the semantic
     * metric gates.
     */
    private static void applyDeterministicWorldState(final Minecraft minecraft) {
        minecraft.options.cloudStatus().set(CloudStatus.OFF);
        // Clouds drift with the client tick counter, and elapsed ticks at a
        // given timeline frame differ run to run — any cloud in view breaks
        // byte-identical captures. Verify the option actually took.
        Metallum.LOGGER.info("Validation cloud status now {}", minecraft.options.getCloudStatus());
        // Validation clients run unfocused under Gradle; without this the
        // pause screen opens on focus loss and its full-screen blur pass
        // changes every captured plane (and pauses the integrated server).
        minecraft.options.pauseOnLostFocus = false;
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            Metallum.LOGGER.warn("No integrated server; validation world determinism not applied");
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            GameRules rules = level.getGameRules();
            rules.set(GameRules.ADVANCE_TIME, false, server);
            rules.set(GameRules.ADVANCE_WEATHER, false, server);
            rules.set(GameRules.SPAWN_MOBS, false, server);
            rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
            WeatherData weather = level.getWeatherData();
            weather.setRaining(false);
            weather.setThundering(false);
            weather.setRainTime(0);
            weather.setThunderTime(0);
            weather.setClearWeatherTime(Integer.MAX_VALUE);
            List<Entity> strays = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ServerPlayer)) {
                    strays.add(entity);
                }
            }
            strays.forEach(Entity::discard);
            Metallum.LOGGER.info(
                    "Validation world determinism applied: cycles frozen, {} stray entities discarded",
                    strays.size()
            );
        });
    }

    private static void installControlledScene(final Minecraft minecraft) {
        applyDeterministicWorldState(minecraft);
        // Quantize the anchor pose so every run derives the identical scene
        // from the saved player state: the block-center X/Z absorbs sub-block
        // drift left by an earlier run, and 45-degree yaw steps absorb save
        // rounding. finishAndStop additionally restores this pose server-side
        // (the authoritative copy for the world save).
        Vec3 loaded = minecraft.player.position();
        cameraOrigin = new Vec3(
                Math.floor(loaded.x) + 0.5,
                Math.round(loaded.y * 2.0) / 2.0,
                Math.floor(loaded.z) + 0.5
        );
        cameraYaw = Math.round(minecraft.player.getYRot() / 45.0F) * 45.0F;
        cameraPitch = 0.0F;
        installSceneClearing(minecraft);
        Vec3 position = cameraOrigin.add(horizontalLook(cameraYaw).scale(4.0));
        ArmorStand armorStand = new ArmorStand(minecraft.level, position.x, position.y, position.z);
        armorStand.setId(CONTROLLED_ENTITY_ID);
        armorStand.setUUID(CONTROLLED_ENTITY_UUID);
        armorStand.setNoGravity(true);
        armorStand.setInvisible(false);
        armorStand.setShowArms(true);
        minecraft.level.addEntity(armorStand);
        controlledEntity = armorStand;
        previousEntityPosition = position;
        Metallum.LOGGER.info(
                "Installed controlled renderer entity id={} uuid={} at {}",
                CONTROLLED_ENTITY_ID,
                CONTROLLED_ENTITY_UUID,
                position
        );
    }

    /**
     * Requests a prioritized Sodium rebuild for every section touched by the
     * given block positions. Together with the validation run configuration's
     * zero-frame chunk update deferral, the mutated scene is meshed before the
     * same frame renders, so occlusion and reveal transitions are frame-exact
     * rather than racing asynchronous worker threads.
     */
    private static void requestImportantRebuild(final Iterable<BlockPos> positions) {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (BlockPos pos : positions) {
            any = true;
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (any) {
            renderer.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, true);
        }
    }

    private static void holdInitialPose(final Minecraft minecraft) {
        applyPlayerPose(minecraft, cameraOrigin, cameraYaw, cameraPitch);
        Vec3 entityPosition = cameraOrigin.add(horizontalLook(cameraYaw).scale(4.0));
        controlledEntity.setOldPosAndRot(
                entityPosition,
                controlledEntity.getYRot(),
                controlledEntity.getXRot()
        );
        controlledEntity.setPos(entityPosition);
        previousEntityPosition = entityPosition;
    }

    private static void applyPlayerPose(
            final Minecraft minecraft,
            final Vec3 position,
            final float yaw,
            final float pitch
    ) {
        minecraft.player.setOldPosAndRot(position, yaw, pitch);
        minecraft.player.setPos(position);
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.setYBodyRot(yaw);
    }

    private static void sleepForAsyncWork(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Vec3 horizontalLook(final float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    private static Vec3 horizontalRight(final float yawDegrees) {
        Vec3 look = horizontalLook(yawDegrees);
        return new Vec3(look.z, 0.0, -look.x);
    }

    /**
     * Seals the controlled scene inside a stone room lit by invisible light
     * blocks. Every environmental pixel source — sky, sun, stars, clouds,
     * weather, distant terrain, and drifting particles (26.2 leaf litter
     * writes depth) — varies across runs in ways gamerules cannot fully pin,
     * and any of them in view breaks byte-identical golden captures. A
     * sealed room removes them by construction; sky light inside is zero, so
     * even the frozen day time stops mattering. Client-level-only mutations:
     * the server save is untouched, and every launch re-carves the identical
     * room from the quantized anchor.
     */
    private static void installSceneClearing(final Minecraft minecraft) {
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        List<BlockPos> touched = new ArrayList<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState shell = Blocks.STONE.defaultBlockState();
        BlockState light = Blocks.LIGHT.defaultBlockState();
        for (int forward = -1; forward <= 8; forward++) {
            for (int lateral = -6; lateral <= 6; lateral++) {
                for (int vertical = -3; vertical <= 6; vertical++) {
                    boolean boundary = forward == -1 || forward == 8
                            || lateral == -6 || lateral == 6
                            || vertical == -3 || vertical == 6;
                    Vec3 sample = cameraOrigin
                            .add(look.scale(forward))
                            .add(right.scale(lateral))
                            .add(0.0, vertical, 0.0);
                    BlockPos pos = BlockPos.containing(sample).immutable();
                    BlockState target;
                    if (boundary) {
                        target = shell;
                    } else if (vertical == 5 && (forward == 1 || forward == 4 || forward == 7)
                            && (lateral == -4 || lateral == 0 || lateral == 4)) {
                        target = light;
                    } else {
                        target = air;
                    }
                    if (minecraft.level.getBlockState(pos) != target) {
                        minecraft.level.setBlock(pos, target, 19);
                    }
                    touched.add(pos);
                }
            }
        }
        requestImportantRebuild(touched);
        Metallum.LOGGER.info("Installed automated validation scene room ({} blocks touched)", touched.size());
    }

    private static void installOcclusionWall(final Minecraft minecraft) {
        removeOcclusionWall(minecraft);
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        Vec3 center = cameraOrigin.add(right.scale(0.40)).add(look.scale(2.0));
        for (int horizontal = -1; horizontal <= 1; horizontal++) {
            for (int vertical = 0; vertical <= 2; vertical++) {
                Vec3 sample = center.add(right.scale(horizontal)).add(0.0, vertical, 0.0);
                BlockPos pos = BlockPos.containing(sample);
                BlockState previous = minecraft.level.getBlockState(pos);
                OCCLUSION_WALL.putIfAbsent(pos.immutable(), previous);
                minecraft.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 19);
            }
        }
        requestImportantRebuild(OCCLUSION_WALL.keySet());
        Metallum.LOGGER.info(
                "Installed automated validation occlusion wall with {} blocks",
                OCCLUSION_WALL.size()
        );
    }

    private static void removeOcclusionWall(final Minecraft minecraft) {
        if (minecraft.level == null || OCCLUSION_WALL.isEmpty()) {
            return;
        }
        OCCLUSION_WALL.forEach((pos, state) -> minecraft.level.setBlock(pos, state, 19));
        requestImportantRebuild(OCCLUSION_WALL.keySet());
        Metallum.LOGGER.info(
                "Removed automated validation occlusion wall with {} blocks",
                OCCLUSION_WALL.size()
        );
        OCCLUSION_WALL.clear();
    }

    private static void installCutoutLeavesScene(final Minecraft minecraft) {
        removeCutoutScene(minecraft);
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        Vec3 center = cameraOrigin.add(right.scale(0.40)).add(look.scale(3.0));
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE);
        for (int horizontal = -1; horizontal <= 1; horizontal++) {
            for (int vertical = 0; vertical <= 2; vertical++) {
                Vec3 sample = center.add(right.scale(horizontal)).add(0.0, vertical, 0.0);
                placeCutoutSceneBlock(minecraft, BlockPos.containing(sample), leaves);
            }
        }
        requestImportantRebuild(CUTOUT_SCENE.keySet());
        Metallum.LOGGER.info(
                "Installed automated validation CUTOUT leaves scene with {} blocks",
                CUTOUT_SCENE.size()
        );
    }

    private static void installCutoutGrassScene(final Minecraft minecraft) {
        removeCutoutScene(minecraft);
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        Vec3 center = cameraOrigin.add(right.scale(0.40)).add(look.scale(3.0));
        BlockState ground = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        for (int horizontal = -1; horizontal <= 1; horizontal++) {
            for (int forward = -1; forward <= 1; forward++) {
                Vec3 sample = center.add(right.scale(horizontal)).add(look.scale(forward));
                BlockPos base = BlockPos.containing(sample);
                placeCutoutSceneBlock(minecraft, base, ground);
                placeCutoutSceneBlock(minecraft, base.above(), grass);
            }
        }
        requestImportantRebuild(CUTOUT_SCENE.keySet());
        Metallum.LOGGER.info(
                "Installed automated validation CUTOUT grass scene with {} blocks",
                CUTOUT_SCENE.size()
        );
    }

    /**
     * Foliage silhouetted against the cleared far plane — the case the user
     * reports and the only one that exercises the sky far-plane motion path.
     *
     * <p>Opens the sealed room's ceiling, clears whatever sits above it (a
     * no-op where the world is already open air), and suspends a half-filled
     * checkerboard of persistent leaves with vines threaded through the gaps.
     * The checkerboard maximises silhouette edge per block, and the camera
     * pitches up steeply so nothing but sky is behind it.</p>
     */
    private static void installCutoutSkyScene(final Minecraft minecraft) {
        removeCutoutScene(minecraft);
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE);
        // All four faces set: the quads render regardless of what the vine
        // would normally need to attach to, which is what makes it a thin
        // free-standing CUTOUT strip against sky. Random ticks are already
        // disabled, so it neither spreads nor decays during the hold.
        BlockState vine = Blocks.VINE.defaultBlockState()
                .setValue(VineBlock.NORTH, Boolean.TRUE)
                .setValue(VineBlock.EAST, Boolean.TRUE)
                .setValue(VineBlock.SOUTH, Boolean.TRUE)
                .setValue(VineBlock.WEST, Boolean.TRUE);
        int cleared = 0;
        for (int vertical = 6; vertical <= 24; vertical++) {
            for (int forward = -1; forward <= 9; forward++) {
                for (int lateral = -6; lateral <= 6; lateral++) {
                    Vec3 sample = cameraOrigin
                            .add(look.scale(forward))
                            .add(right.scale(lateral))
                            .add(0.0, vertical, 0.0);
                    BlockPos pos = BlockPos.containing(sample);
                    if (minecraft.level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    placeCutoutSceneBlock(minecraft, pos, air);
                    cleared++;
                }
            }
        }
        int foliage = 0;
        for (int forward = 4; forward <= 7; forward++) {
            for (int lateral = -4; lateral <= 4; lateral++) {
                for (int vertical = 7; vertical <= 11; vertical++) {
                    Vec3 sample = cameraOrigin
                            .add(look.scale(forward))
                            .add(right.scale(lateral))
                            .add(0.0, vertical, 0.0);
                    BlockPos pos = BlockPos.containing(sample);
                    boolean even = ((forward + lateral + vertical) & 1) == 0;
                    if (even) {
                        placeCutoutSceneBlock(minecraft, pos, leaves);
                        foliage++;
                    } else if (vertical <= 8) {
                        placeCutoutSceneBlock(minecraft, pos, vine);
                        foliage++;
                    }
                }
            }
        }
        requestImportantRebuild(CUTOUT_SCENE.keySet());
        Metallum.LOGGER.info(
                "Installed automated validation CUTOUT sky scene: {} blocks cleared, {} foliage blocks,"
                        + " {} restore entries",
                cleared,
                foliage,
                CUTOUT_SCENE.size()
        );
    }

    private static void placeCutoutSceneBlock(
            final Minecraft minecraft,
            final BlockPos pos,
            final BlockState state
    ) {
        BlockPos immutable = pos.immutable();
        CUTOUT_SCENE.putIfAbsent(immutable, minecraft.level.getBlockState(immutable));
        minecraft.level.setBlock(immutable, state, 19);
    }

    private static void removeCutoutScene(final Minecraft minecraft) {
        if (minecraft.level == null || CUTOUT_SCENE.isEmpty()) {
            return;
        }
        CUTOUT_SCENE.forEach((pos, state) -> minecraft.level.setBlock(pos, state, 19));
        requestImportantRebuild(CUTOUT_SCENE.keySet());
        Metallum.LOGGER.info(
                "Removed automated validation CUTOUT scene with {} blocks",
                CUTOUT_SCENE.size()
        );
        CUTOUT_SCENE.clear();
    }

    private static void appendFrameState(
            final String scenario,
            final Vec3 camera,
            final Vec3 entity,
            final double entityOffset,
            final double cameraOffset
    ) {
        if (FRAME_JSON.length() > 2) {
            FRAME_JSON.append(",\n");
        }
        FRAME_JSON.append(String.format(
                Locale.ROOT,
                "  {\"frame\":%d,\"scenario\":\"%s\","
                        + "\"camera\":[%.9f,%.9f,%.9f],"
                        + "\"entity\":[%.9f,%.9f,%.9f],"
                        + "\"entityOffset\":%.9f,\"cameraOffset\":%.9f,"
                        + "\"guiOpen\":%s}",
                frame,
                scenario,
                camera.x, camera.y, camera.z,
                entity.x, entity.y, entity.z,
                entityOffset,
                cameraOffset,
                Minecraft.getInstance().gui.screen() != null
        ));
    }

    private static void finishAndStop(
            final Minecraft minecraft,
            final int completed,
            final int failures
    ) {
        finishRunState("passed", completed, failures);
        Metallum.LOGGER.info(
                "Automated Minecraft MetalFX validation passed {}/{} GPU captures; stopping client",
                completed,
                10
        );
        removeOcclusionWall(minecraft);
        removeCutoutScene(minecraft);
        // Return the player to the anchor pose so repeated validation runs do
        // not accumulate camera drift in the saved test world. The client-side
        // pose alone is not enough: the integrated server holds the copy that
        // gets saved, and no tick runs between here and stop() to sync it, so
        // the restore must also happen server-side.
        applyPlayerPose(minecraft, cameraOrigin, cameraYaw, cameraPitch);
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            Vec3 anchor = cameraOrigin;
            float yaw = cameraYaw;
            float pitch = cameraPitch;
            server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.snapTo(anchor.x, anchor.y, anchor.z);
                    player.absSnapRotationTo(yaw, pitch);
                }
            });
            sleepForAsyncWork(200L);
        }
        minecraft.stop();
    }

    private static void finishRunState(
            final String status,
            final int completed,
            final int failures
    ) {
        try {
            Files.writeString(
                    outputDirectory.resolve("frame-state.json"),
                    FRAME_JSON + "\n]\n",
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    outputDirectory.resolve("run-state.json"),
                    String.format(
                            Locale.ROOT,
                            """
                    {
                      "mode": "automated-minecraft-client",
                      "usedDedicatedServer": false,
                      "usedSystemScreenshot": false,
                      "usedComputerUse": false,
                      "controlledFrames": 90,
                      "controlledEntity": "armor_stand",
                      "expectedGpuCaptures": 10,
                      "completedGpuCaptures": %d,
                      "failedGpuCaptures": %d,
                      "status": "%s"
                    }
                    """,
                            completed,
                            failures,
                            status
                    ),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write Minecraft validation state", exception);
        }
    }
}
