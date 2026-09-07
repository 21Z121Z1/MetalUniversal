package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
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
 * Frame-interpolation admission for first-person geometry.
 *
 * Minecraft 26.2 computes swing, bob, equip/use transforms inside submitArmWithItem from current
 * interpolated player/item state. Until a previous local pose is carried through the staged
 * geometry path, zero object motion is only an approximation. Hook the first pushPose inside the
 * non-scoping branch so a scoped call that submits nothing does not reject the frame.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMetalFxMixin {
    @Inject(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V")
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
        MetalFxManager.observeFirstPersonMotion();
    }
}
