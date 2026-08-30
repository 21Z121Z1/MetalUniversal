package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    private MemorySegment handle;
    private final boolean trackMetal4MainRenderer;
    private boolean metal4Submitted;
    private boolean metal4CompletionRecorded;
    private boolean presentEncodeCalled;
    private boolean presentSubmitted;
    private boolean presentCompletionRecorded;
    private long nativePresentationTelemetryId;

    MTLCommandBuffer(final MemorySegment handle) {
        this(handle, false);
    }

    MTLCommandBuffer(final MemorySegment handle, final boolean trackMetal4MainRenderer) {
        this.handle = handle;
        this.trackMetal4MainRenderer = trackMetal4MainRenderer;
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder(final String label) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(handle(), label);
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
        }
        return new MTLBlitCommandEncoder(encoder);
    }

    public MTLComputeCommandEncoder makeComputeCommandEncoder() {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeComputeCommandEncoder(handle());
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLComputeCommandEncoder");
        }
        return new MTLComputeCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(
                handle(),
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled,
                clearDepth
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoderV2(
            final MemorySegment[] colorTextures,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColors,
            final int clearDepthEnabled,
            final double clearDepth,
            final String label
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV2(
                handle(),
                colorTextures,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColors,
                clearDepthEnabled,
                clearDepth,
                label
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create indexed MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    /**
     * RenderPassDescriptorV3: per-attachment load/store actions.
     * Load actions: 0=dontCare, 1=load, 2=clear. Store actions:
     * 0=dontCare, 1=store, 2=deferred(.unknown, must be resolved before
     * endEncoding exactly like the V2 deferred-depth contract).
     */
    public MTLRenderCommandEncoder makeRenderCommandEncoderV3(
            final MemorySegment[] colorTextures,
            final MemorySegment depthTexture,
            final int[] colorLoadActions,
            final int[] colorStoreActions,
            final float[] clearColors,
            final int depthLoadAction,
            final int depthStoreAction,
            final double clearDepth,
            final double viewportWidth,
            final double viewportHeight,
            final String label
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV3(
                handle(),
                colorTextures,
                depthTexture,
                colorLoadActions,
                colorStoreActions,
                clearColors,
                depthLoadAction,
                depthStoreAction,
                clearDepth,
                viewportWidth,
                viewportHeight,
                label
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create indexed MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MemorySegment globalFence
    ) {
        MetalNativeBridge.MTLCommandBuffer_clearColorDepthTexturesRegion(
                handle(),
                colorTexture,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public void encodePresentTextureToDrawable(
            final MemorySegment layer,
            final MemorySegment sourceTexture,
            final MemorySegment globalFence
    ) {
        MetalPresentationTelemetry.recordEncodeCall();
        presentEncodeCalled = true;
        nativePresentationTelemetryId = MetalNativeBridge.MTLCommandBuffer_encodePresentTextureToDrawable(
                handle(), layer, sourceTexture, globalFence
        );
    }

    public void commit() {
        MetalNativeBridge.MTLCommandBuffer_commit(handle());
        recordMetal4Submitted();
        recordPresentSubmitted();
    }

    public void commitWithSignal(final MemorySegment semaphore) {
        MetalNativeBridge.MTLCommandBuffer_commitWithSignal(handle(), semaphore);
        recordMetal4Submitted();
        recordPresentSubmitted();
    }

    public boolean isCompleted() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return true;
        }
        boolean completed = MetalNativeBridge.MTLCommandBuffer_isCompleted(handle()) == 1;
        if (completed) {
            recordMetal4Completion();
        }
        return completed;
    }

    public boolean completedSuccessfully() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return false;
        }
        boolean success = MetalNativeBridge.MTLCommandBuffer_completedSuccessfully(handle()) == 1;
        if (trackMetal4MainRenderer && metal4Submitted && !metal4CompletionRecorded
                && MetalNativeBridge.MTLCommandBuffer_isCompleted(handle()) == 1) {
            recordMetal4Completion();
        }
        if (presentSubmitted && !presentCompletionRecorded) {
            presentCompletionRecorded = true;
            MetalPresentationTelemetry.recordCompletion(success);
        }
        return success;
    }

    public double gpuStartTime() {
        return MetalNativeBridge.isNullHandle(handle)
                ? 0.0 : MetalNativeBridge.MTLCommandBuffer_gpuStartTime(handle());
    }

    public double gpuEndTime() {
        return MetalNativeBridge.isNullHandle(handle)
                ? 0.0 : MetalNativeBridge.MTLCommandBuffer_gpuEndTime(handle());
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return true;
        }
        boolean completed = MetalNativeBridge.MTLCommandBuffer_waitUntilCompleted(
                handle(), Math.max(timeoutMs, 0L)
        ) == 0;
        if (completed) {
            recordMetal4Completion();
        }
        return completed;
    }

    public void pushDebugGroup(final String label) {
        MetalNativeBridge.MTLCommandBuffer_pushDebugGroup(handle(), label);
    }

    public void popDebugGroup() {
        MetalNativeBridge.MTLCommandBuffer_popDebugGroup(handle());
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        if (trackMetal4MainRenderer && metal4Submitted && !metal4CompletionRecorded
                && MetalNativeBridge.MTLCommandBuffer_isCompleted(handle()) == 1) {
            recordMetal4Completion();
        }
        if (presentEncodeCalled && !presentSubmitted && nativePresentationTelemetryId > 0L) {
            MetalNativeBridge.metallum_presentation_cancel(nativePresentationTelemetryId);
            nativePresentationTelemetryId = 0L;
        }
        MetalNativeBridge.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }

    public MemorySegment nativeHandle() {
        return handle();
    }

    private void recordMetal4Submitted() {
        if (trackMetal4MainRenderer && !metal4Submitted) {
            metal4Submitted = true;
            Metal4MainRendererTelemetry.recordCommit();
        }
    }

    private void recordMetal4Completion() {
        if (trackMetal4MainRenderer && metal4Submitted && !metal4CompletionRecorded) {
            metal4CompletionRecorded = true;
            Metal4MainRendererTelemetry.recordCompletion();
        }
    }

    private void recordPresentSubmitted() {
        if (presentEncodeCalled && !presentSubmitted) {
            presentSubmitted = true;
            // After commit the native presented/completion handlers own this
            // id; Java must not cancel it when the wrapper is later closed.
            nativePresentationTelemetryId = 0L;
            MetalPresentationTelemetry.recordSubmitted();
        }
    }

    private MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
