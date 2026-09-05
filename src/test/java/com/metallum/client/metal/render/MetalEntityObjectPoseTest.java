package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the object-pose kernels through the property the motion pass
 * actually consumes: the frame-to-frame delta {@code previous *
 * inverse(current)}. The individual matrices are only a means to that delta, so
 * the expectations below are geometric — which world point maps to which — and
 * are derived from the renderer's transform order rather than read back out of
 * the implementation.
 */
final class MetalEntityObjectPoseTest {
    private static final float EPSILON = 1.0E-4F;
    private static final float QUARTER_TURN_TICKS = (float) (Math.PI / 2.0) * 20.0F;

    private static Matrix4f delta(final Matrix4f previous, final Matrix4f current) {
        return new Matrix4f(previous).mul(new Matrix4f(current).invert());
    }

    private static void assertPoint(
            final Vector3f actual,
            final float expectedX,
            final float expectedY,
            final float expectedZ
    ) {
        assertEquals(expectedX, actual.x, EPSILON, "x of " + actual);
        assertEquals(expectedY, actual.y, EPSILON, "y of " + actual);
        assertEquals(expectedZ, actual.z, EPSILON, "z of " + actual);
    }

    private static Vector3f map(final Matrix4f transform, final float x, final float y, final float z) {
        return transform.transformPosition(new Vector3f(x, y, z));
    }

    @Test
    void restingObjectsProduceNoMotion() {
        Matrix4f frame = MetalEntityObjectPose.droppedItem(
                new Matrix4f(), 12.0, 65.0, -40.0, 100.0F, 0.5F
        );
        Matrix4f identity = delta(frame, new Matrix4f(frame));
        assertTrue(identity.equals(new Matrix4f(), EPSILON), "expected identity, got " + identity);
    }

    @Test
    void displayPoseMatchesVanillaTransformOrder() {
        Quaternionf billboard = new Quaternionf().rotationY((float) (Math.PI / 2.0));
        Matrix4f interpolatedTransformation = new Matrix4f().translation(1.0F, 0.0F, 0.0F);
        Matrix4f display = MetalEntityObjectPose.display(
                new Matrix4f(),
                10.0,
                20.0,
                30.0,
                billboard,
                interpolatedTransformation
        );

        // DisplayRenderer.submit first receives the entity translation from the
        // dispatcher, then applies billboard orientation, then the interpolated
        // Transformation. A local +X translation is therefore rotated into -Z.
        assertPoint(map(display, 0.0F, 0.0F, 0.0F), 10.0F, 20.0F, 29.0F);
        assertPoint(map(display, 0.0F, 0.0F, 1.0F), 11.0F, 20.0F, 29.0F);
    }

    @Test
    void displayCameraFacingAnglesMatchRendererHelpers() {
        assertEquals(-35.0F, MetalEntityObjectPose.transformDisplayXRot(35.0F), EPSILON);
        assertEquals(20.0F, MetalEntityObjectPose.transformDisplayYRot(200.0F), EPSILON);
    }

    @Test
    void droppedItemDeltaPivotsAboutTheItemsOwnAxis() {
        // A stationary item whose spin phase advances by exactly a quarter turn.
        // The hover bob still differs between the two frames, so the delta is the
        // quarter turn composed with that residual Y shift.
        float previousAge = 40.0F;
        float currentAge = previousAge + QUARTER_TURN_TICKS;
        float bobPrevious = MetalEntityObjectPose.itemBob(previousAge, 0.0F);
        float bobCurrent = MetalEntityObjectPose.itemBob(currentAge, 0.0F);
        Matrix4f objectDelta = delta(
                MetalEntityObjectPose.droppedItem(new Matrix4f(), 3.0, 70.0, 8.0, previousAge, 0.0F),
                MetalEntityObjectPose.droppedItem(new Matrix4f(), 3.0, 70.0, 8.0, currentAge, 0.0F)
        );

        // The item's own axis is a fixed point apart from the bob difference.
        assertPoint(map(objectDelta, 3.0F, 70.0F + bobCurrent, 8.0F), 3.0F, 70.0F + bobPrevious, 8.0F);
        // Off-axis geometry rotates back by a quarter turn about +Y, which sends
        // the +X offset to +Z.
        assertPoint(map(objectDelta, 4.0F, 70.0F + bobCurrent, 8.0F), 3.0F, 70.0F + bobPrevious, 9.0F);
    }

    @Test
    void droppedItemDeltaIsUnaffectedByTheConstantModelLift() {
        // MetalEntityObjectPose deliberately omits ItemEntityRenderer's
        // -boundingBox.minY + 1/16 lift. That term sits between the world
        // translation and the Y spin, where Y translation and Y rotation commute,
        // so it factors out to the right of the pose and cancels in the delta.
        Matrix4f lift = new Matrix4f().translation(0.0F, 0.3125F, 0.0F);
        Matrix4f previous = MetalEntityObjectPose.droppedItem(
                new Matrix4f(), -22.0, 71.0, 5.0, 30.0F, 1.25F
        );
        Matrix4f current = MetalEntityObjectPose.droppedItem(
                new Matrix4f(), -22.0, 71.2, 5.0, 31.5F, 1.25F
        );
        Matrix4f withoutLift = delta(previous, current);
        Matrix4f withLift = delta(
                new Matrix4f(previous).mul(lift),
                new Matrix4f(current).mul(lift)
        );
        assertTrue(withoutLift.equals(withLift, EPSILON),
                "the omitted lift changed the delta: " + withoutLift + " vs " + withLift);
    }

    @Test
    void turningBoatKeepsItsHullAxisFixed() {
        Matrix4f objectDelta = delta(
                MetalEntityObjectPose.boat(new Matrix4f(), 100.0, 62.0, 100.0, 0.0F, 0.0F, 0.0F, 0, 0.0F, false),
                MetalEntityObjectPose.boat(new Matrix4f(), 100.0, 62.0, 100.0, 90.0F, 0.0F, 0.0F, 0, 0.0F, false)
        );
        // Both frames lift the hull by 0.375 before yawing, so that point is on
        // the rotation axis and must not move.
        assertPoint(map(objectDelta, 100.0F, 62.375F, 100.0F), 100.0F, 62.375F, 100.0F);
        // yRot 0 -> 90 is a 90 degree screen-space turn (180 - yRot), so the bow
        // one block along +X maps back to -Z. A boat that turns in place must not
        // report the identity, or its silhouette inherits camera motion.
        assertPoint(map(objectDelta, 101.0F, 62.375F, 100.0F), 100.0F, 62.375F, 99.0F);
    }

    @Test
    void arrowDeltaFollowsThePoseItWasDrawnWith() {
        Matrix4f previous = MetalEntityObjectPose.arrow(new Matrix4f(), 0.0, 64.0, 0.0, 90.0F, 0.0F);
        Matrix4f translated = delta(
                previous,
                MetalEntityObjectPose.arrow(new Matrix4f(), 1.0, 64.0, 0.0, 90.0F, 0.0F)
        );
        // Same orientation, one block along +X: the rotation cancels and every
        // point shifts back by exactly that block.
        assertPoint(map(translated, 1.0F, 64.0F, 0.0F), 0.0F, 64.0F, 0.0F);
        assertPoint(map(translated, 1.5F, 64.5F, 0.25F), 0.5F, 64.5F, 0.25F);

        // A yaw-only change pivots about the arrow's own origin.
        Matrix4f yawed = delta(
                previous,
                MetalEntityObjectPose.arrow(new Matrix4f(), 0.0, 64.0, 0.0, 180.0F, 0.0F)
        );
        assertPoint(map(yawed, 0.0F, 64.0F, 0.0F), 0.0F, 64.0F, 0.0F);
    }

    @Test
    void newRenderMinecartPivotsAtItsInterpolatedPosition() {
        // AbstractMinecartRenderer.getRenderOffset draws a lerping cart at
        // renderPos, not at the entity position. Composing the pose at the entity
        // position instead reports a translation the cart never made, which is
        // exactly the error this asserts against.
        Matrix4f objectDelta = delta(
                MetalEntityObjectPose.minecartNewRender(
                        new Matrix4f(), 8.5, 70.0, -3.5, 45.0F, 0.0F, 0.0F, 0.0F, 0
                ),
                MetalEntityObjectPose.minecartNewRender(
                        new Matrix4f(), 8.0, 70.0, -3.0, 45.0F, 0.0F, 0.0F, 0.0F, 0
                )
        );
        assertPoint(map(objectDelta, 8.0F, 70.0F, -3.0F), 8.5F, 70.0F, -3.5F);
    }

    @Test
    void minecartRenderVariantsDifferInLiftOrderAndYawConvention() {
        // newRender orients then lifts and uses yRot directly; oldRender lifts
        // then orients and uses 180 - yRot. Getting either wrong drags the hull
        // along an arc it was never drawn on.
        Matrix4f newRender = MetalEntityObjectPose.minecartNewRender(
                new Matrix4f(), 0.0, 0.0, 0.0, 45.0F, 0.0F, 0.0F, 0.0F, 0
        );
        Matrix4f oldRender = MetalEntityObjectPose.minecartOldRender(
                new Matrix4f(), 0.0, 0.0, 0.0, 45.0F, 0.0F, 0.0F, 0.0F, 0
        );
        // The lift is along +Y in both orders, so the model origin agrees.
        assertPoint(map(newRender, 0.0F, 0.0F, 0.0F), 0.0F, 0.375F, 0.0F);
        assertPoint(map(oldRender, 0.0F, 0.0F, 0.0F), 0.0F, 0.375F, 0.0F);
        // Off-axis they diverge: +45 degrees against +135 degrees.
        float diagonal = (float) (Math.sqrt(2.0) / 2.0);
        assertPoint(map(newRender, 1.0F, 0.0F, 0.0F), diagonal, 0.375F, -diagonal);
        assertPoint(map(oldRender, 1.0F, 0.0F, 0.0F), -diagonal, 0.375F, -diagonal);
    }

    @Test
    void hurtShakeIsOnlyAppliedWhileTheHurtTimerRuns() {
        Matrix4f calm = MetalEntityObjectPose.boat(
                new Matrix4f(), 0.0, 0.0, 0.0, 0.0F, 0.0F, 5.0F, 1, 0.0F, false
        );
        Matrix4f expired = MetalEntityObjectPose.boat(
                new Matrix4f(), 0.0, 0.0, 0.0, 0.0F, -1.0F, 5.0F, 1, 0.0F, false
        );
        Matrix4f shaken = MetalEntityObjectPose.boat(
                new Matrix4f(), 0.0, 0.0, 0.0, 0.0F, 4.0F, 5.0F, 1, 0.0F, false
        );
        assertTrue(calm.equals(expired, EPSILON), "a non-positive hurt timer must not rotate the hull");
        assertFalse(calm.equals(shaken, EPSILON), "a running hurt timer must rotate the hull");
    }

    @Test
    void livingRotationMatchesTheRendererSign() {
        // LivingEntityRenderer.setupRotations uses 180 - bodyRot, so a mob whose
        // bodyRot goes 0 -> 90 turns by -90 degrees on screen. Its nose sits at
        // -X in the previous frame and at -Z in the current one; the delta has to
        // undo that turn rather than double it.
        Matrix4f objectDelta = delta(
                MetalEntityObjectPose.living(new Matrix4f(), 0.0, 0.0, 0.0, 0.0F),
                MetalEntityObjectPose.living(new Matrix4f(), 0.0, 0.0, 0.0, 90.0F)
        );
        assertPoint(map(objectDelta, 0.0F, 0.0F, -1.0F), -1.0F, 0.0F, 0.0F);
        assertPoint(map(objectDelta, 0.0F, 0.0F, 0.0F), 0.0F, 0.0F, 0.0F);
    }
}
