package com.metallum.client.metal.render;

import com.metallum.mixin.render.BlockModelRenderStateMetalFxAccessor;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Source-frame geometry continuity for Minecraft display entities.
 *
 * <p>Root display transforms are already represented by MetalEntityObjectPose.
 * Frame interpolation additionally requires the submitted local geometry to be
 * the same topology as the previous real source frame. Minecraft 26.2 gives us
 * two exact signals for that decision:</p>
 *
 * <ul>
 *   <li>Block displays resolve a canonical BlockState with a fixed model seed
 *       (42), so the same state produces the same ordinary baked model.</li>
 *   <li>Item models emit model-identity elements. Vanilla's own oversized-item
 *       cache only reuses geometry when that identity is unchanged and the
 *       render state is not animated.</li>
 * </ul>
 *
 * <p>Special renderers and text never enter the ordinary staged replay, so they
 * remain fail-closed.</p>
 */
public final class MetalDisplayMotionSafety {
    private static final Map<Entity, Object> LAST_GEOMETRY = new WeakHashMap<>();
    private static final Map<EntityRenderState, Boolean> CURRENT_CONTINUITY = new WeakHashMap<>();

    private MetalDisplayMotionSafety() {
    }

    public static void capture(final Entity entity, final EntityRenderState state) {
        if (entity instanceof Display.BlockDisplay display && state instanceof BlockDisplayEntityRenderState blockState) {
            captureBlock(display, blockState);
            return;
        }
        if (entity instanceof Display.ItemDisplay display && state instanceof ItemDisplayEntityRenderState itemState) {
            captureItem(display, itemState);
        }
    }

    public static boolean isFrameInterpolationSafe(final DisplayEntityRenderState state) {
        if (!state.hasSubState()) {
            return true;
        }
        if (!Boolean.TRUE.equals(CURRENT_CONTINUITY.get(state))) {
            return false;
        }
        if (state instanceof BlockDisplayEntityRenderState blockState) {
            BlockModelRenderStateMetalFxAccessor accessor =
                    (BlockModelRenderStateMetalFxAccessor) (Object) blockState.blockModel;
            return accessor.metallum$getSpecialRenderer() == null;
        }
        if (state instanceof ItemDisplayEntityRenderState itemState) {
            MetalItemModelIdentityAccess access = (MetalItemModelIdentityAccess) (Object) itemState.item;
            return !itemState.item.isAnimated() && !access.metallum$hasSpecialLayer();
        }
        return false;
    }

    static ItemGeometry itemGeometry(final ItemDisplayContext context, final List<Object> identity) {
        return identity.isEmpty() ? null : new ItemGeometry(context, List.copyOf(identity));
    }

    private static void captureBlock(final Display.BlockDisplay display, final BlockDisplayEntityRenderState state) {
        Display.BlockDisplay.BlockRenderState renderState = display.blockRenderState();
        if (renderState == null) {
            LAST_GEOMETRY.remove(display);
            CURRENT_CONTINUITY.put(state, false);
            return;
        }

        BlockState current = renderState.blockState();
        BlockModelRenderStateMetalFxAccessor accessor =
                (BlockModelRenderStateMetalFxAccessor) (Object) state.blockModel;
        if (accessor.metallum$getSpecialRenderer() != null) {
            LAST_GEOMETRY.remove(display);
            CURRENT_CONTINUITY.put(state, false);
            return;
        }

        Object previous = LAST_GEOMETRY.put(display, current);
        // BlockState values are canonical immutable state-definition entries in
        // vanilla. Identity is deliberately stricter than semantic equality.
        CURRENT_CONTINUITY.put(state, previous == current);
    }

    private static void captureItem(final Display.ItemDisplay display, final ItemDisplayEntityRenderState state) {
        Display.ItemDisplay.ItemRenderState renderState = display.itemRenderState();
        MetalItemModelIdentityAccess access = (MetalItemModelIdentityAccess) (Object) state.item;
        if (renderState == null || state.item.isAnimated() || access.metallum$hasSpecialLayer()) {
            LAST_GEOMETRY.remove(display);
            CURRENT_CONTINUITY.put(state, false);
            return;
        }

        ItemGeometry current = itemGeometry(renderState.itemTransform(), access.metallum$modelIdentity());
        if (current == null) {
            LAST_GEOMETRY.remove(display);
            CURRENT_CONTINUITY.put(state, false);
            return;
        }
        Object previous = LAST_GEOMETRY.put(display, current);
        CURRENT_CONTINUITY.put(state, Objects.equals(previous, current));
    }

    record ItemGeometry(ItemDisplayContext displayContext, List<Object> modelIdentity) {
    }
}
