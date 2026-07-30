package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IrisMetalRenderTargetFormatsTest {
    @Test
    void preservesComponentWidthAndNumericClass() {
        Map<String, GpuFormat> expected = Map.ofEntries(
                Map.entry("R8", GpuFormat.R8_UNORM),
                Map.entry("RG16", GpuFormat.RG16_UNORM),
                Map.entry("RGBA16", GpuFormat.RGBA16_UNORM),
                Map.entry("R16F", GpuFormat.R16_FLOAT),
                Map.entry("RG32F", GpuFormat.RG32_FLOAT),
                Map.entry("RGBA32F", GpuFormat.RGBA32_FLOAT),
                Map.entry("R8I", GpuFormat.R8_SINT),
                Map.entry("RG16I", GpuFormat.RG16_SINT),
                Map.entry("RGBA32I", GpuFormat.RGBA32_SINT),
                Map.entry("R8UI", GpuFormat.R8_UINT),
                Map.entry("RG16UI", GpuFormat.RG16_UINT),
                Map.entry("RGBA32UI", GpuFormat.RGBA32_UINT),
                Map.entry("RGB10_A2", GpuFormat.RGB10A2_UNORM),
                Map.entry("RGB10_A2UI", GpuFormat.RGB10A2_UINT),
                Map.entry("R11F_G11F_B10F", GpuFormat.RG11B10_FLOAT)
        );
        expected.forEach((name, format) ->
                assertEquals(format, IrisMetalRenderTargetFormats.fromInternalName(name), name));
    }

    @Test
    void promotesUnsupportedThreeChannelAttachmentsWithoutLosingPrecision() {
        assertEquals(
                GpuFormat.RGBA8_UNORM,
                IrisMetalRenderTargetFormats.fromInternalName("RGB8")
        );
        assertEquals(
                GpuFormat.RGBA16_FLOAT,
                IrisMetalRenderTargetFormats.fromInternalName("RGB16F")
        );
        assertEquals(
                GpuFormat.RGBA32_SINT,
                IrisMetalRenderTargetFormats.fromInternalName("RGB32I")
        );
        assertEquals(
                GpuFormat.RGBA16_UINT,
                IrisMetalRenderTargetFormats.fromInternalName("RGB16UI")
        );
    }

    @Test
    void unknownFormatsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalRenderTargetFormats.fromInternalName("PACK_SPECIFIC_MAGIC")
        );
    }
}
