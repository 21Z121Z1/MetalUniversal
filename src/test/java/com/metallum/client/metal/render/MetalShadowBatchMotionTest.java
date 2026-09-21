package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MetalShadowBatchMotionTest {
    private static final long ZERO = Double.doubleToLongBits(0.0);
    private static final long ONE = Double.doubleToLongBits(1.0);
    private static final MetalShadowBatchMotion.PieceKey A =
            new MetalShadowBatchMotion.PieceKey(10, 63, 20, ZERO, ZERO, ZERO, ONE, ONE, ONE);
    private static final MetalShadowBatchMotion.PieceKey B =
            new MetalShadowBatchMotion.PieceKey(11, 63, 20, ZERO, ZERO, ZERO, ONE, ONE, ONE);
    private static final MetalShadowBatchMotion.PieceKey B_HALF =
            new MetalShadowBatchMotion.PieceKey(11, 63, 20, ZERO, ZERO, ZERO, ONE, Double.doubleToLongBits(0.5), ONE);
    private static final MetalShadowBatchMotion.Member OWNER_AB =
            new MetalShadowBatchMotion.Member(7L, 3L, Float.floatToIntBits(0.5F), List.of(A, B));
    private static final MetalShadowBatchMotion.Member OWNER_BA =
            new MetalShadowBatchMotion.Member(7L, 3L, Float.floatToIntBits(0.5F), List.of(B, A));

    @AfterEach
    void reset() {
        MetalShadowBatchMotion.reset();
    }

    @Test
    void stableWorldPieceMembershipReusesExactHistory() {
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertNotNull(first);
        assertFalse(first.hasPrevious());
        assertTrue(first.generation() < 0L);
        MetalShadowBatchMotion.commitSubmittedFrame();

        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample second = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertNotNull(second);
        assertEquals(first.generation(), second.generation());
        assertTrue(second.hasPrevious());
    }

    @Test
    void sameVertexCountButDifferentPieceOrderOrBoundsBreaksContinuity() {
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertNotNull(first);
        MetalShadowBatchMotion.commitSubmittedFrame();

        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample reordered = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_BA));
        assertNotNull(reordered);
        assertNotEquals(first.generation(), reordered.generation());
        assertFalse(reordered.hasPrevious());

        MetalShadowBatchMotion.reset();
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample baseline = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertNotNull(baseline);
        MetalShadowBatchMotion.commitSubmittedFrame();
        MetalShadowBatchMotion.Member changedBounds = new MetalShadowBatchMotion.Member(
                7L, 3L, Float.floatToIntBits(0.5F), List.of(A, B_HALF));
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample changed = MetalShadowBatchMotion.beginShadowBatch(List.of(changedBounds));
        assertNotNull(changed);
        assertNotEquals(baseline.generation(), changed.generation());
        assertFalse(changed.hasPrevious());
    }

    @Test
    void ownerLifetimeAndRadiusArePartOfIdentity() {
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertNotNull(first);
        MetalShadowBatchMotion.commitSubmittedFrame();

        MetalShadowBatchMotion.Member replacedOwner = new MetalShadowBatchMotion.Member(
                7L, 4L, Float.floatToIntBits(0.5F), List.of(A, B));
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample replaced = MetalShadowBatchMotion.beginShadowBatch(List.of(replacedOwner));
        assertNotNull(replaced);
        assertFalse(replaced.hasPrevious());

        MetalShadowBatchMotion.reset();
        MetalShadowBatchMotion.beginFrame();
        assertNotNull(MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB)));
        MetalShadowBatchMotion.commitSubmittedFrame();
        MetalShadowBatchMotion.Member radiusChanged = new MetalShadowBatchMotion.Member(
                7L, 3L, Float.floatToIntBits(0.75F), List.of(A, B));
        MetalShadowBatchMotion.beginFrame();
        assertFalse(MetalShadowBatchMotion.beginShadowBatch(List.of(radiusChanged)).hasPrevious());
    }

    @Test
    void discardedFrameDoesNotAdvanceAndSubmittedGapBreaksContinuity() {
        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertNotNull(first);
        MetalShadowBatchMotion.commitSubmittedFrame();

        MetalShadowBatchMotion.beginFrame();
        assertNotNull(MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_BA)));
        MetalShadowBatchMotion.discardFrame();

        MetalShadowBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample recovered = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));
        assertTrue(recovered.hasPrevious());
        assertEquals(first.generation(), recovered.generation());
        MetalShadowBatchMotion.commitSubmittedFrame();

        MetalShadowBatchMotion.beginFrame();
        MetalShadowBatchMotion.commitSubmittedFrame();
        MetalShadowBatchMotion.beginFrame();
        assertFalse(MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB)).hasPrevious());
    }

    @Test
    void integerBlockCoordinateRecoveryAllowsOnlyFloatRoundoff() {
        assertEquals(10, MetalShadowBatchMotion.blockCoordinate(10.25, -0.25F));
        assertEquals(-30, MetalShadowBatchMotion.blockCoordinate(-29.875, -0.125F));
        assertNull(MetalShadowBatchMotion.blockCoordinate(10.25, -0.20F));
        assertNull(MetalShadowBatchMotion.blockCoordinate(Double.NaN, 0.0F));
        assertNull(MetalShadowBatchMotion.blockCoordinate(0.0, Float.POSITIVE_INFINITY));
    }
}
