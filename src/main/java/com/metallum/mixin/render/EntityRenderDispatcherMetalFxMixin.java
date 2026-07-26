package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMetalFxMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void metallum$captureEntityState(
            final E entity,
            final float partialTick,
            final CallbackInfoReturnable<EntityRenderState> cir
    ) {
        MetalFxManager.captureEntityMotion(entity, cir.getReturnValue());
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void metallum$beginEntitySubmit(
            final S state,
            final CameraRenderState cameraState,
            final double x,
            final double y,
            final double z,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.beginEntitySubmission(state);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends EntityRenderState> void metallum$endEntitySubmit(
            final S state,
            final CameraRenderState cameraState,
            final double x,
            final double y,
            final double z,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endEntitySubmission();
    }
}
