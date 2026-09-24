package com.metallum.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.ServerLevelData;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@SuppressWarnings("UnstableApiUsage")
public final class MetalUniversalClientGameTest implements FabricClientGameTest {
    private static final String SCREENSHOT_NAME = "metaluniversal-hosted-world";
    private static final String RENDER_CONTRACT_RUNTIME =
            "com.metallum.client.validation.contract.RenderContractRuntime";
    private static final int METAL_CAPTURE_SAMPLES = 8;
    private static final String P1_FRAMEBUFFER_SCENARIO = "p1-stationary-framebuffer-equivalence-v2";
    private static final long P1_FRAMEBUFFER_GAME_TIME = 6000L;
    private static final int P1_FRAMEBUFFER_X = 832;
    private static final int P1_FRAMEBUFFER_Y = 128;
    private static final int P1_FRAMEBUFFER_Z = 496;
    private static final float P1_FRAMEBUFFER_YAW = -65.0F;
    private static final float P1_FRAMEBUFFER_PITCH = 25.0F;

    private static void requestStationaryServerHalt(TestSingleplayerContext singleplayer) {
        // Fabric's TestSingleplayerContext.close() disconnects the client and then
        // calls IntegratedServer.halt() from the client thread. In 26.3 that halt
        // executes server work synchronously and can deadlock the GameTest phase
        // barrier. Request it on the server owner immediately before the resource
        // close; the Fabric close remains the sole client disconnect/wait owner.
        singleplayer.getServer().computeOnServer(instance -> {
            instance.halt(false);
            return null;
        });
    }

    private static TestSingleplayerContext openGameplayWorld(ClientGameTestContext context, Path output) {
        TestSingleplayerContext created = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(settings.getSettings()
                            .worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)));
                    settings.setName("MetalUniversal normal terrain");
                    settings.setSeed("1");
                    settings.setGenerateStructures(true);
                }).create();
        String snapshotProperty = System.getProperty("metallum.ci.initialWorld", "");
        if (snapshotProperty.isEmpty()) return created;
        var save = created.getWorldSave();
        created.close();
        Path snapshot = Path.of(snapshotProperty).toAbsolutePath().normalize();
        Path disposable = save.getSaveDirectory().toAbsolutePath().normalize();
        require(!snapshot.startsWith(disposable) && !disposable.startsWith(snapshot), "Snapshot overlaps disposable test world");
        WorldSnapshot.verify(snapshot);
        try {
            // This directory was created by this invocation above; never replace a user-selected world.
            try (var paths = Files.walk(disposable)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            WorldSnapshot.copyVerified(snapshot, disposable);
            Path retained = output.resolve("initial-world");
            WorldSnapshot.copyVerified(snapshot, retained);
            Files.copy(snapshot.resolveSibling(snapshot.getFileName() + "-manifest.json"),
                    output.resolve("initial-world-manifest.json"));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot restore disposable test world from verified snapshot", failure);
        }
        return save.open();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(evidenceDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create CI evidence directory " + evidenceDir, exception);
        }

        boolean metallumLoaded = FabricLoader.getInstance().isModLoaded("metallum");
        boolean sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        boolean irisLoaded = FabricLoader.getInstance().isModLoaded("iris");

        require(metallumLoaded, "MetalUniversal mod was not loaded in the production client");
        boolean vanillaOnly = Boolean.getBoolean("metallum.ci.noOptionalMods");
        boolean sodiumOnly = Boolean.getBoolean("metallum.ci.sodiumOnly");
        require(!(vanillaOnly && sodiumOnly), "Vanilla and Sodium-only lanes cannot both be selected");
        if (FrameWorkloads.ENABLED) FrameWorkloads.validateProducer(FrameWorkloads.PRODUCER, sodiumLoaded, irisLoaded);
        else {
            require(sodiumLoaded == !vanillaOnly, "Sodium runtime presence disagrees with the requested lane");
            require(irisLoaded == (!vanillaOnly && !sodiumOnly),
                    "Iris runtime presence disagrees with the requested lane");
        }
        writeLoadedArtifactIdentity(evidenceDir.resolve("artifact-identity.json"), vanillaOnly, sodiumOnly);

        // Fabric's consistent-settings default is superflat. Exercise the real Overworld here.
        try (TestSingleplayerContext singleplayer = openGameplayWorld(context, evidenceDir)) {
            boolean p1FramebufferScenario = Boolean.getBoolean("metallum.ci.p1StationaryFramebufferCapture");
            int chunkRenderTicks = singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(40);
            JsonObject worldEvidence = singleplayer.getServer().computeOnServer(server -> {
                var level = server.overworld();
                require(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                        "Expected normal noise-based Overworld generation");
                require(level.getSeed() == 1, "Replay must preserve the fixed normal-world seed");
                JsonObject world = new JsonObject();
                world.addProperty("generator", level.getChunkSource().getGenerator().getClass().getSimpleName());
                world.addProperty("seed", level.getSeed());
                world.addProperty("preset", "minecraft:normal");
                world.addProperty("generateStructures", true);
                world.addProperty("minecraftVersion", "26.3");
                world.addProperty("framebufferEquivalenceScenario",
                        p1FramebufferScenario ? P1_FRAMEBUFFER_SCENARIO : "moving-waypoint-diagnostic-v1");
                world.addProperty("simulationFrozenDuringFramebufferCapture", false);
                world.addProperty("serverSimulationFrozenDuringFramebufferCapture", false);
                world.addProperty("clientSimulationFrozenDuringFramebufferCapture", false);
                String snapshot = System.getProperty("metallum.ci.initialWorld", "");
                if (!snapshot.isEmpty()) world.addProperty("replaySourceSnapshotSha256",
                        WorldSnapshot.verify(Path.of(snapshot).toAbsolutePath().normalize()));
                world.addProperty("saveDirectory", singleplayer.getWorldSave().getSaveDirectory().toString());
                return world;
            });
            JsonArray waypoints = new JsonArray();
            worldEvidence.add("waypoints", waypoints);
            if (Boolean.getBoolean("metallum.ci.gameplay")) {
                VanillaGameplay.run(context, singleplayer, evidenceDir, worldEvidence);
                if (Boolean.getBoolean("metallum.ci.stationaryBaseline")) {
                    requestStationaryServerHalt(singleplayer);
                }
                return;
            }
            singleplayer.getServer().runCommand("gamemode spectator @a");
            singleplayer.getServer().runCommand("time set noon");

            String backend = context.computeOnClient(
                    client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            );
            boolean worldLoaded = context.computeOnClient(client -> client.level != null);

            require(worldLoaded, "Minecraft client has no loaded level after Client GameTest world creation");
            require(
                    "Metal".equalsIgnoreCase(backend),
                    "Expected MetalUniversal Metal backend, observed graphics backend: " + backend
            );

            // The ordinary Fabric screenshot is retained as a diagnostic only. GitHub's hosted
            // WindowServer does not reliably scan out CAMetalLayer drawables, so authoritative
            // evidence is sampled from MetalUniversal's pre-present source texture through its
            // production RenderContractRuntime GPU texture -> buffer readback path.
            Path metalCaptureRoot = evidenceDir.resolve("metal-framebuffer");
            startRenderContract(metalCaptureRoot);
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");

            List<CaptureSample> samples = new ArrayList<>();
            boolean p1SceneFrozen = false;
            try {
                JsonArray cameraSamples = new JsonArray();
                if (p1FramebufferScenario) {
                    JsonObject p1GameRules = singleplayer.getServer().computeOnServer(server -> {
                        var rules = server.overworld().getGameRules();
                        rules.set(GameRules.ADVANCE_TIME, false, server);
                        rules.set(GameRules.ADVANCE_WEATHER, false, server);
                        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
                        rules.set(GameRules.SPAWN_MOBS, false, server);
                        JsonObject configured = new JsonObject();
                        configured.addProperty("advance_time", rules.get(GameRules.ADVANCE_TIME));
                        configured.addProperty("advance_weather", rules.get(GameRules.ADVANCE_WEATHER));
                        configured.addProperty("random_tick_speed", rules.get(GameRules.RANDOM_TICK_SPEED));
                        configured.addProperty("spawn_mobs", rules.get(GameRules.SPAWN_MOBS));
                        return configured;
                    });
                    require(!p1GameRules.get("advance_time").getAsBoolean()
                                    && !p1GameRules.get("advance_weather").getAsBoolean()
                                    && p1GameRules.get("random_tick_speed").getAsInt() == 0
                                    && !p1GameRules.get("spawn_mobs").getAsBoolean(),
                            "P1 framebuffer gamerules were not applied to the 26.3 world");
                    worldEvidence.add("p1GameRules", p1GameRules);
                    singleplayer.getServer().runCommand("difficulty peaceful");
                    singleplayer.getServer().runCommand("weather clear 1000000");
                    singleplayer.getServer().runCommand("time set noon");
                    singleplayer.getServer().runCommand("tp @a " + P1_FRAMEBUFFER_X + " " + P1_FRAMEBUFFER_Y
                            + " " + P1_FRAMEBUFFER_Z + " " + P1_FRAMEBUFFER_YAW + " " + P1_FRAMEBUFFER_PITCH);
                    context.waitFor(client -> client.player != null
                            && Math.abs(client.player.getX() - P1_FRAMEBUFFER_X) < 1
                            && Math.abs(client.player.getY() - P1_FRAMEBUFFER_Y) < 1
                            && Math.abs(client.player.getZ() - P1_FRAMEBUFFER_Z) < 1);
                    chunkRenderTicks += singleplayer.getConnection().waitForChunksRender();
                    context.waitTicks(20);

                    boolean serverFrozen = singleplayer.getServer().computeOnServer(server -> {
                        server.overworld().tickRateManager().setFrozen(true);
                        return server.overworld().tickRateManager().isFrozen();
                    });
                    p1SceneFrozen = true;
                    require(serverFrozen, "P1 server simulation did not freeze for the stationary framebuffer samples");
                    boolean clientFrozen = context.computeOnClient(client -> {
                        if (client.level != null) {
                            client.level.tickRateManager().setFrozen(true);
                            return client.level.tickRateManager().isFrozen();
                        }
                        return false;
                    });
                    require(clientFrozen, "P1 client simulation did not freeze for the stationary framebuffer samples");
                    long serverGameTime = singleplayer.getServer().computeOnServer(server -> {
                        var level = server.overworld();
                        ((ServerLevelData) level.getLevelData()).setGameTime(P1_FRAMEBUFFER_GAME_TIME);
                        server.getPlayerList().broadcastAll(
                                new ClientboundSetTimePacket(P1_FRAMEBUFFER_GAME_TIME, Map.of()), level.dimension());
                        return level.getGameTime();
                    });
                    require(serverGameTime == P1_FRAMEBUFFER_GAME_TIME,
                            "P1 server game time was not pinned for the stationary framebuffer samples");
                    context.waitFor(client -> client.level != null
                            && client.level.getGameTime() == P1_FRAMEBUFFER_GAME_TIME);
                    long clientGameTime = context.computeOnClient(client -> client.level.getGameTime());
                    require(clientGameTime == P1_FRAMEBUFFER_GAME_TIME,
                            "P1 client did not receive the fixed server game time before framebuffer sampling");
                    worldEvidence.addProperty("simulationFrozenDuringFramebufferCapture", true);
                    worldEvidence.addProperty("serverSimulationFrozenDuringFramebufferCapture", serverFrozen);
                    worldEvidence.addProperty("clientSimulationFrozenDuringFramebufferCapture", clientFrozen);
                    worldEvidence.addProperty("p1FixedGameTime", P1_FRAMEBUFFER_GAME_TIME);
                    worldEvidence.addProperty("serverGameTimeAtCapture", serverGameTime);
                    worldEvidence.addProperty("clientGameTimeAtCapture", clientGameTime);
                    context.waitTicks(20);
                    context.computeOnClient(client -> {
                        client.gui.hud.getChat().clearMessages(false);
                        client.gui.hud.clearTitles();
                        client.gui.hud.resetTitleTimes();
                        return null;
                    });
                    worldEvidence.addProperty("clientPresentationWarmupTicks", 20);
                    worldEvidence.addProperty("transientHudMessagesCleared", true);
                    worldEvidence.add("cameraSamples", cameraSamples);
                    JsonObject waypoint = new JsonObject();
                    waypoint.addProperty("frameId", 1);
                    waypoint.addProperty("x", P1_FRAMEBUFFER_X);
                    waypoint.addProperty("y", P1_FRAMEBUFFER_Y);
                    waypoint.addProperty("z", P1_FRAMEBUFFER_Z);
                    waypoint.addProperty("yaw", P1_FRAMEBUFFER_YAW);
                    waypoint.addProperty("pitch", P1_FRAMEBUFFER_PITCH);
                    waypoints.add(waypoint);
                }

                for (long frameId = 1; frameId <= METAL_CAPTURE_SAMPLES; frameId++) {
                    if (p1FramebufferScenario) {
                        JsonObject cameraSample = lockAndDescribeP1Camera(context, frameId);
                        validateP1CameraSample(cameraSample, frameId);
                        cameraSamples.add(cameraSample);
                    } else {
                        // Move beyond the spawn region, allowing ordinary generation and chunk upload.
                        // A fixed seed still has a randomized player spawn within the spawn radius.
                        int x = 160 + (int) (frameId - 1) * 96;
                        int z = 160 + (int) (frameId - 1) * 48;
                        int y = 128;
                        singleplayer.getServer().runCommand("tp @a " + x + " " + y + " " + z + " -65 25");
                        if (frameId == 1) {
                            singleplayer.getServer().runCommand("summon minecraft:text_display " + (x + 8) + " " + (y - 3)
                                    + " " + (z + 4) + " {text:\"Vanilla 26.3\",billboard:\"center\",shadow:1b}");
                        }
                        context.waitFor(client -> client.player != null
                                && Math.abs(client.player.getX() - x) < 1 && Math.abs(client.player.getZ() - z) < 1);
                        context.waitTicks(10);
                        singleplayer.getConnection().waitForChunksRender();
                        JsonObject waypoint = new JsonObject();
                        waypoint.addProperty("frameId", frameId);
                        waypoint.addProperty("x", x);
                        waypoint.addProperty("y", y);
                        waypoint.addProperty("z", z);
                        waypoints.add(waypoint);
                    }

                    RenderContractSnapshot before = renderContractSnapshot();
                    beginRenderContractFrame(frameId);
                    try {
                        requestFinalDrawableCapture(frameId);
                        waitForCaptureCompletion(context, before.completedCaptures() + 1, frameId, 100);
                    } finally {
                        endRenderContractFrame(frameId);
                    }

                    Path png = findFrameArtifact(metalCaptureRoot, frameId, "actual.png");
                    Path raw = findFrameArtifact(metalCaptureRoot, frameId, "actual.bin");
                    require(Files.isRegularFile(png), "Missing Metal framebuffer PNG for frame " + frameId);
                    require(Files.isRegularFile(raw), "Missing Metal framebuffer raw readback for frame " + frameId);
                    CaptureSample sample = inspectCapture(frameId, png, raw);
                    samples.add(sample);
                    context.waitTicks(4);
                }
            } finally {
                if (p1SceneFrozen) {
                    context.computeOnClient(client -> {
                        if (client.level != null) client.level.tickRateManager().setFrozen(false);
                        return null;
                    });
                    singleplayer.getServer().computeOnServer(server -> {
                        server.overworld().tickRateManager().setFrozen(false);
                        return null;
                    });
                }
            }

            CaptureSample selected = selectBestCapture(samples);
            require(selected != null, "Metal framebuffer sampling produced no captures");
            Path canonicalMetalFramebuffer = evidenceDir.resolve("metal-framebuffer.png");
            try {
                Files.copy(selected.png(), canonicalMetalFramebuffer, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not stage selected Metal framebuffer", exception);
            }
            writeCaptureSamples(evidenceDir.resolve("metal-framebuffer-samples.json"), samples, selected.frameId());

            RenderContractSnapshot contractSnapshot = renderContractSnapshot();
            closeRenderContract();

            Path windowScreenshot = context.takeScreenshot(SCREENSHOT_NAME);
            require(Files.isRegularFile(windowScreenshot),
                    "Client GameTest did not create diagnostic window screenshot: " + windowScreenshot);
            require(fileSize(windowScreenshot) > 0,
                    "Client GameTest diagnostic window screenshot is empty: " + windowScreenshot);

            writeEvidence(
                    evidenceDir.resolve("runtime-evidence.json"),
                    backend,
                    metallumLoaded,
                    sodiumLoaded,
                    irisLoaded,
                    worldLoaded,
                    chunkRenderTicks,
                    windowScreenshot.toAbsolutePath().normalize(),
                    canonicalMetalFramebuffer.toAbsolutePath().normalize(),
                    contractSnapshot,
                    samples.size(),
                    selected,
                    worldEvidence
            );

            require(contractSnapshot.completedCaptures() == METAL_CAPTURE_SAMPLES,
                    "Expected " + METAL_CAPTURE_SAMPLES + " completed Metal framebuffer captures: " + contractSnapshot);
            require(contractSnapshot.failedCaptures() == 0,
                    "Metal render-contract reported failed captures: " + contractSnapshot);
            require(contractSnapshot.pendingCaptures() == 0,
                    "Metal render-contract still has pending captures: " + contractSnapshot);
            require(contractSnapshot.droppedCaptures() == 0,
                    "Metal render-contract dropped framebuffer captures: " + contractSnapshot);
            require(selected.nonBlackPixels() > 0 && selected.distinctRgb() > 1,
                    "All sampled Metal framebuffers were black/constant; best sample=" + selected);
        } finally {
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");
            closeRenderContractQuietly();
        }
    }

    private static void startRenderContract(Path output) {
        try {
            Files.createDirectories(output);
            System.setProperty("metallum.renderContract.enabled", "true");
            System.setProperty("metallum.renderContract.maxCaptures", Integer.toString(METAL_CAPTURE_SAMPLES + 4));
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            Method start = runtime.getMethod("start", Path.class, String.class);
            start.invoke(null, output, "minecraft-client-gametest");
        } catch (IOException | ClassNotFoundException | NoSuchMethodException
                 | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not start Metal render-contract framebuffer capture", exception);
        }
    }

    private static void beginRenderContractFrame(long frameId) {
        invokeRenderContractFrameMethod("beginFrame", frameId);
    }

    private static void endRenderContractFrame(long frameId) {
        invokeRenderContractFrameMethod("endFrame", frameId);
    }

    private static void requestFinalDrawableCapture(long frameId) {
        invokeRenderContractFrameMethod("requestFinalDrawableCapture", frameId);
    }

    private static void invokeRenderContractFrameMethod(String name, long frameId) {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            runtime.getMethod(name, long.class).invoke(null, frameId);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Could not invoke RenderContractRuntime." + name + "(" + frameId + ")", exception);
        }
    }

    private static void waitForCaptureCompletion(
            ClientGameTestContext context,
            int expectedCompletedCaptures,
            long frameId,
            int maxTicks
    ) {
        for (int tick = 0; tick < maxTicks; tick++) {
            RenderContractSnapshot snapshot = renderContractSnapshot();
            if (snapshot.failedCaptures() > 0 || snapshot.droppedCaptures() > 0) {
                throw new IllegalStateException(
                        "Metal framebuffer capture failed while waiting for frame " + frameId + ": " + snapshot
                );
            }
            if (snapshot.completedCaptures() >= expectedCompletedCaptures && snapshot.pendingCaptures() == 0) {
                return;
            }
            context.waitTicks(1);
        }
        throw new IllegalStateException(
                "Timed out waiting for Metal framebuffer frame " + frameId
                        + "; snapshot=" + renderContractSnapshot()
        );
    }

    private static Path findFrameArtifact(Path root, long frameId, String fileName) {
        Path frameRoot = root.resolve("render-contract")
                .resolve("frames")
                .resolve("frame-%06d".formatted(frameId));
        if (!Files.isDirectory(frameRoot)) {
            throw new IllegalStateException("Render-contract frame directory is missing: " + frameRoot);
        }
        try (Stream<Path> paths = Files.walk(frameRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Could not find " + fileName + " for Metal framebuffer frame " + frameId
                    ));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect Metal framebuffer frame " + frameId, exception);
        }
    }

    private static CaptureSample inspectCapture(long frameId, Path png, Path raw) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(raw);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Metal framebuffer bytes " + raw, exception);
        }
        require(bytes.length > 0 && bytes.length % 4 == 0,
                "Unexpected RGBA8 Metal framebuffer byte count for frame " + frameId + ": " + bytes.length);

        long nonZeroBytes = 0;
        long nonBlackPixels = 0;
        double sumLuma = 0.0;
        double sumLumaSquared = 0.0;
        Set<Integer> distinct = new HashSet<>();
        int pixels = bytes.length / 4;

        for (int offset = 0; offset < bytes.length; offset += 4) {
            int r = bytes[offset] & 0xff;
            int g = bytes[offset + 1] & 0xff;
            int b = bytes[offset + 2] & 0xff;
            int a = bytes[offset + 3] & 0xff;
            if (r != 0) nonZeroBytes++;
            if (g != 0) nonZeroBytes++;
            if (b != 0) nonZeroBytes++;
            if (a != 0) nonZeroBytes++;
            if (r > 4 || g > 4 || b > 4) nonBlackPixels++;
            if (distinct.size() < 8192) {
                distinct.add((r << 16) | (g << 8) | b);
            }
            double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            sumLuma += luma;
            sumLumaSquared += luma * luma;
        }

        double mean = sumLuma / pixels;
        double variance = Math.max(0.0, sumLumaSquared / pixels - mean * mean);
        double stddev = Math.sqrt(variance);
        return new CaptureSample(
                frameId,
                png.toAbsolutePath().normalize(),
                raw.toAbsolutePath().normalize(),
                bytes.length,
                nonZeroBytes,
                nonBlackPixels,
                distinct.size(),
                mean,
                stddev
        );
    }

    private static CaptureSample selectBestCapture(List<CaptureSample> samples) {
        CaptureSample best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (CaptureSample sample : samples) {
            double score = sample.lumaStddev() * 1_000_000.0
                    + sample.distinctRgb() * 1_000.0
                    + sample.nonBlackPixels();
            if (score > bestScore) {
                bestScore = score;
                best = sample;
            }
        }
        return best;
    }

    private static void writeCaptureSamples(Path path, List<CaptureSample> samples, long selectedFrameId) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema\": 1,\n  \"selectedFrameId\": ").append(selectedFrameId)
                .append(",\n  \"samples\": [\n");
        for (int i = 0; i < samples.size(); i++) {
            CaptureSample sample = samples.get(i);
            json.append("    {\n")
                    .append("      \"frameId\": ").append(sample.frameId()).append(",\n")
                    .append("      \"byteCount\": ").append(sample.byteCount()).append(",\n")
                    .append("      \"nonZeroBytes\": ").append(sample.nonZeroBytes()).append(",\n")
                    .append("      \"nonBlackPixels\": ").append(sample.nonBlackPixels()).append(",\n")
                    .append("      \"distinctRgb\": ").append(sample.distinctRgb()).append(",\n")
                    .append("      \"meanLuma\": ").append(String.format(java.util.Locale.ROOT, "%.6f", sample.meanLuma())).append(",\n")
                    .append("      \"lumaStddev\": ").append(String.format(java.util.Locale.ROOT, "%.6f", sample.lumaStddev())).append(",\n")
                    .append("      \"png\": \"").append(escape(sample.png().toString())).append("\",\n")
                    .append("      \"raw\": \"").append(escape(sample.raw().toString())).append("\"\n")
                    .append("    }");
            if (i + 1 < samples.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write Metal framebuffer sample summary " + path, exception);
        }
    }

    private static RenderContractSnapshot renderContractSnapshot() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            Object snapshot = runtime.getMethod("snapshot").invoke(null);
            Class<?> snapshotClass = snapshot.getClass();
            return new RenderContractSnapshot(
                    (boolean) snapshotClass.getMethod("enabled").invoke(snapshot),
                    (String) snapshotClass.getMethod("status").invoke(snapshot),
                    (int) snapshotClass.getMethod("requestedCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("completedCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("failedCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("pendingCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("droppedCaptures").invoke(snapshot)
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Could not inspect Metal render-contract capture state", exception);
        }
    }

    private static void closeRenderContract() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            runtime.getMethod("close").invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Could not close Metal render-contract capture", exception);
        }
    }

    private static void closeRenderContractQuietly() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            runtime.getMethod("close").invoke(null);
        } catch (ReflectiveOperationException ignored) {
            // Best-effort cleanup after the test has already produced its primary failure.
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not stat evidence file " + path, exception);
        }
    }

    /** Read the artifact that actually defined the backend, not a requested path or SHA. */
    private static void writeLoadedArtifactIdentity(Path output, boolean vanillaOnly, boolean sodiumOnly) {
        try {
            Class<?> backend = Class.forName("com.metallum.client.metal.render.MetalDevice");
            Path jar = Path.of(backend.getProtectionDomain().getCodeSource().getLocation().toURI());
            require(Files.isRegularFile(jar), "Production test loaded development classes instead of a JAR: " + jar);
            JsonObject report = new JsonObject();
            report.addProperty("rendererMode", FrameWorkloads.ENABLED ? FrameWorkloads.PRODUCER
                    : vanillaOnly ? "vanilla" : sodiumOnly ? "sodium" : "sodium-iris");
            report.addProperty("loadedJavaArtifact", jar.toString());
            try (var input = Files.newInputStream(jar)) {
                report.addProperty("javaArtifactSha256", sha256(input));
            }
            try (ZipFile archive = new ZipFile(jar.toFile())) {
                try (var input = archive.getInputStream(archive.getEntry("metallum-build-identity.json"))) {
                    JsonObject build = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    String expected = System.getProperty("metallum.ci.expectedSourceSha", "unrecorded");
                    require(expected.matches("[0-9a-f]{40}"), "Production test requires an exact expected source SHA");
                    require(expected.equals(build.get("sourceSha").getAsString()), "Loaded JAR has the wrong source SHA");
                    require(!build.get("dirty").getAsBoolean(), "Loaded JAR was built from a dirty checkout");
                    report.add("build", build);
                }
                try (var input = archive.getInputStream(archive.getEntry("natives/macos/libmetallum.dylib"))) {
                    // This is the bundled artifact hash; it does not claim an independently observed load path.
                    report.addProperty("packagedNativeSha256", sha256(input));
                }
            }
            // Resolve the successfully loaded file, not a caller-provided path or the archive entry.
            // Hash before the workload window, identically for OFF/timing/diagnostic.
            Class<?> bridge = Class.forName("com.metallum.client.metal.render.bridge.MetalNativeBridge");
            Path loadedNative = (Path) bridge.getMethod("loadedLibraryFileForDiagnostics").invoke(null);
            if (loadedNative == null) {
                report.addProperty("loadedNativeUnavailableReason", "loader-does-not-expose-a-file");
            } else try (var input = Files.newInputStream(loadedNative)) {
                String actual = sha256(input);
                report.addProperty("loadedNativeSha256", actual);
                require(actual.equals(report.get("packagedNativeSha256").getAsString()), "Actually loaded native file differs from the packaged binary");
            }
            JsonObject mods = new JsonObject();
            FabricLoader.getInstance().getAllMods().stream()
                    .sorted(java.util.Comparator.comparing(mod -> mod.getMetadata().getId()))
                    .forEach(mod -> mods.addProperty(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
            report.add("mods", mods);
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        } catch (Exception exception) {
            throw new IllegalStateException("Could not verify the loaded production artifact", exception);
        }
    }

    private static String sha256(java.io.InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[65_536];
        for (int count; (count = input.read(chunk)) != -1;) digest.update(chunk, 0, count);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeEvidence(
            Path path,
            String backend,
            boolean metallumLoaded,
            boolean sodiumLoaded,
            boolean irisLoaded,
            boolean worldLoaded,
            int chunkRenderTicks,
            Path windowScreenshot,
            Path metalFramebuffer,
            RenderContractSnapshot contractSnapshot,
            int sampledFrames,
            CaptureSample selected,
            JsonObject worldEvidence
    ) {
        String json = """
                {
                  "schema": 3,
                  "backend": "%s",
                  "minecraft": "26.3",
                  "productionRuntime": true,
                  "clientGameTest": true,
                  "metallumLoaded": %s,
                  "sodiumLoaded": %s,
                  "irisLoaded": %s,
                  "worldLoaded": %s,
                  "chunksRendered": true,
                  "chunkRenderTicks": %d,
                  "windowScreenshot": "%s",
                  "metalFramebuffer": "%s",
                  "metalFramebufferSource": "MetalUniversal pre-present source texture GPU readback",
                  "sampledMetalFrames": %d,
                  "selectedMetalFrameId": %d,
                  "selectedMetalFrameNonZeroBytes": %d,
                  "selectedMetalFrameNonBlackPixels": %d,
                  "selectedMetalFrameDistinctRgb": %d,
                  "selectedMetalFrameMeanLuma": %.6f,
                  "selectedMetalFrameLumaStddev": %.6f,
                  "renderContractRequestedCaptures": %d,
                  "renderContractCompletedCaptures": %d,
                  "renderContractFailedCaptures": %d,
                  "renderContractDroppedCaptures": %d,
                  "completedAt": "%s"
                }
                """.formatted(
                escape(backend),
                metallumLoaded,
                sodiumLoaded,
                irisLoaded,
                worldLoaded,
                chunkRenderTicks,
                escape(windowScreenshot.toString()),
                escape(metalFramebuffer.toString()),
                sampledFrames,
                selected.frameId(),
                selected.nonZeroBytes(),
                selected.nonBlackPixels(),
                selected.distinctRgb(),
                selected.meanLuma(),
                selected.lumaStddev(),
                contractSnapshot.requestedCaptures(),
                contractSnapshot.completedCaptures(),
                contractSnapshot.failedCaptures(),
                contractSnapshot.droppedCaptures(),
                escape(Instant.now().toString())
        );

        try {
            JsonObject report = JsonParser.parseString(json).getAsJsonObject();
            report.add("world", worldEvidence);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write runtime evidence " + path, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static JsonObject lockAndDescribeP1Camera(ClientGameTestContext context, long frameId) {
        return context.computeOnClient(client -> {
            // A frozen Level still processes MouseHandler input on each render tick.
            // Release the captured mouse before fixing both current and interpolated
            // camera rotation so host pointer movement cannot change the sample.
            client.mouseHandler.releaseMouse();
            Entity cameraEntity = client.getCameraEntity();
            require(cameraEntity != null, "P1 framebuffer capture has no camera entity");
            cameraEntity.setYRot(P1_FRAMEBUFFER_YAW);
            cameraEntity.setXRot(P1_FRAMEBUFFER_PITCH);
            cameraEntity.setOldPosAndRot();
            pinVignette(cameraEntity);

            JsonObject sample = new JsonObject();
            sample.addProperty("frameId", frameId);
            sample.addProperty("x", cameraEntity.getX());
            sample.addProperty("y", cameraEntity.getY());
            sample.addProperty("z", cameraEntity.getZ());
            sample.addProperty("yaw", cameraEntity.getYRot());
            sample.addProperty("pitch", cameraEntity.getXRot());
            sample.addProperty("gameTime", client.level.getGameTime());
            sample.addProperty("mouseGrabbed", client.mouseHandler.isMouseGrabbed());
            return sample;
        });
    }

    private static void validateP1CameraSample(JsonObject sample, long frameId) {
        require(sample.get("frameId").getAsLong() == frameId,
                "P1 camera evidence has the wrong frame id: " + sample);
        require(Math.abs(sample.get("x").getAsDouble() - P1_FRAMEBUFFER_X) < 1
                        && Math.abs(sample.get("y").getAsDouble() - P1_FRAMEBUFFER_Y) < 1
                        && Math.abs(sample.get("z").getAsDouble() - P1_FRAMEBUFFER_Z) < 1,
                "P1 camera position drifted before frame " + frameId + ": " + sample);
        require(Float.compare(sample.get("yaw").getAsFloat(), P1_FRAMEBUFFER_YAW) == 0
                        && Float.compare(sample.get("pitch").getAsFloat(), P1_FRAMEBUFFER_PITCH) == 0,
                "P1 camera orientation drifted before frame " + frameId + ": " + sample);
        require(sample.get("gameTime").getAsLong() == P1_FRAMEBUFFER_GAME_TIME,
                "P1 game time drifted before frame " + frameId + ": " + sample);
        require(!sample.get("mouseGrabbed").getAsBoolean(),
                "P1 mouse input remained captured before frame " + frameId + ": " + sample);
    }

    private static void pinVignette(Entity cameraEntity) {
        if (!cameraEntity.level().isClientSide()) return;
        net.minecraft.client.Minecraft.getInstance().gui.hud.vignetteBrightness = 0.0F;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record RenderContractSnapshot(
            boolean enabled,
            String status,
            int requestedCaptures,
            int completedCaptures,
            int failedCaptures,
            int pendingCaptures,
            int droppedCaptures
    ) {
    }

    private record CaptureSample(
            long frameId,
            Path png,
            Path raw,
            long byteCount,
            long nonZeroBytes,
            long nonBlackPixels,
            int distinctRgb,
            double meanLuma,
            double lumaStddev
    ) {
    }
}
