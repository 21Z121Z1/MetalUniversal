package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.minecraft.core.SectionPos;

/**
 * Output oracle for the verify-only cull-reuse experiment.
 *
 * <p>Comparison is expressed only through the public forest presence contract.
 * It does not depend on Sodium's private tree-array layout.
 */
public final class SodiumCullReuseOracle {
    public record Comparison(long regularDifferences, long wideDifferences, boolean oracleTaskListEmpty) {
        public boolean matches() {
            return regularDifferences == 0L && wideDifferences == 0L && oracleTaskListEmpty;
        }
    }

    private SodiumCullReuseOracle() {
    }

    public static Comparison compare(
            SectionTree referenceRegular,
            SectionTree referenceWide,
            SectionTree oracleRegular,
            SectionTree oracleWide,
            boolean oracleTaskListEmpty,
            SectionPos origin,
            int minSectionY,
            int maxSectionY
    ) {
        return new Comparison(
                countMembershipDifferences(referenceRegular, oracleRegular, origin, minSectionY, maxSectionY),
                countMembershipDifferences(referenceWide, oracleWide, origin, minSectionY, maxSectionY),
                oracleTaskListEmpty
        );
    }

    static long countMembershipDifferences(
            SectionTree reference,
            SectionTree oracle,
            SectionPos origin,
            int minSectionY,
            int maxSectionY
    ) {
        if (reference == null || oracle == null) {
            return reference == oracle ? 0L : -1L;
        }

        float maximumDistance = Math.max(reference.buildDistance, oracle.buildDistance);
        int radius = (int) Math.ceil(maximumDistance / 16.0f) + 2;
        long differences = 0L;

        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                for (int y = minSectionY; y <= maxSectionY; y++) {
                    if (reference.tree.isSectionPresent(x, y, z)
                            != oracle.tree.isSectionPresent(x, y, z)) {
                        differences++;
                    }
                }
            }
        }
        return differences;
    }
}
