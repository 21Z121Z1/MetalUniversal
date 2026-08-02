package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
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
final class MetalEntityObjectPose {
    /** {@code AbstractMinecartRenderer} and {@code AbstractBoatRenderer} both lift the hull by this much. */
    private static final float VEHICLE_HULL_LIFT = 0.375F;

    private MetalEntityObjectPose() {
    }

    /**
     * Builds the object-to-world matrix for one extracted render state. States
     * without a known orientation fall back to pure translation, which is still
     * strictly better than letting their pixels inherit camera motion.
     */
    static Matrix4f compose(final EntityRenderState state) {
        Matrix4f out = new Matrix4f();
        if (state instanceof ItemEntityRenderState item) {
            return droppedItem(out, item.x, item.y, item.z, item.ageInTicks, item.bobOffset);
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
