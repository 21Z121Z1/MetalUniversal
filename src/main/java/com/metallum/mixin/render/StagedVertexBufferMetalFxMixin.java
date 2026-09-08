package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalParticleBatchMotion;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StagedVertexBuffer.class)
public abstract class StagedVertexBufferMetalFxMixin {
    @Inject(
            method = "appendDraw(Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/PrimitiveTopology;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;",
            at = @At("RETURN")
    )
    private void metallum$attachParticleMotionDraw(
            final VertexFormat format,
            final PrimitiveTopology primitiveTopology,
            final @Nullable VertexSorting quadSorting,
            final CallbackInfoReturnable<StagedVertexBuffer.Draw> cir
    ) {
        MetalParticleBatchMotion.attachDrawIfActive(cir.getReturnValue(), format, primitiveTopology);
    }

    @Inject(method = "getExecuteInfo", at = @At("RETURN"))
    private void metallum$transferMotionOwner(
            final StagedVertexBuffer.Draw draw,
            final CallbackInfoReturnable<StagedVertexBuffer.ExecuteInfo> cir
    ) {
        MetalEntityMotionCapture.transferExecute(draw, cir.getReturnValue());
    }
}
