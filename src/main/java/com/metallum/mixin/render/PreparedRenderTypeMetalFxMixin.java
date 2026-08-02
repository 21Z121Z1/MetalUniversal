package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMetalFxMixin {
    @Inject(method = "drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V", at = @At("RETURN"))
    private void metallum$drawObjectMotion(
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.Sample sample = MetalEntityMotionCapture.takeExecute(executeInfo);
        if (sample != null) {
            MetalFxManager.drawEntityMotion((PreparedRenderType) (Object) this, executeInfo, sample);
        }
    }
}
