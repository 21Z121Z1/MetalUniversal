package com.metallum.client.metal.render;

/**
 * Render-thread scope proving that an indexed multi-draw originated from
 * Sodium's chunk renderer rather than another RenderPass consumer.
 */
public final class MetalTerrainIcbScope {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private MetalTerrainIcbScope() {
    }

    public static void enter() {
        int[] depth = DEPTH.get();
        depth[0] = Math.addExact(depth[0], 1);
    }

    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] <= 0) {
            throw new IllegalStateException("Terrain ICB scope underflow");
        }
        depth[0]--;
    }

    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }

    static int depthForTest() {
        return DEPTH.get()[0];
    }

    static void resetForTest() {
        DEPTH.remove();
    }
}
