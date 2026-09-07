package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalSyntheticExactMotion;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exact staged-motion ownership for first-person geometry.
 *
 * Minecraft 26.2 computes swing, bob, equip/use transforms inside submitArmWithItem from current
 * interpolated player/item state. Hook the first pushPose after the scoping early-return and bind
 * every resulting staged model/item draw to a transactional synthetic hand owner. Unsupported
 * pipelines or changed manifests still fail closed through MetalExactMotionCoverage.
 */
@Mixin(ItemInHandRenderer.class)
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
            final AbstractClientPlayer player,
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
        MetalSyntheticExactMotion.beginFirstPerson(hand);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void metallum$endFirstPersonGeometry(
            final AbstractClientPlayer player,
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
