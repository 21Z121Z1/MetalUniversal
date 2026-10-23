package com.metallum.reference;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;

/** Stock OpenGL oracle: this mod has no MetalUniversal runtime dependency. */
@SuppressWarnings("UnstableApiUsage")
public final class OpenGlReferenceGameTest implements FabricClientGameTest {
    private static final String PLAYER = "ReferencePlayer";
    private static final String UUID = "8f16930a-42ad-4f9b-9d59-02698f26b145";
    private static final Path OUTPUT = Path.of(System.getProperty("reference.output")).toAbsolutePath();
    private static final String RENDERER = System.getProperty("reference.renderer");
    private static final int WIDTH = Integer.getInteger("reference.width");
    private static final int HEIGHT = Integer.getInteger("reference.height");
    private static final int DISTANCE = Integer.getInteger("reference.distance");

    @Override
    public void runTest(ClientGameTestContext context) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("scenario", "hosted-opengl-reference-v1");
        report.addProperty("sourceSha", System.getProperty("reference.sourceSha"));
        report.addProperty("renderer", RENDERER);
        report.addProperty("status", "running");
        report.addProperty("claim", "Independent stock OpenGL reference only; no Metal parity, physical quality or performance verdict");
        Receipt receipt = new Receipt(report);
        try {
            JsonObject mods = new JsonObject();
            FabricLoader.getInstance().getAllMods().stream()
                    .sorted(java.util.Comparator.comparing(m -> m.getMetadata().getId()))
                    .forEach(m -> mods.addProperty(m.getMetadata().getId(), m.getMetadata().getVersion().getFriendlyString()));
            report.add("mods", mods);
            receipt.checkpoint(report, "setup");
            Path harness = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            require(Files.isRegularFile(harness), "Reference loaded development classes instead of its production JAR");
            report.addProperty("harnessJarSha256", hash(harness));
            try (var stream = getClass().getResourceAsStream("/reference-build.json")) {
                require(stream != null, "Reference build identity is missing");
                report.add("buildIdentity", JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            }
            require(!FabricLoader.getInstance().isModLoaded("metallum"), "Reference must not load MetalUniversal");
            require(mods.has("sodium") == !RENDERER.equals("vanilla"), "Unexpected Sodium runtime");
            require(mods.has("iris") == RENDERER.equals("iris"), "Unexpected Iris runtime");
            String backendAtStartup = context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName());
            report.addProperty("backendAtStartup", backendAtStartup);
            require("OpenGL".equals(report.get("backendAtStartup").getAsString()), "Reference backend fallback at startup");
            receipt.checkpoint(report, "window-setup");
            context.getInput().resizeWindow(WIDTH, HEIGHT);
            context.runOnClient(client -> {
                client.options.pauseOnLostFocus = false;
                client.options.renderDistance().set(DISTANCE);
                client.options.broadcastOptions();
                client.invalidateSurfaceConfiguration();
            });
            if (RENDERER.equals("iris")) {
                receipt.checkpoint(report, "iris-world-preparation");
                context.runOnClient(client -> {
                    // Prepare the complete sending view before the expensive software-GL
                    // draws. The reference image still requires the original active pack.
                    IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
                    boolean enabled = Iris.getIrisConfig().areShadersEnabled();
                    report.addProperty("irisShadersAtWorldCreation", enabled);
                    require(!enabled, "Iris world preparation did not disable shaders");
                });
            }
            receipt.checkpoint(report, "world-create");
            try (var world = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(settings -> {
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(settings.getSettings()
                        .worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)));
                settings.setName("Independent OpenGL reference");
                settings.setSeed("1");
                settings.setGenerateStructures(true);
            }).create()) {
                try {
                    receipt.checkpoint(report, "scene-setup");
                    world.getServer().runCommand("gamemode spectator @a");
                    world.getServer().runCommand("time set noon");
                    world.getServer().runCommand("weather clear");
                    world.getServer().runCommand("tp @a 160.5 138.38 160.5 -65 15");
                    receipt.checkpoint(report, "initial-40-ticks");
                    context.waitTicks(40);
                    receipt.checkpoint(report, "server-view");
                    // Fabric's download predicate requires square corners that 26.3 never sends.
                    // Retain the full download gate using Vanilla's actual declared sending view.
                    var view = world.getServer().computeOnServer(server -> {
                        var tracked = server.getPlayerList().getPlayers().getFirst().getChunkTrackingView();
                        require(tracked instanceof ChunkTrackingView.Positioned, "No positioned server view");
                        return (ChunkTrackingView.Positioned) tracked;
                    });
                    require(view.viewDistance() == DISTANCE && view.center().equals(new ChunkPos(10, 10)), "Server view differs from the requested scene");
                    var chunks = new java.util.ArrayList<ChunkPos>();
                    view.forEach(chunks::add);
                    report.addProperty("expectedChunks", chunks.size());
                    // Unknown until the first client observation; never imply an observed zero.
                    report.addProperty("receivedChunks", -1);
                    receipt.checkpoint(report, "full-chunks");
                    context.waitFor(client -> {
                        long received = chunks.stream().filter(pos -> client.level.getChunkSource()
                                .getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null).count();
                        if (report.get("receivedChunks").getAsLong() != received) {
                            report.addProperty("receivedChunks", received);
                            receipt.checkpoint(report, "full-chunks");
                        }
                        return received == chunks.size();
                    }, 6000);
                    receipt.checkpoint(report, "render-ready");
                    world.getConnection().waitForChunksRender(false, 1200);
                    receipt.checkpoint(report, "verify-full-chunks");
                    long received = context.computeOnClient(client -> chunks.stream().filter(pos -> client.level.getChunkSource()
                            .getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null).count());
                    report.addProperty("receivedChunks", received);
                    receipt.checkpoint(report, "freeze-scene");
                    require(received == chunks.size(), "The complete server view must remain loaded");
                    boolean serverFrozen = world.getServer().computeOnServer(server -> {
                        require(server.overworld().getSeed() == 1, "Unexpected world seed");
                        server.overworld().tickRateManager().setFrozen(true);
                        return server.overworld().tickRateManager().isFrozen();
                    });
                    report.addProperty("serverFrozen", serverFrozen);
                    context.runOnClient(client -> client.level.tickRateManager().setFrozen(true));
                    receipt.checkpoint(report, "frozen-20-ticks");
                    context.waitTicks(20);
                    receipt.checkpoint(report, "iris-reload");
                    if (RENDERER.equals("iris")) {
                        context.runOnClient(client -> {
                            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true);
                            require(Iris.getIrisConfig().areShadersEnabled(), "Iris activation failed");
                        });
                        // Reload replaces the terrain pipeline; require its meshes again.
                        receipt.checkpoint(report, "iris-render-ready");
                        world.getConnection().waitForChunksRender(false, 1200);
                        long afterActivation = context.computeOnClient(client -> chunks.stream().filter(pos -> client.level.getChunkSource()
                                .getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null).count());
                        report.addProperty("receivedChunksAfterIrisActivation", afterActivation);
                        require(afterActivation == chunks.size(), "Iris activation lost the complete sending view");
                    }
                    report.addProperty("settledTicks", 0);
                    receipt.checkpoint(report, "settle-40-ticks");
                    for (int tick = 1; tick <= 40; tick++) {
                        context.waitTick();
                        report.addProperty("settledTicks", tick);
                        receipt.checkpoint(report, "settle-40-ticks");
                    }
                    receipt.checkpoint(report, "capture");
                    JsonObject capture = context.computeOnClient(this::capture);
                    report.add("capture", capture);
                    receipt.checkpoint(report, "capture-complete");
                } catch (Exception | AssertionError failure) {
                    report.addProperty("status", "correctness-failed");
                    report.addProperty("failure", failure.toString());
                    report.addProperty("failurePhase", report.get("phase").getAsString());
                    receipt.checkpoint(report, "failure-before-cleanup");
                    throw failure;
                } finally {
                    // Fabric remains the disconnect/close owner. Request halt on the server owner.
                    receipt.checkpoint(report, "cleanup-server-halt");
                    world.getServer().computeOnServer(server -> { server.halt(false); return null; });
                    receipt.checkpoint(report, "cleanup-world-close");
                }
            }
            receipt.finish(report, "passed");
        } catch (Exception | AssertionError failure) {
            report.addProperty("failure", failure.toString());
            receipt.finish(report, "correctness-failed");
            throw new IllegalStateException("OpenGL reference failed", failure);
        }
    }

    private JsonObject capture(Minecraft client) {
        JsonObject result = new JsonObject();
        var device = RenderSystem.getDevice();
        String backend = device.getDeviceInfo().backendName();
        require("OpenGL".equalsIgnoreCase(backend), "Reference fell back to " + backend);
        result.addProperty("backend", backend);
        result.addProperty("backendDescription", RenderSystem.getBackendDescription());
        result.addProperty("glRenderer", org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER));
        result.addProperty("glVersion", org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_VERSION));
        result.addProperty("playerName", client.getUser().getName());
        result.addProperty("playerUuid", client.getUser().getProfileId().toString());
        require(PLAYER.equals(client.getUser().getName()) && UUID.equals(client.getUser().getProfileId().toString()),
                "Reference identity differs from fixed player");
        require(client.level != null && client.player != null, "Reference world is absent");
        require(client.options.getEffectiveRenderDistance() == DISTANCE, "Effective render distance changed");
        result.addProperty("renderDistance", client.options.getEffectiveRenderDistance());
        result.addProperty("loadedChunks", client.level.getChunkSource().getLoadedChunksCount());
        result.addProperty("worldSeed", 1);
        result.addProperty("camera", "160.5,140,160.5,-65,15");
        result.addProperty("clientFrozen", client.level.tickRateManager().isFrozen());
        result.addProperty("observedCameraX", client.gameRenderer.mainCamera().position().x);
        result.addProperty("observedCameraY", client.gameRenderer.mainCamera().position().y);
        result.addProperty("observedCameraZ", client.gameRenderer.mainCamera().position().z);
        if (RENDERER.equals("iris")) {
            require(Iris.getIrisConfig().areShadersEnabled() && Iris.getCurrentPack().isPresent(), "Iris pack is inactive");
            var pipeline = Iris.getPipelineManager().getPipelineNullable();
            require(pipeline != null && pipeline.getClass().getName().equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline"),
                    "Expected real OpenGL Iris pipeline, found " + pipeline);
            result.addProperty("irisPipeline", pipeline.getClass().getName());
            result.addProperty("shaderPack", Iris.getCurrentPackName());
            result.addProperty("shaderPackSha256", hash(client.gameDirectory.toPath().resolve("shaderpacks").resolve(Iris.getCurrentPackName())));
        }
        try (var control = device.createTexture("OpenGL independent readback control",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, 8, 8, 1, 1)) {
            var encoder = device.createCommandEncoder();
            encoder.clearColorTexture(control, new Vector4f(1, 0, 1, 1));
            byte[] actual = readback(control);
            for (int i = 0; i < actual.length; i++) require((actual[i] & 255) == (i % 4 == 1 ? 0 : 255), "RGBA readback control mismatch at " + i);
            result.addProperty("controlExact", true);
        }
        var texture = client.gameRenderer.mainRenderTarget().getColorTexture();
        require(texture != null && texture.getWidth(0) == WIDTH && texture.getHeight(0) == HEIGHT, "Reference output dimensions changed");
        byte[] raw = readback(texture);
        HashSet<Integer> colors = new HashSet<>();
        long nonBlack = 0;
        BufferedImage png = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int offset = (y * WIDTH + x) * 4;
            int rgb = ((raw[offset] & 255) << 16) | ((raw[offset + 1] & 255) << 8) | (raw[offset + 2] & 255);
            if (colors.size() < 8192) colors.add(rgb);
            if (rgb != 0) nonBlack++;
            png.setRGB(x, HEIGHT - 1 - y, ((raw[offset + 3] & 255) << 24) | rgb);
        }
        require(nonBlack > 0 && colors.size() > 1, "OpenGL world capture is empty/uniform");
        try {
            Files.write(OUTPUT.resolve("frame.rgba8"), raw);
            require(ImageIO.write(png, "PNG", OUTPUT.resolve("frame.png").toFile()), "PNG encoder unavailable");
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        result.addProperty("width", WIDTH); result.addProperty("height", HEIGHT);
        result.addProperty("format", texture.getFormat().toString());
        result.addProperty("rawOrigin", "lower-left");
        result.addProperty("byteCount", raw.length); result.addProperty("nonBlackPixels", nonBlack);
        result.addProperty("distinctRgb", colors.size());
        result.addProperty("rawSha256", hash(OUTPUT.resolve("frame.rgba8")));
        result.addProperty("pngSha256", hash(OUTPUT.resolve("frame.png")));
        int glError = org.lwjgl.opengl.GL11C.glGetError();
        result.addProperty("glError", glError);
        require(glError == org.lwjgl.opengl.GL11C.GL_NO_ERROR, "OpenGL reported error " + glError);
        return result;
    }

    private byte[] readback(GpuTexture texture) {
        require(texture.getFormat() == GpuFormat.RGBA8_UNORM, "Reference requires RGBA8_UNORM");
        int bytes = Math.multiplyExact(Math.multiplyExact(texture.getWidth(0), texture.getHeight(0)), 4);
        var device = RenderSystem.getDevice();
        try (var buffer = device.createBuffer(() -> "OpenGL reference readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes)) {
            var encoder = device.createCommandEncoder();
            try (var fence = encoder.createFence()) {
                encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
                encoder.submit();
                require(fence.awaitCompletion(10_000_000_000L), "OpenGL readback fence timeout");
                try (var mapped = buffer.map(true, false)) {
                    byte[] result = new byte[bytes];
                    var data = mapped.data().duplicate(); data.clear(); data.get(result);
                    return result;
                }
            }
        }
    }

    private static String hash(Path path) {
        try (var input = Files.newInputStream(path)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    /** Independent of Fabric ticks, client/server executors, and their Phaser/Semaphore waits. */
    private static final class Receipt {
        // Includes the complete BSL settling period on hosted software OpenGL.
        // The existing 30-minute job limit remains unchanged; setup/upload need headroom.
        private static final long LIMIT_NANOS = Duration.ofMinutes(25).toNanos();
        private static final long DIAGNOSTIC_NANOS = Duration.ofSeconds(5).toNanos();
        private final long started = System.nanoTime();
        private final java.util.concurrent.atomic.AtomicReference<String> state =
                new java.util.concurrent.atomic.AtomicReference<>("running");
        private final Object writer = new Object();
        private volatile JsonObject snapshot;
        private final Thread watchdog;

        Receipt(JsonObject report) {
            snapshot = report.deepCopy();
            snapshot.addProperty("phase", "setup");
            // Start before any receipt I/O, including the initial setup checkpoint.
            watchdog = new Thread(this::watch, "opengl-reference-wall-clock-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            checkpoint(report, "setup");
        }

        void checkpoint(JsonObject report, String phase) {
            require(state.get().equals("running"), "Reference receipt is already terminal");
            report.addProperty("phase", phase);
            report.addProperty("updatedAt", Instant.now().toString());
            report.addProperty("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
            report.addProperty("wallClockLimitMillis", Duration.ofNanos(LIMIT_NANOS).toMillis());
            JsonObject next = report.deepCopy();
            snapshot = next;
            synchronized (writer) {
                require(state.get().equals("running"), "Reference already timed out");
                write(next, "reference.json");
            }
        }

        void finish(JsonObject report, String status) {
            require(state.get().equals("running"), "Reference already timed out");
            if (System.nanoTime() - started >= LIMIT_NANOS) {
                timeout();
                throw new IllegalStateException("Reference wall-clock limit expired");
            }
            report.addProperty("status", status);
            // Do not publish success until world.close and the final receipt write complete.
            checkpoint(report, status.equals("passed") ? "complete" : report.get("phase").getAsString());
            if (System.nanoTime() - started >= LIMIT_NANOS) timeout();
            require(state.compareAndSet("running", status), "Reference timed out during final receipt write");
            watchdog.interrupt();
        }

        private void watch() {
            try { sleepUntil(started, LIMIT_NANOS); }
            catch (InterruptedException done) { return; }
            timeout();
        }

        private void timeout() {
            // This transition never acquires the writer lock or calls a game executor.
            if (!state.compareAndSet("running", "wall-clock-timeout")) return;
            long expired = System.nanoTime();
            Thread hardStop = new Thread(() -> {
                try { sleepUntil(expired, DIAGNOSTIC_NANOS); }
                catch (InterruptedException ignored) { /* Still fail closed. */ }
                Runtime.getRuntime().halt(124);
            }, "opengl-reference-timeout-hard-stop");
            hardStop.setDaemon(true);
            hardStop.start();
            JsonObject failed = snapshot.deepCopy();
            failed.addProperty("status", "wall-clock-timeout");
            failed.addProperty("timeoutReason", "Independent wall-clock limit exceeded during " + failed.get("phase").getAsString());
            failed.addProperty("timeoutAt", Instant.now().toString());
            failed.addProperty("elapsedMillis", Duration.ofNanos(expired - started).toMillis());
            try {
                // Independent marker cannot be overwritten by an in-flight normal writer.
                // The verifier also requires execution exitCode=0: even blocked diagnostic
                // storage must never turn an in-flight passed receipt into an accepted run.
                write(failed, "timeout.json");
                StringBuilder stacks = new StringBuilder();
                Thread.getAllStackTraces().forEach((thread, frames) -> {
                    stacks.append(thread.getName()).append(" [").append(thread.getState()).append("]\n");
                    for (StackTraceElement frame : frames) stacks.append("    at ").append(frame).append('\n');
                    stacks.append('\n');
                });
                Files.writeString(OUTPUT.resolve("timeout-threads.txt"), stacks.toString());
                synchronized (writer) { write(failed, "reference.json"); }
            } catch (Exception failure) {
                failure.printStackTrace();
            } finally {
                // Shutdown hooks may wait on the stuck client/server, so do not use exit().
                Runtime.getRuntime().halt(124);
            }
        }

        private static void sleepUntil(long start, long budget) throws InterruptedException {
            long remaining;
            while ((remaining = budget - (System.nanoTime() - start)) > 0) {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            }
        }
    }

    private static void write(JsonObject report, String name) {
        try {
            Files.createDirectories(OUTPUT);
            Path pending = OUTPUT.resolve(name + ".pending");
            Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            Files.move(pending, OUTPUT.resolve(name),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalStateException(reason); }
}
