package com.metallum.client.validation;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalFxManager;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    private static int frame;
    private static ArmorStand controlledEntity;
    private static Vec3 cameraOrigin;
    private static float cameraYaw;
    private static float cameraPitch;
    private static Path outputDirectory;
    private static Vec3 previousEntityPosition;
    private static final Map<BlockPos, BlockState> OCCLUSION_WALL = new LinkedHashMap<>();
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

        String scenario;
        double entityOffset = 0.0;
        double cameraOffset = 0.0;
        if (frame < 8) {
            scenario = "static_entity_static_camera";
        } else if (frame < 18) {
            scenario = "moving_entity_static_camera";
            entityOffset = (frame - 7) * 0.04;
        } else if (frame < 28) {
            scenario = "static_entity_moving_camera";
            entityOffset = 0.40;
            cameraOffset = (frame - 17) * 0.02;
        } else if (frame < 38) {
            scenario = "moving_entity_moving_camera";
            entityOffset = 0.40 + (frame - 27) * 0.04;
            cameraOffset = 0.20 + (frame - 27) * 0.02;
        } else if (frame < 46) {
            scenario = "occluded_entity";
            entityOffset = 0.80;
            cameraOffset = 0.40;
            if (frame == 38) {
                installOcclusionWall(minecraft);
            }
        } else if (frame < 54) {
            scenario = "revealed_entity";
            entityOffset = 0.80;
            cameraOffset = 0.40;
            if (frame == 46) {
                removeOcclusionWall(minecraft);
            }
        } else if (frame < 62) {
            scenario = "gui_open";
            entityOffset = 0.80;
            cameraOffset = 0.40;
            if (frame == 54) {
                minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
            }
        } else {
            scenario = "scene_reset";
            entityOffset = 0.80;
            cameraOffset = 0.40;
            if (frame == 62) {
                minecraft.gui.setScreen(null);
                MetalFxManager.resetHistory("automated validation scene reset");
            }
        }

        Vec3 right = horizontalRight(cameraYaw);
        Vec3 cameraPosition = cameraOrigin.add(right.scale(cameraOffset));
        minecraft.player.setOldPosAndRot(cameraPosition, cameraYaw, cameraPitch);
        minecraft.player.setPos(cameraPosition);
        minecraft.player.setYRot(cameraYaw);
        minecraft.player.setXRot(cameraPitch);
        minecraft.player.setYHeadRot(cameraYaw);
        minecraft.player.setYBodyRot(cameraYaw);

        Vec3 baseEntity = cameraOrigin.add(horizontalLook(cameraYaw).scale(4.0));
        Vec3 entityPosition = baseEntity.add(right.scale(entityOffset));
        controlledEntity.setOldPosAndRot(controlledEntity.position(), controlledEntity.getYRot(), controlledEntity.getXRot());
        controlledEntity.setPos(entityPosition);

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
        if (frame < 74) {
            appendFrameState(scenario, cameraPosition, entityPosition, entityOffset, cameraOffset);
        }
        previousEntityPosition = entityPosition;
        frame++;
        if (frame >= 78 && MetalFxManager.validationCapturesPending() == 0) {
            int completed = MetalFxManager.validationCapturesCompleted();
            int failures = MetalFxManager.validationCaptureFailures();
            if (completed != 8 || failures != 0) {
                finishRunState("failed", completed, failures);
                throw new IllegalStateException(
                        "Automated Minecraft GPU validation failed: completed="
                                + completed + "/8, failures=" + failures
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

    private static void installControlledScene(final Minecraft minecraft) {
        cameraOrigin = minecraft.player.position();
        cameraYaw = minecraft.player.getYRot();
        cameraPitch = 0.0F;
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

    private static Vec3 horizontalLook(final float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    private static Vec3 horizontalRight(final float yawDegrees) {
        Vec3 look = horizontalLook(yawDegrees);
        return new Vec3(look.z, 0.0, -look.x);
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
        Metallum.LOGGER.info(
                "Removed automated validation occlusion wall with {} blocks",
                OCCLUSION_WALL.size()
        );
        OCCLUSION_WALL.clear();
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
                8
        );
        removeOcclusionWall(minecraft);
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
                      "controlledFrames": 74,
                      "controlledEntity": "armor_stand",
                      "expectedGpuCaptures": 8,
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
