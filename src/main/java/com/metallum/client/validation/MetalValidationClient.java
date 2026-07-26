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
import net.minecraft.tags.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
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
    // Object-motion acceptance frames. MetalEntityObjectPose reconstructs root
    // transforms for dropped items and vehicles, but the scripted room holds
    // only an ArmorStand, so the core/item path had no automated proof. These
    // frames are appended strictly after the cutout flicker series' last frame
    // (SKY_FLICKER_END_FRAME) rather than inserted into it: frames 90..151
    // belong to the shimmer-remediation thread
    // (docs/cutout-shimmer-remediation-2026-07-27.md §8/§14) and nothing at or
    // below 155 changes behaviour here.
    // One scenario per root-transform category MetalEntityObjectPose covers, so
    // a regression in any single reconstruction shows up as its own failing
    // frame. Each scenario runs 12 frames and captures 8 frames in, leaving the
    // scene swap's disocclusion transient behind. See the coverage table in
    // docs/metalfx-frame-generation.md.
    private static final int OBJECT_SCENE_FRAME = 156;
    private static final int ITEM_CAPTURE_FRAME = 164;
    private static final int VEHICLE_TURN_FRAME = 168;
    private static final int VEHICLE_CAPTURE_FRAME = 176;
    private static final int LIVING_TURN_FRAME = 180;
    private static final int LIVING_CAPTURE_FRAME = 188;
    private static final int ARROW_TURN_FRAME = 192;
    private static final int ARROW_CAPTURE_FRAME = 200;
    private static final int MINECART_TURN_FRAME = 204;
    private static final int MINECART_CAPTURE_FRAME = 212;
    private static final int OBJECT_SERIES_END_FRAME = 216;
    // The timeline now runs past the old 220-frame ceiling.
    private static final int TIMELINE_TIMEOUT_FRAME = 300;
    // Item spin is driven by ageInTicks, which the renderer builds as
    // `tickCount + partialTick`. partialTick is wall-clock and cannot be
    // pinned from here, so the commanded per-frame step is made large enough
    // to dominate it: 5 ticks is 0.25 rad of spin per frame against at most
    // 1 tick (0.05 rad) of jitter, leaving the true delta inside [0.20, 0.30].
    private static final int ITEM_SPIN_TICKS_PER_FRAME = 5;
    // Rotation step shared by the boat, pig and arrow scenarios. Every one of
    // those angles is read through a partialTick lerp of old -> new, so pinning
    // old == new makes the rendered pose exact and these scenarios carry no
    // wall-clock term at all. 6 degrees a frame keeps per-frame motion small.
    private static final float OBJECT_TURN_DEGREES_PER_FRAME = 6.0F;
    // Minecart hurt shake. On a straight rail the renderer re-derives yaw from
    // the rail samples and ignores the cart's own, so the shake is the only
    // rotation available without curving the track.
    //
    // The shake angle is `sin(hurtTime) * hurtTime * damage / 10` degrees, and
    // hurtTime is fed to sin as if it were radians. Counting hurtTime down a
    // step per frame therefore does not give a smooth wobble at all: 10 -> 9 at
    // damage 40 swings 36 degrees in a single frame, which is precisely the
    // large per-frame motion these scenarios avoid. Holding hurtTime fixed and
    // ramping damage instead makes the angle linear in the ramp: at hurtTime 5
    // the angle is -0.479 * damage degrees, so a 4.0 step is about 1.9 degrees
    // a frame, matching the other scenarios' 6 degree yaw step in magnitude.
    //
    // This is the one object scenario that keeps a wall-clock term: the
    // renderer extracts hurtTime as `getHurtTime() - partialTick`, which no
    // amount of old == new pinning removes. The effective hurtTime therefore
    // roams [4, 5] and the realised step lands somewhere in 1.9-6.9 degrees.
    // That stays small in absolute terms and well inside the spread envelope,
    // but it is why this scenario is the least reproducible of the five.
    private static final int MINECART_HURT_TIME = 5;
    private static final float MINECART_DAMAGE_PER_FRAME = 4.0F;
    // Spin angle the item is pinned to on its capture frame. bobOffs is
    // randomised per ItemEntity and is final, so rather than pinning the offset
    // itself the integer tick base absorbs it (see installObjectMotionScene).
    // Landing on a fixed angle keeps the capture off the face-on phase, where
    // the whole visible face shares one depth and the horizontal motion spread
    // that carries the rotation largely collapses.
    private static final double ITEM_CAPTURE_SPIN_RADIANS = Math.PI / 4.0;
    private static int itemTickBase;
    private static final int ITEM_ENTITY_ID = -2_147_000_002;
    private static final int VEHICLE_ENTITY_ID = -2_147_000_003;
    private static final UUID ITEM_ENTITY_UUID =
            UUID.fromString("7a294d59-ecbe-4b47-b864-66c57a3dbf02");
    private static final UUID VEHICLE_ENTITY_UUID =
            UUID.fromString("7a294d59-ecbe-4b47-b864-66c57a3dbf03");
    private static final int LIVING_ENTITY_ID = -2_147_000_004;
    private static final int ARROW_ENTITY_ID = -2_147_000_005;
    private static final int MINECART_ENTITY_ID = -2_147_000_006;
    private static final UUID LIVING_ENTITY_UUID =
            UUID.fromString("7a294d59-ecbe-4b47-b864-66c57a3dbf04");
    private static final UUID ARROW_ENTITY_UUID =
            UUID.fromString("7a294d59-ecbe-4b47-b864-66c57a3dbf05");
    private static final UUID MINECART_ENTITY_UUID =
            UUID.fromString("7a294d59-ecbe-4b47-b864-66c57a3dbf06");
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
    private static ItemEntity spinningItem;
    private static Boat turningVehicle;
    private static Pig turningLiving;
    private static Arrow turningArrow;
    private static Minecart shakingMinecart;
    private static Vec3 cameraOrigin;
    private static float cameraYaw;
    private static float cameraPitch;
    private static Path outputDirectory;
    private static Vec3 previousEntityPosition;
    private static final Map<BlockPos, BlockState> OCCLUSION_WALL = new LinkedHashMap<>();
    private static final Map<BlockPos, BlockState> CUTOUT_SCENE = new LinkedHashMap<>();
    // Kept separate from CUTOUT_SCENE so restoring the object-motion rail never
    // interacts with the shimmer thread's cutout scene bookkeeping.
    private static final Map<BlockPos, BlockState> OBJECT_SCENE = new LinkedHashMap<>();
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
        // Cleared here rather than in applyDeterministicWorldState, which only
        // runs once a level frame has already been driven. Under Gradle the
        // window usually opens unfocused, and the pause screen then opens on
        // the same frame the player joins — before that first level frame. The
        // paused, unfocused client is throttled by the compositor to
        // effectively zero frames, so the timeline never starts and the run
        // exits reporting success while having asserted nothing. This runs from
        // the first rendered frame, well before the world loads.
        if (minecraft.options != null) {
            minecraft.options.pauseOnLostFocus = false;
        }
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
        } else if (frame == OBJECT_SCENE_FRAME) {
            installObjectMotionScene(minecraft);
        } else if (frame == VEHICLE_TURN_FRAME
                || frame == LIVING_TURN_FRAME
                || frame == ARROW_TURN_FRAME
                || frame == MINECART_TURN_FRAME) {
            // Swapping which object is in view is a large one-frame jump for
            // both the outgoing and incoming object; the capture sits 8 frames
            // later, and the reset keeps that transient out of the accumulated
            // history the capture reads. The swap is staging, not the thing
            // under test: no scenario validates a reveal with it.
            MetalFxManager.resetHistory("automated validation object scenario swap");
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
        if (frame >= OBJECT_SERIES_END_FRAME
                && MetalFxManager.validationCapturesPending() == 0
                && !MetalFxManager.flickerSeriesPending()
                && MetalFxManager.flickerMetricCompleted("cutout_grass_hold")
                && MetalFxManager.flickerMetricCompleted("cutout_sky_hold")) {
            int completed = MetalFxManager.validationCapturesCompleted();
            int failures = MetalFxManager.validationCaptureFailures();
            if (completed != 15 || failures != 0) {
                removeOcclusionWall(minecraft);
                removeCutoutScene(minecraft);
                removeObjectMotionScene();
                applyPlayerPose(minecraft, cameraOrigin, cameraYaw, cameraPitch);
                finishRunState("failed", completed, failures);
                throw new IllegalStateException(
                        "Automated Minecraft GPU validation failed: completed="
                                + completed + "/15, failures=" + failures
                );
            }
            finishAndStop(minecraft, completed, failures);
        } else if (frame >= TIMELINE_TIMEOUT_FRAME) {
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
        // Bounding what used to be the open-ended tail. Every frame the
        // shimmer thread owns still resolves to cutout_sky_hold, so this is an
        // append rather than an edit of their range.
        if (timelineFrame < OBJECT_SCENE_FRAME) {
            return new ScenarioPose("cutout_sky_hold", 0.80, 0.40);
        }
        // Object-motion scenarios. The camera offset is held at the value the
        // sky hold ends on so the camera never translates across the
        // transition: the only motion these frames contain is the object's own
        // rotation, which is what makes the spin separable from translation.
        if (timelineFrame < VEHICLE_TURN_FRAME) {
            return new ScenarioPose("item_spin", 0.80, 0.40);
        }
        if (timelineFrame < LIVING_TURN_FRAME) {
            return new ScenarioPose("vehicle_turn", 0.80, 0.40);
        }
        if (timelineFrame < ARROW_TURN_FRAME) {
            return new ScenarioPose("living_turn", 0.80, 0.40);
        }
        if (timelineFrame < MINECART_TURN_FRAME) {
            return new ScenarioPose("arrow_turn", 0.80, 0.40);
        }
        return new ScenarioPose("minecart_rail", 0.80, 0.40);
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
                || timelineFrame == 75 || timelineFrame == SKY_SCENE_FRAME
                // Restores the sky scene's opened ceiling, so it re-meshes terrain.
                || timelineFrame == OBJECT_SCENE_FRAME;
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
        if (isObjectMotionScenario(pose.scenario())) {
            // The ArmorStand would otherwise contribute a second silhouette of
            // zero-motion object pixels, and the spread metric is taken over
            // every valid pixel. Parking it behind the camera leaves the object
            // under test as the only source of object motion in frame.
            Vec3 parked = cameraOrigin.add(horizontalLook(cameraYaw).scale(-4.0));
            controlledEntity.setOldPosAndRot(parked, 0.0F, 0.0F);
            controlledEntity.setPos(parked);
            return driveObjectMotionEntities(pose.scenario());
        }
        return entityPosition;
    }

    private static boolean isObjectMotionScenario(final String scenario) {
        return "item_spin".equals(scenario)
                || "vehicle_turn".equals(scenario)
                || "living_turn".equals(scenario)
                || "arrow_turn".equals(scenario)
                || "minecart_rail".equals(scenario);
    }

    /**
     * Drives every object-motion entity for one frame and returns the position
     * of whichever one is on screen.
     *
     * <p>The returned position is what the capture reports as the validated
     * entity centre, so it has to be the object actually producing the motion
     * pixels — and it has to be in front of the camera, because the readback
     * rejects a centre outside the valid clip half-space.</p>
     *
     * <p>Exactly one object is in view per scenario; the rest are parked behind
     * the camera, where they are frustum-culled and contribute no pixels. Each
     * is held at a fixed world position throughout its scenario, so per-frame
     * translation is zero and the motion the capture measures is purely the
     * object's own rotation. That matters beyond tidiness: measured object
     * motion runs 30-55% off the analytic value, so a scenario that leaned on
     * large per-frame translation would be measuring mostly that error.</p>
     */
    private static Vec3 driveObjectMotionEntities(final String scenario) {
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        Vec3 parked = cameraOrigin.add(look.scale(-4.0));
        // Distances are chosen per category so each silhouette covers enough
        // pixels at the pinned capture size: the arrow is a thin sliver and has
        // to sit closest, the pig and boat are bulky and sit further out.
        Vec3 itemHome = cameraOrigin.add(look.scale(1.5)).add(right.scale(0.40)).add(0.0, 1.3, 0.0);
        Vec3 vehicleHome = cameraOrigin.add(look.scale(3.0)).add(right.scale(0.40)).add(0.0, 0.9, 0.0);
        Vec3 livingHome = cameraOrigin.add(look.scale(2.5)).add(right.scale(0.40)).add(0.0, 0.9, 0.0);
        Vec3 arrowHome = cameraOrigin.add(look.scale(1.0)).add(right.scale(0.40)).add(0.0, 1.3, 0.0);
        Vec3 minecartHome = minecartRailPosition();

        boolean item = "item_spin".equals(scenario);
        boolean vehicle = "vehicle_turn".equals(scenario);
        boolean living = "living_turn".equals(scenario);
        boolean arrow = "arrow_turn".equals(scenario);
        boolean minecart = "minecart_rail".equals(scenario);

        Vec3 itemPosition = item ? itemHome : parked;
        Vec3 vehiclePosition = vehicle ? vehicleHome : parked;
        Vec3 livingPosition = living ? livingHome : parked;
        Vec3 arrowPosition = arrow ? arrowHome : parked;
        // Parked like the rest when it is not its turn. Leaving it on the rail
        // throughout does not keep it out of frame: the rail sits about 23
        // degrees below the horizon at this distance, well inside the 35 degree
        // half-FOV, so a resident cart would add a second silhouette of
        // zero-motion object pixels to every other scenario's measurement — and
        // would on its own clear the arrow scenario's pixel floor. Parking
        // costs nothing, because OldMinecartBehavior.getPos is evaluated from
        // the cart's current position every frame rather than latched: the
        // rail-sampled branch re-selects itself the moment the cart is back on
        // the track, with a history reset on that same frame.
        Vec3 minecartPosition = minecart ? minecartHome : parked;

        if (spinningItem != null) {
            // The spin phase is a pure function of the timeline frame index.
            // bobOffs is randomised per ItemEntity and cannot be assigned (it
            // is final), but it enters getSpin as a constant additive phase and
            // therefore cancels exactly in the frame-to-frame rotation delta
            // the interpolator consumes.
            int stepsFromCapture = (frame - ITEM_CAPTURE_FRAME) * ITEM_SPIN_TICKS_PER_FRAME;
            spinningItem.tickCount = Math.max(0, itemTickBase + stepsFromCapture);
            spinningItem.setDeltaMovement(Vec3.ZERO);
            spinningItem.setOldPosAndRot(itemPosition, 0.0F, 0.0F);
            spinningItem.setPos(itemPosition);
        }
        if (turningVehicle != null) {
            float yaw = vehicle ? (frame - VEHICLE_TURN_FRAME) * OBJECT_TURN_DEGREES_PER_FRAME : 0.0F;
            turningVehicle.setDeltaMovement(Vec3.ZERO);
            // old == new on every lerped rotation channel: getYRot(partialTick)
            // then returns the commanded yaw exactly, so the vehicle's rendered
            // pose carries no wall-clock term.
            turningVehicle.setOldPosAndRot(vehiclePosition, yaw, 0.0F);
            turningVehicle.setPos(vehiclePosition);
            turningVehicle.setYRot(yaw);
            turningVehicle.yRotO = yaw;
            turningVehicle.setXRot(0.0F);
            turningVehicle.xRotO = 0.0F;
        }
        if (turningLiving != null) {
            // LivingEntityRenderer reads bodyRot as rotLerp(partialTick,
            // yBodyRotO, yBodyRot); pinning old == new makes it exact. Head and
            // body are held together so the head-turn animation, which is not a
            // root transform, contributes nothing.
            float yaw = living ? (frame - LIVING_TURN_FRAME) * OBJECT_TURN_DEGREES_PER_FRAME : 0.0F;
            turningLiving.setDeltaMovement(Vec3.ZERO);
            turningLiving.setOldPosAndRot(livingPosition, yaw, 0.0F);
            turningLiving.setPos(livingPosition);
            turningLiving.setYRot(yaw);
            turningLiving.yRotO = yaw;
            turningLiving.setXRot(0.0F);
            turningLiving.xRotO = 0.0F;
            turningLiving.yBodyRot = yaw;
            turningLiving.yBodyRotO = yaw;
            turningLiving.yHeadRot = yaw;
            turningLiving.yHeadRotO = yaw;
            // A standing entity still accumulates a walk cycle if the animation
            // position drifts; zeroing it keeps the limbs out of the measured
            // field, which the root transform does not cover anyway.
            turningLiving.walkAnimation.setSpeed(0.0F);
        }
        if (turningArrow != null) {
            // ArrowRenderer takes both angles through getXRot/getYRot with
            // partialTick, so both channels are pinned old == new.
            float yaw = arrow ? (frame - ARROW_TURN_FRAME) * OBJECT_TURN_DEGREES_PER_FRAME : 0.0F;
            turningArrow.setDeltaMovement(Vec3.ZERO);
            turningArrow.setOldPosAndRot(arrowPosition, yaw, 0.0F);
            turningArrow.setPos(arrowPosition);
            turningArrow.setYRot(yaw);
            turningArrow.yRotO = yaw;
            turningArrow.setXRot(0.0F);
            turningArrow.xRotO = 0.0F;
        }
        if (shakingMinecart != null) {
            shakingMinecart.setDeltaMovement(Vec3.ZERO);
            shakingMinecart.setOldPosAndRot(minecartPosition, 0.0F, 0.0F);
            shakingMinecart.setPos(minecartPosition);
            shakingMinecart.setYRot(0.0F);
            shakingMinecart.yRotO = 0.0F;
            shakingMinecart.setXRot(0.0F);
            shakingMinecart.xRotO = 0.0F;
            // On a rail the renderer discards the cart's own yaw and re-derives
            // orientation from the front/back rail samples, so turning the cart
            // would change nothing on a straight track. The hurt shake is the
            // rotation this scenario drives, and it is the other half of the
            // minecart row in the coverage table. hurtTime is held fixed and
            // damage carries the ramp, which keeps the angle linear in the step
            // rather than swinging with sin(hurtTime).
            shakingMinecart.setHurtTime(minecart ? MINECART_HURT_TIME : 0);
            shakingMinecart.setDamage(minecart
                    ? (frame - MINECART_TURN_FRAME) * MINECART_DAMAGE_PER_FRAME
                    : 0.0F);
            shakingMinecart.setHurtDir(1);
        }

        if (item) {
            return itemPosition;
        }
        if (vehicle) {
            return vehiclePosition;
        }
        if (living) {
            return livingPosition;
        }
        if (arrow) {
            return arrowPosition;
        }
        return minecartPosition;
    }

    /** Centre of the rail tile the minecart sits on, lifted onto the rail. */
    private static Vec3 minecartRailPosition() {
        Vec3 look = horizontalLook(cameraYaw);
        Vec3 right = horizontalRight(cameraYaw);
        Vec3 sample = cameraOrigin.add(look.scale(3.0)).add(right.scale(0.40)).add(0.0, -1.0, 0.0);
        BlockPos rail = BlockPos.containing(sample);
        return new Vec3(rail.getX() + 0.5, rail.getY() + 1.0, rail.getZ() + 0.5);
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

    /**
     * Installs the object-motion scene: re-seals the room the sky scene opened
     * and spawns the two objects whose root transforms MetalEntityObjectPose
     * reconstructs.
     *
     * <p>Both entities are added client-side only, exactly like the controlled
     * ArmorStand, and they come into existence at
     * {@link #OBJECT_SCENE_FRAME} — after every golden capture and well past
     * the frame &lt; 90 window {@code appendFrameState} records. That temporal
     * scoping is what keeps the item's deliberately varying rotation from
     * reaching any frame whose bytes are compared across runs.</p>
     *
     * <p>The item is a block item rather than a flat one: a block model keeps a
     * solid silhouette at every spin phase, where a flat item turns edge-on
     * twice per revolution and would collapse the measured pixel count at an
     * unpredictable phase (bobOffs is random per entity).</p>
     */
    private static void installObjectMotionScene(final Minecraft minecraft) {
        // The sky scene opened the ceiling; restoring it re-seals the room so
        // these frames are backed by stone rather than sky.
        removeCutoutScene(minecraft);

        Vec3 parked = cameraOrigin.add(horizontalLook(cameraYaw).scale(-4.0));
        ItemEntity item = new ItemEntity(
                minecraft.level,
                parked.x, parked.y, parked.z,
                new ItemStack(Blocks.STONE.asItem())
        );
        item.setId(ITEM_ENTITY_ID);
        item.setUUID(ITEM_ENTITY_UUID);
        item.setNoGravity(true);
        item.setDeltaMovement(Vec3.ZERO);
        minecraft.level.addEntity(item);
        spinningItem = item;
        // getSpin is ageInTicks/20 + bobOffs, so choosing the tick count at the
        // capture frame pins the rendered spin angle there regardless of the
        // random offset. Two full turns are added before rounding so the base
        // stays comfortably positive across the whole scenario (the earliest
        // frame sits 8 steps below it) without approximating the 2*pi wrap.
        itemTickBase = (int) Math.round(
                20.0 * (ITEM_CAPTURE_SPIN_RADIANS - item.bobOffs + 4.0 * Math.PI)
        );
        Metallum.LOGGER.info(
                "Item spin pinned: bobOffs={} tickBase={} (capture frame {} lands at {} rad)",
                item.bobOffs,
                itemTickBase,
                ITEM_CAPTURE_FRAME,
                ITEM_CAPTURE_SPIN_RADIANS
        );

        Boat boat = new Boat(EntityTypes.OAK_BOAT, minecraft.level, () -> Items.OAK_BOAT);
        boat.setId(VEHICLE_ENTITY_ID);
        boat.setUUID(VEHICLE_ENTITY_UUID);
        boat.setNoGravity(true);
        boat.setDeltaMovement(Vec3.ZERO);
        boat.setPos(parked);
        minecraft.level.addEntity(boat);
        turningVehicle = boat;

        Pig pig = new Pig(EntityTypes.PIG, minecraft.level);
        pig.setId(LIVING_ENTITY_ID);
        pig.setUUID(LIVING_ENTITY_UUID);
        pig.setNoGravity(true);
        pig.setNoAi(true);
        pig.setDeltaMovement(Vec3.ZERO);
        pig.setPos(parked);
        minecraft.level.addEntity(pig);
        turningLiving = pig;

        Arrow arrowEntity = new Arrow(EntityTypes.ARROW, minecraft.level);
        arrowEntity.setId(ARROW_ENTITY_ID);
        arrowEntity.setUUID(ARROW_ENTITY_UUID);
        arrowEntity.setNoGravity(true);
        arrowEntity.setDeltaMovement(Vec3.ZERO);
        arrowEntity.setPos(parked);
        minecraft.level.addEntity(arrowEntity);
        turningArrow = arrowEntity;

        // The rail has to exist before the cart is placed: OldMinecartBehavior
        // only reports posOnRail/frontPos/backPos when a rail block sits at or
        // just below the cart, and those are what select the rail-sampled
        // branch of the reconstruction rather than the plain fallback.
        installMinecartRail(minecraft);
        Vec3 railPosition = minecartRailPosition();
        Minecart cart = new Minecart(EntityTypes.MINECART, minecraft.level);
        cart.setId(MINECART_ENTITY_ID);
        cart.setUUID(MINECART_ENTITY_UUID);
        cart.setNoGravity(true);
        cart.setDeltaMovement(Vec3.ZERO);
        cart.setPos(railPosition);
        minecraft.level.addEntity(cart);
        shakingMinecart = cart;

        // The re-seal plus the new silhouettes disocclude most of the frame;
        // the captures sit 8 frames later so history is settled by then.
        MetalFxManager.resetHistory("automated validation object motion scene");
        Metallum.LOGGER.info(
                "Installed object-motion scene: item={} vehicle={} living={} arrow={} minecart={} rail={}",
                ITEM_ENTITY_ID,
                VEHICLE_ENTITY_ID,
                LIVING_ENTITY_ID,
                ARROW_ENTITY_ID,
                MINECART_ENTITY_ID,
                railPosition
        );
    }

    /**
     * Lays a short straight rail under the minecart's home tile. Straight is
     * deliberate: a curve would change the sampled direction and therefore the
     * cart's orientation, but only by moving the cart along it, and this
     * scenario keeps per-frame translation at zero and drives the hurt shake
     * instead.
     */
    private static void installMinecartRail(final Minecraft minecraft) {
        Vec3 look = horizontalLook(cameraYaw);
        BlockPos centre = BlockPos.containing(minecartRailPosition()).below();
        BlockState rail = Blocks.RAIL.defaultBlockState()
                .setValue(RailBlock.SHAPE, railShapeAlong(look));
        for (int step = -2; step <= 2; step++) {
            BlockPos pos = BlockPos.containing(
                    Vec3.atCenterOf(centre).add(look.scale(step))
            );
            placeObjectSceneBlock(minecraft, pos, rail);
            // Rails need a solid block beneath or they pop off on the first
            // block update; the room's floor is already stone, but the tile
            // under a lifted rail may not be.
            placeObjectSceneBlock(minecraft, pos.below(), Blocks.STONE.defaultBlockState());
        }
        requestImportantRebuild(OBJECT_SCENE.keySet());

        // Without a rail under the cart, OldMinecartBehavior.getPos returns
        // null and the reconstruction quietly falls back to the non-rail
        // branch. The scenario would still pass — the hurt shake alone produces
        // a spread — while validating a different code path than the one it
        // claims to cover. Fail loudly instead of silently covering the wrong
        // thing.
        BlockPos cartTile = BlockPos.containing(minecartRailPosition()).below();
        if (!minecraft.level.getBlockState(cartTile).is(BlockTags.RAILS)) {
            throw new IllegalStateException(
                    "Minecart validation rail missing at " + cartTile
                            + " (found " + minecraft.level.getBlockState(cartTile) + "); the"
                            + " rail-sampled reconstruction branch would not be exercised"
            );
        }
    }

    /** Rail axis closest to the camera's forward direction. */
    private static RailShape railShapeAlong(final Vec3 look) {
        return Math.abs(look.x) > Math.abs(look.z)
                ? RailShape.EAST_WEST
                : RailShape.NORTH_SOUTH;
    }

    private static void placeObjectSceneBlock(
            final Minecraft minecraft,
            final BlockPos pos,
            final BlockState state
    ) {
        BlockPos immutable = pos.immutable();
        OBJECT_SCENE.putIfAbsent(immutable, minecraft.level.getBlockState(immutable));
        minecraft.level.setBlock(immutable, state, 19);
    }

    private static void removeObjectMotionScene() {
        if (spinningItem != null) {
            spinningItem.discard();
            spinningItem = null;
        }
        if (turningVehicle != null) {
            turningVehicle.discard();
            turningVehicle = null;
        }
        if (turningLiving != null) {
            turningLiving.discard();
            turningLiving = null;
        }
        if (turningArrow != null) {
            turningArrow.discard();
            turningArrow = null;
        }
        if (shakingMinecart != null) {
            shakingMinecart.discard();
            shakingMinecart = null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !OBJECT_SCENE.isEmpty()) {
            OBJECT_SCENE.forEach((pos, state) -> minecraft.level.setBlock(pos, state, 19));
            requestImportantRebuild(OBJECT_SCENE.keySet());
        }
        OBJECT_SCENE.clear();
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
                15
        );
        removeOcclusionWall(minecraft);
        removeCutoutScene(minecraft);
        removeObjectMotionScene();
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
                      "expectedGpuCaptures": 15,
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
