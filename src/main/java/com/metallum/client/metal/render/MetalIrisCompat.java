package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Runtime gate for the Iris-dormancy compat shims.
 *
 * <p>Iris 1.11.2 is an OpenGL renderer: its RenderSystem-init hook calls raw
 * GL entry points (GL.getCapabilities, glGenSamplers, KHR debug, DSA probes)
 * that would crash the JVM on the Metal backend, and its {@code IrisMixinPlugin}
 * only knows how to stand down when it sees "vulkan" in options.txt — a Metal
 * device sails straight into the GL code paths. Until the Iris-on-Metal
 * semantic layer replaces those seams, the metallum {@code mixin.iris.*} shims
 * cancel Iris's GL-touching entry points whenever the LIVE backend is Metal,
 * leaving Iris installed-but-dormant (its pipeline manager serves the real
 * {@code VanillaRenderingPipeline}, whose only GL use — beginLevelRendering's
 * clip-control — is also cancelled).</p>
 *
 * <p>On a Vulkan/GL fallback device every shim is a no-op and Iris behaves
 * exactly as shipped.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalIrisCompat {
    private static volatile boolean announced;

    private MetalIrisCompat() {
    }

    /** True when the live GpuDevice is the Metal backend. */
    public static boolean holdIrisDormant() {
        try {
            if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) {
                return false;
            }
        } catch (Throwable notReady) {
            // No device yet: nothing GL-flavored can be running either; do not
            // suppress Iris based on a guess.
            return false;
        }
        if (!announced) {
            announced = true;
            Metallum.LOGGER.info(
                    "Iris detected on the Metal backend: holding Iris dormant"
                            + " (GL init and clip-control paths cancelled; vanilla pipeline serves)"
            );
        }
        return true;
    }
}
