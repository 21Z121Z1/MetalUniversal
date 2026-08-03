package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private static final boolean STATE_SHADOW_ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.encoderStateShadow", "true")
    );

    private final MetalRenderStateShadow stateShadow = STATE_SHADOW_ENABLED
            ? new MetalRenderStateShadow()
            : null;

    MTLRenderCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setPipeline(pipeline)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setRenderPipelineState(encoder, pipeline);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setDepthStencil(depthStencilState)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setDepthStencilState(encoder, depthStencilState);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setDepthBias(depthBias, slopeScale, clamp)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(encoder, depthBias, slopeScale, clamp);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setWinding(winding.value)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(encoder, winding.value);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setCullMode(final MTLCullMode cullMode) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setCullMode(cullMode.value)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(encoder, cullMode.value);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setFillMode(fillMode.value)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(encoder, fillMode.value);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setBuffer(final MemorySegment buffer, final long offset, final long index, final int stageMask) {
        MemorySegment encoder = handle();
        if (stateShadow == null) {
            MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(encoder, buffer, offset, index, stageMask);
            MetalHotPathTelemetry.renderForwarded();
            return;
        }

        MetalRenderStateShadow.BufferUpdate update = stateShadow.classifyBuffer(buffer, offset, index, stageMask);
        if (update == MetalRenderStateShadow.BufferUpdate.SKIP) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        if (update == MetalRenderStateShadow.BufferUpdate.OFFSET_ONLY) {
            MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(encoder, offset, index, stageMask);
            MetalHotPathTelemetry.renderOffsetOnly();
            MetalHotPathTelemetry.renderForwarded();
        } else {
            MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(encoder, buffer, offset, index, stageMask);
            MetalHotPathTelemetry.renderForwarded();
        }
        stateShadow.recordBuffer(buffer, offset, index, stageMask);
    }

    public void setBufferOffset(final long offset, final long index, final int stageMask) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setBufferOffset(offset, index, stageMask)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(encoder, offset, index, stageMask);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setTexture(final MemorySegment texture, final long index, final int stageMask) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setTexture(texture, index, stageMask)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setTexture(encoder, texture, index, stageMask);
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setTextureAndSampler(
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setTextureAndSampler(texture, sampler, index, stageMask)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setTextureAndSampler(
                encoder,
                texture,
                sampler,
                index,
                stageMask
        );
        MetalHotPathTelemetry.renderForwarded();
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setScissor(x, y, width, height)) {
            MetalHotPathTelemetry.renderSuppressed();
            return;
        }
        MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(encoder, x, y, width, height);
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
        MetalNativeBridge.MTLRenderCommandEncoder_clearDraw(
                handle(),
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
        if (stateShadow != null) {
            stateShadow.invalidateAll();
        }
    }

    public void drawPrimitives(
            final MTLPrimitiveType primitiveType,
            final int firstVertex,
            final int vertexCount,
            final int instanceCount,
            final int baseInstance
    ) {
        MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitives(
                handle(),
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
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitives(
                handle(),
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
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
                handle(),
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
        MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitivesIndirect(
                handle(),
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
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
                handle(),
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
        MetalNativeBridge.MTLRenderCommandEncoder_setDepthStoreAction(handle(), store ? 1 : 0);
    }

    public void updateFence(final MemorySegment fence, final MTLRenderStages stages) {
        MetalNativeBridge.MTLRenderCommandEncoder_updateFence(handle(), fence, stages.value);
    }

    public void waitForFence(final MemorySegment fence, final MTLRenderStages stages) {
        MetalNativeBridge.MTLRenderCommandEncoder_waitForFence(handle(), fence, stages.value);
    }
}
