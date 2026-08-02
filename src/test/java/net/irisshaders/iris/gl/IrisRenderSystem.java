package net.irisshaders.iris.gl;

/**
 * TEST-CLASSPATH SHADOW of Iris's raw-GL entry class (see the shadow of
 * {@link net.irisshaders.iris.Iris} for the mechanism).
 *
 * <p>The real class's {@code <clinit>} chains into live GL queries
 * (SamplerLimits, GL.getCapabilities) and cannot load headlessly — the same
 * trap the in-game dormancy shims defuse. Headless pack loading only reaches
 * it through {@code FeatureFlags} hardware-requirement suppliers, which bind
 * to exactly the five statics below (verified via constant-pool scan). The
 * answers mirror what the metallum Metal backend actually provides
 * (compute/SSBO/image/per-buffer blending: yes — GPU-validated in B0;
 * tessellation: no Metal equivalent), so feature-gated pack code paths are
 * exercised the way they would be on the finished Metal integration.</p>
 */
public class IrisRenderSystem {
    public static boolean supportsBufferBlending() {
        return true;
    }

    public static boolean supportsCompute() {
        return true;
    }

    public static boolean supportsImageLoadStore() {
        return true;
    }

    public static boolean supportsSSBO() {
        return true;
    }

    public static boolean supportsTesselation() {
        return false;
    }
}
