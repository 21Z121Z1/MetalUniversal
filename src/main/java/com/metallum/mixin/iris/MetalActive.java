package com.metallum.mixin.iris;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Runtime gate shared by the Iris intercept mixins.
 *
 * <p>Returns {@code true} only when the Metal GPU backend is the active backend
 * of the running game, queried the same way the existing sodium intercept
 * mixins ({@code DrawContextMixin} / {@code DrawBackendMixin}) do:
 * {@code RenderSystem.getDevice().getDeviceInfo().backendName()}.
 *
 * <p>This is a deliberate improvement over reading {@code options.txt}'s
 * {@code preferredGraphicsBackend} option: that option only tells us Metal is
 * <i>preferred</i>, not that a Metal device was actually created (the backend
 * could have fallen back to Vulkan/GL if device creation failed). Querying the
 * live {@code GpuDevice} reflects the real active backend, so the Iris mixins
 * are guaranteed to no-op whenever Metal is not actually in use &mdash; including
 * on non-Apple platforms, in dev runs on OpenGL/Vulkan, and when the user
 * explicitly picked a different graphics API.
 *
 * <p>Any throwable escaping the device accessor (e.g. the device not yet being
 * initialized during very early bootstrap, before Iris shaderpack compilation
 * could run anyway) is treated as "not Metal" so the mixins never disrupt
 * Iris's normal GL path.
 */
final class MetalActive {
    private static final String METAL_BACKEND_NAME = "Metal";

    private MetalActive() {
    }

    static boolean isMetalActive() {
        try {
            var device = RenderSystem.getDevice();
            return device != null
                    && METAL_BACKEND_NAME.equals(device.getDeviceInfo().backendName());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
