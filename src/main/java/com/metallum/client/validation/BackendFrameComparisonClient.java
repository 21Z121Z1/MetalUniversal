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
import net.minecraft.world.entity.Entity;
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
        if (stopRequested && pendingCaptures == 0 && AUTO_STOP) {
            writeSession(failedCaptures == 0 ? "passed" : "failed", null);
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
