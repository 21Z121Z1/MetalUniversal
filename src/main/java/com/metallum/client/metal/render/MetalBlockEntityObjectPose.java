package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

/**
 * Rebuilds the root object-to-world transform a block entity renderer applies
 * before handing geometry to a feature renderer.
 *
 * <p>The block-entity counterpart of {@link MetalEntityObjectPose}, and it follows
 * the same rules: absolute world space, and constant factors left out because they
 * cancel in the {@code previous * inverse(current)} delta the interpolator
 * consumes. {@code LevelRenderer.submitBlockEntities} translates the pose stack by
 * {@code blockPos - cameraPos} before dispatching, so a block entity's geometry is
 * placed at its own block position and any transform a renderer adds sits on top of
 * that.</p>
 *
 * <p>Only the piston is modelled. It is the one block entity that emits
 * {@code core/block} geometry, through {@code submitMovingBlock}; every other block
 * entity renders entity-format models and belongs to the {@code ENTITY} family.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalBlockEntityObjectPose {
    private static final long BLOCK_ENTITY_SALT = 0xD6E8FEB86659FD93L;

    /**
     * Which of the two moving blocks a piston submits.
     *
     * <p>The distinction is not cosmetic. {@code PistonHeadRenderer.submit}
     * translates by the interpolated offset, submits the moved block, and then
     * <em>pops that translation</em> before submitting the base — so the moving head
     * travels and the piston body does not. Giving the body the head's transform
     * would drag a stationary block across the screen in every generated frame.</p>
     */
    enum PistonPart {
        /** {@code PistonHeadRenderState.block}: travels with the interpolated offset. */
        MOVED_BLOCK(0x9E3779B97F4A7C15L),
        /** {@code PistonHeadRenderState.base}: the piston body, which does not move. */
        BASE(0xC2B2AE3D27D4EB4FL);

        private final long salt;

        PistonPart(final long salt) {
            this.salt = salt;
        }

        long salt() {
            return salt;
        }
    }

    private MetalBlockEntityObjectPose() {
    }

    /**
     * {@code PistonHeadRenderer.submit}: the moved block is translated by the
     * progress-interpolated offset, the base is not. The world origin is the
     * dispatcher's {@code BlockEntityRenderState.blockPos}; the nested
     * {@code MovingBlockRenderState.blockPos} is only the block's sampling/seed
     * position used while tesselating its model.
     *
     * <p>Takes the fields rather than the render state so it can be exercised
     * directly. {@code PistonHeadRenderState} initialises a field from
     * {@code BlockEntityTypes}, which needs a registry, so one cannot be constructed
     * outside a running game.</p>
     */
    static Matrix4f piston(
            final Matrix4f out,
            final int blockX,
            final int blockY,
            final int blockZ,
            final float xOffset,
            final float yOffset,
            final float zOffset,
            final PistonPart part
    ) {
        float x = blockX;
        float y = blockY;
        float z = blockZ;
        if (part == PistonPart.MOVED_BLOCK) {
            x += xOffset;
            y += yOffset;
            z += zOffset;
        }
        return out.identity().translate(x, y, z);
    }

    /** Core overload for the world position already applied by the dispatcher. */
    static Matrix4f piston(
            final Matrix4f out,
            final BlockPos dispatcherPosition,
            final float xOffset,
            final float yOffset,
            final float zOffset,
            final PistonPart part
    ) {
        if (dispatcherPosition == null) {
            return out.identity();
        }
        return piston(out, dispatcherPosition.getX(), dispatcherPosition.getY(), dispatcherPosition.getZ(),
                xOffset, yOffset, zOffset, part);
    }

    /** Convenience for the render path; {@link #piston} above is the tested core. */
    static Matrix4f piston(final Matrix4f out, final PistonHeadRenderState state, final PistonPart part) {
        MovingBlockRenderState moving = part == PistonPart.MOVED_BLOCK ? state.block : state.base;
        if (moving == null || state.blockPos == null) {
            return out.identity();
        }
        // LevelRenderer has already translated the PoseStack by state.blockPos before
        // PistonHeadRenderer.submit runs. moving.blockPos is used for lighting and
        // random seeds by MovingBlockFeatureRenderer, not as a second world origin.
        return piston(out, state.blockPos, state.xOffset, state.yOffset, state.zOffset, part);
    }

    /** Root transform for a block entity whose renderer adds no model-space translation. */
    static Matrix4f generic(final Matrix4f out, final BlockPos blockPos) {
        return out.identity().translate(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }

    /** Stable identity for a non-piston block entity at one block position. */
    static long blockEntityObjectId(final BlockPos blockPos) {
        return mix(blockPos.asLong() ^ BLOCK_ENTITY_SALT);
    }

    /**
     * A stable identity for one of a piston's moving blocks.
     *
     * <p>Derived from the block position rather than from the render state, because
     * {@code BlockEntityRenderDispatcher} calls {@code createRenderState} every
     * frame: the render state instance and the {@code MovingBlockRenderState}s
     * hanging off it are new each time, so neither can carry history. The block
     * entity itself does not move, so its position is the stable part.</p>
     *
     * <p>The packed position is run through a bit mix rather than used directly.
     * {@code BlockPos.asLong} concentrates its entropy in the low bits, and this id
     * shares a key space with the entity path's UUID-derived ids; an unmixed value
     * would sit in a narrow, predictable region of that space. The part salt keeps a
     * piston's two blocks from sharing one history, which would otherwise let the
     * body inherit the head's previous transform.</p>
     */
    static long objectId(final BlockPos blockPos, final PistonPart part) {
        return mix(blockPos.asLong() ^ part.salt());
    }

    /** SplitMix64 finalizer: full avalanche, so nearby block positions land far apart. */
    private static long mix(final long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }
}
