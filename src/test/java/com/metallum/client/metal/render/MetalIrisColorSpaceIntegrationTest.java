package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Physical Metal coverage for fixed-Iris final-output color conversion. */
@EnabledOnOs(OS.MAC)
final class MetalIrisColorSpaceIntegrationTest {
    private static final int WIDTH = 2;
    private static final int HEIGHT = 2;
    private static final int PIXEL_BYTES = 4;
    private static final int TOLERANCE = 3;

    private static final double[][] SRGB_XYZ = matrix(
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041
    );
    private static final double[][] XYZ_P3D65 = matrix(
            2.4933963, -0.9313459, -0.4026945,
            -0.8294868, 1.7626597, 0.0236246,
            0.0358507, -0.0761827, 0.9570140
    );
    private static final double[][] XYZ_REC2020 = matrix(
            1.7166511880, -0.3556707838, -0.2533662814,
            -0.6666843518, 1.6164812366, 0.0157685458,
            0.0176398574, -0.0427706133, 0.9421031212
    );
    private static final double[][] XYZ_ADOBE_RGB = matrix(
            2.04158790381075, -0.56500697427886, -0.34473135077833,
            -0.96924363628088, 1.87596750150772, 0.0415550574071756,
            0.0134442806320311, -0.118362392231018, 1.01517499439121
    );
    private static final double[][] D65_DCI = matrix(
            1.02449672775258, 0.0151635410224164, 0.0196885223342068,
            0.0256121933371582, 0.972586305624413, 0.00471635229242733,
            0.00638423065008769, -0.0122680827367302, 1.14794244517368
    );

    private static final int[] SOURCE = {
            rgba(32, 96, 160, 255), rgba(208, 64, 32, 255),
            rgba(72, 184, 48, 255), rgba(224, 176, 96, 255)
    };

    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        device = new MetalDevice(
                (identifier, type) -> null,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Metal fixed Iris color-space integration device",
                MemorySegment.NULL
        );
        encoder = device.createCommandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void convertsCoordinateVaryingPixelsForEveryFixedIrisOutputSpace() {
        try (IrisMetalColorSpaceConverter converter = new IrisMetalColorSpaceConverter(1);
             MetalGpuTexture target = createTarget()) {
            converter.prepare(device, GpuFormat.RGBA8_UNORM, false);
            MetalGpuTextureView targetView = new MetalGpuTextureView(target, 0, 1);
            try {
                for (ColorSpace colorSpace : new ColorSpace[]{
                        ColorSpace.DCI_P3,
                        ColorSpace.DISPLAY_P3,
                        ColorSpace.REC2020,
                        ColorSpace.ADOBE_RGB
                }) {
                    uploadSource(target);
                    assertEquals(true, converter.execute(targetView, colorSpace));
                    ByteBuffer actual = readback(target);
                    for (int index = 0; index < SOURCE.length; index++) {
                        int expected = reference(SOURCE[index], colorSpace);
                        assertPixelNear(actual, index, expected, colorSpace.name());
                    }
                }
            } finally {
                targetView.close();
            }
        }
    }

    @Test
    void packOwnedCorrectionBypassesIrisConverterWithoutSubmittingASecondPass() {
        try (IrisMetalColorSpaceConverter converter = new IrisMetalColorSpaceConverter(2);
             MetalGpuTexture target = createTarget()) {
            converter.prepare(device, GpuFormat.RGBA8_UNORM, true);
            MetalGpuTextureView targetView = new MetalGpuTextureView(target, 0, 1);
            try {
                uploadSource(target);
                assertFalse(converter.execute(targetView, ColorSpace.DISPLAY_P3));
                ByteBuffer actual = readback(target);
                for (int index = 0; index < SOURCE.length; index++) {
                    assertPixelNear(actual, index, SOURCE[index], "pack-owned bypass");
                }
            } finally {
                targetView.close();
            }
        }
    }

    @Test
    void rejectsClosedMainColorViewBeforeEncoding() {
        try (IrisMetalColorSpaceConverter converter = new IrisMetalColorSpaceConverter(3);
             MetalGpuTexture target = createTarget()) {
            converter.prepare(device, GpuFormat.RGBA8_UNORM, false);
            MetalGpuTextureView targetView = new MetalGpuTextureView(target, 0, 1);
            targetView.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> converter.execute(targetView, ColorSpace.DISPLAY_P3)
            );
        }
    }

    private MetalGpuTexture createTarget() {
        return (MetalGpuTexture) device.createTexture(
                "fixed Iris color-space target",
                GpuTexture.USAGE_RENDER_ATTACHMENT
                        | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_SRC
                        | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
    }

    private void uploadSource(final MetalGpuTexture target) {
        ByteBuffer source = ByteBuffer.allocateDirect(SOURCE.length * PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int pixel : SOURCE) {
            source.put((byte) ((pixel >>> 24) & 0xFF));
            source.put((byte) ((pixel >>> 16) & 0xFF));
            source.put((byte) ((pixel >>> 8) & 0xFF));
            source.put((byte) (pixel & 0xFF));
        }
        source.flip();
        encoder.writeToTexture(target, source, 0, 0, 0, 0, WIDTH, HEIGHT);
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int size = WIDTH * HEIGHT * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "fixed Iris color-space readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size);
            copy.put(source).flip();
            return copy;
        }
    }

    private static int reference(final int rgba, final ColorSpace colorSpace) {
        double red = inverseSrgb(((rgba >>> 24) & 0xFF) / 255.0);
        double green = inverseSrgb(((rgba >>> 16) & 0xFF) / 255.0);
        double blue = inverseSrgb(((rgba >>> 8) & 0xFF) / 255.0);
        double[] linear = switch (colorSpace) {
            case DCI_P3 -> apply(
                    multiply(D65_DCI, multiply(XYZ_P3D65, SRGB_XYZ)), red, green, blue
            );
            case DISPLAY_P3 -> apply(multiply(XYZ_P3D65, SRGB_XYZ), red, green, blue);
            case REC2020 -> apply(multiply(XYZ_REC2020, SRGB_XYZ), red, green, blue);
            case ADOBE_RGB -> apply(multiply(XYZ_ADOBE_RGB, SRGB_XYZ), red, green, blue);
            case SRGB -> new double[]{red, green, blue};
        };
        return rgba(
                encode(linear[0], colorSpace),
                encode(linear[1], colorSpace),
                encode(linear[2], colorSpace),
                (rgba & 0xFF)
        );
    }

    private static double inverseSrgb(final double value) {
        return value < 0.04045
                ? value / 12.92
                : Math.max(Math.pow(0.947867 * value + 0.0521327, 2.4), 0.0);
    }

    private static int encode(final double value, final ColorSpace colorSpace) {
        return clampByte(switch (colorSpace) {
            case DCI_P3 -> Math.pow(value, 1.0 / 2.6);
            case DISPLAY_P3 -> eotf(value, 12.92, 1.0 / 2.4, 1.055, 0.0031308);
            case REC2020 -> eotf(value, 4.5, 0.45, 1.0993, 0.0181);
            case ADOBE_RGB -> Math.pow(value, 1.0 / 2.2);
            case SRGB -> value;
        });
    }

    private static double eotf(
            final double value,
            final double linearFactor,
            final double exponent,
            final double alpha,
            final double beta
    ) {
        return value < beta
                ? value * linearFactor
                : clamp(alpha * Math.pow(value, exponent) - (alpha - 1.0));
    }

    private static double[] apply(
            final double[][] transform,
            final double red,
            final double green,
            final double blue
    ) {
        return new double[]{
                transform[0][0] * red + transform[0][1] * green + transform[0][2] * blue,
                transform[1][0] * red + transform[1][1] * green + transform[1][2] * blue,
                transform[2][0] * red + transform[2][1] * green + transform[2][2] * blue
        };
    }

    private static double[][] multiply(final double[][] left, final double[][] right) {
        double[][] result = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int index = 0; index < 3; index++) {
                    result[row][column] += left[row][index] * right[index][column];
                }
            }
        }
        return result;
    }

    private static double[][] matrix(final double... values) {
        if (values.length != 9) {
            throw new IllegalArgumentException("A color-space matrix requires nine values");
        }
        return new double[][]{
                {values[0], values[1], values[2]},
                {values[3], values[4], values[5]},
                {values[6], values[7], values[8]}
        };
    }

    private static int clampByte(final double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return (int) Math.round(clamp(value) * 255.0);
    }

    private static double clamp(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int rgba(final int red, final int green, final int blue, final int alpha) {
        return ((red & 0xFF) << 24) | ((green & 0xFF) << 16) | ((blue & 0xFF) << 8) | (alpha & 0xFF);
    }

    private static void assertPixelNear(
            final ByteBuffer actual,
            final int index,
            final int expected,
            final String label
    ) {
        int offset = index * PIXEL_BYTES;
        assertChannelNear(actual.get(offset), (expected >>> 24) & 0xFF, label + " red pixel " + index);
        assertChannelNear(actual.get(offset + 1), (expected >>> 16) & 0xFF, label + " green pixel " + index);
        assertChannelNear(actual.get(offset + 2), (expected >>> 8) & 0xFF, label + " blue pixel " + index);
        assertEquals(expected & 0xFF, Byte.toUnsignedInt(actual.get(offset + 3)), label + " alpha pixel " + index);
    }

    private static void assertChannelNear(final byte actual, final int expected, final String label) {
        int value = Byte.toUnsignedInt(actual);
        if (Math.abs(value - expected) > TOLERANCE) {
            assertEquals(expected, value, label);
        }
    }
}
