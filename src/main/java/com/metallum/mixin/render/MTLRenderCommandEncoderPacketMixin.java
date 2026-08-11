package com.metallum.mixin.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MetalRenderCommandPacketFacade;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;

/**
 * Experimental ordered render command stream.
 *
 * <p>Only native calls which survived the existing encoder state shadow are
 * redirected. Direct and indirect draws remain in-order packet operations.
 * Triangle-fan, native multi-draw, clear, fence and debug-group boundaries use
 * the existing flush surface and therefore execute explicitly.</p>
 */
@Mixin(value = MTLRenderCommandEncoder.class, remap = false)
public abstract class MTLRenderCommandEncoderPacketMixin {
    @Unique
    private @Nullable MetalRenderCommandPacketFacade metallum$commandPacket;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$createCommandPacket(
            final MemorySegment handle,
            final CallbackInfo ci
    ) {
        this.metallum$commandPacket = MetalRenderCommandPacketFacade.createIfAvailable();
    }

    @Inject(method = "flushState", at = @At("HEAD"))
    private void metallum$flushCommandPacket(
            final MemorySegment encoder,
            final CallbackInfo ci
    ) {
        if (this.metallum$commandPacket != null) {
            this.metallum$commandPacket.flush(encoder);
        }
    }

    @Inject(method = "endEncoding", at = @At("RETURN"))
    private void metallum$closeCommandPacket(final CallbackInfo ci) {
        if (this.metallum$commandPacket != null) {
            this.metallum$commandPacket.close();
            this.metallum$commandPacket = null;
        }
    }

    @Redirect(
            method = "setRenderPipelineState",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setRenderPipelineState(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;)V")
    )
    private void metallum$packetPipeline(
            final MemorySegment encoder,
            final MemorySegment pipeline
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.pipeline(encoder, pipeline)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setRenderPipelineState(encoder, pipeline);
        }
    }

    @Redirect(
            method = "setDepthStencilState",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setDepthStencilState(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;)V")
    )
    private void metallum$packetDepthStencil(
            final MemorySegment encoder,
            final MemorySegment state
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.depthStencil(encoder, state)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setDepthStencilState(encoder, state);
        }
    }

    @Redirect(
            method = "setDepthBias",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setDepthBias(Ljava/lang/foreign/MemorySegment;FFF)V")
    )
    private void metallum$packetDepthBias(
            final MemorySegment encoder,
            final float bias,
            final float slope,
            final float clamp
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.depthBias(encoder, bias, slope, clamp)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(encoder, bias, slope, clamp);
        }
    }

    @Redirect(
            method = "setFrontFacingWinding",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setFrontFacingWinding(Ljava/lang/foreign/MemorySegment;I)V")
    )
    private void metallum$packetWinding(
            final MemorySegment encoder,
            final int winding
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.winding(encoder, winding)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(encoder, winding);
        }
    }

    @Redirect(
            method = "setCullMode",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setCullMode(Ljava/lang/foreign/MemorySegment;J)V")
    )
    private void metallum$packetCullMode(
            final MemorySegment encoder,
            final long cullMode
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.cullMode(encoder, cullMode)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(encoder, cullMode);
        }
    }

    @Redirect(
            method = "setTriangleFillMode",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setTriangleFillMode(Ljava/lang/foreign/MemorySegment;I)V")
    )
    private void metallum$packetFillMode(
            final MemorySegment encoder,
            final int fillMode
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.fillMode(encoder, fillMode)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(encoder, fillMode);
        }
    }

    @Redirect(
            method = "setBuffer",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setBuffer(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;JJI)V")
    )
    private void metallum$packetBuffer(
            final MemorySegment encoder,
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.buffer(encoder, buffer, offset, index, stageMask)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(
                    encoder, buffer, offset, index, stageMask
            );
        }
    }

    @Redirect(
            method = {"setBuffer", "setBufferOffset"},
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setBufferOffset(Ljava/lang/foreign/MemorySegment;JJI)V")
    )
    private void metallum$packetBufferOffset(
            final MemorySegment encoder,
            final long offset,
            final long index,
            final int stageMask
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.bufferOffset(encoder, offset, index, stageMask)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(
                    encoder, offset, index, stageMask
            );
        }
    }

    @Redirect(
            method = "setTexture",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setTexture(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;JI)V")
    )
    private void metallum$packetTexture(
            final MemorySegment encoder,
            final MemorySegment texture,
            final long index,
            final int stageMask
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.texture(encoder, texture, index, stageMask)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setTexture(
                    encoder, texture, index, stageMask
            );
        }
    }

    @Redirect(
            method = "setTextureAndSampler",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setTextureAndSampler(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;JI)V")
    )
    private void metallum$packetTextureAndSampler(
            final MemorySegment encoder,
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.textureAndSampler(
                encoder, texture, sampler, index, stageMask
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_setTextureAndSampler(
                    encoder, texture, sampler, index, stageMask
            );
        }
    }

    @Redirect(
            method = "setScissorRect",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_setScissorRect(Ljava/lang/foreign/MemorySegment;JJJJ)V")
    )
    private void metallum$packetScissor(
            final MemorySegment encoder,
            final long x,
            final long y,
            final long width,
            final long height
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.scissor(encoder, x, y, width, height)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(
                    encoder, x, y, width, height
            );
        }
    }

    @Redirect(
            method = {
                    "drawPrimitives",
                    "drawIndexedPrimitives",
                    "drawIndexedPrimitivesIndirect",
                    "drawPrimitivesIndirect"
            },
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/mtl/MTLRenderCommandEncoder;flushState(Ljava/lang/foreign/MemorySegment;)V")
    )
    private void metallum$deferPreDrawFlush(
            final MTLRenderCommandEncoder encoderObject,
            final MemorySegment encoder
    ) {
        if (this.metallum$commandPacket == null) {
            encoderObject.flushPendingState();
        }
    }

    @Redirect(
            method = "drawPrimitives",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_drawPrimitives(Ljava/lang/foreign/MemorySegment;JJJJJ)V")
    )
    private void metallum$packetDrawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long firstVertex,
            final long vertexCount,
            final long instanceCount,
            final long baseInstance
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.drawPrimitives(
                encoder,
                primitiveType,
                (int) firstVertex,
                (int) vertexCount,
                (int) instanceCount,
                (int) baseInstance
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitives(
                    encoder,
                    primitiveType,
                    firstVertex,
                    vertexCount,
                    instanceCount,
                    baseInstance
            );
        }
    }

    @Redirect(
            method = "drawIndexedPrimitives",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_drawIndexedPrimitives(Ljava/lang/foreign/MemorySegment;JJJLjava/lang/foreign/MemorySegment;JJJJ)V")
    )
    private void metallum$packetDrawIndexed(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexCount,
            final long indexType,
            final MemorySegment indexBuffer,
            final long offset,
            final long instanceCount,
            final long baseVertex,
            final long baseInstance
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.drawIndexed(
                encoder,
                primitiveType,
                (int) indexCount,
                indexType,
                indexBuffer,
                offset,
                (int) instanceCount,
                (int) baseVertex,
                (int) baseInstance
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitives(
                    encoder,
                    primitiveType,
                    indexCount,
                    indexType,
                    indexBuffer,
                    offset,
                    instanceCount,
                    baseVertex,
                    baseInstance
            );
        }
    }

    @Redirect(
            method = "drawPrimitivesIndirect",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_drawPrimitivesIndirect(Ljava/lang/foreign/MemorySegment;JLjava/lang/foreign/MemorySegment;JJJ)V")
    )
    private void metallum$packetDrawPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final long drawCount,
            final long stride
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.drawPrimitivesIndirect(
                encoder,
                primitiveType,
                indirectBuffer,
                indirectOffset,
                (int) drawCount,
                stride
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitivesIndirect(
                    encoder,
                    primitiveType,
                    indirectBuffer,
                    indirectOffset,
                    drawCount,
                    stride
            );
        }
    }

    @Redirect(
            method = "drawIndexedPrimitivesIndirect",
            at = @At(value = "INVOKE", target = "Lcom/metallum/client/metal/render/bridge/MetalNativeBridge;MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(Ljava/lang/foreign/MemorySegment;JJLjava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;JJJ)V")
    )
    private void metallum$packetDrawIndexedIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final long drawCount,
            final long stride
    ) {
        if (this.metallum$commandPacket == null
                || !this.metallum$commandPacket.drawIndexedIndirect(
                encoder,
                primitiveType,
                indexType,
                indexBuffer,
                indirectBuffer,
                indirectOffset,
                (int) drawCount,
                stride
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
                    encoder,
                    primitiveType,
                    indexType,
                    indexBuffer,
                    indirectBuffer,
                    indirectOffset,
                    drawCount,
                    stride
            );
        }
    }
}
