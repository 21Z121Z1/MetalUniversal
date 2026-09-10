package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes each real block-entity state at the 26.2 dispatcher submit boundary. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMetalFxMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void metallum$observeBlockEntity(
            final S state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeBlockEntity(state);
    }
}
