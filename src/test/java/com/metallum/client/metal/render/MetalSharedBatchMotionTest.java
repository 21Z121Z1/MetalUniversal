package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetalSharedBatchMotionTest {
    private static final MetalSharedBatchMotion.Member A4 = new MetalSharedBatchMotion.Member(10L, 1L, 4);
    private static final MetalSharedBatchMotion.Member A8 = new MetalSharedBatchMotion.Member(10L, 1L, 8);
    private static final MetalSharedBatchMotion.Member B4 = new MetalSharedBatchMotion.Member(20L, 2L, 4);
    private static final MetalSharedBatchMotion.Member B8 = new MetalSharedBatchMotion.Member(20L, 2L, 8);

    @AfterEach
    void reset() {
        MetalSharedBatchMotion.reset();
    }

    @Test
    void flameSpanMatchesPinnedMinecraftLoopSemantics() {
        // width 1 => scale 1.4. Height 1.4 starts at h=1 and emits 3 layers: 1,.55,.10.
        assertEquals(12, MetalSharedBatchMotion.flameVertexSpan(1.0F, 1.4F));
        assertEquals(4, MetalSharedBatchMotion.flameVertexSpan(1.0F, 0.1F));
        assertEquals(-1, MetalSharedBatchMotion.flameVertexSpan(0.0F, 1.0F));
        assertEquals(-1, MetalSharedBatchMotion.flameVertexSpan(Float.NaN, 1.0F));
        assertEquals(-1, MetalSharedBatchMotion.flameVertexSpan(1.0F, Float.POSITIVE_INFINITY));
    }

    @Test
    void sameSubmittedMembershipAndSpansReuseExactNegativeGeneration() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(first);
        assertTrue(first.generation() < 0L);
        assertFalse(first.hasPrevious());
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample second = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(second);
        assertEquals(first.generation(), second.generation());
        assertTrue(second.hasPrevious());
    }

    @Test
    void equalTotalButRedistributedMemberSpansInvalidateOrdinalContinuity() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        // Both frames have 12 aggregate vertices; the entity boundary moves from 4 to 8.
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample redistributed = MetalSharedBatchMotion.beginFlameBatch(List.of(A8, B4));
        assertNotNull(redistributed);
        assertNotEquals(first.generation(), redistributed.generation());
        assertFalse(redistributed.hasPrevious());
    }

    @Test
    void reorderedOrReplacedMembershipAllocatesFreshGeneration() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample reordered = MetalSharedBatchMotion.beginFlameBatch(List.of(B8, A4));
        assertNotNull(reordered);
        assertNotEquals(first.generation(), reordered.generation());
        assertFalse(reordered.hasPrevious());
    }

    @Test
    void discardedFrameCannotAdvanceSubmittedMembership() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        assertNotNull(MetalSharedBatchMotion.beginFlameBatch(List.of(B4)));
        MetalSharedBatchMotion.discardFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample recovered = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(recovered);
        assertEquals(first.generation(), recovered.generation());
        assertTrue(recovered.hasPrevious());
    }

    @Test
    void successfulFrameWithoutFlameBreaksContinuity() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample returned = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(returned);
        assertNotEquals(first.generation(), returned.generation());
        assertFalse(returned.hasPrevious());
    }

    @Test
    void secondSharedFlameBatchOrInvalidMemberFailsClosed() {
        MetalSharedBatchMotion.beginFrame();
        assertNotNull(MetalSharedBatchMotion.beginFlameBatch(List.of(A4)));
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(A4)));

        MetalSharedBatchMotion.reset();
        MetalSharedBatchMotion.beginFrame();
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(
                new MetalSharedBatchMotion.Member(10L, -1L, 4)
        )));
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(
                new MetalSharedBatchMotion.Member(10L, 1L, 0)
        )));
    }
}
