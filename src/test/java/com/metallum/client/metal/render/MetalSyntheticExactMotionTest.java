package com.metallum.client.metal.render;

import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalSyntheticExactMotionTest {
    @AfterEach
    void reset() {
        MetalSyntheticExactMotion.reset();
        MetalEntityMotionCapture.beginFrame();
    }

    @Test
    void firstPersonHistoryAdvancesOnlyAfterSubmittedSourceFrame() {
        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample first =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND);
        assertNotNull(first);
        assertFalse(first.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample second =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND);
        assertNotNull(second);
        assertTrue(second.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.discardFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample afterDiscard =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND);
        assertNotNull(afterDiscard);
        assertTrue(afterDiscard.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
    }

    @Test
    void submittedFrameWithoutHandBreaksContinuity() {
        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        assertNotNull(MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND));
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample returned =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND);
        assertNotNull(returned);
        assertFalse(returned.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
    }
}
