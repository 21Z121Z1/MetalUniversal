package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Captures exact staged positions at the last safe point before Minecraft frees the CPU slices. */
@Mixin(StagedVertexBuffer.Draw.class)
public abstract class StagedVertexDrawMetalFxMixin {
    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    @Final
    private PrimitiveTopology primitiveTopology;

    @Shadow
    @Final
    private List<ByteBufferBuilder.Result> vertexBufferSlices;

    @Shadow
    private int vertexCount;

    @Shadow
    private int indexCount;

    @Inject(method = "freeVertexData", at = @At("HEAD"))
    private void metallum$captureExactPreviousPositions(final CallbackInfo ci) {
        MetalEntityMotionCapture.captureVertexData(
                (StagedVertexBuffer.Draw) (Object) this,
                format,
                primitiveTopology,
                vertexBufferSlices,
                vertexCount,
                indexCount
        );
    }
}
