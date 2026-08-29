package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxHistoryStampTest {
    @Test
    void commitRequiresExactFrameAndEpoch() {
        MetalFxHistoryStamp stamp = new MetalFxHistoryStamp(17L, 4L);
        assertTrue(stamp.canCommit(17L, 4L));
        assertFalse(stamp.canCommit(18L, 4L));
        assertFalse(stamp.canCommit(17L, 5L));
    }

    @Test
    void failureCanRejectAnySubmittedFrameInCurrentEpochButNotOldEpoch() {
        MetalFxHistoryStamp stamp = new MetalFxHistoryStamp(17L, 4L);
        assertTrue(stamp.canReject(4L));
        assertFalse(stamp.canReject(5L));
    }
}
