package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StagedVertexBuffer.class)
public abstract class StagedVertexBufferMetalFxMixin {
    @Inject(method = "getExecuteInfo", at = @At("RETURN"))
    private void metallum$transferMotionOwner(
            final StagedVertexBuffer.Draw draw,
            final CallbackInfoReturnable<StagedVertexBuffer.ExecuteInfo> cir
    ) {
        MetalEntityMotionCapture.transferExecute(draw, cir.getReturnValue());
    }
}
