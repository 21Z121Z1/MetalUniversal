package com.metallum.client.metal.render;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Content identity for a Metal binary archive.  A single mutable
 * {@code pso.binaryarchive} is unsafe across devices, OS/compiler builds or
 * shader-pack ABIs, so the archive filename is derived from every input that
 * changes the pipeline contract.
 */
record MetalPsoArchiveIdentity(
        String platform,
        String osBuild,
        String deviceName,
        String backendAbi,
        String shaderPackSha,
        String renderTargetSchema,
        boolean metal4
) {
    private static final String VERSION = "pso-archive-v2";

    MetalPsoArchiveIdentity {
        platform = requireText(platform, "platform");
        osBuild = requireText(osBuild, "osBuild");
        deviceName = requireText(deviceName, "deviceName");
        backendAbi = requireText(backendAbi, "backendAbi");
        shaderPackSha = requireText(shaderPackSha, "shaderPackSha");
        renderTargetSchema = requireText(renderTargetSchema, "renderTargetSchema");
    }

    static MetalPsoArchiveIdentity forDevice(final String deviceName, final boolean metal4) {
        String platform = System.getProperty("os.name", "unknown").trim();
        String osBuild = System.getProperty("os.version", "unknown").trim();
        String shaderPackSha = firstNonBlank(
                System.getProperty("metallum.shaderPackSha"),
                System.getenv("METALLUM_SHADER_PACK_SHA"),
                "unknown"
        );
        String backendAbi = firstNonBlank(
                System.getProperty("metallum.irisAbi"),
                "iris-metal-abi-v3"
        );
        return new MetalPsoArchiveIdentity(
                platform,
                osBuild,
                deviceName == null || deviceName.isBlank() ? "unknown-device" : deviceName,
                backendAbi,
                shaderPackSha,
                "mrt-v3|depth-stencil-v2|msl-" + MetalMslDiskCache.CACHE_SALT,
                metal4
        );
    }

    String canonical() {
        return String.join("\n",
                VERSION,
                "platform=" + platform,
                "osBuild=" + osBuild,
                "device=" + deviceName,
                "backendAbi=" + backendAbi,
                "shaderPackSha=" + shaderPackSha,
                "renderTargetSchema=" + renderTargetSchema,
                "metal4=" + metal4
        );
    }

    String digest() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical().getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable for PSO archive identity", failure);
        }
    }

    String filename() {
        return "pso-v2-" + digest() + (metal4 ? ".mtl4.binaryarchive" : ".binaryarchive");
    }

    boolean exactShaderPackIdentity() {
        return !"unknown".equalsIgnoreCase(shaderPackSha);
    }

    private static String firstNonBlank(final String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "unknown";
    }

    private static String requireText(final String value, final String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
