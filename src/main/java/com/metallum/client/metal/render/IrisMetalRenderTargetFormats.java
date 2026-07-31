package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;

import java.util.Arrays;
import java.util.Map;

/** Iris logical render-target formats lowered to renderable Metal formats. */
final class IrisMetalRenderTargetFormats {
    static final int MAX_LOGICAL_TARGETS = 32;
    static final GpuFormat DEFAULT_FORMAT = GpuFormat.RGBA8_UNORM;

    private IrisMetalRenderTargetFormats() {
    }

    static GpuFormat[] from(final PackDirectives directives) {
        Map<Integer, RenderTargetSettings> settings = directives
                .getRenderTargetDirectives()
                .getRenderTargetSettings();
        int highest = settings.keySet().stream()
                .filter(index -> index != null && index >= 0)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (highest >= MAX_LOGICAL_TARGETS) {
            throw new IllegalArgumentException(
                    "Iris render target colortex" + highest + " exceeds the supported 0.."
                            + (MAX_LOGICAL_TARGETS - 1) + " range"
            );
        }

        GpuFormat[] formats = new GpuFormat[highest + 1];
        Arrays.fill(formats, DEFAULT_FORMAT);
        for (Map.Entry<Integer, RenderTargetSettings> entry : settings.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= formats.length) {
                throw new IllegalArgumentException("Invalid Iris render-target index " + index);
            }
            RenderTargetSettings target = entry.getValue();
            if (target.getInternalFormat() != null) {
                formats[index] = fromInternalName(target.getInternalFormat().name());
            }
        }
        return formats;
    }

    static GpuFormat fromInternalName(final String name) {
        return switch (name) {
            case "R8" -> GpuFormat.R8_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            case "RGB8" -> GpuFormat.RGBA8_UNORM;
            case "RGBA", "RGBA8" -> GpuFormat.RGBA8_UNORM;
            case "R16" -> GpuFormat.R16_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RGB16" -> GpuFormat.RGBA16_UNORM;
            case "RGBA16" -> GpuFormat.RGBA16_UNORM;
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "RGB16F" -> GpuFormat.RGBA16_FLOAT;
            case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
            case "R8I" -> GpuFormat.R8_SINT;
            case "RG8I" -> GpuFormat.RG8_SINT;
            case "RGB8I" -> GpuFormat.RGBA8_SINT;
            case "RGBA8I" -> GpuFormat.RGBA8_SINT;
            case "R8UI" -> GpuFormat.R8_UINT;
            case "RG8UI" -> GpuFormat.RG8_UINT;
            case "RGB8UI" -> GpuFormat.RGBA8_UINT;
            case "RGBA8UI" -> GpuFormat.RGBA8_UINT;
            case "R16I" -> GpuFormat.R16_SINT;
            case "RG16I" -> GpuFormat.RG16_SINT;
            case "RGB16I" -> GpuFormat.RGBA16_SINT;
            case "RGBA16I" -> GpuFormat.RGBA16_SINT;
            case "R16UI" -> GpuFormat.R16_UINT;
            case "RG16UI" -> GpuFormat.RG16_UINT;
            case "RGB16UI" -> GpuFormat.RGBA16_UINT;
            case "RGBA16UI" -> GpuFormat.RGBA16_UINT;
            case "R32I" -> GpuFormat.R32_SINT;
            case "RG32I" -> GpuFormat.RG32_SINT;
            case "RGB32I" -> GpuFormat.RGBA32_SINT;
            case "RGBA32I" -> GpuFormat.RGBA32_SINT;
            case "R32UI" -> GpuFormat.R32_UINT;
            case "RG32UI" -> GpuFormat.RG32_UINT;
            case "RGB32UI" -> GpuFormat.RGBA32_UINT;
            case "RGBA32UI" -> GpuFormat.RGBA32_UINT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "RGB10_A2UI" -> GpuFormat.RGB10A2_UINT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            default -> throw new IllegalArgumentException(
                    "Unsupported Iris render-target format " + name
            );
        };
    }
}
