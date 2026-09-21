package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the exact x/y/z offset already extracted by the vanilla 26.2 piston renderer. */
@Mixin(PistonHeadRenderer.class)
public abstract class PistonHeadRendererMetalFxMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void metallum$capturePistonMotion(
            final PistonMovingBlockEntity blockEntity,
            final PistonHeadRenderState state,
            final float partialTicks,
            final Vec3 cameraPosition,
            final ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress,
            final CallbackInfo ci
    ) {
        MetalFxManager.capturePistonMotion(blockEntity, state);
    }
}
