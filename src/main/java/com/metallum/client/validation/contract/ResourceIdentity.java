package com.metallum.client.validation.contract;

import java.util.Objects;

/** Stable logical identity for one allocation generation of a GPU resource. */
public record ResourceIdentity(
        String semanticName,
        long runtimeId,
        long generation,
        String nativeHandleHashOrDebugId,
        String format,
        int width,
        int height,
        int depthOrLayers,
        int mipLevel,
        int sampleCount,
        int usage
) {
    public ResourceIdentity {
        semanticName = requireName(semanticName, "semanticName");
        nativeHandleHashOrDebugId = requireName(nativeHandleHashOrDebugId, "nativeHandleHashOrDebugId");
        format = requireName(format, "format");
        if (runtimeId <= 0L || generation <= 0L) {
            throw new IllegalArgumentException("Resource identity ids must be positive");
        }
        if (width <= 0 || height <= 0 || depthOrLayers <= 0 || mipLevel < 0 || sampleCount <= 0) {
            throw new IllegalArgumentException("Resource dimensions and sample count must be positive");
        }
    }

    public String stableKey() {
        return semanticName + "@" + generation;
    }

    /** Allocation/subresource key for future hazard and physical-plan consumers. */
    public String allocationKey() {
        return "allocation/" + runtimeId + "/generation/" + generation + "/mip/" + mipLevel;
    }

    private static String requireName(final String value, final String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
