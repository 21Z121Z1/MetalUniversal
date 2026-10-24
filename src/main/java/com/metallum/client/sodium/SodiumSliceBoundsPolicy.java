package com.metallum.client.sodium;

/**
 * Inclusive six-plane bounds test used by the Sodium LevelSlice fast path.
 *
 * <p>Minecraft section-slice coordinates stay far enough from integer overflow
 * that the sign of the OR expression is equivalent to six inclusive range
 * comparisons.
 */
public final class SodiumSliceBoundsPolicy {
    private SodiumSliceBoundsPolicy() {
    }

    public static boolean containsInclusive(
            int minX, int maxX,
            int minY, int maxY,
            int minZ, int maxZ,
            int x, int y, int z
    ) {
        return (x - minX
                | maxX - x
                | y - minY
                | maxY - y
                | z - minZ
                | maxZ - z) >= 0;
    }
}
