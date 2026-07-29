package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.mojang.math.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Rebuilds the root object-to-world transform that an entity renderer applies
 * before it hands geometry to a feature renderer.
 *
 * <p>Motion vectors are derived from {@code previous * inverse(current)} of
 * this matrix, so only the part of the chain that changes between two rendered
 * frames matters. Constant factors — model-space scales, the {@code (-1,-1,1)}
 * flip every entity model ends with, per-entity seed jitter, and the item
 * cluster's deterministic copy offsets — cancel exactly and are deliberately
 * left out. Where an offset is time-varying it has to be reproduced here or the
 * interpolator sees an object that translates without rotating.</p>
 *
 * <p>World space here is absolute, matching the view matrix built by
 * {@code MetalFxMath.viewMatrix}: the object matrix is only ever combined with
 * view-projection matrices built from the same origin, never with Minecraft's
 * camera-relative pose stack.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalEntityObjectPose {
    /** {@code AbstractMinecartRenderer} and {@code AbstractBoatRenderer} both lift the hull by this much. */
    private static final float VEHICLE_HULL_LIFT = 0.375F;
    private static final float ITEM_FRAME_PLANE_OFFSET = 0.46875F;
    private static final float ITEM_FRAME_RENDER_OFFSET = 0.3F;
    private static final float ITEM_FRAME_CONTENT_Z = 0.4375F;
    private static final float ITEM_FRAME_INVISIBLE_CONTENT_Z = 0.5F;
    private static final float MAP_PIXEL_SCALE = 0.0078125F;
    private static final long ITEM_FRAME_BASE_SALT = 0x4F1BBCDCBFA54001L;
    private static final long ITEM_FRAME_CONTENT_SALT = 0x91E10DA5C79E7B1DL;

    public enum EntityPart {
        ITEM_FRAME_BASE(ITEM_FRAME_BASE_SALT),
        ITEM_FRAME_CONTENT(ITEM_FRAME_CONTENT_SALT),
        END_CRYSTAL_MODEL(0xD6E8FEB86659FD93L),
        END_CRYSTAL_BEAM(0xA5A3564E27F1C2B7L);

        private final long salt;

        EntityPart(final long salt) {
            this.salt = salt;
        }

        long salt() {
            return salt;
        }
    }

    private MetalEntityObjectPose() {
    }

    /**
     * Builds the object-to-world matrix for one extracted render state. States
     * without a known orientation fall back to pure translation, which is still
     * strictly better than letting their pixels inherit camera motion.
     */
    static Matrix4f compose(final EntityRenderState state) {
        Matrix4f out = new Matrix4f();
        if (state instanceof DisplayEntityRenderState display) {
            return display(out, display);
        }
        if (state instanceof ItemEntityRenderState item) {
            return droppedItem(out, item.x, item.y, item.z, item.ageInTicks, item.bobOffset);
        }
        if (state instanceof ItemFrameRenderState itemFrame) {
            return itemFrameContent(
                    out,
                    itemFrame.x,
                    itemFrame.y,
                    itemFrame.z,
                    itemFrame.direction,
                    itemFrame.rotation,
                    itemFrame.mapId != null,
                    itemFrame.isInvisible
            );
        }
        if (state instanceof PaintingRenderState painting) {
            return painting(out, painting.x, painting.y, painting.z, painting.direction);
        }
        if (state instanceof ArmorStandRenderState armorStand) {
            return armorStand(
                    out,
                    armorStand.x,
                    armorStand.y,
                    armorStand.z,
                    armorStand.scale,
                    armorStand.yRot,
                    armorStand.wiggle
            );
        }
        if (state instanceof EndCrystalRenderState endCrystal) {
            return endCrystal(out, endCrystal.x, endCrystal.y, endCrystal.z);
        }
        if (state instanceof MinecartRenderState cart) {
            return minecart(out, cart);
        }
        if (state instanceof BoatRenderState boat) {
            return boat(
                    out,
                    boat.x, boat.y, boat.z,
                    boat.yRot,
                    boat.hurtTime, boat.damageTime, boat.hurtDir,
                    boat.bubbleAngle, boat.isUnderWater
            );
        }
        if (state instanceof ArrowRenderState arrow) {
            return arrow(out, arrow.x, arrow.y, arrow.z, arrow.yRot, arrow.xRot);
        }
        if (state instanceof LivingEntityRenderState living && Float.isFinite(living.bodyRot)) {
            return living(out, living.x, living.y, living.z, living.bodyRot);
        }
        return out.translation((float) state.x, (float) state.y, (float) state.z);
    }

    static MetalEntityMotionCapture.Source sourceFor(final EntityRenderState state) {
        if (state instanceof DisplayEntityRenderState) {
            return MetalEntityMotionCapture.Source.DISPLAY;
        }
        if (state instanceof PaintingRenderState) {
            return MetalEntityMotionCapture.Source.PAINTING;
        }
        if (state instanceof ArmorStandRenderState) {
            return MetalEntityMotionCapture.Source.ARMOR_STAND;
        }
        if (state instanceof EndCrystalRenderState) {
            return MetalEntityMotionCapture.Source.END_CRYSTAL;
        }
        return MetalEntityMotionCapture.Source.ENTITY;
    }

    /**
     * {@code DisplayRenderer.submit}: billboard orientation followed by the
     * interpolated renderer transformation. Both are part of the submitted
     * root, so translation-only reconstruction would miss display turns and
     * scale changes even when the entity never changes position.
     */
    static Matrix4f display(final Matrix4f out, final DisplayEntityRenderState state) {
        out.translation((float) state.x, (float) state.y, (float) state.z);
        net.minecraft.world.entity.Display.RenderState renderState = state.renderState;
        if (renderState == null) {
            return out;
        }
        out.rotate(displayOrientation(renderState, state));
        Transformation transformation = renderState.transformation().get(state.interpolationProgress);
        if (transformation != null) {
            out.mul(transformation.getMatrix());
        }
        return out;
    }

    private static Quaternionf displayOrientation(
            final net.minecraft.world.entity.Display.RenderState renderState,
            final DisplayEntityRenderState state
    ) {
        float yRot;
        float xRot;
        switch (renderState.billboardConstraints()) {
            case FIXED -> {
                yRot = -state.entityYRot;
                xRot = state.entityXRot;
            }
            case HORIZONTAL -> {
                yRot = -state.entityYRot;
                xRot = -state.cameraXRot;
            }
            case VERTICAL -> {
                yRot = 180.0F - state.cameraYRot;
                xRot = state.entityXRot;
            }
            case CENTER -> {
                yRot = 180.0F - state.cameraYRot;
                xRot = -state.cameraXRot;
            }
            default -> {
                yRot = 0.0F;
                xRot = 0.0F;
            }
        }
        return new Quaternionf().rotationYXZ(
                (float) Math.toRadians(yRot),
                (float) Math.toRadians(xRot),
                0.0F
        );
    }

    /** Root used by the frame model, including the model's local half-block shift. */
    static Matrix4f itemFrameFrame(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final Direction direction
    ) {
        return itemFrameRoot(out, x, y, z, direction).translate(-0.5F, -0.5F, -0.5F);
    }

    /**
     * Root used by the framed item or map. The frame model is popped before this
     * content path, so its half-block shift must not be included here.
     */
    static Matrix4f itemFrameContent(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final Direction direction,
            final int rotation,
            final boolean map,
            final boolean invisible
    ) {
        itemFrameRoot(out, x, y, z, direction)
                .translate(0.0F, 0.0F, invisible ? ITEM_FRAME_INVISIBLE_CONTENT_Z : ITEM_FRAME_CONTENT_Z);
        if (map) {
            out.rotateZ((float) Math.toRadians((rotation % 4) * 90.0F + 180.0F));
            out.scale(MAP_PIXEL_SCALE, MAP_PIXEL_SCALE, MAP_PIXEL_SCALE);
            out.translate(-64.0F, -64.0F, -1.0F);
        } else {
            out.rotateZ((float) Math.toRadians(rotation * 45.0F));
            out.scale(0.5F, 0.5F, 0.5F);
        }
        return out;
    }

    private static Matrix4f itemFrameRoot(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final Direction direction
    ) {
        out.translation((float) x, (float) y, (float) z);
        if (direction == null) {
            return out;
        }
        out.translate(
                direction.getStepX() * ITEM_FRAME_RENDER_OFFSET,
                -0.25F + direction.getStepY() * ITEM_FRAME_RENDER_OFFSET,
                direction.getStepZ() * ITEM_FRAME_RENDER_OFFSET
        ).translate(
                direction.getStepX() * ITEM_FRAME_PLANE_OFFSET,
                direction.getStepY() * ITEM_FRAME_PLANE_OFFSET,
                direction.getStepZ() * ITEM_FRAME_PLANE_OFFSET
        );
        float xRot;
        float yRot;
        if (direction.getAxis().isHorizontal()) {
            xRot = 0.0F;
            yRot = 180.0F - direction.toYRot();
        } else {
            xRot = -90.0F * direction.getAxisDirection().getStep();
            yRot = 180.0F;
        }
        out.rotateX((float) Math.toRadians(xRot));
        out.rotateY((float) Math.toRadians(yRot));
        return out;
    }

    /** {@code PaintingRenderer.submit}: a direction-dependent world yaw. */
    static Matrix4f painting(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final Direction direction
    ) {
        out.translation((float) x, (float) y, (float) z);
        if (direction != null) {
            out.rotateY((float) Math.toRadians(180.0F - direction.get2DDataValue() * 90.0F));
        }
        return out;
    }

    /** {@code ArmorStandRenderer.setupRotations} plus the living root scale. */
    static Matrix4f armorStand(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float scale,
            final float yRot,
            final float wiggle
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.scale(scale, scale, scale);
        out.rotateY((float) Math.toRadians(180.0F - yRot));
        if (wiggle < 5.0F) {
            out.rotateY((float) Math.toRadians(Mth.sin(wiggle / 1.5F * Mth.PI) * 3.0F));
        }
        return out;
    }

    /** {@code EndCrystalRenderer.submit}'s rigid root. Model-part animation is separate. */
    static Matrix4f endCrystal(
            final Matrix4f out,
            final double x,
            final double y,
            final double z
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.scale(2.0F, 2.0F, 2.0F);
        out.translate(0.0F, -0.5F, 0.0F);
        return out;
    }

    /** Root left on the pose stack while {@code submitCrystalBeams} emits its geometry. */
    static Matrix4f endCrystalBeam(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final Vec3 beamOffset
    ) {
        out.translation((float) x, (float) y, (float) z);
        if (beamOffset != null) {
            out.translate((float) beamOffset.x, (float) beamOffset.y, (float) beamOffset.z);
        }
        return out;
    }

    static long objectId(final long entityObjectId, final EntityPart part) {
        return mix(entityObjectId ^ part.salt());
    }

    private static long mix(final long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }

    /**
     * {@code ItemEntityRenderer.submit}: hover bob on Y, then a Y spin.
     *
     * <p>The renderer also lifts the item by {@code -modelBoundingBox.minY +
     * 1/16}. That term is constant for a given stack and only ever multiplies a
     * Y translation against a Y rotation, which commute, so it cancels exactly
     * in the frame-to-frame delta and is skipped rather than re-deriving the
     * model bounding box on the render thread.</p>
     */
    static Matrix4f droppedItem(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float ageInTicks,
            final float bobOffset
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.translate(0.0F, itemBob(ageInTicks, bobOffset), 0.0F);
        out.rotateY(itemSpin(ageInTicks, bobOffset));
        return out;
    }

    /** {@code ItemEntityRenderer.submit} hover term. */
    static float itemBob(final float ageInTicks, final float bobOffset) {
        return Mth.sin(ageInTicks / 10.0F + bobOffset) * 0.1F + 0.1F;
    }

    /** {@code ItemEntity.getSpin}, in radians. */
    static float itemSpin(final float ageInTicks, final float bobOffset) {
        return ageInTicks / 20.0F + bobOffset;
    }

    private static Matrix4f minecart(final Matrix4f out, final MinecartRenderState cart) {
        if (cart.isNewRender) {
            // AbstractMinecartRenderer.getRenderOffset moves the cart onto its
            // interpolated position, so the entity position is not where the
            // hull is drawn.
            Vec3 renderPos = cart.renderPos;
            return minecartNewRender(
                    out,
                    renderPos != null ? renderPos.x : cart.x,
                    renderPos != null ? renderPos.y : cart.y,
                    renderPos != null ? renderPos.z : cart.z,
                    cart.yRot, cart.xRot,
                    cart.hurtTime, cart.damageTime, cart.hurtDir
            );
        }

        Vec3 posOnRail = cart.posOnRail;
        Vec3 frontPos = cart.frontPos;
        Vec3 backPos = cart.backPos;
        if (posOnRail == null || frontPos == null || backPos == null) {
            return minecartOldRender(
                    out,
                    cart.x, cart.y, cart.z,
                    cart.yRot, cart.xRot,
                    cart.hurtTime, cart.damageTime, cart.hurtDir
            );
        }

        // AbstractMinecartRenderer.oldRender rides the sampled rail point and
        // re-derives the orientation from the front/back samples, discarding the
        // extracted rotations whenever the rail direction is usable.
        float yRot = cart.yRot;
        float xRot = cart.xRot;
        Vec3 direction = backPos.add(-frontPos.x, -frontPos.y, -frontPos.z);
        if (direction.length() != 0.0) {
            direction = direction.normalize();
            yRot = (float) (Math.atan2(direction.z, direction.x) * 180.0 / Math.PI);
            xRot = (float) (Math.atan(direction.y) * 73.0);
        }
        return minecartOldRender(
                out,
                posOnRail.x, (frontPos.y + backPos.y) / 2.0, posOnRail.z,
                yRot, xRot,
                cart.hurtTime, cart.damageTime, cart.hurtDir
        );
    }

    /** {@code AbstractMinecartRenderer.newRender}: orient first, then lift. */
    static Matrix4f minecartNewRender(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float yRot,
            final float xRot,
            final float hurtTime,
            final float damageTime,
            final int hurtDir
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.rotateY((float) Math.toRadians(yRot));
        out.rotateZ((float) Math.toRadians(-xRot));
        out.translate(0.0F, VEHICLE_HULL_LIFT, 0.0F);
        return appendHurtShake(out, hurtTime, damageTime, hurtDir);
    }

    /** {@code AbstractMinecartRenderer.oldRender}: lift first, then orient. */
    static Matrix4f minecartOldRender(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float yRot,
            final float xRot,
            final float hurtTime,
            final float damageTime,
            final int hurtDir
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.translate(0.0F, VEHICLE_HULL_LIFT, 0.0F);
        out.rotateY((float) Math.toRadians(180.0F - yRot));
        out.rotateZ((float) Math.toRadians(-xRot));
        return appendHurtShake(out, hurtTime, damageTime, hurtDir);
    }

    /** {@code AbstractBoatRenderer.submit}. */
    static Matrix4f boat(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float yRot,
            final float hurtTime,
            final float damageTime,
            final int hurtDir,
            final float bubbleAngle,
            final boolean underWater
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.translate(0.0F, VEHICLE_HULL_LIFT, 0.0F);
        out.rotateY((float) Math.toRadians(180.0F - yRot));
        appendHurtShake(out, hurtTime, damageTime, hurtDir);
        if (!underWater && !Mth.equal(bubbleAngle, 0.0F)) {
            // Mojang builds this from an unnormalized (1, 0, 1) axis; reuse the
            // same call so the reconstructed pose matches the drawn one instead
            // of a mathematically tidier rotation.
            out.rotate(new Quaternionf().setAngleAxis(
                    bubbleAngle * (float) (Math.PI / 180.0), 1.0F, 0.0F, 1.0F
            ));
        }
        return out;
    }

    /** {@code ArrowRenderer.submit}. */
    static Matrix4f arrow(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float yRot,
            final float xRot
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.rotateY((float) Math.toRadians(yRot - 90.0F));
        out.rotateZ((float) Math.toRadians(xRot));
        return out;
    }

    /**
     * {@code LivingEntityRenderer.setupRotations}. The constant 180 degree
     * offset cancels in the delta, but the sign has to match the on-screen
     * rotation so a turning mob reprojects correctly instead of inheriting
     * camera motion at its silhouette.
     */
    static Matrix4f living(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final float bodyRot
    ) {
        out.translation((float) x, (float) y, (float) z);
        out.rotateY((float) Math.toRadians(180.0F - bodyRot));
        return out;
    }

    /** Shared hurt wobble of {@code AbstractMinecartRenderer} and {@code AbstractBoatRenderer}. */
    private static Matrix4f appendHurtShake(
            final Matrix4f out,
            final float hurtTime,
            final float damageTime,
            final int hurtDir
    ) {
        if (hurtTime > 0.0F) {
            out.rotateX((float) Math.toRadians(
                    Mth.sin(hurtTime) * hurtTime * damageTime / 10.0F * hurtDir
            ));
        }
        return out;
    }
}
