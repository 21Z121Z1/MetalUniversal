package com.metallum.client.metal.render;

import com.mojang.math.Transformation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

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
     * without a source-proven orientation fall back to pure translation; that
     * fallback remains conservative for Temporal but is not sufficient evidence
     * for enabling the shipping frame-interpolation producer gate.
     */
    static Matrix4f compose(final EntityRenderState state) {
        Matrix4f out = new Matrix4f();
        if (state instanceof DisplayEntityRenderState display) {
            return display(out, display);
        }
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
     * Reproduces {@code DisplayRenderer.submit}: the dispatcher has already
     * established the display entity's world translation, then the renderer
     * applies its billboard orientation followed by the interpolated display
     * transformation before submitting block/item/text geometry.
     *
     * <p>Unlike the ordinary fallback above this transform is source-complete for
     * the display root, including camera-facing billboard modes. Camera rotation is
     * intentionally part of the object transform here because vanilla makes it part
     * of the display's submitted pose; omitting it makes CENTER/HORIZONTAL/VERTICAL
     * displays inherit the wrong history when the camera turns.</p>
     */
    private static Matrix4f display(final Matrix4f out, final DisplayEntityRenderState state) {
        Display.RenderState renderState = state.renderState;
        if (renderState == null) {
            return out.translation((float) state.x, (float) state.y, (float) state.z);
        }

        Quaternionf orientation = new Quaternionf();
        switch (renderState.billboardConstraints()) {
            case FIXED -> orientation.rotationYXZ(
                    (float) (-Math.PI / 180.0) * state.entityYRot,
                    (float) (Math.PI / 180.0) * state.entityXRot,
                    0.0F
            );
            case HORIZONTAL -> orientation.rotationYXZ(
                    (float) (-Math.PI / 180.0) * state.entityYRot,
                    (float) (Math.PI / 180.0) * transformDisplayXRot(state.cameraXRot),
                    0.0F
            );
            case VERTICAL -> orientation.rotationYXZ(
                    (float) (-Math.PI / 180.0) * transformDisplayYRot(state.cameraYRot),
                    (float) (Math.PI / 180.0) * state.entityXRot,
                    0.0F
            );
            case CENTER -> orientation.rotationYXZ(
                    (float) (-Math.PI / 180.0) * transformDisplayYRot(state.cameraYRot),
                    (float) (Math.PI / 180.0) * transformDisplayXRot(state.cameraXRot),
                    0.0F
            );
        }

        Transformation transformation = renderState.transformation().get(state.interpolationProgress);
        return display(
                out,
                state.x,
                state.y,
                state.z,
                orientation,
                transformation.getMatrix()
        );
    }

    /**
     * Tested core of the display transform. The order deliberately matches
     * {@code DisplayRenderer.submit}: world translation, billboard orientation,
     * then the interpolated {@link Transformation} matrix.
     */
    static Matrix4f display(
            final Matrix4f out,
            final double x,
            final double y,
            final double z,
            final Quaternionfc orientation,
            final Matrix4fc transformation
    ) {
        return out.translation((float) x, (float) y, (float) z)
                .rotate(orientation)
                .mul(transformation);
    }

    /** {@code DisplayRenderer.transformYRot}. */
    static float transformDisplayYRot(final float cameraYRot) {
        return cameraYRot - 180.0F;
    }

    /** {@code DisplayRenderer.transformXRot}. */
    static float transformDisplayXRot(final float cameraXRot) {
        return -cameraXRot;
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
        Vec3 posDelta = backPos.subtract(frontPos);
        if (posDelta.length() != 0.0) {
            Vec3 direction = posDelta.normalize();
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
