package com.metallum.client.sodium;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SodiumSliceBoundsPolicyTest {
    @Test
    void matchesSixInclusiveComparisonsAtAndAroundEveryFace() {
        int minX = -18, maxX = 17;
        int minY = 30, maxY = 65;
        int minZ = -34, maxZ = 1;

        for (int x = minX - 2; x <= maxX + 2; x++) {
            for (int y = minY - 2; y <= maxY + 2; y++) {
                for (int z = minZ - 2; z <= maxZ + 2; z++) {
                    boolean expected = x >= minX && x <= maxX
                            && y >= minY && y <= maxY
                            && z >= minZ && z <= maxZ;
                    assertEquals(expected, SodiumSliceBoundsPolicy.containsInclusive(
                            minX, maxX, minY, maxY, minZ, maxZ, x, y, z));
                }
            }
        }
    }

    @Test
    void matchesOrdinaryPredicateAcrossMinecraftScaleCoordinates() {
        Random random = new Random(0x4d4554414c4c554dL);
        for (int i = 0; i < 100_000; i++) {
            int minX = random.nextInt(-30_000_000, 30_000_000);
            int minY = random.nextInt(-2_048, 2_048);
            int minZ = random.nextInt(-30_000_000, 30_000_000);
            int maxX = minX + random.nextInt(1, 64);
            int maxY = minY + random.nextInt(1, 64);
            int maxZ = minZ + random.nextInt(1, 64);
            int x = minX + random.nextInt(-8, 72);
            int y = minY + random.nextInt(-8, 72);
            int z = minZ + random.nextInt(-8, 72);

            boolean expected = x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
            assertEquals(expected, SodiumSliceBoundsPolicy.containsInclusive(
                    minX, maxX, minY, maxY, minZ, maxZ, x, y, z));
        }
    }
}
