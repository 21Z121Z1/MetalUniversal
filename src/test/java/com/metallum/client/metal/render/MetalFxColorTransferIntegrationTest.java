package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the shipping Java -> FFM -> Swift -> Metal transfer, not a shader copy in the test. */
@EnabledOnOs(OS.MAC)
final class MetalFxColorTransferIntegrationTest {
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        var handle = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(handle));
        device = new MetalDevice(MetalShaderSourceAdapters.from((id, type) -> null),
                new GpuDebugOptions(2, true, true, true), handle, MemorySegment.NULL,
                "MetalFX transfer contract", MemorySegment.NULL);
        encoder = device.commandEncoder();
        if (Boolean.getBoolean("metallum.opt.metal4MainRenderer")) {
            assertTrue(device.metal4MainRendererEnabled(), "requested Metal 4 must not silently test Metal 3");
        }
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) device.close();
    }

    @Test
    void everySdrCodePointIsDecodedOnceAndRoundTripsWithoutChangingCoverage() {
        int width = 256, height = 3;
        try (var display = texture(GpuFormat.RGBA8_UNORM, width, height, true);
             var linear = texture(GpuFormat.RGBA16_FLOAT, width, height, true);
             var restored = texture(GpuFormat.RGBA8_UNORM, width, height, true)) {
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                pixels.put((byte) x).put((byte) (255 - x)).put((byte) ((x * 29 + y * 17) & 255))
                        .put((byte) ((x * 71 + y * 83) & 255));
            }
            pixels.flip();
            encoder.writeToTexture(display, pixels, 0, 0, 0, 0, width, height);
            assertTrue(encoder.encodeMetalFxColorTransfer(display, linear, true));
            assertTrue(encoder.encodeMetalFxColorTransfer(linear, restored, false));
            ByteBuffer actualLinear = readback(linear);
            ByteBuffer roundTrip = readback(restored);
            for (int pixel = 0; pixel < width * height; pixel++) {
                for (int channel = 0; channel < 4; channel++) {
                    int code = Byte.toUnsignedInt(pixels.get(pixel * 4 + channel));
                    double normalized = code / 255.0;
                    double expected = channel == 3 ? normalized : decode(normalized);
                    assertEquals(expected, Float.float16ToFloat(actualLinear.getShort(pixel * 8 + channel * 2)),
                            0.00051, "linear pixel=" + pixel + " channel=" + channel);
                    assertEquals(code, Byte.toUnsignedInt(roundTrip.get(pixel * 4 + channel)), 1,
                            "SDR round-trip pixel=" + pixel + " channel=" + channel);
                }
            }
            // Independent mid-grey oracle catches both a missing decode and a
            // paired wrong-direction conversion that still round-trips.
            assertEquals(0.21586, Float.float16ToFloat(actualLinear.getShort(128 * 8)), 0.00051);
            assertFalse(encoder.encodeMetalFxColorTransfer(linear, restored, true), "double decode rejected");
            assertFalse(encoder.encodeMetalFxColorTransfer(display, linear, false), "wrong transfer direction rejected");
        }
    }

    @Test
    void outputTransferIsNumericallyProvenWithoutUsingTheDecodeKernelAsOracle() {
        float[] values = {0f, 0.001f, 0.003f, 0.0032f, 0.018f, 0.18f, 0.5f, 1f, -0.05f, 1.25f};
        for (int width : new int[]{10, 31}) { // recreation and a non-threadgroup-multiple width
            try (var linear = texture(GpuFormat.RGBA16_FLOAT, width, 1, true);
                 var display = texture(GpuFormat.RGBA8_UNORM, width, 1, true)) {
                ByteBuffer pixels = ByteBuffer.allocateDirect(width * 8).order(ByteOrder.nativeOrder());
                for (int x = 0; x < width; x++) {
                    for (int c = 0; c < 3; c++) pixels.putShort(Float.floatToFloat16(values[(x + c) % values.length]));
                    pixels.putShort(Float.floatToFloat16(x / (float) (width - 1)));
                }
                pixels.flip();
                encoder.writeToTexture(linear, pixels, 0, 0, 0, 0, width, 1);
                assertTrue(encoder.encodeMetalFxColorTransfer(linear, display, false));
                ByteBuffer actual = readback(display);
                for (int x = 0; x < width; x++) for (int c = 0; c < 4; c++) {
                    double value = Float.float16ToFloat(pixels.getShort(x * 8 + c * 2));
                    double result = c == 3 ? value : encode(Math.max(0, value));
                    int expected = (int) Math.round(Math.clamp(result, 0.0, 1.0) * 255);
                    assertEquals(expected, Byte.toUnsignedInt(actual.get(x * 4 + c)), 1,
                            "encode width=" + width + " pixel=" + x + " channel=" + c);
                }
            }
        }
    }

    @Test
    void incompatibleDimensionsFormatAndWriteUsageDoNotEncode() {
        try (var source = texture(GpuFormat.RGBA8_UNORM, 7, 3, true);
             var wrongSize = texture(GpuFormat.RGBA16_FLOAT, 8, 3, true);
             var wrongFormat = texture(GpuFormat.RGBA8_UNORM, 7, 3, true);
             var noWrite = texture(GpuFormat.RGBA16_FLOAT, 7, 3, false)) {
            encoder.clearColorTexture(wrongFormat, new Vector4f(0.25f, 0.5f, 0.75f, 1f));
            assertFalse(encoder.encodeMetalFxColorTransfer(source, wrongSize, true));
            assertFalse(encoder.encodeMetalFxColorTransfer(source, wrongFormat, true));
            assertFalse(encoder.encodeMetalFxColorTransfer(source, noWrite, true));
            ByteBuffer unchanged = readback(wrongFormat);
            assertEquals(64, Byte.toUnsignedInt(unchanged.get(0)), 1);
            assertEquals(128, Byte.toUnsignedInt(unchanged.get(1)), 1);
            assertEquals(191, Byte.toUnsignedInt(unchanged.get(2)), 1);
            assertEquals(255, Byte.toUnsignedInt(unchanged.get(3)));
        }
    }

    private MetalGpuTexture texture(GpuFormat format, int width, int height, boolean writable) {
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
        if (writable) usage |= MetalGpuTexture.USAGE_SHADER_WRITE;
        return (MetalGpuTexture) device.createTexture(() -> "MetalFX transfer fixture", usage,
                format, width, height, 1, 1);
    }

    private ByteBuffer readback(MetalGpuTexture texture) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (var buffer = (MetalGpuBuffer) device.createBuffer(() -> "transfer readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            var copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            copy.put(buffer.currentStorage().limit(size).slice());
            return copy.flip();
        }
    }

    // Independent double-precision reference (IEC sRGB), never read constants
    // or shader text from the production implementation to calculate expected pixels.
    private static double decode(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
    private static double encode(double v) {
        return v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
    }
}
