package com.metallum.e2e;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Opt-in real-client workload using Fabric's input driver, in the existing normal world. */
public final class VanillaGameplay {
    private static final int NATIVE_WIDTH = Integer.getInteger("metallum.ci.nativeWidth", 0);
    private static final int NATIVE_HEIGHT = Integer.getInteger("metallum.ci.nativeHeight", 0);
    // Test-only, bounded timestamps. No FFM timing, readback or per-frame allocation.
    private static final long[] FRAME_TIMES = new long[131_072];
    private static boolean recordingFrames;
    private static int frameCount;
    private static long startedNanos;
    private static int invalidSettingsFrames;
    private static int throttledFrames;
    private static int droppedSamples;

    public static void sourceFramePresented(Minecraft client) {
        if (!recordingFrames) return;
        long now = System.nanoTime();
        if (frameCount < FRAME_TIMES.length) FRAME_TIMES[frameCount++] = now;
        else droppedSamples++;
        var target = client.gameRenderer.mainRenderTarget();
        if (client.getWindow().getWidth() != NATIVE_WIDTH || client.getWindow().getHeight() != NATIVE_HEIGHT
                || target.width != NATIVE_WIDTH || target.height != NATIVE_HEIGHT
                || client.options.getEffectiveRenderDistance() != 32) invalidSettingsFrames++;
        if (client.getFramerateLimitTracker().getFramerateLimit() < 260) throttledFrames++;
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world,
                    Path output, JsonObject worldEvidence) {
        var input = context.getInput();
        JsonObject report = new JsonObject();
        report.addProperty("scenario", "vanilla-normal-gameplay-native-max-v2");
        report.addProperty("pid", ProcessHandle.current().pid());
        report.add("world", worldEvidence);
        JsonArray phases = new JsonArray();
        report.add("phases", phases);
        require(NATIVE_WIDTH > 0 && NATIVE_HEIGHT > 0, "Native display dimensions are required");
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            client.options.graphicsPreset().set(GraphicsPreset.FABULOUS);
            client.options.renderDistance().set(32);
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260);
            client.getWindow().setPreferredFullscreenVideoMode(java.util.Optional.empty());
            client.options.fullscreen().set(true);
            client.getWindow().setFullscreen(true);
        });
        context.waitFor(client -> client.getWindow().getWidth() == NATIVE_WIDTH
                && client.getWindow().getHeight() == NATIVE_HEIGHT, 600);
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("weather clear");
        world.getServer().runCommand("tp @a 160.5 140 160.5 -65 15");
        context.waitFor(client -> client.player != null && client.player.getY() > 130
                && client.player.getAbilities().mayfly);
        // Establish the flight pose before measurement. Route movement itself is input-driven.
        context.runOnClient(client -> {
            client.player.getAbilities().flying = true;
            client.player.onUpdateAbilities();
        });
        // Profiling live streaming does not require Fabric's full square of
        // downloaded chunks (including corners outside Vanilla's send radius).
        // Let received geometry finish and retain ordinary generation during flight.
        context.waitTicks(100);
        world.getConnection().waitForChunksRender(false, 1200);
        JsonObject settings = context.computeOnClient(VanillaGameplay::settings);
        report.add("settings", settings);
        require(settings.get("effectiveRenderDistance").getAsInt() == 32, "Maximum view distance did not activate");
        require(settings.get("renderWidth").getAsInt() == NATIVE_WIDTH
                && settings.get("renderHeight").getAsInt() == NATIVE_HEIGHT, "Render target is not native resolution");
        report.addProperty("reuseEncoderState", Boolean.getBoolean("metallum.opt.reuseEncoderState"));
        int initialVisibleSections = context.computeOnClient(client -> client.levelRenderer.visibleSections().size());
        report.addProperty("initialVisibleSections", initialVisibleSections);
        long[] initialMetal4 = context.computeOnClient(client -> MetalNativeBridge.metallum_metal4_main_renderer_stats());
        require(initialMetal4[0] == 1, "Gameplay profiling requires an active Metal 4 main renderer");
        report.addProperty("metal4MainRendererActive", true);
        report.addProperty("status", "ready");
        write(output.resolve("gameplay-ready.json"), report);
        if (Boolean.getBoolean("metallum.ci.waitForProfiler")) {
            // The launcher releases this only after Instruments signals recording started.
            context.waitFor(client -> Files.exists(output.resolve("profiler-started")), 1200);
        }
        context.runOnClient(client -> {
            frameCount = invalidSettingsFrames = throttledFrames = droppedSamples = 0;
            startedNanos = System.nanoTime();
            recordingFrames = true;
        });
        try {
            phase(context, output, report, phases, "flight-new-chunks");
            double startX = context.computeOnClient(client -> client.player.getX());
            double startZ = context.computeOnClient(client -> client.player.getZ());
            input.holdKey(options -> options.keyUp);
            input.holdKey(options -> options.keySprint);
            for (int leg = 0; leg < 6; leg++) {
                input.lookAt(-65 + leg * 12, 15);
                // Fabric's synthetic input drives key state directly; report the
                // input to Vanilla's AFK limiter just as a real mouse event does.
                context.runOnClient(client -> client.getFramerateLimitTracker().onInputReceived());
                context.waitTicks(160);
            }
            input.releaseKey(options -> options.keyUp);
            input.releaseKey(options -> options.keySprint);
            double distance = context.computeOnClient(client -> Math.hypot(
                    client.player.getX() - startX, client.player.getZ() - startZ));
            report.addProperty("flightDistanceBlocks", distance);
            require(distance > 100, "Input-driven flight did not traverse terrain: " + distance);

            phase(context, output, report, phases, "land-walk-jump");
            BlockPos position = context.computeOnClient(client -> client.player.blockPosition());
            int groundY = world.getServer().computeOnServer(server -> server.overworld()
                    .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, position.getX(), position.getZ()));
            world.getServer().runCommand("tp @a " + (position.getX() + 0.5) + " " + groundY
                    + " " + (position.getZ() + 0.5) + " 0 15");
            context.runOnClient(client -> {
                client.player.getAbilities().flying = false;
                client.player.onUpdateAbilities();
            });
            context.waitFor(client -> !client.player.getAbilities().flying);
            input.holdKey(options -> options.keyUp);
            input.holdKey(options -> options.keyJump);
            context.waitTicks(120);
            input.releaseKey(options -> options.keyUp);
            input.releaseKey(options -> options.keyJump);
            context.waitTicks(20);

            phase(context, output, report, phases, "inventory-place-break");
            input.pressKey(options -> options.keyInventory);
            context.waitFor(client -> client.gui.screen() != null);
            context.waitTicks(30);
            input.pressKey(options -> options.keyInventory);
            context.waitFor(client -> client.gui.screen() == null);
            world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:stone 64");
            input.pressKey(options -> options.keyHotbarSlots[0]);
            context.waitFor(client -> client.player.getMainHandItem().is(net.minecraft.world.item.Items.STONE));
            // Aim beyond the player's collision box; a near-vertical placement
            // targets the block occupied by the player and Vanilla rejects it.
            input.lookAt(0, 45);
            context.waitTicks(10);
            BlockPos target = context.computeOnClient(client -> {
                require(client.hitResult instanceof BlockHitResult && client.hitResult.getType() == HitResult.Type.BLOCK,
                        "No ground block targeted for placement");
                BlockHitResult hit = (BlockHitResult) client.hitResult;
                // Tall grass/snow can be replaced in-place; Vanilla owns that decision.
                return new net.minecraft.world.item.context.BlockPlaceContext(client.player,
                        net.minecraft.world.InteractionHand.MAIN_HAND, client.player.getMainHandItem(), hit).getClickedPos();
            });
            report.addProperty("placementTarget", target.toShortString());
            write(output.resolve("gameplay-progress.json"), report);
            input.holdKeyFor(options -> options.keyUse, 2);
            context.waitFor(client -> client.level.getBlockState(target).is(net.minecraft.world.level.block.Blocks.STONE));
            report.addProperty("placedBlock", target.toShortString());
            input.lookAt(target);
            input.holdKeyFor(options -> options.keyAttack, 10);
            context.waitFor(client -> client.level.getBlockState(target).isAir());
            report.addProperty("placedAndBroken", true);

            phase(context, output, report, phases, "weather-camera");
            world.getServer().runCommand("weather rain");
            for (int step = 0; step < 8; step++) {
                input.lookAt(step * 45, step % 2 == 0 ? -15 : 30);
                context.waitTicks(30);
            }
            report.addProperty("status", "completed");
            long[] finalMetal4 = context.computeOnClient(client -> MetalNativeBridge.metallum_metal4_main_renderer_stats());
            require(finalMetal4[0] == 1 && finalMetal4[2] > initialMetal4[2],
                    "Metal 4 did not submit work during gameplay");
            report.addProperty("metal4Submissions", finalMetal4[2] - initialMetal4[2]);
            report.add("sourceFrames", context.computeOnClient(client -> finishFrames()));
            JsonObject finalSettings = context.computeOnClient(VanillaGameplay::settings);
            report.add("finalSettings", finalSettings);
            require(settings.equals(finalSettings), "Rendering settings changed during the route");
            require(invalidSettingsFrames == 0 && droppedSamples == 0 && throttledFrames == 0,
                    "Source frame measurements failed the full-resolution, maximum-distance or cadence gate");
            report.addProperty("completedAt", Instant.now().toString());
            write(output.resolve("gameplay.json"), report);
            context.takeScreenshot("vanilla-gameplay-completed");
        } catch (RuntimeException | Error failure) {
            report.addProperty("status", "failed");
            report.addProperty("failure", failure.toString());
            write(output.resolve("gameplay.json"), report);
            throw failure;
        } finally {
            context.runOnClient(client -> recordingFrames = false);
            input.releaseKey(options -> options.keyUp);
            input.releaseKey(options -> options.keySprint);
            input.releaseKey(options -> options.keyJump);
            input.releaseKey(options -> options.keyAttack);
            input.releaseKey(options -> options.keyUse);
        }
    }

    private static void phase(ClientGameTestContext context, Path output, JsonObject report,
                              JsonArray phases, String name) {
        JsonObject phase = context.computeOnClient(client -> {
            JsonObject value = new JsonObject();
            value.addProperty("name", name);
            value.addProperty("at", Instant.now().toString());
            value.addProperty("x", client.player.getX());
            value.addProperty("y", client.player.getY());
            value.addProperty("z", client.player.getZ());
            value.addProperty("sourceFrames", frameCount);
            value.addProperty("elapsedNanos", System.nanoTime() - startedNanos);
            return value;
        });
        phases.add(phase);
        write(output.resolve("gameplay-progress.json"), report);
    }

    private static JsonObject settings(Minecraft client) {
        JsonObject value = new JsonObject();
        value.addProperty("framebufferWidth", client.getWindow().getWidth());
        value.addProperty("framebufferHeight", client.getWindow().getHeight());
        value.addProperty("renderWidth", client.gameRenderer.mainRenderTarget().width);
        value.addProperty("renderHeight", client.gameRenderer.mainRenderTarget().height);
        value.addProperty("renderDistance", client.options.renderDistance().get());
        value.addProperty("effectiveRenderDistance", client.options.getEffectiveRenderDistance());
        value.addProperty("simulationDistance", client.options.simulationDistance().get());
        value.addProperty("graphicsPreset", client.options.graphicsPreset().get().toString());
        value.addProperty("ambientOcclusion", client.options.ambientOcclusion().get());
        value.addProperty("clouds", client.options.cloudStatus().get().toString());
        value.addProperty("cloudRange", client.options.cloudRange().get());
        value.addProperty("particles", client.options.particles().get().toString());
        value.addProperty("mipmapLevels", client.options.mipmapLevels().get());
        value.addProperty("entityDistanceScaling", client.options.entityDistanceScaling().get());
        value.addProperty("entityShadows", client.options.entityShadows().get());
        value.addProperty("biomeBlendRadius", client.options.biomeBlendRadius().get());
        value.addProperty("improvedTransparency", client.options.improvedTransparency().get());
        value.addProperty("textureFiltering", client.options.textureFiltering().get().toString());
        value.addProperty("maxAnisotropyBit", client.options.maxAnisotropyBit().get());
        value.addProperty("cutoutLeaves", client.options.cutoutLeaves().get());
        value.addProperty("weatherRadius", client.options.weatherRadius().get());
        value.addProperty("vsync", client.options.enableVsync().get());
        value.addProperty("fpsLimitOption", client.options.framerateLimit().get());
        return value;
    }

    private static JsonObject finishFrames() {
        recordingFrames = false;
        long elapsed = System.nanoTime() - startedNanos;
        long[] intervals = new long[Math.max(0, frameCount - 1)];
        for (int i = 1; i < frameCount; i++) intervals[i - 1] = FRAME_TIMES[i] - FRAME_TIMES[i - 1];
        java.util.Arrays.sort(intervals);
        JsonObject value = new JsonObject();
        value.addProperty("boundary", "Minecraft.renderFrame: after GpuSurface.present; source submissions, not display refresh or generated frames");
        value.addProperty("count", frameCount);
        value.addProperty("elapsedNanos", elapsed);
        value.addProperty("fps", frameCount * 1_000_000_000.0 / elapsed);
        value.addProperty("invalidSettingsFrames", invalidSettingsFrames);
        value.addProperty("throttledFrames", throttledFrames);
        value.addProperty("droppedSamples", droppedSamples);
        if (intervals.length > 0) {
            for (int percentile : new int[]{50, 95, 99}) {
                int index = (int) Math.ceil(intervals.length * percentile / 100.0) - 1;
                value.addProperty("intervalP" + percentile + "Ms", intervals[index] / 1_000_000.0);
            }
        }
        return value;
    }

    private static void write(Path path, JsonObject value) {
        try {
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n");
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write gameplay report", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
