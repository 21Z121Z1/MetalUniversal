package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalSyntheticExactMotion;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exact staged-motion ownership for first-person geometry on Minecraft 26.3.
 *
 * <p>26.3 renamed ItemInHandRenderer to FirstPersonHandsAndItemsRenderer and
 * moved player/item interpolation into render-state objects. The geometric
 * ownership boundary is otherwise unchanged: begin after the method's scoping
 * checks at the first pose push, then end transactionally when submission
 * returns.</p>
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemInHandRendererMetalFxMixin {
    @Inject(
            method = "submitArmWithItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
                    ordinal = 0
            )
    )
    private void metallum$observeFirstPersonGeometry(
            final PlayerRenderState playerState,
            final FirstPersonHandsAndItemsRenderState handState,
            final float frameInterp,
            final float xRot,
            final InteractionHand hand,
            final float attack,
            final ItemStack itemStack,
            final float inverseArmHeight,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final int lightCoords,
            final CallbackInfo ci
    ) {
        MetalSyntheticExactMotion.beginFirstPerson(hand, itemStack);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void metallum$endFirstPersonGeometry(
            final PlayerRenderState playerState,
            final FirstPersonHandsAndItemsRenderState handState,
            final float frameInterp,
            final float xRot,
            final InteractionHand hand,
            final float attack,
            final ItemStack itemStack,
            final float inverseArmHeight,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final int lightCoords,
            final CallbackInfo ci
    ) {
        MetalSyntheticExactMotion.endFirstPerson();
    }
}
