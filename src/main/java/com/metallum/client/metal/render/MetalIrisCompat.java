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
    /**
     * Opt-in switch for the experimental Iris-on-Metal semantic layer. With
     * the default {@code false}, the shims keep Iris safely dormant on Metal;
     * {@code -Dmetallum.iris.semantic=true} enables the incomplete B2-1 path.
     * With the semantic layer disabled the shims fall back to the pure
     * dormancy behaviour described above: no pack is loaded, no terrain
     * pipeline is overridden, and the client renders exactly as it did before
     * the semantic layer existed. Any doubt about a regression should be
     * bisected with this flag first.
     *
     * <p>What B2-1 actually covers: a pack's {@code gbuffers_terrain} draws
     * sodium's solid and cutout terrain, with its uniform block filled from
     * real frame state and its samplers resolved from their real Mojang or Iris
     * resources. Terrain kinds whose DRAWBUFFERS name
     * more than the main target stay on sodium's own shader until the terrain
     * pass carries those attachments. There is no shadow pass and no
     * composite/final chain, so what reaches the screen is the raw gbuffer0
     * output.</p>
     */
    private static final boolean SEMANTIC_LAYER =
            Boolean.parseBoolean(System.getProperty("metallum.iris.semantic", "false"));

    private static volatile boolean announced;
    private static volatile boolean semanticAnnounced;

    private MetalIrisCompat() {
    }

    /** True when the experimental semantic layer was requested at startup. */
    public static boolean semanticLayerRequested() {
        return SEMANTIC_LAYER;
    }

    /**
     * True when the semantic layer owns the Iris seams: the live device is
     * Metal and the kill switch is not set.
     *
     * <p>Where {@link #holdIrisDormant()} means "cancel this GL-flavoured Iris
     * entry point", this means "let Iris run and serve it ourselves". The two
     * are used together: a shim that must stay cancelled even with the semantic
     * layer active tests {@code holdIrisDormant()} alone; a shim that the
     * semantic layer takes over tests {@code holdIrisDormant() &&
     * !semanticLayerEnabled()}.</p>
     */
    public static boolean semanticLayerEnabled() {
        if (!SEMANTIC_LAYER) {
            return false;
        }
        if (!holdIrisDormant()) {
            return false;
        }
        if (!semanticAnnounced) {
            semanticAnnounced = true;
            Metallum.LOGGER.info(
                    "Iris-on-Metal semantic layer active: shader packs load for real and sodium terrain"
                            + " draws through the pack's gbuffers_terrain programs"
                            + " (experimental opt-in via -Dmetallum.iris.semantic=true)"
            );
        }
        return true;
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
