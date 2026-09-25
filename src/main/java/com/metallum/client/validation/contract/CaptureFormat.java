package com.metallum.client.validation.contract;

import java.util.Locale;

public record CaptureFormat(
        String name,
        int bytesPerTexel,
        int componentCount,
        ComponentType componentType,
        boolean normalized,
        boolean depth,
        boolean stencil
) {
    public enum ComponentType {
        UINT8,
        SINT8,
        UINT16,
        SINT16,
        UINT32,
        SINT32,
        FLOAT16,
        FLOAT32,
        UNKNOWN
    }

    public CaptureFormat {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Capture format name must not be blank");
        }
        if (bytesPerTexel <= 0 || componentCount <= 0 || componentType == null) {
            throw new IllegalArgumentException("Invalid capture format dimensions");
        }
    }

    public static CaptureFormat fromFormat(final String formatName, final int bytesPerTexel) {
        String name = formatName == null || formatName.isBlank() ? "UNKNOWN" : formatName;
        String upper = name.toUpperCase(Locale.ROOT);
        int components = upper.startsWith("RGBA") || upper.startsWith("BGRA") ? 4
                : upper.startsWith("RGB") || upper.startsWith("BGR") ? 3
                : upper.startsWith("RG") ? 2
                : 1;
        boolean depth = upper.startsWith("D") || upper.contains("DEPTH");
        boolean stencil = upper.contains("S8") || upper.contains("STENCIL");
        boolean normalized = upper.contains("UNORM") || upper.contains("SNORM")
                || upper.matches("(?:R|RG|RGB|RGBA)(8|16)(?:_.*)?");
        ComponentType type;
        if (upper.contains("16_FLOAT") || upper.contains("16F") || upper.contains("HALF")) {
            type = ComponentType.FLOAT16;
        } else if (upper.contains("32_FLOAT") || upper.contains("32F") || upper.endsWith("_FLOAT")) {
            type = ComponentType.FLOAT32;
        } else if (upper.contains("16_UINT") || upper.matches("(?:B|R|RG|RGB|RGBA|BGR|BGRA)16(?:_.*)?")) {
            type = ComponentType.UINT16;
        } else if (upper.contains("16_SINT")) {
            type = ComponentType.SINT16;
        } else if (upper.contains("32_UINT")) {
            type = ComponentType.UINT32;
        } else if (upper.contains("32_SINT")) {
            type = ComponentType.SINT32;
        } else if (upper.contains("8_UINT") || upper.matches("(?:B|R|RG|RGB|RGBA|BGR|BGRA)8(?:_.*)?")) {
            type = ComponentType.UINT8;
        } else if (upper.contains("8_SINT")) {
            type = ComponentType.SINT8;
        } else {
            type = ComponentType.UNKNOWN;
        }
        return new CaptureFormat(name, bytesPerTexel, components, type, normalized, depth, stencil);
    }
}
