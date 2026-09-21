package com.metallum.client.metal.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact source-frame identity for Minecraft 26.2's one shared entity-shadow staged draw.
 *
 * <p>ShadowFeatureRenderer concatenates every submitted entity and every terrain ShadowPiece into
 * one ENTITY/QUADS draw. Aggregate vertex count is not a sufficient correspondence proof: one
 * terrain block can disappear while another appears and preserve the same count. The signature
 * therefore records owner lifetime, shadow radius, ordered world block coordinates, and the exact
 * AABB bounds that determine each emitted quad. Previous staged positions remain the authoritative
 * per-vertex positions; this class only proves that vertex ordinal still names the same geometry.</p>
 */
final class MetalShadowBatchMotion {
    static final long SHADOW_OBJECT_ID = 0x4D465853484457L; // ASCII "MFXSHDW".
    private static final double BLOCK_COORDINATE_EPSILON = 1.0e-3;

    record PieceKey(
            int blockX, int blockY, int blockZ,
            long minXBits, long minYBits, long minZBits,
            long maxXBits, long maxYBits, long maxZBits
    ) {
    }

    record Member(long objectId, long generation, int radiusBits, List<PieceKey> pieces) {
        Member {
            pieces = List.copyOf(pieces);
        }

        int vertexSpan() {
            return Math.multiplyExact(pieces.size(), 4);
        }
    }

    private record Signature(List<Member> members) {
        Signature {
            members = List.copyOf(members);
        }
    }

    private static @Nullable Signature previousSignature;
    private static long previousGeneration;
    private static @Nullable Signature pendingSignature;
    private static long pendingGeneration;
    private static long nextGeneration = -1L;
    private static boolean frameOpen;
    private static boolean batchOpened;

    private MetalShadowBatchMotion() {
    }

    static void beginFrame() {
        pendingSignature = null;
        pendingGeneration = 0L;
        batchOpened = false;
        frameOpen = true;
    }

    /** Builds the exact ordinal identity for one entity-owned Shadow submit. */
    static @Nullable Member member(
            final MetalEntityMotionCapture.Sample owner,
            final EntityRenderState state,
            final ShadowFeatureRenderer.Submit submit
    ) {
        if (owner == null || owner.generation() <= 0L || state == null || submit == null
                || !Float.isFinite(submit.radius()) || submit.radius() <= 0.0F
                || submit.pieces() == null || submit.pieces().isEmpty()) {
            return null;
        }
        if (!Double.isFinite(state.x) || !Double.isFinite(state.y) || !Double.isFinite(state.z)) {
            return null;
        }

        ArrayList<PieceKey> pieces = new ArrayList<>(submit.pieces().size());
        for (EntityRenderState.ShadowPiece piece : submit.pieces()) {
            if (piece == null || piece.shapeBelow() == null
                    || !Float.isFinite(piece.relativeX())
                    || !Float.isFinite(piece.relativeY())
                    || !Float.isFinite(piece.relativeZ())) {
                return null;
            }
            Integer blockX = blockCoordinate(state.x, piece.relativeX());
            Integer blockY = blockCoordinate(state.y, piece.relativeY());
            Integer blockZ = blockCoordinate(state.z, piece.relativeZ());
            if (blockX == null || blockY == null || blockZ == null) {
                return null;
            }

            final AABB bounds;
            try {
                bounds = piece.shapeBelow().bounds();
            } catch (RuntimeException invalidShape) {
                return null;
            }
            if (bounds == null
                    || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY) || !Double.isFinite(bounds.minZ)
                    || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)
                    || bounds.maxX < bounds.minX || bounds.maxY < bounds.minY || bounds.maxZ < bounds.minZ) {
                return null;
            }
            pieces.add(new PieceKey(
                    blockX, blockY, blockZ,
                    Double.doubleToLongBits(bounds.minX),
                    Double.doubleToLongBits(bounds.minY),
                    Double.doubleToLongBits(bounds.minZ),
                    Double.doubleToLongBits(bounds.maxX),
                    Double.doubleToLongBits(bounds.maxY),
                    Double.doubleToLongBits(bounds.maxZ)
            ));
        }
        try {
            Math.multiplyExact(pieces.size(), 4);
        } catch (ArithmeticException overflow) {
            return null;
        }
        return new Member(
                owner.objectId(),
                owner.generation(),
                Float.floatToIntBits(submit.radius()),
                pieces
        );
    }

    /**
     * Reconstructs the integer BlockPos used by EntityRenderer.extractShadow. That source stores
     * relativeX/Y/Z as float(blockCoordinate - interpolatedEntityCoordinate), so adding the
     * interpolated state coordinate back must land within float round-off of an integer.
     */
    static @Nullable Integer blockCoordinate(final double entityCoordinate, final float relativeCoordinate) {
        if (!Double.isFinite(entityCoordinate) || !Float.isFinite(relativeCoordinate)) {
            return null;
        }
        double absolute = entityCoordinate + (double) relativeCoordinate;
        double rounded = Math.rint(absolute);
        if (!Double.isFinite(absolute) || Math.abs(absolute - rounded) > BLOCK_COORDINATE_EPSILON
                || rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            return null;
        }
        return (int) rounded;
    }

    static MetalEntityMotionCapture.@Nullable Sample beginShadowBatch(final List<Member> members) {
        if (!frameOpen || batchOpened || members == null || members.isEmpty()) {
            return null;
        }
        for (Member member : members) {
            if (member == null || member.generation() <= 0L || member.pieces().isEmpty()) {
                return null;
            }
            try {
                if (member.vertexSpan() <= 0) {
                    return null;
                }
            } catch (ArithmeticException overflow) {
                return null;
            }
        }

        Signature signature = new Signature(members);
        boolean hasPrevious = signature.equals(previousSignature);
        long generation = hasPrevious ? previousGeneration : allocateGeneration();
        if (generation >= 0L) {
            return null;
        }

        batchOpened = true;
        pendingSignature = signature;
        pendingGeneration = generation;
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(
                SHADOW_OBJECT_ID,
                generation,
                identity,
                hasPrevious ? identity : null
        );
    }

    static void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        previousSignature = pendingSignature;
        previousGeneration = pendingSignature == null ? 0L : pendingGeneration;
        pendingSignature = null;
        pendingGeneration = 0L;
        batchOpened = false;
        frameOpen = false;
    }

    static void discardFrame() {
        pendingSignature = null;
        pendingGeneration = 0L;
        batchOpened = false;
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        previousSignature = null;
        previousGeneration = 0L;
        pendingSignature = null;
        pendingGeneration = 0L;
        nextGeneration = -1L;
        batchOpened = false;
        frameOpen = wasOpen;
    }

    private static long allocateGeneration() {
        long generation = nextGeneration;
        nextGeneration = generation == Long.MIN_VALUE ? -1L : generation - 1L;
        return generation;
    }
}
