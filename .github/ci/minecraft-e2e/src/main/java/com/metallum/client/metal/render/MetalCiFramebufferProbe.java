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

/** CI-only control probes for MetalUniversal's production GPU readback path. */
public final class MetalCiFramebufferProbe {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int PIXELS = WIDTH * HEIGHT;
    private static final int BYTES = PIXELS * 4;
    private static final Vector4f EXPECTED_CLEAR = new Vector4f(1.0f, 0.0f, 1.0f, 1.0f);

    private MetalCiFramebufferProbe() {
    }

    public static ProbeSuite run(Path outputDirectory) {
        MetalDevice device = MetalDevice.current();
        if (device == null) throw new IllegalStateException("MetalDevice.current() is null inside production Minecraft");
        GpuFormat rgba8 = resolveRgba8Format();
        device.waitForSubmittedGpuWork();

        ProbeResult bufferCopy = runBufferCopy(device, outputDirectory.resolve("01-buffer-copy"));
        ProbeResult textureRoundTrip = runTextureRoundTrip(device, rgba8, outputDirectory.resolve("02-texture-roundtrip"));
        ProbeResult renderClear = runRenderClear(device, rgba8, outputDirectory.resolve("03-render-clear"));
        ProbeSuite suite = new ProbeSuite(bufferCopy, textureRoundTrip, renderClear);
        writeSuite(outputDirectory, suite);
        return suite;
    }

    private static ProbeResult runBufferCopy(MetalDevice device, Path output) {
        MetalGpuBuffer source = (MetalGpuBuffer) device.createBuffer(
                () -> "CI readback buffer-copy source", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC, BYTES);
        MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                () -> "CI readback buffer-copy destination",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, BYTES);
        try {
            byte[] expected = new byte[BYTES];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) (0x31 + (i % 97));
            write(source.currentStorage(), expected);
            zero(destination.currentStorage(), BYTES);

            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.blitCommandEncoder().copyFromBufferToBuffer(
                    source.nativeHandle(), 0L, destination.nativeHandle(), 0L, BYTES);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            byte[] actual = read(destination.currentStorage(), BYTES);
            ProbeResult result = inspect("buffer-copy", actual, expected);
            writeResult(output, result, actual);
            return result;
        } finally {
            source.close();
            destination.close();
            device.waitForSubmittedGpuWork();
        }
    }

    private static ProbeResult runTextureRoundTrip(MetalDevice device, GpuFormat format, Path output) {
        MetalGpuBuffer source = (MetalGpuBuffer) device.createBuffer(
                () -> "CI texture-roundtrip source", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC, BYTES);
        MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                () -> "CI texture-roundtrip destination",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, BYTES);
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "CI texture-roundtrip private texture", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                format, WIDTH, HEIGHT, 1, 1);
        try {
            byte[] expected = rgbaPattern();
            write(source.currentStorage(), expected);
            zero(destination.currentStorage(), BYTES);

            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.blitCommandEncoder().copyFromBufferToTexture(
                    source.nativeHandle(), 0L, texture.nativeHandle(), 0L, 0L, 0L, 0L,
                    WIDTH, HEIGHT, WIDTH * 4L, BYTES);
            encoder.copyTextureToBuffer(texture, destination, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            byte[] actual = read(destination.currentStorage(), BYTES);
            ProbeResult result = inspect("texture-roundtrip", actual, expected);
            writeResult(output, result, actual);
            return result;
        } finally {
            source.close();
            destination.close();
            texture.close();
            device.waitForSubmittedGpuWork();
        }
    }

    private static ProbeResult runRenderClear(MetalDevice device, GpuFormat format, Path output) {
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "CI render-clear private texture", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                format, WIDTH, HEIGHT, 1, 1);
        MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                () -> "CI render-clear destination",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, BYTES);
        try {
            zero(destination.currentStorage(), BYTES);
            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.clearColorTexture(texture, EXPECTED_CLEAR);
            encoder.copyTextureToBuffer(texture, destination, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            byte[] actual = read(destination.currentStorage(), BYTES);
            byte[] expected = new byte[BYTES];
            for (int offset = 0; offset < expected.length; offset += 4) {
                expected[offset] = (byte) 0xff;
                expected[offset + 2] = (byte) 0xff;
                expected[offset + 3] = (byte) 0xff;
            }
            ProbeResult result = inspect("render-clear", actual, expected);
            writeResult(output, result, actual);
            return result;
        } finally {
            destination.close();
            texture.close();
            device.waitForSubmittedGpuWork();
        }
    }

    private static byte[] rgbaPattern() {
        byte[] bytes = new byte[BYTES];
        for (int pixel = 0; pixel < PIXELS; pixel++) {
            int offset = pixel * 4;
            bytes[offset] = (byte) (32 + (pixel % 191));
            bytes[offset + 1] = (byte) (17 + ((pixel * 3) % 211));
            bytes[offset + 2] = (byte) (71 + ((pixel * 7) % 173));
            bytes[offset + 3] = (byte) 0xff;
        }
        return bytes;
    }

    private static ProbeResult inspect(String name, byte[] actual, byte[] expected) {
        long nonZeroBytes = 0L, mismatchBytes = 0L, nonBlackPixels = 0L;
        long redSum = 0L, greenSum = 0L, blueSum = 0L, alphaSum = 0L;
        Set<Integer> distinctRgb = new HashSet<>();
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != 0) nonZeroBytes++;
            if (actual[i] != expected[i]) mismatchBytes++;
        }
        for (int offset = 0; offset < actual.length; offset += 4) {
            int r = actual[offset] & 0xff;
            int g = actual[offset + 1] & 0xff;
            int b = actual[offset + 2] & 0xff;
            int a = actual[offset + 3] & 0xff;
            if (r > 4 || g > 4 || b > 4) nonBlackPixels++;
            if (distinctRgb.size() < 8192) distinctRgb.add((r << 16) | (g << 8) | b);
            redSum += r; greenSum += g; blueSum += b; alphaSum += a;
        }
        return new ProbeResult(name, actual.length, nonZeroBytes, mismatchBytes, nonBlackPixels, distinctRgb.size(),
                redSum / (double) PIXELS, greenSum / (double) PIXELS,
                blueSum / (double) PIXELS, alphaSum / (double) PIXELS);
    }

    private static void write(ByteBuffer storage, byte[] bytes) {
        ByteBuffer buffer = storage.duplicate();
        buffer.position(0).limit(bytes.length);
        buffer.put(bytes);
    }

    private static void zero(ByteBuffer storage, int length) {
        ByteBuffer buffer = storage.duplicate();
        buffer.position(0).limit(length);
        while (buffer.remaining() >= Long.BYTES) buffer.putLong(0L);
        while (buffer.hasRemaining()) buffer.put((byte) 0);
    }

    private static byte[] read(ByteBuffer storage, int length) {
        ByteBuffer buffer = storage.duplicate();
        buffer.position(0).limit(length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static GpuFormat resolveRgba8Format() {
        for (String className : new String[]{"com.mojang.blaze3d.GpuFormats", "com.mojang.blaze3d.GpuFormat"}) {
            try {
                Class<?> owner = Class.forName(className);
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || !GpuFormat.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (!(value instanceof GpuFormat format)) continue;
                    String identity = (field.getName() + " " + format).toUpperCase(Locale.ROOT);
                    if (identity.contains("RGBA8") && !identity.contains("SRGB") && !identity.contains("SNORM")
                            && !identity.contains("UINT") && !identity.contains("SINT")) return format;
                }
            } catch (ClassNotFoundException ignored) {
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not inspect Minecraft GPU formats", exception);
            }
        }
        throw new IllegalStateException("Could not resolve Minecraft RGBA8 UNORM GpuFormat");
    }

    private static void writeResult(Path directory, ProbeResult result, byte[] actual) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("actual.raw"), actual);
            Files.writeString(directory.resolve("result.json"), result.toJson(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write Metal control probe evidence", exception);
        }
    }

    private static void writeSuite(Path directory, ProbeSuite suite) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("suite.json"), suite.toJson(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write Metal control suite evidence", exception);
        }
    }

    public record ProbeResult(String name, long byteCount, long nonZeroBytes, long mismatchBytes,
                              long nonBlackPixels, int distinctRgb,
                              double meanRed, double meanGreen, double meanBlue, double meanAlpha) {
        public boolean exact() { return mismatchBytes == 0L; }
        String toJson() {
            return """
                    {"name":"%s","byteCount":%d,"nonZeroBytes":%d,"mismatchBytes":%d,"nonBlackPixels":%d,"distinctRgb":%d,"meanRed":%.6f,"meanGreen":%.6f,"meanBlue":%.6f,"meanAlpha":%.6f,"exact":%s}
                    """.formatted(name, byteCount, nonZeroBytes, mismatchBytes, nonBlackPixels, distinctRgb,
                    meanRed, meanGreen, meanBlue, meanAlpha, exact());
        }
    }

    public record ProbeSuite(ProbeResult bufferCopy, ProbeResult textureRoundTrip, ProbeResult renderClear) {
        public boolean allExact() { return bufferCopy.exact() && textureRoundTrip.exact() && renderClear.exact(); }
        String toJson() {
            return "{\n  \"bufferCopy\": " + bufferCopy.toJson().trim()
                    + ",\n  \"textureRoundTrip\": " + textureRoundTrip.toJson().trim()
                    + ",\n  \"renderClear\": " + renderClear.toJson().trim()
                    + ",\n  \"allExact\": " + allExact() + "\n}\n";
        }
    }
}
