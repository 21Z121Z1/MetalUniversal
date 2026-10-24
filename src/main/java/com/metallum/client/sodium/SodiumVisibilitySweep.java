package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import net.minecraft.client.renderer.chunk.VisibilitySet;

import java.util.Arrays;

/**
 * Independent directed-visibility solver for Sodium's 16^3 section graph.
 *
 * <p>The graph allows one signed direction per axis. That makes each pass a
 * directed acyclic grid: Y/Z are traversed in dependency order and each X row
 * is propagated as a 16-bit mask. No Sodium private bit-array layout is read.
 */
public final class SodiumVisibilitySweep {
    private static final int ALL_X = 0xFFFF;
    private static final int[] DIRECTION_SETS = {
            0b010101,
            0b010110,
            0b011001,
            0b100101
    };

    private SodiumVisibilitySweep() {
    }

    public static VisibilitySet[] resolve(int[] opaqueRows, int filled, int[] scratchRows) {
        if (opaqueRows == null || opaqueRows.length != 256
                || scratchRows == null || scratchRows.length != 256) {
            throw new IllegalArgumentException("Visibility sweep requires 256 X-row masks");
        }

        if (filled == 16 * 16 * 16) {
            VisibilitySet none = new VisibilitySet();
            none.setAll(false);
            return new VisibilitySet[] { none };
        }
        if (filled < 256) {
            VisibilitySet all = new VisibilitySet();
            all.setAll(true);
            return new VisibilitySet[] { all };
        }

        VisibilitySet[] result = new VisibilitySet[DIRECTION_SETS.length];
        for (int i = 0; i < DIRECTION_SETS.length; i++) {
            int allowed = DIRECTION_SETS[i];
            int origins = (~allowed) & 0x3F;
            VisibilitySet set = new VisibilitySet();
            for (int origin = 0; origin < GraphDirection.COUNT; origin++) {
                if ((origins & (1 << origin)) == 0) {
                    continue;
                }
                int reached = reach(opaqueRows, scratchRows, allowed, origin);
                if (reached == 0) {
                    continue;
                }
                for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
                    if ((reached & (1 << direction)) != 0) {
                        set.set(GraphDirection.toEnum(origin), GraphDirection.toEnum(direction), true);
                    }
                }
            }
            result[i] = set;
        }
        return result;
    }

    static int reach(int[] opaqueRows, int[] scratchRows, int allowedDirections, int originDirection) {
        Arrays.fill(scratchRows, 0);

        final int xDirection = (allowedDirections & (1 << GraphDirection.EAST)) != 0
                ? GraphDirection.EAST : GraphDirection.WEST;
        final int yDirection = (allowedDirections & (1 << GraphDirection.UP)) != 0
                ? GraphDirection.UP : GraphDirection.DOWN;
        final int zDirection = (allowedDirections & (1 << GraphDirection.SOUTH)) != 0
                ? GraphDirection.SOUTH : GraphDirection.NORTH;

        final boolean originX = originDirection == GraphDirection.WEST || originDirection == GraphDirection.EAST;
        final boolean originY = originDirection == GraphDirection.DOWN || originDirection == GraphDirection.UP;
        final int originCoordinate = switch (originDirection) {
            case GraphDirection.DOWN, GraphDirection.NORTH, GraphDirection.WEST -> 0;
            case GraphDirection.UP, GraphDirection.SOUTH, GraphDirection.EAST -> 15;
            default -> throw new IllegalArgumentException("Invalid graph direction " + originDirection);
        };

        int reachedFaces = 0;
        int y = yDirection == GraphDirection.UP ? 0 : 15;
        final int yEnd = yDirection == GraphDirection.UP ? 16 : -1;
        final int yStep = yDirection == GraphDirection.UP ? 1 : -1;

        for (; y != yEnd; y += yStep) {
            int z = zDirection == GraphDirection.SOUTH ? 0 : 15;
            final int zEnd = zDirection == GraphDirection.SOUTH ? 16 : -1;
            final int zStep = zDirection == GraphDirection.SOUTH ? 1 : -1;

            for (; z != zEnd; z += zStep) {
                final int rowIndex = (y << 4) | z;
                final int open = (~opaqueRows[rowIndex]) & ALL_X;
                int seeds = 0;

                if (originX) {
                    seeds |= 1 << originCoordinate;
                } else if (originY && y == originCoordinate) {
                    seeds |= ALL_X;
                } else if (!originY && z == originCoordinate) {
                    seeds |= ALL_X;
                }

                final int previousY = yDirection == GraphDirection.UP ? y - 1 : y + 1;
                if (previousY >= 0 && previousY < 16) {
                    seeds |= scratchRows[(previousY << 4) | z];
                }
                final int previousZ = zDirection == GraphDirection.SOUTH ? z - 1 : z + 1;
                if (previousZ >= 0 && previousZ < 16) {
                    seeds |= scratchRows[(y << 4) | previousZ];
                }

                int reachable = seeds & open;
                for (int step = 0; step < 15; step++) {
                    int expanded = xDirection == GraphDirection.EAST
                            ? (reachable | ((reachable << 1) & ALL_X))
                            : (reachable | (reachable >>> 1));
                    expanded &= open;
                    if (expanded == reachable) {
                        break;
                    }
                    reachable = expanded;
                }
                scratchRows[rowIndex] = reachable;

                if (reachable == 0) {
                    continue;
                }
                reachedFaces |= 1 << originDirection;

                if (yDirection == GraphDirection.DOWN && y == 0) reachedFaces |= 1 << GraphDirection.DOWN;
                if (yDirection == GraphDirection.UP && y == 15) reachedFaces |= 1 << GraphDirection.UP;
                if (zDirection == GraphDirection.NORTH && z == 0) reachedFaces |= 1 << GraphDirection.NORTH;
                if (zDirection == GraphDirection.SOUTH && z == 15) reachedFaces |= 1 << GraphDirection.SOUTH;
                if (xDirection == GraphDirection.WEST && (reachable & 1) != 0) reachedFaces |= 1 << GraphDirection.WEST;
                if (xDirection == GraphDirection.EAST && (reachable & 0x8000) != 0) reachedFaces |= 1 << GraphDirection.EAST;
            }
        }

        return reachedFaces;
    }
}
