package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private static final boolean STATE_SHADOW_ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.encoderStateShadow", "false")
    );

    private final MetalRenderStateShadow stateShadow = STATE_SHADOW_ENABLED
            ? new MetalRenderStateShadow()
            : null;
    private final @Nullable MetalRenderStatePacket statePacket =
            MetalRenderStatePacket.createIfAvailable();

    MTLRenderCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null && !this.stateShadow.setPipeline(pipeline)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null || !this.statePacket.appendPipeline(encoder, pipeline)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setRenderPipelineState(encoder, pipeline);
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null && !this.stateShadow.setDepthStencil(depthStencilState)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendDepthStencil(encoder, depthStencilState)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setDepthStencilState(
                    encoder,
                    depthStencilState
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null
                && !this.stateShadow.setDepthBias(depthBias, slopeScale, clamp)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendDepthBias(encoder, depthBias, slopeScale, clamp)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(
                    encoder,
                    depthBias,
                    slopeScale,
                    clamp
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null && !this.stateShadow.setWinding(winding.value)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendWinding(encoder, winding.value)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(
                    encoder,
                    winding.value
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setCullMode(final MTLCullMode cullMode) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null && !this.stateShadow.setCullMode(cullMode.value)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendCullMode(encoder, cullMode.value)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(encoder, cullMode.value);
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null && !this.stateShadow.setFillMode(fillMode.value)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendFillMode(encoder, fillMode.value)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(
                    encoder,
                    fillMode.value
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setBuffer(
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        MemorySegment encoder = handle();
        if (this.stateShadow == null) {
            if (this.statePacket == null
                    || !this.statePacket.appendBuffer(
                    encoder,
                    buffer,
                    offset,
                    index,
                    stageMask
            )) {
                MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(
                        encoder,
                        buffer,
                        offset,
                        index,
                        stageMask
                );
            }
            MetalHotPathTelemetry.renderForwarded();
            return;
        }

        MetalRenderStateShadow.BufferUpdate update = this.stateShadow.classifyBuffer(
                buffer,
                offset,
                index,
                stageMask
        );
        if (update == MetalRenderStateShadow.BufferUpdate.SKIP) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (update == MetalRenderStateShadow.BufferUpdate.OFFSET_ONLY) {
            if (this.statePacket == null
                    || !this.statePacket.appendBufferOffset(
                    encoder,
                    offset,
                    index,
                    stageMask
            )) {
                MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(
                        encoder,
                        offset,
                        index,
                        stageMask
                );
            }
            MetalHotPathTelemetry.renderOffsetOnly();
            MetalHotPathTelemetry.renderForwarded();
        } else {
            if (this.statePacket == null
                    || !this.statePacket.appendBuffer(
                    encoder,
                    buffer,
                    offset,
                    index,
                    stageMask
            )) {
                MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(
                        encoder,
                        buffer,
                        offset,
                        index,
                        stageMask
                );
            }
            MetalHotPathTelemetry.renderForwarded();
        }
        this.stateShadow.recordBuffer(buffer, offset, index, stageMask);
    }

    public void setBufferOffset(final long offset, final long index, final int stageMask) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null
                && !this.stateShadow.setBufferOffset(offset, index, stageMask)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendBufferOffset(
                encoder,
                offset,
                index,
                stageMask
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(
                    encoder,
                    offset,
                    index,
                    stageMask
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setTexture(final MemorySegment texture, final long index, final int stageMask) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null
                && !this.stateShadow.setTexture(texture, index, stageMask)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendTexture(
                encoder,
                texture,
                index,
                stageMask
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_setTexture(
                    encoder,
                    texture,
                    index,
                    stageMask
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setTextureAndSampler(
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null
                && !this.stateShadow.setTextureAndSampler(texture, sampler, index, stageMask)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendTextureAndSampler(
                encoder,
                texture,
                sampler,
                index,
                stageMask
        )) {
            MetalNativeBridge.MTLRenderCommandEncoder_setTextureAndSampler(
                    encoder,
                    texture,
                    sampler,
                    index,
                    stageMask
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        MemorySegment encoder = handle();
        if (this.stateShadow != null
                && !this.stateShadow.setScissor(x, y, width, height)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (this.statePacket == null
                || !this.statePacket.appendScissor(encoder, x, y, width, height)) {
            MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(
                    encoder,
                    x,
                    y,
                    width,
                    height
            );
        }
        MetalHotPathTelemetry.renderForwarded();
    }

    public void clearDraw(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final boolean clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final boolean clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_clearDraw(
                encoder,
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled ? 1 : 0,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled ? 1 : 0,
                clearDepth
        );
        // The native clear helper may install its own temporary pipeline and
        // bindings. Fail closed: the next ordinary draw repopulates all state.
        if (this.stateShadow != null) {
            this.stateShadow.invalidateAll();
        }
    }

    public void drawPrimitives(
            final MTLPrimitiveType primitiveType,
            final int firstVertex,
            final int vertexCount,
            final int instanceCount,
            final int baseInstance
    ) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitives(
                encoder,
                primitiveType.value,
                firstVertex,
                vertexCount,
                instanceCount,
                baseInstance
        );
    }

    public void drawIndexedPrimitives(
            final MTLPrimitiveType primitiveType,
            final int indexCount,
            final MTLIndexType indexType,
            final MemorySegment indexBuffer,
            final long offset,
            final int instanceCount,
            final int baseVertex,
            final int baseInstance
    ) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitives(
                encoder,
                primitiveType.value,
                indexCount,
                indexType.value,
                indexBuffer,
                offset,
                instanceCount,
                baseVertex,
                baseInstance
        );
    }

    public void drawIndexedPrimitivesIndirect(
            final MTLPrimitiveType primitiveType,
            final MTLIndexType indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final int drawCount,
            final long stride
    ) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
                encoder,
                primitiveType.value,
                indexType.value,
                indexBuffer,
                indirectBuffer,
                indirectBufferOffset,
                drawCount,
                stride
        );
    }

    public void drawPrimitivesIndirect(
            final MTLPrimitiveType primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final int drawCount,
            final long stride
    ) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitivesIndirect(
                encoder,
                primitiveType.value,
                indirectBuffer,
                indirectBufferOffset,
                drawCount,
                stride
        );
    }

    public void drawIndexedPrimitivesTriangleFan(
            final MemorySegment indexBuffer,
            final MemorySegment fanIndexBuffer,
            final long fanIndexBufferOffset,
            final long indexType,
            final long offset,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final int baseInstance
    ) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
                encoder,
                indexBuffer,
                fanIndexBuffer,
                fanIndexBufferOffset,
                indexType,
                offset,
                indexCount,
                baseVertex,
                instanceCount,
                baseInstance
        );
    }

    /**
     * Resolves a depth attachment created with {@code storeAction = .unknown}.
     * Must be called before {@code endEncoding()} on every encoder whose
     * descriptor deferred the depth store decision, and must not be called on
     * encoders whose descriptor set a concrete store action.
     */
    public void setDepthStoreAction(final boolean store) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_setDepthStoreAction(
                encoder,
                store ? 1 : 0
        );
    }

    public void updateFence(final MemorySegment fence, final MTLRenderStages stages) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_updateFence(
                encoder,
                fence,
                stages.value
        );
    }

    public void waitForFence(final MemorySegment fence, final MTLRenderStages stages) {
        MemorySegment encoder = handle();
        flushState(encoder);
        MetalNativeBridge.MTLRenderCommandEncoder_waitForFence(
                encoder,
                fence,
                stages.value
        );
    }

    @Override
    public void endEncoding() {
        if (!MetalNativeBridge.isNullHandle(this.handle)) {
            flushState(this.handle);
        }
        try {
            super.endEncoding();
        } finally {
            if (this.statePacket != null) {
                this.statePacket.close();
            }
        }
    }

    private void flushState(final MemorySegment encoder) {
        if (this.statePacket != null) {
            this.statePacket.flush(encoder);
        }
    }
}
