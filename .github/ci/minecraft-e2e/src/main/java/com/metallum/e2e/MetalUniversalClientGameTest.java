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
import java.util.Set;
import java.util.stream.Stream;

@SuppressWarnings("UnstableApiUsage")
public final class MetalUniversalClientGameTest implements FabricClientGameTest {
    private static final String SCREENSHOT_NAME = "metaluniversal-hosted-world";
    private static final String RENDER_CONTRACT_RUNTIME =
            "com.metallum.client.validation.contract.RenderContractRuntime";
    private static final int METAL_CAPTURE_SAMPLES = 8;
    private static final int MIN_CAPTURE_DISTINCT_RGB = 256;
    private static final double MIN_CAPTURE_LUMA_STDDEV = 5.0;

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
        if (FrameWorkloads.ENABLED) FrameWorkloads.validateProducer(FrameWorkloads.PRODUCER, sodiumLoaded, irisLoaded);
        else {
            require(sodiumLoaded == !vanillaOnly, "Sodium runtime presence disagrees with the requested lane");
            require(irisLoaded == "iris".equals(System.getProperty("metallum.ci.rendererMode")), "Iris runtime presence disagrees with the requested lane");
        }
        writeLoadedArtifactIdentity(evidenceDir.resolve("artifact-identity.json"), vanillaOnly);

        // Fabric's consistent-settings default is superflat. Exercise the real Overworld here.
        try (TestSingleplayerContext singleplayer = openGameplayWorld(context, evidenceDir)) {
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
            for (long frameId = 1; frameId <= METAL_CAPTURE_SAMPLES; frameId++) {
                // Move beyond the spawn region, allowing ordinary generation and chunk upload.
                // A fixed seed still has a randomized player spawn within the spawn radius.
                int x = 160 + (int) (frameId - 1) * 96;
                int z = 160 + (int) (frameId - 1) * 48;
                // Keep camera coordinates independent of spawn timing and vegetation heightmaps.
                // Teleporting still exercises ordinary generation and uploads along the route.
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

                // waitForChunksRender() proves the connection-side chunk window completed, but
                // after a long spectator teleport it can still precede the client renderer's
                // occlusion rebuild and mesh publication. Use only public 26.3 renderer state
                // here: this correctness lane intentionally does not depend on profiling mixins.
                context.waitFor(client -> renderReadiness(client).get("ready").getAsBoolean());
                context.waitTicks(4);
                context.waitFor(client -> renderReadiness(client).get("ready").getAsBoolean());
                JsonObject terrainEvidence = context.computeOnClient(MetalUniversalClientGameTest::renderReadiness);
                require(terrainEvidence.get("ready").getAsBoolean(),
                        "Terrain renderer regressed before framebuffer capture for frame " + frameId + ": " + terrainEvidence);

                JsonObject waypoint = new JsonObject();
                waypoint.addProperty("frameId", frameId);
                waypoint.addProperty("x", x);
                waypoint.addProperty("y", y);
                waypoint.addProperty("z", z);
                waypoint.add("terrainReadiness", terrainEvidence);
                waypoints.add(waypoint);
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
                require(sample.nonBlackPixels() > 0
                                && sample.distinctRgb() >= MIN_CAPTURE_DISTINCT_RGB
                                && sample.lumaStddev() >= MIN_CAPTURE_LUMA_STDDEV,
                        "Render-ready Metal framebuffer is black/degenerate at frame " + frameId + ": " + sample);
                samples.add(sample);

                // Sampling is deliberately spaced. This rejects the possibility that a single
                // transitional frame (world load, resize, GUI hand-off) is mistaken for the
                // renderer's steady-state output.
                context.waitTicks(4);
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
            require(selected.nonBlackPixels() > 0
                            && selected.distinctRgb() >= MIN_CAPTURE_DISTINCT_RGB
                            && selected.lumaStddev() >= MIN_CAPTURE_LUMA_STDDEV,
                    "All sampled Metal framebuffers failed the content-quality gate; best sample=" + selected);
        } finally {
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");
            closeRenderContractQuietly();
        }
    }

    private static JsonObject renderReadiness(net.minecraft.client.Minecraft client) {
        JsonObject evidence = new JsonObject();
        if (client.level == null || client.levelRenderer == null) {
            evidence.addProperty("ready", false);
            evidence.addProperty("worldLoaded", false);
            return evidence;
        }

        var renderer = client.levelRenderer;
        var dispatcher = renderer.sectionRenderDispatcher();
        boolean hasRenderedAllSections = renderer.hasRenderedAllSections();
        int expectedChunks = renderer.sectionOcclusionGraph().expectedChunks().size();
        int visibleSections = renderer.visibleSections().size();
        int compileQueueSize = dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
        boolean allVisibleSectionsCompiled = visibleSections > 0;
        if (allVisibleSectionsCompiled) {
            for (var section : renderer.visibleSections()) {
                if (section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED) {
                    allVisibleSectionsCompiled = false;
                    break;
                }
            }
        }

        boolean ready = dispatcher != null
                && hasRenderedAllSections
                && expectedChunks == 0
                && compileQueueSize == 0
                && visibleSections > 0
                && allVisibleSectionsCompiled;
        evidence.addProperty("ready", ready);
        evidence.addProperty("worldLoaded", true);
        evidence.addProperty("hasRenderedAllSections", hasRenderedAllSections);
        evidence.addProperty("expectedChunks", expectedChunks);
        evidence.addProperty("compileQueueSize", compileQueueSize);
        evidence.addProperty("visibleSections", visibleSections);
        evidence.addProperty("allVisibleSectionsCompiled", allVisibleSectionsCompiled);
        evidence.addProperty("authority",
                "public LevelRenderer/SectionRenderDispatcher state after teleport; no profiling mixins");
        return evidence;
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
        json.append("{\n  \"schema\": 2,\n")
                .append("  \"sourceReadbackOrientation\": \"renderpearl-present-source\",\n")
                .append("  \"diagnosticPngOrientation\": \"top-left\",\n")
                .append("  \"diagnosticPngTransform\": \"flip-y\",\n")
                .append("  \"minDistinctRgb\": ").append(MIN_CAPTURE_DISTINCT_RGB).append(",\n")
                .append("  \"minLumaStddev\": ").append(String.format(java.util.Locale.ROOT, "%.1f", MIN_CAPTURE_LUMA_STDDEV)).append(",\n")
                .append("  \"selectedFrameId\": ").append(selectedFrameId)
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

    private static void writeLoadedArtifactIdentity(Path output, boolean vanillaOnly) {
        com.metallum.client.validation.RuntimeArtifactIdentity.write(output);
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
