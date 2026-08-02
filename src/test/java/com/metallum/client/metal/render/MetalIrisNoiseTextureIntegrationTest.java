package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** GPU readback coverage for Iris's real custom/default {@code noisetex}. */
@EnabledOnOs(OS.MAC)
final class MetalIrisNoiseTextureIntegrationTest {
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        device = new MetalDevice(
                (identifier, type) -> null,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris noise texture integration device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void customPngPreservesPixelsAndFiltering() throws IOException {
        byte[] png = twoPixelPng();
        CustomTextureData.PngData data = new CustomTextureData.PngData(
                new TextureFilteringData(false, true),
                png
        );
        try (IrisMetalNoiseTexture noise = new IrisMetalNoiseTexture(device, 64, data)) {
            ByteBuffer pixels = readback(noise.texture());
            assertPixel(pixels, 0, 255, 0, 0, 255);
            assertPixel(pixels, 1, 0, 128, 255, 64);
            assertEquals("pack-noise-png", noise.source());
            assertEquals(AddressMode.CLAMP_TO_EDGE, noise.binding().sampler().getAddressModeU());
            assertEquals(FilterMode.NEAREST, noise.binding().sampler().getMinFilter());
        }
    }

    @Test
    void defaultNoiseMatchesIrisFixedSeedAndSampling() {
        int size = 4;
        try (IrisMetalNoiseTexture noise = new IrisMetalNoiseTexture(device, size, null)) {
            ByteBuffer pixels = readback(noise.texture());
            int[] expected = irisNoiseArgb(size);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = expected[x * size + y];
                    int offset = (x + y * size) * 4;
                    assertPixel(
                            pixels,
                            offset / 4,
                            (argb >>> 16) & 0xFF,
                            (argb >>> 8) & 0xFF,
                            argb & 0xFF,
                            0xFF
                    );
                }
            }
            assertEquals("iris-default-noise", noise.source());
            assertEquals(AddressMode.REPEAT, noise.binding().sampler().getAddressModeU());
            assertEquals(FilterMode.LINEAR, noise.binding().sampler().getMinFilter());
        }
    }

    @Test
    void unsupportedCustomNoiseFailsClosed() {
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> new IrisMetalNoiseTexture(device, 16, new CustomTextureData.LightmapMarker())
        );
        assertTrue(failure.getMessage().contains("LightmapMarker"));
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris noisetex readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size);
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    private static byte[] twoPixelPng() throws IOException {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0x400080FF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static int[] irisNoiseArgb(final int size) {
        Random random = new Random(0);
        int[] pixels = new int[size * size];
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                pixels[x * size + y] = random.nextInt() | 0xFF000000;
            }
        }
        return pixels;
    }

    private static void assertPixel(
            final ByteBuffer pixels,
            final int index,
            final int red,
            final int green,
            final int blue,
            final int alpha
    ) {
        int offset = index * 4;
        assertEquals(red, Byte.toUnsignedInt(pixels.get(offset)), "red at pixel " + index);
        assertEquals(green, Byte.toUnsignedInt(pixels.get(offset + 1)), "green at pixel " + index);
        assertEquals(blue, Byte.toUnsignedInt(pixels.get(offset + 2)), "blue at pixel " + index);
        assertEquals(alpha, Byte.toUnsignedInt(pixels.get(offset + 3)), "alpha at pixel " + index);
    }
}
