package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * CI-only control probe for the production Metal texture -> shared-buffer path.
 *
 * <p>This class intentionally lives in the production renderer package so the
 * separate Client GameTest mod can exercise the exact package-private backend
 * objects without adding a test API to MetalUniversal itself. It is compiled
 * against, but is not packaged into, the production MetalUniversal JAR.</p>
 */
public final class MetalCiFramebufferProbe {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final Vector4f EXPECTED_CLEAR = new Vector4f(1.0f, 0.0f, 1.0f, 1.0f);

    private MetalCiFramebufferProbe() {
    }

    /**
     * Creates a private RGBA8 Metal render target, clears it on the GPU to
     * magenta, copies it with MetalCommandEncoder.copyTextureToBuffer(), waits
     * for submitted GPU work, and inspects the CPU-visible shared MTLBuffer.
     */
    public static ProbeResult runKnownColorReadback(Path outputDirectory) {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            throw new IllegalStateException("MetalDevice.current() is null inside production Minecraft");
        }

        GpuFormat format = resolveRgba8Format();
        int textureUsage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "CI known-color Metal readback control",
                textureUsage,
                format,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        int byteCount = Math.multiplyExact(Math.multiplyExact(WIDTH, HEIGHT), texture.pixelSize());
        MetalGpuBuffer readback = (MetalGpuBuffer) device.createBuffer(
                () -> "CI known-color Metal readback control buffer",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                byteCount
        );

        try {
            MetalCommandEncoder encoder = device.commandEncoder();
            // Drain any preceding Minecraft work first so the control starts at
            // a clean submission boundary and cannot accidentally sample a
            // renderer-owned encoder.
            device.waitForSubmittedGpuWork();

            encoder.clearColorTexture(texture, EXPECTED_CLEAR);
            encoder.copyTextureToBuffer(texture, readback, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            ByteBuffer mapped = readback.currentStorage();
            mapped.position(0);
            mapped.limit(byteCount);
            byte[] bytes = new byte[byteCount];
            mapped.get(bytes);

            ProbeResult result = inspect(format, texture.pixelSize(), bytes);
            writeEvidence(outputDirectory, result, bytes);
            return result;
        } finally {
            readback.close();
            texture.close();
        }
    }

    private static ProbeResult inspect(GpuFormat format, int bytesPerPixel, byte[] bytes) {
        long nonZeroBytes = 0L;
        long nonBlackPixels = 0L;
        long redSum = 0L;
        long greenSum = 0L;
        long blueSum = 0L;
        long alphaSum = 0L;
        Set<Integer> distinctRgb = new HashSet<>();

        if (bytesPerPixel < 4 || bytes.length % bytesPerPixel != 0) {
            throw new IllegalStateException(
                    "Known-color control expected a four-byte-or-larger color format, got "
                            + format + " blockSize=" + bytesPerPixel + " bytes=" + bytes.length
            );
        }

        int pixels = bytes.length / bytesPerPixel;
        for (int offset = 0; offset < bytes.length; offset += bytesPerPixel) {
            int r = bytes[offset] & 0xff;
            int g = bytes[offset + 1] & 0xff;
            int b = bytes[offset + 2] & 0xff;
            int a = bytes[offset + 3] & 0xff;
            for (int component = 0; component < bytesPerPixel; component++) {
                if (bytes[offset + component] != 0) nonZeroBytes++;
            }
            if (r > 4 || g > 4 || b > 4) nonBlackPixels++;
            if (distinctRgb.size() < 8192) distinctRgb.add((r << 16) | (g << 8) | b);
            redSum += r;
            greenSum += g;
            blueSum += b;
            alphaSum += a;
        }

        return new ProbeResult(
                format.toString(),
                WIDTH,
                HEIGHT,
                bytesPerPixel,
                bytes.length,
                nonZeroBytes,
                nonBlackPixels,
                distinctRgb.size(),
                redSum / (double) pixels,
                greenSum / (double) pixels,
                blueSum / (double) pixels,
                alphaSum / (double) pixels
        );
    }

    private static GpuFormat resolveRgba8Format() {
        for (String className : new String[]{
                "com.mojang.blaze3d.GpuFormats",
                "com.mojang.blaze3d.GpuFormat"
        }) {
            try {
                Class<?> owner = Class.forName(className);
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            || !GpuFormat.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (!(value instanceof GpuFormat format)) continue;
                    String identity = (field.getName() + " " + format).toUpperCase(Locale.ROOT);
                    if (identity.contains("RGBA8")
                            && !identity.contains("SRGB")
                            && !identity.contains("SNORM")
                            && !identity.contains("UINT")
                            && !identity.contains("SINT")) {
                        return format;
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // Try the next holder used by this Minecraft revision.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not inspect Minecraft GPU formats", exception);
            }
        }
        throw new IllegalStateException("Could not resolve Minecraft's RGBA8 UNORM GpuFormat");
    }

    private static void writeEvidence(Path directory, ProbeResult result, byte[] bytes) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("known-color.raw"), bytes);
            String json = """
                    {
                      "schema": 1,
                      "format": "%s",
                      "width": %d,
                      "height": %d,
                      "bytesPerPixel": %d,
                      "byteCount": %d,
                      "nonZeroBytes": %d,
                      "nonBlackPixels": %d,
                      "distinctRgb": %d,
                      "meanRed": %.6f,
                      "meanGreen": %.6f,
                      "meanBlue": %.6f,
                      "meanAlpha": %.6f
                    }
                    """.formatted(
                    escape(result.format()),
                    result.width(),
                    result.height(),
                    result.bytesPerPixel(),
                    result.byteCount(),
                    result.nonZeroBytes(),
                    result.nonBlackPixels(),
                    result.distinctRgb(),
                    result.meanRed(),
                    result.meanGreen(),
                    result.meanBlue(),
                    result.meanAlpha()
            );
            Files.writeString(directory.resolve("known-color.json"), json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write known-color Metal probe evidence", exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ProbeResult(
            String format,
            int width,
            int height,
            int bytesPerPixel,
            int byteCount,
            long nonZeroBytes,
            long nonBlackPixels,
            int distinctRgb,
            double meanRed,
            double meanGreen,
            double meanBlue,
            double meanAlpha
    ) {
        public boolean looksLikeMagentaClear() {
            return nonZeroBytes > 0
                    && nonBlackPixels == (long) width * height
                    && meanRed > 200.0
                    && meanGreen < 32.0
                    && meanBlue > 200.0
                    && meanAlpha > 200.0;
        }
    }
}
