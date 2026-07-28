package com.metallum.client.metal.render;

import com.metallum.Metallum;
import org.jspecify.annotations.Nullable;

/**
 * Process-wide registry of the currently-active {@link MetalDevice}.
 *
 * <p>The Metal device is created by {@link MetalBackend#createDevice} and held
 * inside a {@code GpuDevice} wrapper as its package-private {@code backend}
 * field. Code outside {@code com.metallum.client.metal.render} (notably the
 * Iris intercept mixins in {@code com.metallum.mixin.iris}) cannot reach the
 * {@code MetalDevice} instance directly because the class is package-private
 * and the {@code GpuDevice.backend} field is inaccessible without a mixin
 * accessor.
 *
 * <p>This registry breaks that boundary in a controlled way:
 * <ul>
 *   <li>{@link MetalDevice}'s constructor calls {@link #setActiveDevice} to
 *       publish itself.</li>
 *   <li>{@link MetalDevice#close()} calls {@link #clearActiveDevice} to
 *       deregister.</li>
 *   <li>Callers in other packages (via the public
 *       {@link MetalCrossShaderCompiler} API) query {@link #getActiveDevice()}
 *       to obtain the live device for shaderpack pipeline construction.</li>
 * </ul>
 *
 * <p>Only one Metal device is expected to exist at a time (the game creates a
 * single {@code GpuDevice}); the registry overwrites any prior reference, and
 * {@link #clearActiveDevice} nulls it on close. All accessors are
 * thread-safe-via-simple-volatile: the device is created and destroyed on the
 * render thread, and readers query it from the same thread.
 */
public final class MetalDeviceRegistry {
    private static volatile @Nullable MetalDevice activeDevice;

    private MetalDeviceRegistry() {
    }

    /**
     * Publish the given device as the active Metal device. Called from the
     * {@link MetalDevice} constructor.
     *
     * @param device the newly-created Metal device (must not be null).
     */
    static void setActiveDevice(final MetalDevice device) {
        if (device == null) {
            throw new NullPointerException("activeDevice");
        }
        if (activeDevice != null && activeDevice != device) {
            Metallum.LOGGER.warn(
                    "[MetalUniversal] MetalDeviceRegistry: replacing an existing active MetalDevice; "
                            + "the previous device was not closed cleanly."
            );
        }
        activeDevice = device;
    }

    /**
     * Clear the active device reference. Called from {@link MetalDevice#close()}.
     */
    static void clearActiveDevice(final MetalDevice device) {
        if (activeDevice == device) {
            activeDevice = null;
        }
    }

    /**
     * Returns the currently-active {@link MetalDevice}, or {@code null} if no
     * Metal device has been created (or the device has been closed).
     *
     * <p>This method is intended for use by the public entry points in
     * {@link MetalCrossShaderCompiler} that construct shaderpack pipelines.
     * Callers should treat a {@code null} return as "Metal is not active" and
     * fall back accordingly.
     *
     * @return the active Metal device, or {@code null}.
     */
    public static @Nullable MetalDevice getActiveDevice() {
        return activeDevice;
    }
}
