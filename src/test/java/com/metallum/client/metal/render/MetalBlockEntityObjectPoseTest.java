package com.metallum.client.metal.render;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the piston root transform against {@code PistonHeadRenderer.submit}.
 *
 * <p>The assertions are on the delta the interpolator actually consumes,
 * {@code previous * inverse(current)}, not on the absolute matrices. That is the
 * only quantity a motion vector is built from, and it is where the mistakes show:
 * an offset applied to the wrong one of a piston's two blocks produces a matrix that
 * looks reasonable in isolation and a delta that drags a stationary block across the
 * screen.</p>
 */
final class MetalBlockEntityObjectPoseTest {
    /**
     * Stands in for {@code PistonHeadRenderState}, which cannot be constructed here:
     * it initialises a field from {@code BlockEntityTypes} and that needs a registry.
     */
    private record Piston(BlockPos blockPos, float xOffset, float yOffset, float zOffset) {
        Matrix4f pose(final MetalBlockEntityObjectPose.PistonPart part) {
            return MetalBlockEntityObjectPose.piston(new Matrix4f(),
                    blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                    xOffset, yOffset, zOffset, part);
        }
    }

    private static Piston state(final BlockPos blockPos, final float x, final float y, final float z) {
        return new Piston(blockPos, x, y, z);
    }

    private static Matrix4f delta(
            final Piston previous,
            final Piston current,
            final MetalBlockEntityObjectPose.PistonPart part
    ) {
        // Mirrors what the capture layer hands the shader.
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(
                MetalBlockEntityObjectPose.objectId(current.blockPos(), part),
                1L,
                current.pose(part),
                previous.pose(part)
        );
        return MetalEntityMotionCapture.objectCurrentToPrevious(sample);
    }

    @Test
    void theMovedBlockSitsAtItsBlockPositionPlusTheInterpolatedOffset() {
        Matrix4f pose = state(new BlockPos(10, 64, -3), 0.25F, 0.0F, 0.0F)
                .pose(MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK);

        assertEquals(new Matrix4f().translate(10.25F, 64.0F, -3.0F), pose,
                "LevelRenderer places a block entity at its own block position and"
                        + " PistonHeadRenderer.submit adds the offset on top");
    }

    @Test
    void theBaseIgnoresTheOffsetBecauseSubmitPopsItFirst() {
        Piston extending = state(new BlockPos(10, 64, -3), 0.75F, 0.0F, 0.0F);
        Matrix4f base = extending.pose(MetalBlockEntityObjectPose.PistonPart.BASE);

        assertEquals(new Matrix4f().translate(10.0F, 64.0F, -3.0F), base,
                "PistonHeadRenderer.submit pops the offset translation before submitting the base");
        assertNotEquals(extending.pose(MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK),
                base,
                "while the piston is mid-travel the two parts must not share a transform");
    }

    @Test
    void theMovedBlockDeltaIsExactlyTheChangeInOffset() {
        BlockPos pos = new BlockPos(-40, 12, 300);
        Matrix4f motion = delta(state(pos, 0.25F, 0.0F, 0.0F), state(pos, 0.5F, 0.0F, 0.0F),
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK);

        // previous * inverse(current) for two pure translations is the difference,
        // and the block position cancels — which is why an absolute-space error in
        // the position would not corrupt the motion vector, only a wrong offset would.
        assertEquals(new Matrix4f().translate(-0.25F, 0.0F, 0.0F), motion,
                "the head retreated 0.25 blocks between frames, so history lies 0.25 back along x");
    }

    @Test
    void aStationaryBaseGetsNoMotionEvenWhileTheHeadTravels() {
        BlockPos pos = new BlockPos(7, 70, 7);
        Piston previous = state(pos, 0.0F, 0.25F, 0.0F);
        Piston current = state(pos, 0.0F, 0.75F, 0.0F);

        assertEquals(new Matrix4f(), delta(previous, current, MetalBlockEntityObjectPose.PistonPart.BASE),
                "the piston body never moves, so its delta must be identity; anything else makes the"
                        + " interpolator warp a static block");
        assertNotEquals(new Matrix4f(), delta(previous, current,
                        MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK),
                "the head did move, so this test would also pass if piston() ignored the offset entirely");
    }

    @Test
    void anIdleHeadAlsoGetsNoMotion() {
        BlockPos pos = new BlockPos(0, 0, 0);
        assertEquals(new Matrix4f(),
                delta(state(pos, 0.5F, 0.0F, 0.0F), state(pos, 0.5F, 0.0F, 0.0F),
                        MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK),
                "a piston paused mid-extension must not be given velocity");
    }

    @Test
    void objectIdIsStableAcrossFramesAndSeparatesTheTwoParts() {
        BlockPos pos = new BlockPos(3, 44, 5);

        // BlockEntityRenderDispatcher builds a fresh render state every frame, so the
        // id must not depend on any instance.
        assertEquals(MetalBlockEntityObjectPose.objectId(pos, MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK),
                MetalBlockEntityObjectPose.objectId(new BlockPos(3, 44, 5),
                        MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK),
                "the same position must key the same history in the next frame");
        assertNotEquals(MetalBlockEntityObjectPose.objectId(pos, MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK),
                MetalBlockEntityObjectPose.objectId(pos, MetalBlockEntityObjectPose.PistonPart.BASE),
                "one piston owns two moving blocks; sharing a key would let the body inherit the head's"
                        + " previous transform");
    }

    @Test
    void neighbouringPositionsDoNotCollideOrCluster() {
        Set<Long> ids = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (MetalBlockEntityObjectPose.PistonPart part : MetalBlockEntityObjectPose.PistonPart.values()) {
                        assertTrue(ids.add(MetalBlockEntityObjectPose.objectId(new BlockPos(x, y, z), part)),
                                "collision at " + x + "," + y + "," + z + " " + part);
                    }
                }
            }
        }

        // A run of adjacent positions must not produce a run of adjacent ids: this id
        // shares a key space with UUID-derived entity ids, and a clustered region
        // raises the chance of meeting one of them.
        long first = MetalBlockEntityObjectPose.objectId(new BlockPos(0, 0, 0),
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK);
        long second = MetalBlockEntityObjectPose.objectId(new BlockPos(0, 0, 1),
                MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK);
        assertTrue(Math.abs(first - second) > 1_000_000L,
                "adjacent block positions produced adjacent ids, so the mix is not avalanching: "
                        + first + " vs " + second);
    }

    @Test
    void aNonFiniteOffsetProducesATransformTheStoreRejects() {
        Matrix4f poisoned = state(new BlockPos(1, 2, 3), Float.NaN, 0.0F, 0.0F)
                .pose(MetalBlockEntityObjectPose.PistonPart.MOVED_BLOCK);

        // piston() does not filter; MetalMotionStateStore.observe drops non-finite
        // transforms, and this asserts the poison is visible to that guard rather than
        // hidden behind a silently substituted identity.
        assertFalse(MetalFxMath.isFinite(poisoned),
                "a NaN offset must remain detectable, so the store declines to record it and the"
                        + " previous transform survives");
    }
}
