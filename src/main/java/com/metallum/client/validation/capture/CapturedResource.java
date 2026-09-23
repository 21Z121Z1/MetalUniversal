package com.metallum.client.validation.capture;

import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.ResourceIdentity;

import java.util.Arrays;

/** Immutable CPU-side representation of a completed GPU readback. */
public record CapturedResource(
        String semanticName,
        ResourceIdentity resource,
        CaptureFormat captureFormat,
        int width,
        int height,
        byte[] bytes
) {
    public CapturedResource {
        if (semanticName == null || semanticName.isBlank() || resource == null
                || captureFormat == null || width <= 0 || height <= 0 || bytes == null) {
            throw new IllegalArgumentException("Invalid captured resource");
        }
        long expected = (long) width * height * captureFormat.bytesPerTexel();
        if (expected != bytes.length) {
            throw new IllegalArgumentException(
                    "Readback byte count " + bytes.length + " does not match " + expected
            );
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int texelCount() {
        return width * height;
    }

    public CapturedResource copy() {
        return new CapturedResource(semanticName, resource, captureFormat, width, height, bytes);
    }

    public boolean sameShape(final CapturedResource other) {
        return other != null
                && width == other.width
                && height == other.height
                && captureFormat.bytesPerTexel() == other.captureFormat.bytesPerTexel();
    }

    @Override
    public String toString() {
        return "CapturedResource[" + semanticName + " " + width + "x" + height
                + " " + captureFormat.name() + " bytes=" + bytes.length + "]";
    }
}
