package com.metallum.client.validation;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalFxManager;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final Set<Integer> CAPTURE_FRAMES = parseFrames(
            System.getProperty("metallum.backend.compare.frames", "90")
    );
    private static final int IRIS_RELOAD_FRAME = Integer.getInteger(
            "metallum.backend.compare.iris-reload-frame",
            -1
    );
    private static final List<Integer> COMPLETED_FRAMES = new ArrayList<>();
    private static int levelFrame = -1;
    private static int pendingCaptures;
    private static int failedCaptures;
    private static boolean sessionWritten;
    private static boolean stopRequested;
    private static boolean irisReloadAttempted;
    private static boolean irisReloadCompleted;

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
        if (minecraft.options != null) {
            minecraft.options.pauseOnLostFocus = false;
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

    private static void capture(final GameRenderer renderer, final int frame) {
        pendingCaptures++;
        GpuBuffer buffer = null;
        GpuFence fence = null;
        try {
            RenderTarget target = MetalFxManager.presentTarget(renderer);
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
        return String.format(
                Locale.ROOT,
                "{\n"
                        + "  \"schema\": 1,\n"
                        + "  \"backend\": \"%s\",\n"
                        + "  \"backendDescription\": \"%s\",\n"
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
                        + "  \"metalFxMode\": \"%s\"\n"
                        + "}\n",
                jsonEscape(backend),
                jsonEscape(RenderSystem.getBackendDescription()),
                frame,
                texture.getWidth(0),
                texture.getHeight(0),
                texture.getFormat(),
                byteCount,
                jsonEscape(target.getClass().getSimpleName()),
                Boolean.getBoolean("metallum.metal.hud"),
                Boolean.getBoolean("metallum.iris.semantic"),
                jsonEscape(System.getProperty("metallum.metalfx.mode", "unspecified"))
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
            Files.writeString(
                    directory.resolve("session.json"),
                    String.format(
                            Locale.ROOT,
                            "{\n  \"schema\": 1,\n  \"status\": \"%s\",\n"
                                    + "  \"backend\": \"%s\",\n  \"requestedFrames\": %s,\n"
                                    + "  \"completedFrames\": %s,\n  \"failedCaptures\": %d,\n"
                                    + "  \"irisReloadFrame\": %d,\n"
                                    + "  \"irisReloadAttempted\": %s,\n"
                                    + "  \"irisReloadCompleted\": %s\n}\n",
                            jsonEscape(status),
                            jsonEscape(backendName()),
                            CAPTURE_FRAMES,
                            COMPLETED_FRAMES,
                            failedCaptures,
                            IRIS_RELOAD_FRAME,
                            irisReloadAttempted,
                            irisReloadCompleted
                    ),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignoredException) {
            // Diagnostic metadata must not turn a rendered frame into a crash.
        }
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

    private static String jsonEscape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
