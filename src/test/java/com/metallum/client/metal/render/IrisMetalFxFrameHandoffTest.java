package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalFxFrameHandoffTest {
    private static final FrameSynthesisContract.FrameStamp FRAME =
            new FrameSynthesisContract.FrameStamp(7L, 3L);
    private static final FrameSynthesisContract.DepthConvention DEPTH =
            FrameSynthesisContract.DepthConvention.FORWARD_ZERO_TO_ONE;

    @BeforeEach
    @AfterEach
    void reset() {
        IrisMetalFxFrameHandoff.resetForTests();
    }

    @Test
    void irisFrameRequiresFinalAndColorSpaceBeforeMetalFxConsumption() {
        IrisMetalFxFrameHandoff.beginFrame(4, FRAME, 1144, 642, DEPTH);

        assertFalse(IrisMetalFxFrameHandoff.admit(4, FRAME, 1144, 642, DEPTH).accepted());
        IrisMetalFxFrameHandoff.recordFinal(4, true);
        assertFalse(IrisMetalFxFrameHandoff.admit(4, FRAME, 1144, 642, DEPTH).accepted());
        IrisMetalFxFrameHandoff.recordColorSpace(4);

        IrisMetalFxFrameHandoff.Admission accepted =
                IrisMetalFxFrameHandoff.admit(4, FRAME, 1144, 642, DEPTH);
        assertTrue(accepted.irisActive());
        assertTrue(accepted.accepted());
        assertEquals(4, accepted.receipt().irisGeneration());
    }

    @Test
    void staleIdentityDimensionsAndFailedFinalRejectOneFrame() {
        IrisMetalFxFrameHandoff.beginFrame(4, FRAME, 1144, 642, DEPTH);
        IrisMetalFxFrameHandoff.recordFinal(4, true);
        IrisMetalFxFrameHandoff.recordColorSpace(4);

        assertFalse(IrisMetalFxFrameHandoff.admit(
                4,
                new FrameSynthesisContract.FrameStamp(8L, 3L),
                1144,
                642,
                DEPTH
        ).accepted());
        assertFalse(IrisMetalFxFrameHandoff.admit(5, FRAME, 1144, 642, DEPTH).accepted());
        assertFalse(IrisMetalFxFrameHandoff.admit(4, FRAME, 1145, 642, DEPTH).accepted());
        assertFalse(IrisMetalFxFrameHandoff.admit(
                4,
                FRAME,
                1144,
                642,
                FrameSynthesisContract.DepthConvention.REVERSED_ZERO_TO_ONE
        ).accepted());

        IrisMetalFxFrameHandoff.beginFrame(4, FRAME, 1144, 642, DEPTH);
        IrisMetalFxFrameHandoff.recordFinal(4, false);
        IrisMetalFxFrameHandoff.recordColorSpace(4);
        assertFalse(IrisMetalFxFrameHandoff.admit(4, FRAME, 1144, 642, DEPTH).accepted());
    }

    @Test
    void historyInvalidationRejectsReceiptButInactiveIrisDoesNotGateMetalFx() {
        IrisMetalFxFrameHandoff.beginFrame(4, FRAME, 1144, 642, DEPTH);
        IrisMetalFxFrameHandoff.recordFinal(4, true);
        IrisMetalFxFrameHandoff.recordColorSpace(4);
        IrisMetalFxFrameHandoff.invalidateCurrent("resize");
        assertFalse(IrisMetalFxFrameHandoff.admit(4, FRAME, 1144, 642, DEPTH).accepted());

        IrisMetalFxFrameHandoff.Admission inactive =
                IrisMetalFxFrameHandoff.admit(0, null, 1144, 642, DEPTH);
        assertFalse(inactive.irisActive());
        assertTrue(inactive.accepted());
    }
}
