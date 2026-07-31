package net.irisshaders.iris.gl;

/** Headless feature-query facade matching the Metal backend's connected primitives. */
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
