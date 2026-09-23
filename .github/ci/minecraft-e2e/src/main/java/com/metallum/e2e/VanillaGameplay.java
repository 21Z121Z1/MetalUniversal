package com.metallum.e2e;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.FrameEvidenceRuntime;
import com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsPreset;
import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Opt-in real-client workload using Fabric's input driver, in the existing normal world. */
public final class VanillaGameplay {
    private static final String EVIDENCE_PHASE = System.getProperty("metallum.ci.frameEvidencePhase", "stationary");
    private static final boolean STATIONARY_BASELINE = Boolean.getBoolean("metallum.ci.stationaryBaseline");
    private static final String OPTIMIZATION_PROFILE = System.getProperty(
            "metallum.ci.optimizationProfile", "baseline-v1");
    private static final boolean REUSE_ENCODER_STATE = Boolean.getBoolean("metallum.opt.reuseEncoderState");
    private static final boolean REUSE_NATIVE_ENCODER_ARGUMENTS =
            Boolean.getBoolean("metallum.opt.reuseNativeEncoderArguments");
    private static final boolean REUSE_STATE_CANDIDATE = "reuse-encoder-state-v1".equals(OPTIMIZATION_PROFILE)
            || "reuse-encoder-state-diagnostic-v1".equals(OPTIMIZATION_PROFILE);
    private static final boolean DIAGNOSTIC_REUSE_CANDIDATE =
            "reuse-encoder-state-diagnostic-v1".equals(OPTIMIZATION_PROFILE);
    private static final boolean ENCODER_ARGUMENT_CANDIDATE =
            "encoder-argument-reuse-v1".equals(OPTIMIZATION_PROFILE);
    private static final boolean REUSE_CANDIDATE = REUSE_STATE_CANDIDATE || ENCODER_ARGUMENT_CANDIDATE;
    private static final boolean STABLE_SCENE = STATIONARY_BASELINE || (FrameWorkloads.ID.equals("P0") && FrameWorkloads.PRODUCER.equals("vanilla"));
    private static final int TARGET_FPS = FrameWorkloads.ENABLED ? Integer.getInteger("metallum.ci.targetFps", 60) : STATIONARY_BASELINE ? 60 : 260;
    private static final boolean VSYNC = FrameWorkloads.ENABLED || STATIONARY_BASELINE;
    private static final String ROUTE = FrameWorkloads.ENABLED ? "frame-workload-" + FrameWorkloads.ID + "-v1" :
            STATIONARY_BASELINE ? "vanilla-stationary-60-v1" : "vanilla-normal-gameplay-native-max-v3";
    private static final boolean EXPECT_METAL4 = !FrameWorkloads.ENABLED || "metal4".equals(System.getProperty("metallum.ci.gameplayBackend", "metal3"));
    private static SourceWindow.Accumulator sourceWindow;
    private static final int DEFAULT_WARMUP_SECONDS = 5;
    private static final int DEFAULT_SAMPLE_SECONDS = 10;
    private static final int MAX_WINDOW_SECONDS = 300;
    private static final int WINDOW_TIMEOUT_MARGIN_TICKS = 1200;
    private static final int WARMUP_SECONDS = configuredSeconds("metallum.ci.frameEvidenceWarmupSeconds", DEFAULT_WARMUP_SECONDS);
    private static final int SAMPLE_SECONDS = configuredSeconds("metallum.ci.frameEvidenceSampleSeconds", DEFAULT_SAMPLE_SECONDS);
    private static final int WINDOW_SECONDS = validateWindowContract();
    private static final long WARMUP_NS = FrameWorkloads.ENABLED ? FrameWorkloads.WARMUP_NS : secondsToNanos(WARMUP_SECONDS);
    private static final long SAMPLE_NS = FrameWorkloads.ENABLED ? FrameWorkloads.SAMPLE_NS : secondsToNanos(SAMPLE_SECONDS);
    private static final int WINDOW_TIMEOUT_TICKS = windowTimeoutTicks();
    private static long sampleStartNanos;
    private static int stationaryVisibleSections;
    private static final int NATIVE_WIDTH = Integer.getInteger("metallum.ci.nativeWidth", 0);
    private static final int NATIVE_HEIGHT = Integer.getInteger("metallum.ci.nativeHeight", 0);
    private static final boolean PRESENTATION_METRICS = Boolean.getBoolean("metallum.ci.presentationMetrics");
    // Test-only bounded timestamps, with an opt-in scalar native wait getter.
    // No GPU readback or per-frame allocation.
    private static final int FRAME_TIME_CAPACITY = FrameWorkloads.ENABLED ? 0 : 131_072;
    private static final long[] FRAME_TIMES = new long[FRAME_TIME_CAPACITY];
    private static boolean recordingFrames;
    private static int frameCount;
    private static long startedNanos;
    private static int invalidSettingsFrames;
    private static int throttledFrames;
    private static int droppedSamples;
    private static long drawableWaitNanos;
    private static int drawableWaitSamples;
    private static GpuSurface.Configuration lastPresentedConfiguration;
    private static java.util.List<java.util.function.BooleanSupplier> qualityChecks = java.util.List.of();

    public static void sourceFramePresented(Minecraft client, GpuSurface.Configuration presented) {
        lastPresentedConfiguration = presented;
        if (!recordingFrames) return;
        long now = System.nanoTime();
        if (FrameWorkloads.ENABLED) {
            frameCount++;
            sourceWindow.record(now);
            if (now < sampleStartNanos || now >= sampleStartNanos + SAMPLE_NS) return;
        } else if (frameCount < FRAME_TIMES.length) FRAME_TIMES[frameCount++] = now;
        else droppedSamples++;
        if (PRESENTATION_METRICS) {
            long wait = MetalNativeBridge.metallum_presentation_latest_drawable_wait_nanos();
            if (wait >= 0) {
                drawableWaitNanos += wait;
                drawableWaitSamples++;
            }
        }
        var target = client.gameRenderer.mainRenderTarget();
        if (presented == null || client.options.getEffectiveRenderDistance() != 32
                || (!FrameWorkloads.TRANSITIONS && (client.getWindow().getWidth() != NATIVE_WIDTH
                || client.getWindow().getHeight() != NATIVE_HEIGHT || target.width != NATIVE_WIDTH
                || target.height != NATIVE_HEIGHT || presented.width() != NATIVE_WIDTH || presented.height() != NATIVE_HEIGHT)))
            invalidSettingsFrames++;
        for (var check : qualityChecks) {
            if (!check.getAsBoolean()) { invalidSettingsFrames++; break; }
        }
        if (STABLE_SCENE && client.levelRenderer.visibleSections().size() != stationaryVisibleSections)
            invalidSettingsFrames++;
        if (STABLE_SCENE && (client.player == null
                || Math.abs(client.player.getX() - 160.5) > 0.001 || Math.abs(client.player.getY() - 140) > 0.001
                || Math.abs(client.player.getZ() - 160.5) > 0.001
                || Math.abs(net.minecraft.util.Mth.wrapDegrees(client.player.getYRot() + 65)) > 0.001 || Math.abs(client.player.getXRot() - 15) > 0.001))
            invalidSettingsFrames++;
        if (client.getFramerateLimitTracker().getFramerateLimit() < TARGET_FPS) throttledFrames++;
    }

    private static long[] sliceCacheCounters(boolean enabled) {
        if (!enabled) return new long[3]; // The same harness also runs the pre-cache baseline JAR.
        try {
            Class<?> cache = Class.forName("com.metallum.client.terrain.VanillaTerrainSliceCache");
            return new long[] { cache.getField("hits").getLong(null), cache.getField("misses").getLong(null),
                    cache.getField("verifiedHits").getLong(null) };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read terrain slice cache activation evidence", failure);
        }
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world,
                    Path output, JsonObject worldEvidence) {
        if (FrameWorkloads.ENABLED) {
            FrameWorkloads.validate();
            require(TARGET_FPS >= 15 && TARGET_FPS < 260 && WARMUP_NS >= 0 && SAMPLE_NS > 0,
                    "Invalid fixed cadence/window contract");
            require(!FrameWorkloads.ID.equals("T0") || SAMPLE_NS >= 50_000_000_000L, "T0 needs at least a 50-second route window");
        }
        require(EVIDENCE_PHASE.equals("stationary") || EVIDENCE_PHASE.equals("streaming"), "Unknown frame evidence phase");
        require(!STATIONARY_BASELINE || EVIDENCE_PHASE.equals("stationary"), "Stationary baseline requires stationary phase");
        require(!STATIONARY_BASELINE || worldEvidence.has("replaySourceSnapshotSha256"), "Stationary baseline requires a verified initial snapshot");
        if (FrameWorkloads.ENABLED) {
            require(OPTIMIZATION_PROFILE.equals("frame-trial-v1") && !STATIONARY_BASELINE
                    && EVIDENCE_PHASE.equals("stationary") && !REUSE_NATIVE_ENCODER_ARGUMENTS,
                    "Versioned trials cannot impersonate a guarded optimization profile");
        } else {
            require(OPTIMIZATION_PROFILE.equals("baseline-v1") || REUSE_CANDIDATE,
                    "Unknown optimization profile: " + OPTIMIZATION_PROFILE);
            require(REUSE_STATE_CANDIDATE == REUSE_ENCODER_STATE,
                    "Optimization profile and reuseEncoderState property disagree");
            require(ENCODER_ARGUMENT_CANDIDATE == REUSE_NATIVE_ENCODER_ARGUMENTS,
                    "Optimization profile and reuseNativeEncoderArguments property disagree");
            require(!REUSE_CANDIDATE || DIAGNOSTIC_REUSE_CANDIDATE || STATIONARY_BASELINE,
                    "Timing reuse candidates require the stationary route");
        }
        var input = context.getInput();
        JsonObject report = new JsonObject();
        report.addProperty("scenario", ROUTE);
        report.addProperty("pid", ProcessHandle.current().pid());
        if (FrameWorkloads.ENABLED) {
            report.addProperty("frameEvidenceWarmupSeconds", WARMUP_NS / 1e9);
            report.addProperty("frameEvidenceSampleSeconds", SAMPLE_NS / 1e9);
            report.addProperty("frameEvidenceWindowSeconds", (WARMUP_NS + SAMPLE_NS) / 1e9);
        } else {
            report.addProperty("frameEvidenceWarmupSeconds", WARMUP_SECONDS);
            report.addProperty("frameEvidenceSampleSeconds", SAMPLE_SECONDS);
            report.addProperty("frameEvidenceWindowSeconds", WINDOW_SECONDS);
        }
        report.add("world", worldEvidence);
        JsonArray phases = new JsonArray();
        report.add("phases", phases);
        require(NATIVE_WIDTH > 0 && NATIVE_HEIGHT > 0, "Native display dimensions are required");
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            client.options.graphicsPreset().set(GraphicsPreset.FABULOUS);
            client.options.renderDistance().set(32);
            client.options.enableVsync().set(VSYNC);
            client.options.framerateLimit().set(TARGET_FPS);
            client.getWindow().setPreferredFullscreenVideoMode(java.util.Optional.empty());
            client.options.fullscreen().set(true);
            client.getWindow().setFullscreen(true);
            // OptionInstance.set does not send ClientInformation. The server
            // otherwise keeps the smaller view requested during world entry.
            client.options.broadcastOptions();
        });
        // Fabric intentionally decouples its virtual framebuffer from native
        // resize events. Set the public test-input size as well as fullscreen.
        input.resizeWindow(NATIVE_WIDTH, NATIVE_HEIGHT);
        context.runOnClient(Minecraft::invalidateSurfaceConfiguration);
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
        input.lookAt(-65, 15);
        context.waitTicks(100);
        // Use Vanilla's actual sending footprint, not Fabric's square (whose
        // corners are intentionally never sent). Do not measure a 32-distance
        // scene while most of its normally generated terrain is still absent.
        var startingView = world.getServer().computeOnServer(server -> {
            var view = server.getPlayerList().getPlayers().getFirst().getChunkTrackingView();
            require(view instanceof ChunkTrackingView.Positioned positioned && positioned.viewDistance() == 32,
                    "The server did not activate the requested 32-chunk view: " + view);
            return (ChunkTrackingView.Positioned) view;
        });
        var expectedChunks = new java.util.ArrayList<ChunkPos>();
        startingView.forEach(expectedChunks::add);
        long terrainWaitStart = System.nanoTime();
        context.waitFor(client -> expectedChunks.stream().allMatch(pos ->
                client.level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null), 6000);
        world.getConnection().waitForChunksRender(false, 1200);
        context.waitTicks(40);
        JsonObject terrainReady = new JsonObject();
        terrainReady.addProperty("serverTrackedViewDistance", startingView.viewDistance());
        terrainReady.addProperty("expectedChunks", expectedChunks.size());
        long receivedChunks = context.computeOnClient(client -> expectedChunks.stream().filter(pos ->
                client.level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null).count());
        require(receivedChunks == expectedChunks.size(), "The full starting view must remain loaded");
        terrainReady.addProperty("receivedChunks", receivedChunks);
        terrainReady.addProperty("waitNanos", System.nanoTime() - terrainWaitStart);
        terrainReady.addProperty("scope", "Vanilla server sending footprint at the starting camera; normal generation");
        report.add("initialTerrainReadiness", terrainReady);
        FrameWorkloads.prepare(context, world);
        JsonObject settings = context.computeOnClient(VanillaGameplay::settings);
        report.add("settings", settings);
        require(settings.get("effectiveRenderDistance").getAsInt() == 32, "Maximum view distance did not activate");
        require(settings.get("renderWidth").getAsInt() == NATIVE_WIDTH
                && settings.get("renderHeight").getAsInt() == NATIVE_HEIGHT, "Render target is not native resolution");
        require(settings.get("nativeWindowPixelWidth").getAsInt() == NATIVE_WIDTH
                && settings.get("nativeWindowPixelHeight").getAsInt() == NATIVE_HEIGHT
                && settings.get("presentWidth").getAsInt() == NATIVE_WIDTH
                && settings.get("presentHeight").getAsInt() == NATIVE_HEIGHT,
                "Native window/present dimensions differ from the physical display: " + settings);
        report.addProperty("reuseEncoderState", REUSE_ENCODER_STATE);
        report.addProperty("reuseNativeEncoderArguments", REUSE_NATIVE_ENCODER_ARGUMENTS);
        report.add("optimizationProfile", optimizationProfile());
        boolean terrainSliceCache = Boolean.getBoolean("metallum.opt.terrainSliceCache");
        boolean verifyTerrainSliceCache = Boolean.getBoolean("metallum.terrain.verifySliceCache");
        report.addProperty("terrainSliceCache", terrainSliceCache);
        report.addProperty("verifyTerrainSliceCache", verifyTerrainSliceCache);
        long[] initialCache = context.computeOnClient(client -> sliceCacheCounters(terrainSliceCache));
        int initialVisibleSections = context.computeOnClient(client -> client.levelRenderer.visibleSections().size());
        report.addProperty("initialVisibleSections", initialVisibleSections);
        long[] initialMetal4 = context.computeOnClient(client -> MetalNativeBridge.metallum_metal4_main_renderer_stats());
        require(initialMetal4[0] == (EXPECT_METAL4 ? 1 : 0), "Requested Metal lowering did not activate");
        report.addProperty("metal4MainRendererActive", initialMetal4[0] == 1);
        report.addProperty("backendRequested", EXPECT_METAL4 ? "metal4" : "metal3");
        JsonObject initialContent = world.getServer().computeOnServer(server -> {
            server.saveEverything(false, true, true);
            return WorldSnapshot.capture(world.getWorldSave().getSaveDirectory(),
                    output.resolve(worldEvidence.has("replaySourceSnapshotSha256") ? "prewarmup-world" : "initial-world"), worldEvidence);
        });
        if (worldEvidence.has("replaySourceSnapshotSha256")) {
            initialContent.addProperty("preWarmupSnapshotSha256", initialContent.get("snapshotSha256").getAsString());
            initialContent.addProperty("preWarmupSnapshotDirectory", "prewarmup-world");
            initialContent.addProperty("snapshotSha256", worldEvidence.get("replaySourceSnapshotSha256").getAsString());
            initialContent.addProperty("snapshotDirectory", "initial-world");
        }
        JsonObject stationaryTerrain = null;
        if (STABLE_SCENE) {
            var readiness = new StationaryTerrain();
            context.waitFor(readiness::ready, 1200);
            // Incremental loading can leave a conservative graph dependent on arrival order.
            // Request Vanilla's normal full rebuild after upload convergence, before warmup.
            context.runOnClient(client -> client.levelRenderer.sectionOcclusionGraph().invalidate());
            var rebuiltReadiness = new StationaryTerrain();
            context.waitFor(rebuiltReadiness::ready, 1200);
            stationaryTerrain = rebuiltReadiness.evidence();
            stationaryTerrain.addProperty("fullGraphRebuildAfterLoad", true);
            stationaryVisibleSections = stationaryTerrain.get("visibleSections").getAsInt();
            report.addProperty("initialVisibleSections", stationaryVisibleSections);
            report.add("stationaryTerrain", stationaryTerrain);
        }
        JsonObject profile = new JsonObject();
        profile.addProperty("profileId", FrameWorkloads.ENABLED || STATIONARY_BASELINE ? ROUTE : "vanilla-normal-" + EVIDENCE_PHASE + "-v1");
        if (FrameWorkloads.ENABLED) {
            profile.addProperty("workloadId", FrameWorkloads.ID);
            profile.addProperty("protocolVersion", 1);
            profile.addProperty("producer", FrameWorkloads.PRODUCER);
            profile.addProperty("transitionWindow", FrameWorkloads.TRANSITIONS);
            profile.addProperty("workloadSha256", System.getProperty("metallum.ci.workloadSha256", "unavailable"));
            profile.add("producerReceipt", context.computeOnClient(client -> FrameWorkloads.producerReceipt()));
        }
        profile.add("initialContent", initialContent);
        profile.add("quality", settings.deepCopy());
        if (stationaryTerrain != null) profile.add("stationaryTerrain", stationaryTerrain.deepCopy());
        JsonObject targetIntent = new JsonObject();
        targetIntent.addProperty("fpsLimit", TARGET_FPS);
        targetIntent.addProperty("vsync", VSYNC);
        targetIntent.addProperty("authority", "requested-options-not-system-deadline");
        profile.add("targetIntent", targetIntent);
        JsonObject route = new JsonObject();
        route.addProperty("id", ROUTE);
        route.addProperty("inputAuthority", "Fabric client GameTest input driver");
        route.addProperty("samplePhase", FrameWorkloads.ENABLED ? "workload-" + FrameWorkloads.ID :
                EVIDENCE_PHASE.equals("stationary") ? "stationary-full-view" : "flight-new-chunks");
        route.addProperty("camera", "160.5,140,160.5 yaw=-65 pitch=15; creative flight fixed view");
        if (FrameWorkloads.ENABLED) {
            route.addProperty("warmupSeconds", WARMUP_NS / 1e9);
            route.addProperty("sampleSeconds", SAMPLE_NS / 1e9);
        } else {
            route.addProperty("warmupSeconds", WARMUP_SECONDS);
            route.addProperty("sampleSeconds", SAMPLE_SECONDS);
        }
        route.addProperty("warmupNs", WARMUP_NS);
        route.addProperty("sampleNs", SAMPLE_NS);
        route.addProperty("sourceFrameStorageCapacity", FRAME_TIME_CAPACITY);
        route.addProperty("completion", FrameWorkloads.ENABLED ? "fixed window, complete versioned action sequence, verified producer/scene/settings; transitions are not steady-state comparisons" :
                STATIONARY_BASELINE ? "fixed-view window duration, stable terrain, pose and quality assertions"
                : "selected window duration and all existing flight/place-break/quality assertions");
        profile.add("route", route);
        profile.addProperty("instrumentationMode", System.getProperty("metallum.frameEvidence.mode", "off"));
        profile.add("optimizationProfile", optimizationProfile());
        report.add("frameEvidenceProfile", profile);
        JsonObject windowClockAnchors = windowClockAnchors();
        report.add("windowClockAnchors", windowClockAnchors);
        report.addProperty("status", "ready");
        write(output.resolve("gameplay-ready.json"), report);
        if (Boolean.getBoolean("metallum.ci.waitForProfiler")) {
            // The launcher releases this only after Instruments signals recording started.
            context.waitFor(client -> Files.exists(output.resolve("profiler-started")), 1200);
        }
        input.lookAt(-65, 15);
        JsonObject stationaryWindow = context.computeOnClient(client -> {
            // Loading the full view can exceed Vanilla's AFK threshold. The
            // route starts with synthetic camera input, just like each flight leg.
            client.getFramerateLimitTracker().onInputReceived();
            // Cache requested OptionInstance values once. Checking them creates no per-frame JSON
            // and catches a transient quality change even when final settings are restored.
            var guardedOptions = java.util.List.of(client.options.graphicsPreset(), client.options.renderDistance(),
                    client.options.simulationDistance(), client.options.ambientOcclusion(), client.options.cloudStatus(),
                    client.options.cloudRange(), client.options.particles(), client.options.mipmapLevels(),
                    client.options.entityDistanceScaling(), client.options.entityShadows(), client.options.biomeBlendRadius(),
                    client.options.improvedTransparency(), client.options.textureFiltering(), client.options.maxAnisotropyBit(),
                    client.options.cutoutLeaves(), client.options.weatherRadius(), client.options.enableVsync(),
                    client.options.framerateLimit());
            qualityChecks = guardedOptions.stream().map(option -> {
                Object initial = option.get();
                return (java.util.function.BooleanSupplier) () -> initial.equals(option.get());
            }).toList();
            frameCount = invalidSettingsFrames = throttledFrames = droppedSamples = 0;
            drawableWaitNanos = 0;
            drawableWaitSamples = 0;
            startedNanos = System.nanoTime();
            long start = startedNanos;
            sampleStartNanos = Math.addExact(start, WARMUP_NS);
            if (FrameWorkloads.ENABLED) sourceWindow = new SourceWindow.Accumulator(sampleStartNanos, Math.addExact(sampleStartNanos, SAMPLE_NS));
            recordingFrames = true;
            JsonObject timing = new JsonObject();
            timing.addProperty("event", "source-route-start");
            timing.add("routeStart", clockPair("source-route-start"));
            timing.addProperty("routeStartNs", start);
            if (FrameWorkloads.ENABLED || EVIDENCE_PHASE.equals("stationary")) {
                timing.add("armWindow", armWindowWithClockAnchors(profile, ROUTE, start));
                timing.add("declaredSourceWindow", declaredSourceWindow(sampleStartNanos));
            }
            return timing;
        });
        long stationaryStart = stationaryWindow.get("routeStartNs").getAsLong();
        windowClockAnchors.getAsJsonArray("events").add(stationaryWindow);
        try {
            if (FrameWorkloads.ENABLED) {
                report.add("workload", FrameWorkloads.run(context, world, sampleStartNanos,
                        sampleStartNanos + SAMPLE_NS, NATIVE_WIDTH, NATIVE_HEIGHT));
                report.add("sourceSampleWindow", context.computeOnClient(client -> sourceWindow.finish()));
                if (STABLE_SCENE) {
                    JsonObject finalTerrain = context.computeOnClient(StationaryTerrain::capture);
                    report.add("finalStationaryTerrain", finalTerrain);
                    require(finalTerrain != null && finalTerrain.get("visibleSectionSha256").equals(
                            report.getAsJsonObject("stationaryTerrain").get("visibleSectionSha256")),
                            "P0 visible section identity changed across the window");
                    report.addProperty("stationaryGeometryChanged", !finalTerrain.get("visibleDrawSha256").equals(
                            report.getAsJsonObject("stationaryTerrain").get("visibleDrawSha256")));
                }
                require(!FrameEvidenceRuntime.ENABLED || FrameEvidenceRuntime.windowComplete(), "Frame evidence window is incomplete");
            } else {
                phase(context, output, report, phases, "stationary-full-view");
                // Identical off/on workload clock: observer presence never controls the route.
                context.waitFor(client -> System.nanoTime() - stationaryStart >= WARMUP_NS + SAMPLE_NS,
                        WINDOW_TIMEOUT_TICKS);
                context.waitTick(); // Same extra tick in off/on; finish the frame containing the boundary.
                require(!EVIDENCE_PHASE.equals("stationary") || !FrameEvidenceRuntime.ENABLED || FrameEvidenceRuntime.windowComplete(),
                        "Frame evidence did not finish the predeclared stationary window");
                if (EVIDENCE_PHASE.equals("stationary")) {
                    JsonObject endAnchor = context.computeOnClient(client -> clockPair("source-window-end-observed"));
                    windowClockAnchors.getAsJsonArray("events").add(endAnchor);
                }
                JsonObject stationarySourceFrames = context.computeOnClient(client ->
                        SourceWindow.summarize(FRAME_TIMES, frameCount, sampleStartNanos, sampleStartNanos + SAMPLE_NS));
                stationarySourceFrames.addProperty("storageCapacity", FRAME_TIME_CAPACITY);
                stationarySourceFrames.addProperty("storageOverflow", droppedSamples);
                report.add("stationarySourceFrames", stationarySourceFrames);
                if (STATIONARY_BASELINE) {
                    JsonObject finalTerrain = context.computeOnClient(StationaryTerrain::capture);
                    report.add("finalStationaryTerrain", finalTerrain);
                    require(finalTerrain != null && finalTerrain.get("visibleSectionSha256").equals(
                            report.getAsJsonObject("stationaryTerrain").get("visibleSectionSha256")),
                            "Stationary visible section identity changed across the window");
                    report.addProperty("stationaryGeometryChanged", !finalTerrain.get("visibleDrawSha256").equals(
                            report.getAsJsonObject("stationaryTerrain").get("visibleDrawSha256")));
                }
                if (!STATIONARY_BASELINE) {
                    phase(context, output, report, phases, "flight-new-chunks");
                    if (EVIDENCE_PHASE.equals("streaming")) {
                        JsonObject streamingWindow = context.computeOnClient(client -> {
                            long start = System.nanoTime();
                            JsonObject timing = armWindowWithClockAnchors(profile, "streaming", start);
                            timing.add("declaredSourceWindow", declaredSourceWindow(Math.addExact(start, WARMUP_NS)));
                            return timing;
                        });
                        windowClockAnchors.getAsJsonArray("events").add(streamingWindow);
                    }
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
                    require(!EVIDENCE_PHASE.equals("streaming") || !FrameEvidenceRuntime.ENABLED || FrameEvidenceRuntime.windowComplete(),
                            "Frame evidence did not finish the predeclared streaming window");
                    if (EVIDENCE_PHASE.equals("streaming")) {
                        JsonObject endAnchor = context.computeOnClient(client -> clockPair("source-window-end-observed"));
                        windowClockAnchors.getAsJsonArray("events").add(endAnchor);
                    }
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
                }
            }
            report.addProperty("status", "completed");
            long[] finalMetal4 = context.computeOnClient(client -> MetalNativeBridge.metallum_metal4_main_renderer_stats());
            require(finalMetal4[0] == (EXPECT_METAL4 ? 1 : 0)
                    && (!EXPECT_METAL4 || finalMetal4[2] > initialMetal4[2]), "Metal lowering changed or submitted no work");
            report.addProperty("metal4Submissions", finalMetal4[2] - initialMetal4[2]);
            report.add("sourceFrames", context.computeOnClient(client -> finishFrames()));
            JsonObject cacheEvidence = context.computeOnClient(client -> {
                JsonObject stats = new JsonObject();
                long[] counters = sliceCacheCounters(terrainSliceCache);
                stats.addProperty("hits", counters[0] - initialCache[0]);
                stats.addProperty("misses", counters[1] - initialCache[1]);
                stats.addProperty("verifiedHits", counters[2] - initialCache[2]);
                return stats;
            });
            report.add("terrainSliceCacheEvidence", cacheEvidence);
            require(!terrainSliceCache || cacheEvidence.get("hits").getAsLong() > 0, "Terrain slice cache did not activate");
            require(!verifyTerrainSliceCache || cacheEvidence.get("verifiedHits").getAsLong() > 0,
                    "Terrain slice differential oracle did not activate");
            JsonObject finalSettings = context.computeOnClient(VanillaGameplay::settings);
            report.add("finalSettings", finalSettings);
            report.add("optimizationActivation", context.computeOnClient(client -> optimizationActivation()));
            report.add("efficiencyActivation", context.computeOnClient(client -> efficiencyActivation()));
            require(settings.equals(finalSettings), "Rendering settings changed during the route");
            require(invalidSettingsFrames == 0 && droppedSamples == 0 && throttledFrames == 0,
                    "Source frame measurements failed the full-resolution, maximum-distance or cadence gate");
            context.runOnClient(client -> FrameEvidenceRuntime.validationFinished("passed"));
            report.addProperty("completedAt", Instant.now().toString());
            write(output.resolve("gameplay.json"), report);
            context.takeScreenshot("vanilla-gameplay-completed");
        } catch (RuntimeException | Error failure) {
            context.runOnClient(client -> FrameEvidenceRuntime.validationFinished("failed"));
            report.add("sourceFrames", context.computeOnClient(client -> finishFrames()));
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
            value.addProperty("drawableWaitNanos", drawableWaitNanos);
            value.addProperty("drawableWaitSamples", drawableWaitSamples);
            value.addProperty("elapsedNanos", System.nanoTime() - startedNanos);
            return value;
        });
        phases.add(phase);
        write(output.resolve("gameplay-progress.json"), report);
    }

    /**
     * Java-side wall/monotonic calibration only. Native presentedTime is on a
     * separate clock and is deliberately absent from this record.
     */
    private static JsonObject clockPair(String label) {
        long monoBefore = System.nanoTime();
        Instant wall = Instant.now();
        long monoAfter = System.nanoTime();
        JsonObject value = new JsonObject();
        value.addProperty("label", label);
        value.addProperty("source", "Java System.nanoTime + java.time.Instant");
        value.addProperty("nativePresentedTimeMixed", false);
        value.addProperty("monoBeforeNs", monoBefore);
        value.addProperty("monoAfterNs", monoAfter);
        value.addProperty("wall", wall.toString());
        value.addProperty("uncertaintyNs", Math.max(0L, monoAfter - monoBefore));
        return value;
    }

    private static JsonObject windowClockAnchors() {
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", 1);
        value.addProperty("clock", "java-System.nanoTime");
        value.addProperty("wallClock", "java.time.Instant");
        value.addProperty("source", "VanillaGameplay driver; Java-side calibration only");
        value.addProperty("nativePresentedTimeMixed", false);
        value.addProperty("archiveWindowAuthority", "frame-evidence.json.window.startNs/endNs");
        value.addProperty("coverageClaim", "anchors estimate a Java-to-wall mapping; they do not prove continuous Xcode coverage");
        value.add("events", new JsonArray());
        return value;
    }

    private static JsonObject armWindowWithClockAnchors(JsonObject profile, String route, long armedAtNs) {
        JsonObject value = new JsonObject();
        value.addProperty("event", "FrameEvidenceRuntime.armWindow");
        value.addProperty("anchorNs", armedAtNs);
        value.addProperty("anchorAuthority", "shared-source-workload-recorder-java-clock");
        value.addProperty("route", route);
        value.add("before", clockPair("before-arm-window"));
        FrameEvidenceRuntime.armWindowAt(profile, WARMUP_NS, SAMPLE_NS, armedAtNs);
        value.add("after", clockPair("after-arm-window"));
        return value;
    }

    private static JsonObject declaredSourceWindow(long startNs) {
        JsonObject value = new JsonObject();
        value.addProperty("clock", "java-System.nanoTime");
        value.addProperty("startNs", startNs);
        value.addProperty("endNs", Math.addExact(startNs, SAMPLE_NS));
        value.addProperty("warmupNs", WARMUP_NS);
        value.addProperty("sampleNs", SAMPLE_NS);
        value.addProperty("authority", "route declaration; archive.window.startNs/endNs is recorder authority");
        return value;
    }

    private static JsonObject optimizationProfile() {
        JsonObject value = new JsonObject();
        value.addProperty("id", OPTIMIZATION_PROFILE);
        value.addProperty("pairKey", DIAGNOSTIC_REUSE_CANDIDATE ? null : STATIONARY_BASELINE ? ROUTE : null);
        value.addProperty("feature", ENCODER_ARGUMENT_CANDIDATE
                ? "encoder-native-argument-reuse" : "encoder-cpu-state-reuse");
        value.addProperty("reuseEncoderState", REUSE_ENCODER_STATE);
        value.addProperty("reuseNativeEncoderArguments", REUSE_NATIVE_ENCODER_ARGUMENTS);
        value.addProperty("candidate", REUSE_CANDIDATE);
        value.addProperty("activationTelemetry", MetalRenderStatePacketTelemetry.reuseActivationTelemetryEnabled());
        return value;
    }

    private static JsonObject optimizationActivation() {
        var snapshot = MetalRenderStatePacketTelemetry.snapshot();
        boolean packetReuse = snapshot.packetStorageReuseHits() > 0;
        boolean shadowReuse = snapshot.shadowReuseHits() > 0;
        boolean stateReuseActive = REUSE_ENCODER_STATE && packetReuse && shadowReuse;
        boolean argumentReuseActive = REUSE_NATIVE_ENCODER_ARGUMENTS
                && snapshot.nativeEncoderArgumentReuseCalls() > 0;
        boolean active = ENCODER_ARGUMENT_CANDIDATE ? argumentReuseActive : stateReuseActive;
        boolean requested = REUSE_ENCODER_STATE || REUSE_NATIVE_ENCODER_ARGUMENTS;
        JsonObject value = new JsonObject();
        value.addProperty("requested", requested);
        value.addProperty("active", active);
        value.addProperty("status", !requested ? "not-requested" : active ? "active" : "inactive");
        value.addProperty("telemetryEnabled", MetalRenderStatePacketTelemetry.reuseActivationTelemetryEnabled());
        value.addProperty("scope", "startup-to-gameplay-completion");
        value.addProperty("cumulative", true);
        value.addProperty("packetStorageAllocations", snapshot.packetStorageAllocations());
        value.addProperty("packetStorageReuseHits", snapshot.packetStorageReuseHits());
        value.addProperty("shadowAllocations", snapshot.shadowAllocations());
        value.addProperty("shadowReuseHits", snapshot.shadowReuseHits());
        value.addProperty("nativeEncoderArgumentReuseCalls", snapshot.nativeEncoderArgumentReuseCalls());
        return value;
    }

    private static JsonObject efficiencyActivation() {
        JsonObject value = new JsonObject();
        // Reflection keeps the driver usable with the exact baseline JAR,
        // which may predate a candidate's new counter. Absence stays absent.
        for (String[] lane : new String[][]{
                {"dynamicUploadRangeCopy", "com.metallum.client.metal.render.mtl.MetalHotPathTelemetry", "snapshot", "dynamicRangeCopies"},
                {"nativeMultiDrawBatch", "com.metallum.client.metal.render.mtl.MetalHotPathTelemetry", "snapshot", "nativeMultiDrawBatches"},
                {"asyncPrecompile", "com.metallum.client.metal.render.mtl.MetalHotPathTelemetry", "snapshot", "preparedPipelineCount"}}) {
            JsonObject row = new JsonObject();
            boolean requested = Boolean.getBoolean("metallum.opt." + lane[0]);
            row.addProperty("requested", requested);
            row.addProperty("scope", "startup-to-gameplay-completion; activation only");
            try {
                Object counter = Class.forName(lane[1]).getMethod(lane[2]).invoke(null);
                long count = ((Number) (lane[3].isEmpty() ? counter : counter.getClass().getMethod(lane[3]).invoke(counter))).longValue();
                row.addProperty("count", count);
                row.addProperty("active", requested && count > 0);
            } catch (ReflectiveOperationException unavailable) {
                row.addProperty("active", false);
                row.addProperty("unavailableReason", unavailable.getClass().getSimpleName());
            }
            value.add(lane[0], row);
        }
        JsonObject pacing = new JsonObject();
        try {
            JsonObject snapshot = (JsonObject) Class.forName("com.metallum.client.metal.render.MetalFramePacing").getMethod("snapshot").invoke(null);
            pacing.add("decision", snapshot);
            pacing.addProperty("active", snapshot.get("enabled").getAsBoolean()
                    && "metallum".equals(snapshot.get("owner").getAsString()) && snapshot.get("effectiveFps").getAsInt() < 260);
        } catch (ReflectiveOperationException unavailable) {
            pacing.addProperty("active", false);
        }
        value.add("pacingPolicy", pacing);
        return value;
    }

    private static JsonObject settings(Minecraft client) {
        JsonObject value = new JsonObject();
        var physical = client.getWindow().queryFramebufferSize();
        value.addProperty("nativeWindowPixelWidth", physical.width());
        value.addProperty("nativeWindowPixelHeight", physical.height());
        value.addProperty("presentWidth", lastPresentedConfiguration == null ? 0 : lastPresentedConfiguration.width());
        value.addProperty("presentHeight", lastPresentedConfiguration == null ? 0 : lastPresentedConfiguration.height());
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

    private static int configuredSeconds(String property, int defaultValue) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) return defaultValue;
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(property + " must be an integer number of seconds", failure);
        }
        if (value < 1 || value > MAX_WINDOW_SECONDS) {
            throw new IllegalArgumentException(property + " must be between 1 and " + MAX_WINDOW_SECONDS + " seconds");
        }
        return value;
    }

    private static int validateWindowContract() {
        int total;
        try {
            total = Math.addExact(WARMUP_SECONDS, SAMPLE_SECONDS);
        } catch (ArithmeticException impossible) {
            throw new IllegalArgumentException("frame evidence window duration overflow", impossible);
        }
        if (total > MAX_WINDOW_SECONDS) {
            throw new IllegalArgumentException("frame evidence warmup plus sample must not exceed " + MAX_WINDOW_SECONDS + " seconds");
        }
        if ("streaming".equals(EVIDENCE_PHASE)
                && (WARMUP_SECONDS != DEFAULT_WARMUP_SECONDS || SAMPLE_SECONDS != DEFAULT_SAMPLE_SECONDS)) {
            throw new IllegalArgumentException("streaming frame evidence only supports the declared 5s warmup and 10s sample route");
        }
        return total;
    }

    private static long secondsToNanos(int seconds) {
        try {
            return Math.multiplyExact((long) seconds, 1_000_000_000L);
        } catch (ArithmeticException impossible) {
            throw new IllegalArgumentException("frame evidence duration does not fit in nanoseconds", impossible);
        }
    }

    private static int windowTimeoutTicks() {
        try {
            return Math.toIntExact(Math.addExact(Math.multiplyExact((long) WINDOW_SECONDS, 20L),
                    WINDOW_TIMEOUT_MARGIN_TICKS));
        } catch (ArithmeticException impossible) {
            throw new IllegalArgumentException("frame evidence wait timeout overflow", impossible);
        }
    }

    private static JsonObject finishFrames() {
        recordingFrames = false;
        long elapsed = System.nanoTime() - startedNanos;
        if (FrameWorkloads.ENABLED) {
            JsonObject value = sourceWindow.finish();
            value.addProperty("invalidSettingsFrames", invalidSettingsFrames);
            value.addProperty("throttledFrames", throttledFrames);
            value.addProperty("droppedSamples", droppedSamples);
            value.addProperty("instrumentation", "constant-memory source-return histogram, identical in OFF/timing/diagnostic");
            return value;
        }
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
        value.addProperty("drawableWaitNanos", drawableWaitNanos);
        value.addProperty("drawableWaitSamples", drawableWaitSamples);
        value.addProperty("instrumentation", PRESENTATION_METRICS
                ? "one existing native drawable-wait getter per source frame" : "source timestamps only");
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
