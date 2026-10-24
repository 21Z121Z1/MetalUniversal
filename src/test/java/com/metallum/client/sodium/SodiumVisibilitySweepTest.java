package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SodiumVisibilitySweepTest {
    private static final int[] SETS = {0b010101, 0b010110, 0b011001, 0b100101};

    @Test
    void directedRowSweepMatchesStraightforwardFloods() {
        Random random = new Random(0x4d6574616cL);
        int[] scratch = new int[256];

        for (int sample = 0; sample < 240; sample++) {
            int[] opaqueRows = new int[256];
            boolean[] opaque = new boolean[4096];
            int threshold = 8 + random.nextInt(88);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int row = 0;
                    for (int x = 0; x < 16; x++) {
                        boolean blocked = random.nextInt(100) < threshold;
                        opaque[index(x, y, z)] = blocked;
                        if (blocked) row |= 1 << x;
                    }
                    opaqueRows[(y << 4) | z] = row;
                }
            }

            for (int directions : SETS) {
                int origins = (~directions) & 0x3f;
                for (int origin = 0; origin < GraphDirection.COUNT; origin++) {
                    if ((origins & (1 << origin)) == 0) continue;
                    assertEquals(
                            referenceReach(opaque, directions, origin),
                            SodiumVisibilitySweep.reach(opaqueRows, scratch, directions, origin),
                            "sample=" + sample + " directions=" + directions + " origin=" + origin
                    );
                }
            }
        }
    }

    private static int referenceReach(boolean[] opaque, int allowed, int origin) {
        boolean[] visited = new boolean[4096];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!onFace(x, y, z, origin)) continue;
                    int index = index(x, y, z);
                    if (!opaque[index]) {
                        visited[index] = true;
                        queue.add(index);
                    }
                }
            }
        }
        if (queue.isEmpty()) return 0;

        int reached = 1 << origin;
        while (!queue.isEmpty()) {
            int packed = queue.removeFirst();
            int x = packed & 15;
            int z = (packed >>> 4) & 15;
            int y = (packed >>> 8) & 15;

            for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
                if ((allowed & (1 << direction)) == 0) continue;
                int nx = x + GraphDirection.x(direction);
                int ny = y + GraphDirection.y(direction);
                int nz = z + GraphDirection.z(direction);
                if (nx < 0 || nx >= 16 || ny < 0 || ny >= 16 || nz < 0 || nz >= 16) {
                    reached |= 1 << direction;
                    continue;
                }
                int next = index(nx, ny, nz);
                if (!opaque[next] && !visited[next]) {
                    visited[next] = true;
                    queue.addLast(next);
                }
            }
        }
        return reached;
    }

    private static boolean onFace(int x, int y, int z, int direction) {
        return switch (direction) {
            case GraphDirection.DOWN -> y == 0;
            case GraphDirection.UP -> y == 15;
            case GraphDirection.NORTH -> z == 0;
            case GraphDirection.SOUTH -> z == 15;
            case GraphDirection.WEST -> x == 0;
            case GraphDirection.EAST -> x == 15;
            default -> false;
        };
    }

    private static int index(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }
}
