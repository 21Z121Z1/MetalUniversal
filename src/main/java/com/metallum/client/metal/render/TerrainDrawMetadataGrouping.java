package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure producer-order contracts for Sodium's two indexed command helpers.
 *
 * <p>The local helper writes a slot for every facing but advances its final
 * draw ordinal only for visible faces.  The shared helper writes one slot per
 * visible run, with empty faces following Sodium's exact state transition.
 * Keeping these rules independent of native pointers makes the ordinal
 * mapping directly testable without inventing a second producer.</p>
 */
final class TerrainDrawMetadataGrouping {
    private TerrainDrawMetadataGrouping() {
    }

    static List<Integer> localVisibleFacingMasks(final int mask, final int facingCount) {
        ArrayList<Integer> visible = new ArrayList<>(facingCount);
        for (int facing = 0; facing < facingCount; facing++) {
            int facingMask = localPutFacingMask(mask, facing, facingCount);
            if (facingMask != 0) {
                visible.add(facingMask);
            }
        }
        return List.copyOf(visible);
    }

    /** Returns zero for the hidden overwrite put, otherwise the one-face mask. */
    static int localPutFacingMask(final int mask, final int putCallIndex, final int facingCount) {
        if (putCallIndex < 0 || putCallIndex >= facingCount) {
            throw new IllegalArgumentException("Sodium local facing put index out of range");
        }
        return ((mask >>> putCallIndex) & 1) == 0 ? 0 : 1 << putCallIndex;
    }

    /**
     * Mirrors {@code addSharedIndexedDrawCommands} without reading Sodium's
     * packed pointer.  {@code vertexCounts} and {@code facings} are in the
     * producer's face order and have exactly {@link ModelQuadFacing#COUNT}
     * entries.
     */
    static List<Integer> sharedFacingGroups(
            final int[] vertexCounts,
            final int[] facings,
            final int visibleFaces
    ) {
        int count = ModelQuadFacing.COUNT;
        if (vertexCounts.length != count || facings.length != count) {
            throw new IllegalArgumentException("Sodium shared face arrays must match facing count");
        }
        ArrayList<Integer> groups = new ArrayList<>();
        int lastMaskBit = 0;
        int pendingMask = 0;
        for (int face = 0; face <= count; face++) {
            int maskBit = 0;
            int vertexCount = 0;
            if (face < count) {
                vertexCount = vertexCounts[face];
                if (vertexCount != 0) {
                    maskBit = (visibleFaces >>> facings[face]) & 1;
                }
            }
            if (maskBit == 0) {
                if (lastMaskBit == 1) {
                    if (face >= count || vertexCount != 0) {
                        groups.add(pendingMask);
                        pendingMask = 0;
                    }
                }
            } else {
                pendingMask |= 1 << face;
            }
            lastMaskBit = maskBit;
        }
        return List.copyOf(groups);
    }

    /**
     * Shared-index grouping for conservative terrain candidates.  Passing
     * Sodium's ALL bitmap reuses the exact producer grouping state machine
     * while deliberately ignoring the CPU cull/visible-face mask: every face
     * with non-zero mesh geometry is admitted until a later GPU decision.
     */
    static List<Integer> sharedGeometryGroups(
            final int[] vertexCounts,
            final int[] facings
    ) {
        List<Integer> groups = sharedFacingGroups(
                vertexCounts,
                facings,
                ModelQuadFacing.ALL
        );
        if (!groups.isEmpty()) {
            return groups;
        }

        // Sodium's sentinel loop carries a pending run across empty faces;
        // when the final unassigned slot is empty, the bytecode's last-mask
        // state does not expose that pending run at the sentinel.  The
        // conservative candidate must not drop real geometry in that case.
        int pendingMask = 0;
        for (int face = 0; face < ModelQuadFacing.COUNT; face++) {
            if (vertexCounts[face] != 0) {
                pendingMask |= 1 << face;
            }
        }
        return pendingMask == 0 ? List.of() : List.of(pendingMask);
    }
}
