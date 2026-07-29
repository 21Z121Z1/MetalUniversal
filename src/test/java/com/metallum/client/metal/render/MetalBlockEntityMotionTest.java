package com.metallum.client.metal.render;

import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalBlockEntityMotionTest {
    private static final float EPSILON = 1.0E-6F;

    @AfterEach
    void clearCapture() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
    }

    @Test
    void aStationaryBlockEntityHasNoValidObjectMotion() {
        BlockPos position = new BlockPos(4, 65, -9);
        Matrix4f pose = MetalBlockEntityObjectPose.generic(new Matrix4f(), position);
        MetalEntityMotionCapture.Sample sample = sample(
                MetalBlockEntityObjectPose.blockEntityObjectId(position),
                pose,
                pose,
                MetalEntityMotionCapture.Source.BLOCK_ENTITY
        );

        assertTrue(sample.hasPrevious());
        assertFalse(sample.hasObjectMotion(),
                "a static block entity must not replay camera-only/zero motion as valid object motion");
        assertMatrixEquals(new Matrix4f(), MetalEntityMotionCapture.objectCurrentToPrevious(sample));
    }

    @Test
    void aTranslatedBlockEntityProducesTheCurrentToPreviousDelta() {
        BlockPos previousPosition = new BlockPos(4, 65, -9);
        BlockPos currentPosition = new BlockPos(5, 65, -9);
        Matrix4f previous = MetalBlockEntityObjectPose.generic(new Matrix4f(), previousPosition);
        Matrix4f current = MetalBlockEntityObjectPose.generic(new Matrix4f(), currentPosition);
        MetalEntityMotionCapture.Sample sample = sample(
                MetalBlockEntityObjectPose.blockEntityObjectId(currentPosition),
                current,
                previous,
                MetalEntityMotionCapture.Source.BLOCK_ENTITY
        );

        assertTrue(sample.hasObjectMotion());
        assertMatrixEquals(new Matrix4f().translate(-1.0F, 0.0F, 0.0F),
                MetalEntityMotionCapture.objectCurrentToPrevious(sample));
    }

    @Test
    void pistonExtensionInterpolatesTheMovedBlockInEveryDirection() {
        float[][] directions = {
                {1.0F, 0.0F, 0.0F},
                {-1.0F, 0.0F, 0.0F},
                {0.0F, 1.0F, 0.0F},
                {0.0F, -1.0F, 0.0F},
                {0.0F, 0.0F, 1.0F},
                {0.0F, 0.0F, -1.0F}
        };
        BlockPos pistonPosition = new BlockPos(31, 70, -17);
        for (float[] direction : directions) {
            Matrix4f previous = MetalBlockEntityObjectPose.piston(
                    new Matrix4f(), pistonPosition.getX(), pistonPosition.getY(), pistonPosition.getZ(),
                    direction[0] * 0.25F, direction[1] * 0.25F, direction[2] * 0.25F,
                    MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
            );
            Matrix4f current = MetalBlockEntityObjectPose.piston(
                    new Matrix4f(), pistonPosition.getX(), pistonPosition.getY(), pistonPosition.getZ(),
                    direction[0] * 0.75F, direction[1] * 0.75F, direction[2] * 0.75F,
                    MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
            );
            MetalEntityMotionCapture.Sample sample = sample(
                    MetalBlockEntityObjectPose.objectId(
                            pistonPosition, MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
                    ),
                    current,
                    previous,
                    MetalEntityMotionCapture.Source.PISTON_MOVED_BLOCK
            );

            assertTrue(sample.hasObjectMotion(), "direction was " + direction[0] + ","
                    + direction[1] + "," + direction[2]);
            assertMatrixEquals(new Matrix4f().translate(
                            direction[0] * -0.5F,
                            direction[1] * -0.5F,
                            direction[2] * -0.5F
                    ),
                    MetalEntityMotionCapture.objectCurrentToPrevious(sample),
                    "extension delta was wrong for direction " + direction[0] + ","
                            + direction[1] + "," + direction[2]);
        }
    }

    @Test
    void pistonRetractionReversesProgressAndTheBaseStaysStationary() {
        BlockPos pistonPosition = new BlockPos(-12, 64, 19);
        float directionX = 1.0F;
        Matrix4f previousMoved = MetalBlockEntityObjectPose.piston(
                new Matrix4f(), pistonPosition.getX(), pistonPosition.getY(), pistonPosition.getZ(),
                directionX * 0.75F, 0.0F, 0.0F,
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
        );
        Matrix4f currentMoved = MetalBlockEntityObjectPose.piston(
                new Matrix4f(), pistonPosition.getX(), pistonPosition.getY(), pistonPosition.getZ(),
                directionX * 0.25F, 0.0F, 0.0F,
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
        );
        MetalEntityMotionCapture.Sample moved = sample(
                MetalBlockEntityObjectPose.objectId(
                        pistonPosition, MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
                ),
                currentMoved,
                previousMoved,
                MetalEntityMotionCapture.Source.PISTON_MOVED_BLOCK
        );
        MetalEntityMotionCapture.Sample base = sample(
                MetalBlockEntityObjectPose.objectId(pistonPosition, MetalBlockEntityObjectPose.PistonPart.BASE),
                MetalBlockEntityObjectPose.piston(
                        new Matrix4f(), pistonPosition.getX(), pistonPosition.getY(), pistonPosition.getZ(),
                        0.75F, 0.0F, 0.0F, MetalBlockEntityObjectPose.PistonPart.BASE
                ),
                MetalBlockEntityObjectPose.piston(
                        new Matrix4f(), pistonPosition.getX(), pistonPosition.getY(), pistonPosition.getZ(),
                        0.25F, 0.0F, 0.0F, MetalBlockEntityObjectPose.PistonPart.BASE
                ),
                MetalEntityMotionCapture.Source.PISTON_BASE
        );

        assertMatrixEquals(new Matrix4f().translate(0.5F, 0.0F, 0.0F),
                MetalEntityMotionCapture.objectCurrentToPrevious(moved));
        assertFalse(base.hasObjectMotion());
        assertMatrixEquals(new Matrix4f(), MetalEntityMotionCapture.objectCurrentToPrevious(base));
    }

    @Test
    void movingBlockSamplingPositionMayCrossASectionWithoutChangingTheObjectRoot() {
        BlockPos pistonPosition = new BlockPos(16, 64, 16);
        BlockPos previousSamplingPosition = new BlockPos(15, 64, 16);
        BlockPos currentSamplingPosition = new BlockPos(16, 64, 16);
        Matrix4f previous = MetalBlockEntityObjectPose.piston(
                new Matrix4f(), pistonPosition, 0.25F, 0.0F, 0.0F,
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
        );
        Matrix4f current = MetalBlockEntityObjectPose.piston(
                new Matrix4f(), pistonPosition, 0.50F, 0.0F, 0.0F,
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
        );
        MetalEntityMotionCapture.Sample sample = sample(
                MetalBlockEntityObjectPose.objectId(
                        pistonPosition, MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK
                ),
                current,
                previous,
                MetalEntityMotionCapture.Source.PISTON_MOVED_BLOCK
        );

        assertNotEquals(previousSamplingPosition, currentSamplingPosition);
        assertMatrixEquals(new Matrix4f().translate(16.25F, 64.0F, 16.0F), previous);
        assertMatrixEquals(new Matrix4f().translate(16.50F, 64.0F, 16.0F), current);
        assertMatrixEquals(new Matrix4f().translate(-0.25F, 0.0F, 0.0F),
                MetalEntityMotionCapture.objectCurrentToPrevious(sample));
    }

    @Test
    void aBlockPositionChangeUsesAFreshHistoryKey() {
        MetalMotionStateStore store = new MetalMotionStateStore();
        BlockPos beforeSection = new BlockPos(15, 64, 15);
        BlockPos afterSection = new BlockPos(16, 64, 15);
        MetalMotionStateStore.ObjectKey oldKey = new MetalMotionStateStore.ObjectKey(
                MetalBlockEntityObjectPose.blockEntityObjectId(beforeSection), 1L
        );
        MetalMotionStateStore.ObjectKey newKey = new MetalMotionStateStore.ObjectKey(
                MetalBlockEntityObjectPose.blockEntityObjectId(afterSection), 1L
        );

        store.beginFrame();
        store.observe(oldKey, MetalBlockEntityObjectPose.generic(new Matrix4f(), beforeSection));
        store.commitSubmittedFrame();
        store.beginFrame();
        Matrix4f current = MetalBlockEntityObjectPose.generic(new Matrix4f(), afterSection);
        store.observe(newKey, current);

        assertNotEquals(oldKey, newKey);
        assertNull(store.previousIfContinuous(newKey, current, 2.0F),
                "cross-section block-entity positions must not inherit another object's history");
    }

    @Test
    void disappearanceDropsHistoryBeforeAReappearance() {
        MetalMotionStateStore store = new MetalMotionStateStore();
        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(42L, 1L);
        Matrix4f pose = new Matrix4f().translate(2.0F, 3.0F, 4.0F);

        store.beginFrame();
        store.observe(key, pose);
        store.commitSubmittedFrame();
        store.beginFrame();
        store.commitSubmittedFrame();
        store.beginFrame();
        store.observe(key, pose);

        assertNull(store.previousIfContinuous(key, pose, 2.0F),
                "a disappeared object must re-enter with missing history");
    }

    @Test
    void resetDropsHistoryAndAConditionalTeleportDoesNotWriteAHugeVelocity() {
        MetalMotionStateStore store = new MetalMotionStateStore();
        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(99L, 1L);
        Matrix4f first = new Matrix4f().translate(1.0F, 0.0F, 0.0F);
        Matrix4f teleported = new Matrix4f().translate(100.0F, 0.0F, 0.0F);

        store.beginFrame();
        store.observe(key, first);
        store.commitSubmittedFrame();
        store.reset();
        store.beginFrame();
        store.observe(key, teleported);
        assertNull(store.previousIfContinuous(key, teleported, 2.0F));
        store.commitSubmittedFrame();
        store.beginFrame();
        assertNotNull(store.previousIfContinuous(key, teleported, 2.0F));
    }

    @Test
    void blockEntitySubmissionWindowAttachesGenericAndPistonSources() {
        MetalEntityMotionCapture.beginFrame();
        Object genericState = new Object();
        Object genericSubmit = new Object();
        MetalEntityMotionCapture.attachBlockEntityState(
                genericState,
                sample(1L, new Matrix4f().translate(1.0F, 0.0F, 0.0F),
                        new Matrix4f(), MetalEntityMotionCapture.Source.BLOCK_ENTITY)
        );
        MetalEntityMotionCapture.beginBlockEntitySubmission(genericState);
        MetalEntityMotionCapture.captureModelSubmit(genericSubmit);
        MetalEntityMotionCapture.beginModelBuild(genericSubmit);
        MetalEntityMotionCapture.endModelBuild();
        MetalEntityMotionCapture.endBlockEntitySubmission();

        Object moved = new Object();
        Object base = new Object();
        Object pistonState = new Object();
        MetalEntityMotionCapture.attachPistonState(
                pistonState,
                moved,
                sample(2L, new Matrix4f().translate(1.5F, 0.0F, 0.0F), new Matrix4f(),
                        MetalEntityMotionCapture.Source.PISTON_MOVED_BLOCK),
                base,
                sample(3L, new Matrix4f(), new Matrix4f(), MetalEntityMotionCapture.Source.PISTON_BASE)
        );
        MetalEntityMotionCapture.beginBlockEntitySubmission(pistonState);
        MetalEntityMotionCapture.captureMovingBlockSubmit(moved);
        MetalEntityMotionCapture.endBlockEntitySubmission();

        MetalEntityMotionCapture.Diagnostics diagnostics = MetalEntityMotionCapture.diagnostics();
        assertEquals(2, diagnostics.blockEntityStatesAttached());
        assertEquals(2, diagnostics.blockEntitySubmissionsMatched());
        assertEquals(1, diagnostics.blockEntityModelSubmitsCaptured());
        assertEquals(1, diagnostics.blockEntityMovingSubmitsCaptured());
        assertEquals(2, diagnostics.modelSubmitsCaptured());
        assertEquals(1, diagnostics.modelBuildsMatched());
    }

    private static MetalEntityMotionCapture.Sample sample(
            final long objectId,
            final Matrix4f current,
            final Matrix4f previous,
            final MetalEntityMotionCapture.Source source
    ) {
        return new MetalEntityMotionCapture.Sample(objectId, 1L, current, previous, source);
    }

    private static void assertMatrixEquals(final Matrix4f expected, final Matrix4f actual) {
        assertMatrixEquals(expected, actual, "matrix mismatch");
    }

    private static void assertMatrixEquals(
            final Matrix4f expected,
            final Matrix4f actual,
            final String message
    ) {
        assertTrue(expected.equals(actual, EPSILON),
                message + ": expected " + expected + " but was " + actual);
    }
}
