package com.metallum.e2e;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Opt-in real-client workload using Fabric's input driver, in the existing normal world. */
final class VanillaGameplay {
    static void run(ClientGameTestContext context, TestSingleplayerContext world,
                    Path output, JsonObject worldEvidence) {
        var input = context.getInput();
        JsonObject report = new JsonObject();
        report.addProperty("scenario", "vanilla-normal-gameplay-v1");
        report.addProperty("pid", ProcessHandle.current().pid());
        report.add("world", worldEvidence);
        JsonArray phases = new JsonArray();
        report.add("phases", phases);
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            client.options.renderDistance().set(16);
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260);
        });
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
        try {
            phase(context, output, report, phases, "flight-new-chunks");
            double startX = context.computeOnClient(client -> client.player.getX());
            double startZ = context.computeOnClient(client -> client.player.getZ());
            input.holdKey(options -> options.keyUp);
            input.holdKey(options -> options.keySprint);
            for (int leg = 0; leg < 6; leg++) {
                input.lookAt(-65 + leg * 12, 15);
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
            input.lookAt(0, 75);
            context.waitTicks(10);
            BlockPos target = context.computeOnClient(client -> {
                require(client.hitResult instanceof BlockHitResult && client.hitResult.getType() == HitResult.Type.BLOCK,
                        "No ground block targeted for placement");
                BlockHitResult hit = (BlockHitResult) client.hitResult;
                return hit.getBlockPos().relative(hit.getDirection());
            });
            input.pressKey(options -> options.keyUse);
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
            report.addProperty("completedAt", Instant.now().toString());
            write(output.resolve("gameplay.json"), report);
            context.takeScreenshot("vanilla-gameplay-completed");
        } catch (RuntimeException | Error failure) {
            report.addProperty("status", "failed");
            report.addProperty("failure", failure.toString());
            write(output.resolve("gameplay.json"), report);
            throw failure;
        } finally {
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
            return value;
        });
        phases.add(phase);
        write(output.resolve("gameplay-progress.json"), report);
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
