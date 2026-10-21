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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int P1_FRAMEBUFFER_YAW = -65;
    private static final int P1_FRAMEBUFFER_PITCH = 25;

    private static void requestServerOwnedHalt(TestSingleplayerContext singleplayer) {
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
            Throwable primaryFailure = null;
            try {
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
                    requestServerOwnedHalt(singleplayer);
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

            if (p1FramebufferScenario) {
                // P1 compares real GPU readbacks across two independent client launches. Use the
                // same generated terrain point, freeze world/weather/texture animation, and pin
                // the vanilla vignette state so client tick timing cannot masquerade as pixels.
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
                JsonObject waypoint = new JsonObject();
                waypoint.addProperty("frameId", 1);
                waypoint.addProperty("x", P1_FRAMEBUFFER_X);
                waypoint.addProperty("y", P1_FRAMEBUFFER_Y);
                waypoint.addProperty("z", P1_FRAMEBUFFER_Z);
                waypoint.addProperty("yaw", P1_FRAMEBUFFER_YAW);
                waypoint.addProperty("pitch", P1_FRAMEBUFFER_PITCH);
                waypoints.add(waypoint);
            }

            List<CaptureSample> samples = new ArrayList<>();
            boolean p1SceneFrozen = false;
            try {
                JsonArray cameraSamples = new JsonArray();
                if (p1FramebufferScenario) {
                    boolean serverFrozen = singleplayer.getServer().computeOnServer(server -> {
                        server.overworld().tickRateManager().setFrozen(true);
                        return server.overworld().tickRateManager().isFrozen();
                    });
                    p1SceneFrozen = true;
                    require(serverFrozen, "P1 server simulation did not freeze for the stationary framebuffer samples");
                    boolean clientFrozen = context.computeOnClient(client -> {
                        if (client.level != null) {
                            client.level.tickRateManager().setFrozen(true);
                            pinVignette(client.getCameraEntity());
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
                }

                for (long frameId = 1; frameId <= METAL_CAPTURE_SAMPLES; frameId++) {
                    int x = p1FramebufferScenario ? P1_FRAMEBUFFER_X : 160 + (int) (frameId - 1) * 96;
                    int y = p1FramebufferScenario ? P1_FRAMEBUFFER_Y : 128;
                    int z = p1FramebufferScenario ? P1_FRAMEBUFFER_Z : 160 + (int) (frameId - 1) * 48;
                    if (!p1FramebufferScenario) {
                        // Keep camera coordinates independent of spawn timing and vegetation heightmaps.
                        // Teleporting still exercises ordinary generation and uploads along the route.
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
                        waypoint.addProperty("yaw", -65);
                        waypoint.addProperty("pitch", 25);
                        waypoints.add(waypoint);
                    } else {
                        JsonObject cameraSample = lockAndDescribeP1Camera(context, frameId);
                        validateP1CameraSample(cameraSample, frameId);
                        cameraSamples.add(cameraSample);
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

                    // Sampling is deliberately spaced. This rejects the possibility that a single
                    // transitional frame (world load, resize, GUI hand-off) is mistaken for steady output.
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
            writeOptimizationEvidence(evidenceDir.resolve("optimization-evidence.json"));

            // The moving terrain samples are diagnostic images. Add an independent,
            // known geometry/color expectation so shattered terrain cannot pass as
            // a merely non-black framebuffer. Keep P1's stationary protocol intact.
            if (!p1FramebufferScenario) {
                verifyTerrainColorProbe(context, singleplayer, metalCaptureRoot, evidenceDir);
            }
            writeMetalFxEvidence(context, evidenceDir.resolve("metalfx-runtime-evidence.json"));
            if (System.getProperty("metallum.metalfx.captureMotionValidation", "false").equals("true")) {
                verifyMotionSourceEntries(context, singleplayer, evidenceDir);
            }

            // Resource identity events are emitted by the render owner. Retire its
            // recorder there too, before any subsequent frame can orphan a buffer.
            RenderContractSnapshot contractSnapshot = context.computeOnClient(client -> {
                closeRenderContract();
                return renderContractSnapshot();
            });
            verifyTerminalTrace(metalCaptureRoot,contractSnapshot);

            if (Boolean.getBoolean("metallum.ci.stationaryTerrainControl")) {
                require(p1FramebufferScenario, "The isolated terrain control requires completed stationary samples");
                // CI still needs the independent terrain RGB expectation. Run
                // it after the eight immutable P1 captures, in its own recorder
                // directory, so physical P1 payloads keep exactly eight frames.
                Path controlRoot = evidenceDir.resolve("terrain-color-control");
                startRenderContract(controlRoot);
                try {
                    verifyTerrainColorProbe(context, singleplayer, controlRoot, evidenceDir);
                    RenderContractSnapshot control = renderContractSnapshot();
                    require(control.requestedCaptures() == 1 && control.completedCaptures() == 1
                                    && control.failedCaptures() == 0 && control.pendingCaptures() == 0
                                    && control.droppedCaptures() == 0,
                            "The isolated terrain control did not complete exactly one capture: " + control);
                } finally {
                    context.runOnClient(client -> closeRenderContract());
                }
                RenderContractSnapshot control=context.computeOnClient(client->renderContractSnapshot());
                verifyTerminalTrace(controlRoot,control);
                require(control.requestedCaptures()==1 && control.completedCaptures()==1
                                && control.failedCaptures()==0 && control.pendingCaptures()==0 && control.droppedCaptures()==0,
                        "The isolated terrain control lost its terminal capture: "+control);
            }

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

            int expectedCaptures = METAL_CAPTURE_SAMPLES + (p1FramebufferScenario ? 0 : 1);
            require(contractSnapshot.completedCaptures() == expectedCaptures,
                    "Expected " + expectedCaptures + " completed Metal framebuffer captures: " + contractSnapshot);
            require(contractSnapshot.failedCaptures() == 0,
                    "Metal render-contract reported failed captures: " + contractSnapshot);
            require(contractSnapshot.pendingCaptures() == 0,
                    "Metal render-contract still has pending captures: " + contractSnapshot);
            require(contractSnapshot.droppedCaptures() == 0,
                    "Metal render-contract dropped framebuffer captures: " + contractSnapshot);
            require(selected.nonBlackPixels() > 0 && selected.distinctRgb() > 1,
                    "All sampled Metal framebuffers were black/constant; best sample=" + selected);
            // Ordinary conformance uses the same Fabric phase barrier as the
            // stationary lane. Halt on the server owner before Fabric closes
            // the client, without bypassing its disconnect/wait or later tests.
            requestServerOwnedHalt(singleplayer);
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                System.err.println("Primary Minecraft client contract failure before world disconnect:");
                failure.printStackTrace(System.err);
                throw failure;
            } finally {
                // This scope ends before TestSingleplayerContext.close() renders
                // its disconnect frame, including when a producer assertion fails.
                try {
                    context.runOnClient(client -> closeRenderContract());
                } catch (RuntimeException | Error cleanupFailure) {
                    if (primaryFailure == null) throw cleanupFailure;
                    primaryFailure.addSuppressed(cleanupFailure);
                    System.err.println("Render-contract cleanup also failed:");
                    cleanupFailure.printStackTrace(System.err);
                }
            }
        } finally {
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");
        }
    }

    private static void verifyTerrainColorProbe(ClientGameTestContext context,
                                               TestSingleplayerContext singleplayer,
                                               Path captureRoot, Path evidenceDir) {
        int[] previousOptions = context.computeOnClient(client -> {
            int[] previous = {client.gui.hud.isHidden() ? 1 : 0, client.options.fov().get()};
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            client.options.fov().set(70);
            return previous;
        });
        try {
            singleplayer.getServer().runCommand("tp @a 160.5 128 160.5 0 0");
            context.waitFor(client -> client.player != null
                    && Math.abs(client.player.getX() - 160.5) < 0.1
                    && Math.abs(client.player.getZ() - 160.5) < 0.1);
            // Returning from the moving route can briefly leave the server's
            // destination chunks unloaded. Client render completion alone does
            // not make /fill valid; it can succeed with no destination chunks.
            singleplayer.getServer().waitFor(server ->
                    server.overworld().hasChunksAt(142, 159, 178, 174), 1200);
            singleplayer.getConnection().waitForChunksRender();
            singleplayer.getServer().runCommand("fill 142 120 159 178 140 174 minecraft:air");
            // Looking south, east is screen-left. The probe samples the center
            // of each large wall, away from seams, silhouettes and the GUI.
            singleplayer.getServer().runCommand("fill 166 124 172 178 136 172 minecraft:red_concrete");
            singleplayer.getServer().runCommand("fill 155 124 172 165 136 172 minecraft:lime_concrete");
            singleplayer.getServer().runCommand("fill 142 124 172 154 136 172 minecraft:blue_concrete");
            require(singleplayer.getServer().computeOnServer(server -> hasTerrainProbeWalls(server.overworld())),
                    "Terrain probe walls were not completely built on the server");
            context.waitFor(client -> client.level != null && hasTerrainProbeWalls(client.level), 1200);
            singleplayer.getServer().runCommand("weather clear 1000000");
            singleplayer.getServer().runCommand("time set noon");
            context.waitTicks(30);
            singleplayer.getConnection().waitForChunksRender();
            long frame = METAL_CAPTURE_SAMPLES + 1L;
            RenderContractSnapshot before = renderContractSnapshot();
            beginRenderContractFrame(frame);
            try {
                requestFinalDrawableCapture(frame);
                waitForCaptureCompletion(context, before.completedCaptures() + 1, frame, 100);
            } finally { endRenderContractFrame(frame); }
            Path png = findFrameArtifact(captureRoot, frame, "actual.png");
            Path raw = findFrameArtifact(captureRoot, frame, "actual.bin");
            try {
                byte[] header = Files.readAllBytes(png);
                int width = java.nio.ByteBuffer.wrap(header).getInt(16);
                int height = java.nio.ByteBuffer.wrap(header).getInt(20);
                JsonObject result = TerrainColorProbe.inspect(Files.readAllBytes(raw), width, height);
                Files.copy(png, evidenceDir.resolve("terrain-color-probe.png"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(evidenceDir.resolve("terrain-color-probe.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(result));
                require(result.get("passed").getAsBoolean(), "Terrain color/coverage probe failed: " + result);
            } catch (IOException failure) { throw new IllegalStateException("Cannot read terrain probe", failure); }
        } finally {
            context.computeOnClient(client -> {
                if (client.gui.hud.isHidden() != (previousOptions[0] != 0)) client.gui.hud.toggle();
                client.options.fov().set(previousOptions[1]);
                return null;
            });
        }
    }

    private static boolean hasTerrainProbeWalls(BlockGetter level) {
        for (int x = 142; x <= 178; x++) {
            var expected = Blocks.CONCRETE.pick(x >= 166 ? DyeColor.RED : x >= 155 ? DyeColor.LIME : DyeColor.BLUE);
            for (int y = 124; y <= 136; y++) {
                if (!level.getBlockState(new BlockPos(x, y, 172)).is(expected)) return false;
            }
        }
        return true;
    }

    private static void startRenderContract(Path output) {
        try {
            Files.createDirectories(output);
            System.setProperty("metallum.renderContract.enabled", "true");
            // Preserve every normal source record within the same physical
            // manifest/root budgets, independently decoded by the CI oracle.
            System.setProperty("metallum.renderContract.compressManifest","true");
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

    private static void verifyTerminalTrace(Path captureRoot,RenderContractSnapshot contractSnapshot) {
        Path tracePath=captureRoot.resolve("render-contract/pass-manifest.json");
        try {
            JsonObject trace=TerminalTrace.read(tracePath);
            System.out.println("Persisted terminal trace "+tracePath+": "+trace);
            TerminalTrace.requireComplete(trace);
            require(contractSnapshot.ready() && contractSnapshot.status().equals("passed")
                            && contractSnapshot.manifestFinalized(),
                    "Render contract terminal snapshot failed: "+contractSnapshot+"; trace="+trace);
        } catch(IOException failure) {throw new IllegalStateException("Cannot read the final render trace "+tracePath,failure);}
    }

    private static RenderContractSnapshot renderContractSnapshot() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            Object snapshot = runtime.getMethod("snapshot").invoke(null);
            Class<?> snapshotClass = snapshot.getClass();
            return new RenderContractSnapshot(
                    (boolean) snapshotClass.getMethod("enabled").invoke(snapshot),
                    (String) snapshotClass.getMethod("status").invoke(snapshot),
                    (boolean) snapshotClass.getMethod("ready").invoke(snapshot),
                    (boolean) snapshotClass.getMethod("manifestFinalized").invoke(snapshot),
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

    private static void writeMetalFxEvidence(ClientGameTestContext context, Path output) {
        var status = context.computeOnClient(client -> com.metallum.client.metal.render.MetalFxManager.scalingStatus());
        String profile = System.getProperty("metallum.ci.metalfxProfile", "off");
        if (profile.equals("requested-temporal")) {
            require(status.requestedMode().equals("TEMPORAL"), "Temporal request never reached the production manager");
            String expected = status.temporalSupported() && status.motionPipelineAvailable()
                    ? "TEMPORAL" : status.spatialSupported() ? "SPATIAL" : "OFF";
            require(status.effectiveMode().equals(expected) && !status.runtimeDisabled(),
                    "Incorrect MetalFX capability fallback: " + status);
            if (expected.equals("SPATIAL")) {
                require(status.spatialEncodes() > 0 && status.temporalEncodes() == 0,
                        "Spatial fallback must execute and must not count as Temporal: " + status);
            } else if (expected.equals("TEMPORAL")) {
                require(status.temporalEncodes() > 0, "Supported Temporal never encoded: " + status);
            }
            require(status.frameGenerationRequested() && !status.frameGenerationEnabled()
                    && status.frameGenerationQueued() == 0, "Incomplete production motion gate must reject frame generation");
        } else {
            require(status.requestedMode().equals("OFF") && status.effectiveMode().equals("OFF"),
                    "Ordinary conformance unexpectedly enabled MetalFX: " + status);
        }
        JsonObject report = new GsonBuilder().create().toJsonTree(status).getAsJsonObject();
        report.addProperty("profile", profile);
        report.addProperty("verified", true);
        report.addProperty("boundary", "production encode activation and capability fallback; completion/pixels in separate evidence");
        try { Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
        catch (IOException failure) { throw new IllegalStateException("Could not write MetalFX runtime evidence", failure); }
    }

    private static void verifyMotionSourceEntries(ClientGameTestContext context,
            TestSingleplayerContext singleplayer, Path output) {
        boolean previous = context.computeOnClient(client -> client.options.improvedTransparency().get());
        var previousCamera = context.computeOnClient(client -> client.options.getCameraType());
        var previousGameMode = context.computeOnClient(client -> client.gameMode.getPlayerMode());
        boolean previousHudHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        boolean previousRain=context.computeOnClient(client->client.level.isRaining());
        boolean previousThunder=context.computeOnClient(client->client.level.isThundering());
        float previousRainLevel=context.computeOnClient(client->client.level.getRainLevel(1));
        float previousThunderLevel=context.computeOnClient(client->client.level.getThunderLevel(1));
        boolean previousFlying = context.computeOnClient(client -> client.player.getAbilities().flying);
        var previousMainHand = context.computeOnClient(client -> client.player.getMainHandItem().copy());
        var previousOffHand = context.computeOnClient(client -> client.player.getOffhandItem().copy());
        var previousPostEffects = context.computeOnClient(client -> java.util.List.copyOf(client.player.getActivePostEffects()));
        try {
            singleplayer.getServer().runCommand("gamemode creative @a");
            singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player -> {
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }));
            singleplayer.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword[minecraft:enchantment_glint_override=true]");
            singleplayer.getServer().runCommand("item replace entity @a weapon.offhand with minecraft:shield");
            context.runOnClient(client -> {
                client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                if (client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
            context.waitFor(client -> client.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE
                    && client.player.getAbilities().flying && !client.player.getMainHandItem().isEmpty()
                    && !client.player.getOffhandItem().isEmpty());
            singleplayer.getServer().runCommand("tp @a 160.5 128 160.5 0 0");
            verifyCloudSourceEntries(context,singleplayer,output);
            singleplayer.getServer().runCommand("difficulty normal");
            singleplayer.getServer().runOnServer(server -> {
                var zombie=new net.minecraft.world.entity.monster.zombie.Zombie(server.overworld());
                zombie.setPos(160.5,126,165);zombie.setNoAi(true);zombie.setNoGravity(true);
                zombie.setCustomName(net.minecraft.network.chat.Component.literal("Motion depth overlay"));zombie.setCustomNameVisible(true);
                server.overworld().addFreshEntity(zombie);
                var falling=net.minecraft.world.entity.item.FallingBlockEntity.fall(server.overworld(),new BlockPos(160,128,166),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                falling.setNoGravity(true);falling.setDeltaMovement(.03,0,0);
            });
            singleplayer.getServer().runCommand("summon minecraft:experience_orb 160.5 128 166 {NoGravity:1b,Value:1s}");
            singleplayer.getServer().runCommand("summon minecraft:end_crystal 163 128 169 {beam_target:[I;160,131,171],ShowBottom:0b}");
            singleplayer.getServer().runCommand("summon minecraft:shulker_bullet 159 130 166 {NoGravity:1b}");
            singleplayer.getServer().runCommand("summon minecraft:wind_charge 157 130 167 {NoGravity:1b}");
            singleplayer.getServer().runCommand("summon minecraft:breeze_wind_charge 162 130 166 {NoGravity:1b}");
            singleplayer.getServer().runCommand("summon minecraft:ender_dragon 170 130 178 {NoAI:1b,NoGravity:1b,Silent:1b}");
            singleplayer.getServer().runCommand("setblock 156 128 165 minecraft:enchanting_table");
            singleplayer.getServer().runCommand("setblock 158 128 165 minecraft:lectern[facing=north,has_book=true]{Book:{id:\"minecraft:writable_book\",count:1}}");
            singleplayer.getServer().runCommand("setblock 160 128 168 minecraft:conduit");
            singleplayer.getServer().runCommand("setblock 162 128 165 minecraft:decorated_pot");
            singleplayer.getServer().runCommand("setblock 164 128 165 minecraft:skeleton_skull");
            singleplayer.getServer().runCommand("fillbiome 148 112 156 159 144 176 minecraft:snowy_plains");
            singleplayer.getServer().runCommand("weather rain 600");
            context.waitTicks(40);
            for (boolean oit : new boolean[]{false, true}) {
                context.runOnClient(client -> client.options.improvedTransparency().set(oit));
                singleplayer.getServer().runOnServer(server -> {
                    for(int i=0;i<3;i++) {
                        var bolt=new net.minecraft.world.entity.LightningBolt(net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT,server.overworld());
                        bolt.setPos(159+i*2,128,168);bolt.setVisualOnly(true);bolt.setSilent(true);
                        server.overworld().addFreshEntity(bolt);
                    }
                });
                if (oit) {
                    singleplayer.getServer().runCommand("data merge entity @e[type=minecraft:ender_dragon,limit=1] {Health:0.0f}");
                    // A visible vertical 16-block frame exercises the active cage/wind/eye path,
                    // after the ordinary phase has exercised the inactive shell.
                    singleplayer.getServer().runCommand("fill 159 127 167 161 129 169 minecraft:water");
                    singleplayer.getServer().runCommand("fill 158 126 168 162 126 168 minecraft:prismarine");
                    singleplayer.getServer().runCommand("fill 158 130 168 162 130 168 minecraft:prismarine");
                    singleplayer.getServer().runCommand("fill 158 127 168 158 129 168 minecraft:prismarine");
                    singleplayer.getServer().runCommand("fill 162 127 168 162 129 168 minecraft:prismarine");
                    singleplayer.getServer().runCommand("setblock 160 128 168 minecraft:conduit");
                    context.waitFor(client -> client.level != null
                            && client.level.getBlockEntity(new BlockPos(160, 128, 168)) instanceof net.minecraft.world.level.block.entity.ConduitBlockEntity conduit
                            && conduit.isActive(), 200);
                }
                singleplayer.getServer().runCommand("particle minecraft:flame 160.5 128 164 1 1 1 0.01 100 force @a");
                singleplayer.getServer().runCommand("particle minecraft:campfire_cosy_smoke 160.5 128 165 1 1 1 0.01 100 force @a");
                context.waitTicks(12);
            }
            singleplayer.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:compass[minecraft:enchantment_glint_override=true]");
            context.waitFor(client -> client.player.getMainHandItem().is(net.minecraft.world.item.Items.COMPASS)
                    && client.player.getMainHandItem().hasFoil());
            context.waitTicks(8);
            singleplayer.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword[minecraft:enchantment_glint_override=true]");
            context.waitFor(client -> client.player.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_SWORD)
                    && client.player.getMainHandItem().hasFoil());
            context.waitTicks(5);
            singleplayer.getServer().runCommand("setblock 158 128 165 minecraft:lectern[facing=north,has_book=false]");
            context.waitFor(client -> client.level != null && !client.level.getBlockState(new BlockPos(158, 128, 165))
                    .getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK));
            context.waitTicks(3);
            var beforeEmpty = context.computeOnClient(client -> com.metallum.client.metal.render.MetalMotionDrawCoverage.census());
            context.waitTicks(5);
            var census = context.computeOnClient(client -> com.metallum.client.metal.render.MetalMotionDrawCoverage.census());
            JsonObject report = new GsonBuilder().create().toJsonTree(census).getAsJsonObject();
            report.add("runtimeShaderResourcesAfterCapture",context.computeOnClient(RuntimeShaderSourceEvidence::capture));
            report.addProperty("verified", false);
            report.addProperty("boundary", "real Minecraft source draw/owner/history and GPU motion replay; Temporal/FG quality checked separately");
            try { Files.writeString(output.resolve("motion-source-draw-coverage.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
            catch (IOException failure) { throw new IllegalStateException("Could not write pending source motion evidence", failure); }
            require(census.rawMotionValidation(), "Real draw coverage capture was not enabled");
            require(!census.programs().isEmpty(), "Source-program coverage is missing");
            require(census.sourceFrame().collected() && census.sourceFrame().closed() && census.sourceFrame().frameId()>0
                            && census.sourceFrame().epoch()>0 && !census.sourceFrame().programs().isEmpty(),
                    "Actual source frame has no independent backend draw inventory: "+census.sourceFrame());
            require(census.sourceFrame().programs().values().stream().anyMatch(row->row.actualCalls()>0),
                    "The current source inventory contains only planned hooks/replays");
            require(census.programs().values().stream().anyMatch(row -> row.encodedDisocclusionDraws() > 0),
                    "New/reset source geometry never encoded explicit invalid-history ownership");
            require(census.programs().values().stream().anyMatch(row -> row.source() != null
                            && row.source().flags().contains("GLINT") && row.ownedColorExecutions() > 0
                            && row.encodedMotionDraws() > 0),
                    "Actual glint source never proved owned exact motion");
            require(census.programs().values().stream().anyMatch(row -> row.source() != null
                            && row.source().flags().contains("GLINT_SPECIAL") && row.ownedColorExecutions() > 0
                            && row.encodedMotionDraws() > 0),
                    "Actual compass special-glint source never proved its seven-attribute layout and exact history");
            require(census.programs().values().stream().anyMatch(row -> row.source() != null
                            && row.source().flags().contains("IS_SEE_THROUGH") && row.ownedColorExecutions() > 0
                            && row.encodedMotionDraws() > 0),
                    "Actual see-through source never proved its depth policy and owned exact motion");
            for (String suffix : new String[]{"opaque_particle", "oit_depth_bounds_particle", "oit_transmittance_particle", "oit_accumulate_particle"}) {
                var rows = census.pipelines().entrySet().stream().filter(row -> row.getValue().actualDraws() > 0
                        && row.getKey().endsWith("/" + suffix)).toList();
                require(!rows.isEmpty(), "Minecraft never executed particle source pipeline " + suffix + ": " + census);
                for (var row : rows) {
                    require(row.getValue().actualDraws() > 0
                        && row.getValue().actualDraws() == row.getValue().hookedExecutions(),
                        "Real particle draws bypassed the motion hook: " + row);
                    if (suffix.equals("opaque_particle") || suffix.equals("oit_accumulate_particle")) {
                        require(row.getValue().ownedColorExecutions() > 0, "Particle color draw has no staged motion owner: " + row);
                    }
                }
                var programs = census.programs().values().stream().filter(row -> row.actualDraws() > 0 && row.source() != null
                        && row.source().location().endsWith("/"+suffix)).toList();
                require(!programs.isEmpty(), "No actual original shader program for "+suffix);
                for (var program : programs) {
                    require(program.actualDraws() > 0 && program.actualDraws() == program.hookedExecutions()
                                    && program.source().vertexShader().equals("minecraft:core/particle")
                                    && program.source().vertexBindings().getFirst().elements().getFirst().semantic().equals("Position"),
                            "Particle source semantics/variant bypassed the real hook: "+program);
                }
                if (suffix.equals("opaque_particle") || suffix.equals("oit_accumulate_particle")) {
                    require(programs.stream().mapToLong(row -> row.encodedMotionDraws()).sum() > 0,
                            "Particle hook captured ownership but never encoded actual GPU motion: "+suffix);
                }
            }
            require(census.programs().values().stream().mapToLong(row -> row.internalMotionReplays()).sum() > 0,
                    "Diagnostic replay draws must be reported separately from real Minecraft source draws");
            long oitFeatures = census.pipelines().entrySet().stream()
                    .filter(row -> row.getKey().contains("oit_accumulate_entity"))
                    .mapToLong(row -> row.getValue().encodedMotionDraws()).sum();
            require(oitFeatures > 0, "PreparedRenderType OIT color never encoded owned motion");
            var beam = census.pipelines().get("minecraft:pipeline/end_crystal_beam");
            require(beam != null && beam.ownedColorExecutions() > 0 && beam.encodedMotionDraws() > 0,
                    "Crystal model alone cannot prove its optional custom beam: " + beam);
            var rays=census.pipelines().entrySet().stream().filter(row->row.getKey().contains("dragon_rays"))
                    .map(java.util.Map.Entry::getValue).toList();
            require(!rays.isEmpty() && rays.stream().mapToLong(row->row.ownedColorExecutions()).sum()>0
                            && rays.stream().mapToLong(row->row.encodedMotionDraws()+row.encodedDisocclusionDraws()).sum()>0,
                    "Dragon model admission must also reach its death-ray custom geometry: "+rays);
            for (String state : new String[]{"EnchantTableRenderState", "LecternRenderState", "DecoratedPotRenderState", "ConduitRenderState", "SkullBlockRenderState"}) {
                var counts = census.owners().get("net.minecraft.client.renderer.blockentity.state." + state);
                require(counts != null && counts.sourceColorDraws() > 0 && counts.matchingGeometryDraws() > 0
                                && counts.matchingTransformDraws() > 0 && counts.matchingSurfaceDraws() > 0 && counts.encodedMotionDraws() > 0,
                        "Block entity never proved its real source draw and complete previous history: " + state + " " + counts);
            }
            for (String state : new String[]{"EndCrystalRenderState", "ExperienceOrbRenderState", "ShulkerBulletRenderState", "FallingBlockRenderState",
                    "LightningBoltRenderState", "EnderDragonRenderState",
                    "EntityRenderState[minecraft:wind_charge]", "EntityRenderState[minecraft:breeze_wind_charge]"}) {
                var counts = census.owners().get("net.minecraft.client.renderer.entity.state." + state);
                require(counts != null && counts.sourceColorDraws() > 0 && counts.matchingGeometryDraws() > 0
                                && counts.matchingTransformDraws() > 0 && counts.matchingSurfaceDraws() > 0 && counts.encodedMotionDraws() > 0,
                        "Entity never proved its real source draw and complete previous history: " + state + " " + counts);
            }
            for(String kind:new String[]{"RainOwner","SnowOwner"}) {
                var counts=census.owners().get("com.metallum.client.metal.render.MetalWeatherMotion$"+kind);
                require(counts!=null && counts.sourceColorDraws()>0 && counts.matchingGeometryDraws()>0
                                && counts.matchingTransformDraws()>0 && counts.matchingSurfaceDraws()>0 && counts.encodedMotionDraws()>0,
                        "Actual weather draw never proved its own scrolling history: "+kind+" "+counts);
            }
            for(String suffix:new String[]{"weather","oit_accumulate_weather"}) {
                var counts=census.pipelines().get("minecraft:pipeline/"+suffix);
                require(counts!=null && counts.actualDraws()>0 && counts.actualDraws()==counts.hookedExecutions()
                                && counts.ownedColorExecutions()>0 && counts.encodedMotionDraws()>0,
                        "Weather bypassed its ordinary/OIT execution hook: "+suffix+" "+counts);
            }
            var cloud=census.owners().get("com.metallum.client.metal.render.MetalCloudMotion$CloudOwner");
            require(cloud!=null && cloud.sourceColorDraws()>0 && cloud.matchingGeometryDraws()>0
                            && cloud.matchingTransformDraws()>0 && cloud.matchingSurfaceDraws()>0 && cloud.encodedMotionDraws()>0,
                    "Actual procedural cloud never proved its source and accepted face history: "+cloud);
            for(String suffix:new String[]{"clouds","flat_clouds","oit_accumulate_clouds","oit_accumulate_flat_clouds"}) {
                var counts=census.pipelines().get("minecraft:pipeline/"+suffix);
                require(counts!=null && counts.actualDraws()>0 && counts.actualDraws()==counts.hookedExecutions()
                                && counts.ownedColorExecutions()>0 && counts.encodedMotionDraws()>0,
                        "Cloud bypassed a flat/fancy ordinary/OIT source boundary: "+suffix+" "+counts);
            }
            String lecternClass = "net.minecraft.client.renderer.blockentity.state.LecternRenderState";
            require(beforeEmpty.owners().get(lecternClass).capturedStates() == census.owners().get(lecternClass).capturedStates(),
                    "Empty lectern must not claim source geometry or become an exact candidate");
            var rawCapture = context.computeOnClient(client -> com.metallum.client.metal.render.MetalFxManager
                    .requestRawMotionValidation(output.resolve("raw-source-motion")));
            context.waitFor(client -> rawCapture.isDone(), 200);
            var raw = rawCapture.join();
            try {CloudMotionEvidence.sourceFrameEvidence(output.resolve("raw-source-motion"),raw.sourceCommit(),raw.frameId(),raw.historyEpoch());}
            catch(IOException failure) {throw new IllegalStateException("Cannot inspect frame-bound source census",failure);}
            require(raw.sourceCommit().equals(System.getProperty("metallum.ci.expectedSourceSha"))
                            && raw.frameId() > 0 && raw.historyEpoch() > 0 && raw.worldValidPixels() > 0
                            && raw.finiteValidPixels() >= raw.worldValidPixels() && raw.maxMotionPixels() > 0
                            && raw.worldDepthPixels() > 0 && raw.handDepthPixels() > 0 && raw.handValidPixels() > 0
                            && raw.worldPixelsOutsideHand() > 0
                            && raw.validCorrespondencePixels() > 0
                            && raw.motionContractVersion() == 4 && raw.mergedFinitePixels() == (long)raw.width()*raw.height()
                            && raw.presentationHandPixels() >= raw.handDepthPixels()
                            && !raw.temporalQualityValidated() && !raw.frameGenerationQualityValidated(),
                    "Real producer GPU motion readback failed or claimed unsupported quality: " + raw);
            var invert = net.minecraft.resources.Identifier.withDefaultNamespace("invert");
            context.runOnClient(client -> client.player.setActivePostEffects(java.util.List.of(invert)));
            context.waitFor(client -> client.gameRenderer.getAppliedPostEffects().contains(invert), 200);
            context.waitTicks(5);
            var postEffectCapture = context.computeOnClient(client -> com.metallum.client.metal.render.MetalFxManager
                    .requestRawMotionValidation(output.resolve("raw-source-motion-post-effect")));
            context.waitFor(client -> postEffectCapture.isDone(), 200);
            var postEffect = postEffectCapture.join();
            try {CloudMotionEvidence.sourceFrameEvidence(output.resolve("raw-source-motion-post-effect"),postEffect.sourceCommit(),postEffect.frameId(),postEffect.historyEpoch());}
            catch(IOException failure) {throw new IllegalStateException("Cannot inspect frame-bound post-effect source census",failure);}
            require(!postEffect.handDepthSourceWasMain() && postEffect.handDepthPixels() > 0
                            && postEffect.handValidPixels() > 0 && postEffect.worldPixelsOutsideHand() > 0
                            && postEffect.validCorrespondencePixels() > 0
                            && postEffect.motionContractVersion() == 4
                            && postEffect.mergedFinitePixels() == (long)postEffect.width()*postEffect.height()
                            && postEffect.presentationHandPixels() >= postEffect.handDepthPixels(),
                    "Post effects did not retain isolated actual HUD depth and exact hand history: "+postEffect);
            report.addProperty("verified", true);
            report.addProperty("activeConduitExercised", true);
            report.addProperty("emptyLecternAbsent", true);
            report.addProperty("firstPersonHeldItemsExercised", true);
            report.addProperty("glintSourceMotionExercised", true);
            report.addProperty("specialGlintSourceMotionExercised", true);
            report.addProperty("seeThroughSourceMotionExercised", true);
            report.addProperty("rainSnowScrollingMotionExercised",true);
            report.addProperty("proceduralCloudFaceMotionExercised",true);
            report.addProperty("postEffectHudDepthIsolated", true);
            try { Files.writeString(output.resolve("motion-source-draw-coverage.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
            catch (IOException failure) { throw new IllegalStateException("Could not write source motion evidence", failure); }
        } finally {
            context.runOnClient(client -> {
                client.options.improvedTransparency().set(previous);
                client.options.setCameraType(previousCamera);
                if (client.gui.hud.isHidden() != previousHudHidden) client.gui.hud.toggle();
                client.player.setActivePostEffects(previousPostEffects);
            });
            singleplayer.getServer().runCommand("gamemode "+previousGameMode.getName()+" @a");
            singleplayer.getServer().runOnServer(server->{
                server.setWeatherParameters(previousRain ? 0 : 6000,previousRain ? 6000 : 0,previousRain,previousThunder);
                server.overworld().setRainLevel(previousRainLevel);server.overworld().setThunderLevel(previousThunderLevel);
            });
            context.runOnClient(client->{client.level.setRainLevel(previousRainLevel);client.level.setThunderLevel(previousThunderLevel);});
            singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player -> {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,previousMainHand.copy());
                player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,previousOffHand.copy());
                player.getAbilities().flying = previousFlying;
                player.onUpdateAbilities();
            }));
        }
    }

    private static void verifyCloudSourceEntries(ClientGameTestContext context,TestSingleplayerContext singleplayer,Path output) {
        long previousSourceFrame=0,previousSourceEpoch=0;
        var original=context.computeOnClient(client->client.options.cloudStatus().get());
        boolean transparency=context.computeOnClient(client->client.options.improvedTransparency().get());
        float height=context.computeOnClient(client->client.gameRenderer.mainCamera().attributeProbe()
                .getValue(net.minecraft.world.attribute.EnvironmentAttributes.CLOUD_HEIGHT,1));
        try {
            singleplayer.getServer().runCommand("weather clear 600");
            singleplayer.getServer().runOnServer(server->{server.overworld().setRainLevel(0);server.overworld().setThunderLevel(0);});
            context.runOnClient(client->{client.level.setRainLevel(0);client.level.setThunderLevel(0);});
            for(var status:new net.minecraft.client.CloudStatus[]{net.minecraft.client.CloudStatus.FAST,net.minecraft.client.CloudStatus.FANCY}) {
                for(boolean oit:new boolean[]{false,true}) {
                    context.runOnClient(client->{client.options.cloudStatus().set(status);client.options.improvedTransparency().set(oit);});
                    // Upward view before entities/weather are spawned isolates world ownership to clouds.
                    singleplayer.getServer().runCommand("tp @a 160.5 "+(height-16)+" 160.5 0 -35");
                    context.waitTicks(8);
                    Path dir=output.resolve("cloud-motion-"+status.name().toLowerCase(java.util.Locale.ROOT)+(oit ? "-oit" : "-ordinary"));
                    var pending=context.computeOnClient(client->com.metallum.client.metal.render.MetalFxManager.requestRawMotionValidation(dir));
                    context.waitFor(client->pending.isDone(),200);var raw=pending.join();
                    CloudMotionEvidence.requireSourceFrameAdvance(previousSourceFrame,previousSourceEpoch,raw.frameId(),raw.historyEpoch());
                    previousSourceFrame=raw.frameId();previousSourceEpoch=raw.historyEpoch();
                    try {
                        var frozen=CloudMotionEvidence.sourceFrameEvidence(dir,raw.sourceCommit(),raw.frameId(),raw.historyEpoch());
                        var report=new JsonObject();var gson=new GsonBuilder().setPrettyPrinting().create();
                        report.addProperty("cloudStatus",status.name());report.addProperty("oit",oit);
                        report.add("raw",gson.toJsonTree(raw));report.add("sourceCensus",frozen.get("sourceCensus"));
                        report.add("cloudLayers",frozen.get("cloudLayers"));
                        report.add("runtimeShaderResourcesAfterCapture",context.computeOnClient(RuntimeShaderSourceEvidence::capture));
                        report.addProperty("sourceEvidenceFrameId",frozen.get("frameId").getAsLong());
                        report.addProperty("sourceEvidenceHistoryEpoch",frozen.get("historyEpoch").getAsLong());
                        report.addProperty("sourceEvidenceSourceCommit",frozen.get("sourceCommit").getAsString());
                        report.addProperty("sourceEvidenceTraceFrameId",frozen.get("recordedTraceFrameId").getAsLong());
                        Files.writeString(dir.resolve("cloud-source-census.json"),gson.toJson(report)+"\n");
                    } catch(IOException failure) {throw new IllegalStateException("Cannot retain actual cloud source census",failure);}
                    require(raw.worldValidPixels()>0 && raw.worldSurfacePixelsOutsideHand()>0 && raw.finiteValidPixels()>=raw.worldValidPixels()
                                    && raw.validCorrespondencePixels()>0 && raw.mergedFinitePixels()==(long)raw.width()*raw.height(),
                            "Actual cloud view has no finite owned surface correspondence: "+status+" oit="+oit+" "+raw);
                    try {
                        var pixels=CloudMotionEvidence.inspect(dir,raw.width(),raw.height());
                        Files.writeString(dir.resolve("cloud-ownership-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(pixels));
                        require(pixels.worldSurfaceOutsideHud()>0 && pixels.reactiveWorldOutsideHud()>0
                                        && pixels.worldSurfaceOutsideHud()==raw.worldSurfacePixelsOutsideHand()
                                        && pixels.reactiveWorldOutsideHud()==raw.worldReactivePixelsOutsideHand()
                                        && pixels.transparentWorldOutsideHud()==raw.transparentWorldSurfacePixelsOutsideHand(),
                                "Cloud ownership did not independently survive HUD exclusion: "+pixels+"; "+raw);
                    } catch(IOException failure) {throw new IllegalStateException("Cannot inspect actual cloud source pixels",failure);}
                }
            }
        } finally {
            context.runOnClient(client->{client.options.cloudStatus().set(original);client.options.improvedTransparency().set(transparency);});
            singleplayer.getServer().runCommand("tp @a 160.5 128 160.5 0 0");
        }
    }

    private static void writeOptimizationEvidence(Path output) {
        String profile = System.getProperty("metallum.ci.transportProfile", "default");
        JsonObject report = new JsonObject();
        report.addProperty("profile", profile);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject enabled = gson.toJsonTree(com.metallum.client.metal.render.MetalBackendOptimizations.snapshot()).getAsJsonObject();
        for (String flag : List.of("fusedStateDraw", "reuseEncoderState", "reuseNativeEncoderArguments", "dynamicUploadRangeCopy", "batchIndexedVertexRebase", "nativeIndexedVertexRebase")) {
            boolean value = enabled.get(flag).getAsBoolean();
            require(value == !profile.equals("baseline"), "Wrong optimization policy in " + profile + ": " + flag);
        }
        report.add("enabled", enabled);
        var counters = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.snapshot();
        long fused = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.fusedDrawCalls();
        long rejected = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.rejectedFusedDrawCalls();
        if (enabled.get("fusedStateDraw").getAsBoolean()) {
            require(fused > 0, "Production Minecraft never activated fused state/draw");
            require(rejected == 0, "Production fused state/draw rejected valid packets");
        } else require(fused == 0, "Fused state/draw ignored its rollback property");
        if (enabled.get("reuseEncoderState").getAsBoolean()) {
            require(counters.shadowReuseHits() > 0 && counters.packetStorageReuseHits() > 0,
                    "Production Minecraft never reused encoder state storage");
        } else require(counters.shadowReuseHits() == 0 && counters.packetStorageReuseHits() == 0,
                "Encoder state reuse ignored its rollback property");
        if (enabled.get("reuseNativeEncoderArguments").getAsBoolean()) {
            require(counters.nativeEncoderArgumentReuseCalls() > 0, "Production Minecraft never reused native encoder arguments");
        } else require(counters.nativeEncoderArgumentReuseCalls() == 0, "Native argument reuse ignored its rollback property");
        report.addProperty("fusedDrawCalls", fused);
        report.addProperty("rejectedFusedDrawCalls", rejected);
        long rebases = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedVertexRebases();
        report.addProperty("indexedVertexRebases", rebases);
        long batches = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseBatches();
        long batchDraws = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseBatchDraws();
        long restoresAvoided = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseRestoresAvoided();
        report.addProperty("indexedRebaseBatches", batches);
        report.addProperty("indexedRebaseBatchDraws", batchDraws);
        report.addProperty("indexedRebaseRestoresAvoided", restoresAvoided);
        long nativeBatches = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.nativeIndexedRebaseBatches();
        long nativeDraws = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.nativeIndexedRebaseDraws();
        report.addProperty("nativeIndexedRebaseBatches", nativeBatches);
        report.addProperty("nativeIndexedRebaseDraws", nativeDraws);
        if (!enabled.get("nativeIndexedVertexRebase").getAsBoolean()) {
            require(nativeBatches == 0 && nativeDraws == 0, "Native indexed batch ignored its rollback property");
        }
        if (!enabled.get("batchIndexedVertexRebase").getAsBoolean()) {
            require(batches == 0 && restoresAvoided == 0, "Indexed batch rebasing ignored its rollback property");
        }
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            require(rebases > 0, "Sodium never activated the admitted indexed vertex-fetch rebasing path");
            if (enabled.get("batchIndexedVertexRebase").getAsBoolean()) {
                require(batches > 0 && restoresAvoided > 0, "Sodium never activated admitted indexed batch rebasing");
                if (enabled.get("nativeIndexedVertexRebase").getAsBoolean()) {
                    require(nativeBatches > 0 && nativeDraws > 0, "Sodium never activated the native indexed batch entry");
                }
            }
        }
        report.addProperty("verified", true);
        report.add("state", gson.toJsonTree(counters));
        report.add("uploads", gson.toJsonTree(com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.snapshot()));
        try {
            Files.writeString(output, gson.toJson(report) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write optimization activation evidence", failure);
        }
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
            report.addProperty("renderContractStatus",contractSnapshot.status());
            report.addProperty("renderContractReady",contractSnapshot.ready());
            report.addProperty("renderContractManifestFinalized",contractSnapshot.manifestFinalized());
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
        if (cameraEntity == null || !cameraEntity.level().isClientSide()) return;
        // The scenario is fixed to a clear noon, sky-lit camera position. That
        // makes vanilla's target vignette brightness exactly zero; pin the same
        // settled value without adding a dependency on Blaze3D's Lightmap type
        // to this separate CI source set.
        net.minecraft.client.Minecraft.getInstance().gui.hud.vignetteBrightness = 0.0F;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record RenderContractSnapshot(
            boolean enabled,
            String status,
            boolean ready,
            boolean manifestFinalized,
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
