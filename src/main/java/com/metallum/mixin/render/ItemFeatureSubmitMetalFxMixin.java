package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Binds an item submit to the entity that produced it. Dropped items, item
 * frames and held items all reach the renderer through this record, and it is
 * the only point where the owning entity is still on the stack. Submits built
 * outside {@code EntityRenderDispatcher.submit} — GUI items and the first-person
 * hand — find no owner and are left alone.
 */
@Mixin(ItemFeatureRenderer.Submit.class)
public abstract class ItemFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final PoseStack.Pose pose,
            final ItemDisplayContext displayContext,
            final int lightCoords,
            final int overlayCoords,
            final int outlineColor,
            final int[] tintLayers,
            final List<?> quads,
            final ItemStackRenderState.FoilType foilType,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this, pose);
    }
}
