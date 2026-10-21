package com.metallum.client.metal.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalSyntheticExactMotionTest {
    @BeforeEach
    void enableCapture() { MetalEntityMotionCapture.setEnabled(true); }

    @AfterEach
    void reset() {
        MetalSyntheticExactMotion.reset();
        MetalEntityMotionCapture.beginFrame();
        MetalEntityMotionCapture.setEnabled(false);
    }

    @Test
    void firstPersonHistoryAdvancesOnlyAfterSubmittedSourceFrame() {
        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample first =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        assertNotNull(first);
        assertFalse(first.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample second =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, ItemStack.EMPTY.copy());
        assertNotNull(second);
        assertTrue(second.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.discardFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample afterDiscard =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, ItemStack.EMPTY.copy());
        assertNotNull(afterDiscard);
        assertTrue(afterDiscard.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
    }

    @Test
    void submittedFrameWithoutHandBreaksContinuity() {
        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        assertNotNull(MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND, ItemStack.EMPTY));
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample returned =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND, ItemStack.EMPTY.copy());
        assertNotNull(returned);
        assertFalse(returned.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
    }

    @Test
    void matchedHandVerticesDoNotProveTheIndependentHudProjection() {
        // GameRenderer.render3dHud uses hudFov and its own model-view stack;
        // a camera-locked point has zero motion even when the world camera turns.
        var point = new Vector4f(.2F, .1F, -1, 1);
        var hud = new Matrix4f().perspective((float)Math.toRadians(90), 1.5F, .05F, 100);
        var world = new Matrix4f().perspective((float)Math.toRadians(70), 1.5F, .05F, 100).rotateY(.1F);
        var handClip = new Vector4f(point).mul(hud);
        var wrongClip = new Vector4f(point).mul(world);
        assertTrue(Math.abs(handClip.x / handClip.w - wrongClip.x / wrongClip.w) * 640 > 1,
                "World matrices would invent more than one pixel of hand motion");

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();
        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        var matched = MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        assertNotNull(matched);
        assertTrue(matched.hasPrevious(), "Geometry continuity is retained independently");
        assertFalse(MetalExactMotionCoverage.complete(), "Unproven projection must not admit interpolation");
        assertEquals(null, MetalExactMotionCoverage.firstFailureReason(),
                "A candidate is no longer unconditionally failed; actual per-draw proof is still required");
        MetalSyntheticExactMotion.endFirstPerson();
    }
}
