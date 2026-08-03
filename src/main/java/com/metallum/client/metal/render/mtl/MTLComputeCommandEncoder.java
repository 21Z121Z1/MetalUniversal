package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

/**
 * Compute command encoder participating in the backend's single-MTLFence
 * hazard chain: the owner must {@link #waitForFence} right after creation and
 * {@link #updateFence} before {@link #endEncoding()}, mirroring how render and
 * blit encoders are sequenced by {@code MetalCommandEncoder}.
 */
@Environment(EnvType.CLIENT)
public final class MTLComputeCommandEncoder extends MTLCommandEncoder {
    private static final boolean STATE_SHADOW_ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.encoderStateShadow", "true")
    );

    private final MetalComputeStateShadow stateShadow = STATE_SHADOW_ENABLED
            ? new MetalComputeStateShadow()
            : null;

    MTLComputeCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setComputePipelineState(final MemorySegment pipelineState) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setPipeline(pipelineState)) {
            MetalHotPathTelemetry.computeSuppressed();
            return;
        }
        MetalNativeBridge.MTLComputeCommandEncoder_setComputePipelineState(encoder, pipelineState);
        MetalHotPathTelemetry.computeForwarded();
    }

    public void setBuffer(final MemorySegment buffer, final long offset, final int index) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setBuffer(buffer, offset, index)) {
            MetalHotPathTelemetry.computeSuppressed();
            return;
        }
        MetalNativeBridge.MTLComputeCommandEncoder_setBuffer(encoder, buffer, offset, index);
        MetalHotPathTelemetry.computeForwarded();
    }

    public void setTexture(final MemorySegment texture, final int index) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setTexture(texture, index)) {
            MetalHotPathTelemetry.computeSuppressed();
            return;
        }
        MetalNativeBridge.MTLComputeCommandEncoder_setTexture(encoder, texture, index);
        MetalHotPathTelemetry.computeForwarded();
    }

    public void setSamplerState(final MemorySegment sampler, final int index) {
        MemorySegment encoder = handle();
        if (stateShadow != null && !stateShadow.setSampler(sampler, index)) {
            MetalHotPathTelemetry.computeSuppressed();
            return;
        }
        MetalNativeBridge.MTLComputeCommandEncoder_setSamplerState(encoder, sampler, index);
        MetalHotPathTelemetry.computeForwarded();
    }

    public void dispatchThreadgroups(
            final int groupsX,
            final int groupsY,
            final int groupsZ,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        MetalNativeBridge.MTLComputeCommandEncoder_dispatchThreadgroups(
                handle(), groupsX, groupsY, groupsZ, threadsPerGroupX, threadsPerGroupY, threadsPerGroupZ
        );
    }

    public void dispatchThreadgroupsIndirect(
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        MetalNativeBridge.MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
                handle(), indirectBuffer, indirectOffset, threadsPerGroupX, threadsPerGroupY, threadsPerGroupZ
        );
    }

    public void updateFence(final MemorySegment fence) {
        MetalNativeBridge.MTLComputeCommandEncoder_updateFence(handle(), fence);
    }

    public void waitForFence(final MemorySegment fence) {
        MetalNativeBridge.MTLComputeCommandEncoder_waitForFence(handle(), fence);
    }
}
