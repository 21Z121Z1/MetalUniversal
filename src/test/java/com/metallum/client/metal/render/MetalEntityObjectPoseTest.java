package com.metallum.client.metal.render;

import com.mojang.math.Transformation;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
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

    private static DisplayEntityRenderState displayState(
            final Display.BillboardConstraints billboard,
            final float entityYRot,
            final float entityXRot,
            final Transformation transformation
    ) {
        BlockDisplayEntityRenderState state = new BlockDisplayEntityRenderState();
        state.x = 8.0;
        state.y = 70.0;
        state.z = -4.0;
        state.renderState = new Display.RenderState(
                Display.GenericInterpolator.constant(transformation),
                billboard,
                -1,
                Display.FloatInterpolator.constant(0.0F),
                Display.FloatInterpolator.constant(0.0F),
                0
        );
        state.interpolationProgress = 1.0F;
        state.entityYRot = entityYRot;
        state.entityXRot = entityXRot;
        state.cameraYRot = 25.0F;
        state.cameraXRot = -10.0F;
        return state;
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

    @Test
    void displayRootIncludesRendererBillboardRotationAndInterpolatedScale() {
        Transformation previousTransform = new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf()
        );
        Transformation currentTransform = new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(2.0F, 1.5F, 0.75F), new Quaternionf()
        );
        Matrix4f previous = MetalEntityObjectPose.compose(displayState(
                Display.BillboardConstraints.FIXED, 0.0F, 0.0F, previousTransform
        ));
        Matrix4f current = MetalEntityObjectPose.compose(displayState(
                Display.BillboardConstraints.FIXED, 90.0F, 0.0F, currentTransform
        ));

        assertFalse(previous.equals(current, EPSILON), "display rotation and scale must reach the root pose");
        assertEquals(2.0F, new Vector3f(current.m00(), current.m01(), current.m02()).length(), EPSILON);
        assertTrue(delta(previous, current).transformPosition(new Vector3f(1.0F, 0.0F, 0.0F))
                        .distance(new Vector3f(1.0F, 0.0F, 0.0F)) > EPSILON,
                "a display turn must produce nonzero object motion");
    }

    @Test
    void displayBillboardConstraintsFollowMinecraftsRendererOrder() {
        Transformation identity = Transformation.IDENTITY;
        Matrix4f horizontal = MetalEntityObjectPose.compose(displayState(
                Display.BillboardConstraints.HORIZONTAL, 35.0F, 0.0F, identity
        ));
        Matrix4f vertical = MetalEntityObjectPose.compose(displayState(
                Display.BillboardConstraints.VERTICAL, 35.0F, 0.0F, identity
        ));

        assertFalse(horizontal.equals(vertical, EPSILON),
                "horizontal and vertical billboards must use their distinct vanilla branches");
    }

    @Test
    void itemFrameBaseAndContentHaveIndependentRendererRoots() {
        Matrix4f base = MetalEntityObjectPose.itemFrameFrame(
                new Matrix4f(), 4.0, 65.0, 9.0, Direction.NORTH
        );
        Matrix4f content = MetalEntityObjectPose.itemFrameContent(
                new Matrix4f(), 4.0, 65.0, 9.0, Direction.NORTH, 1, true, false
        );

        assertFalse(base.equals(content, EPSILON));
        Matrix4f rotatedContent = MetalEntityObjectPose.itemFrameContent(
                new Matrix4f(), 4.0, 65.0, 9.0, Direction.NORTH, 2, true, false
        );
        assertFalse(content.equals(rotatedContent, EPSILON),
                "map rotation must be part of the content root");
    }

    @Test
    void paintingArmorStandAndCrystalRootsPreserveRendererLocalTransforms() {
        Matrix4f paintingNorth = MetalEntityObjectPose.painting(
                new Matrix4f(), 0.0, 64.0, 0.0, Direction.NORTH
        );
        Matrix4f paintingEast = MetalEntityObjectPose.painting(
                new Matrix4f(), 0.0, 64.0, 0.0, Direction.EAST
        );
        assertFalse(paintingNorth.equals(paintingEast, EPSILON));

        Matrix4f armorSmall = MetalEntityObjectPose.armorStand(
                new Matrix4f(), 0.0, 64.0, 0.0, 0.5F, 0.0F, 6.0F
        );
        Matrix4f armorTurned = MetalEntityObjectPose.armorStand(
                new Matrix4f(), 0.0, 64.0, 0.0, 1.0F, 90.0F, 6.0F
        );
        assertFalse(armorSmall.equals(armorTurned, EPSILON));

        Matrix4f crystalPrevious = MetalEntityObjectPose.endCrystal(
                new Matrix4f(), 2.0, 70.0, -3.0
        );
        Matrix4f crystalCurrent = MetalEntityObjectPose.endCrystal(
                new Matrix4f(), 2.0, 70.5, -3.0
        );
        assertTrue(delta(crystalPrevious, crystalCurrent).transformPosition(
                        new Vector3f(2.0F, 69.5F, -3.0F)
                ).distance(new Vector3f(2.0F, 69.5F, -3.0F)) > EPSILON,
                "crystal root translation must reach the motion delta");
    }
}
