package com.metallum.client.metal.render;

/**
 * Signals that the selected Iris pack cannot be represented by this Metal
 * execution surface.
 *
 * <p>This remains an {@link UnsupportedOperationException} for compatibility
 * with the existing admission tests, while giving pipeline lifecycle code a
 * typed boundary that must never be converted into a shaders-off success.</p>
 */
public final class IrisMetalPackRejectedException extends UnsupportedOperationException {
    public IrisMetalPackRejectedException(final String message) {
        super(message);
    }

    public IrisMetalPackRejectedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
