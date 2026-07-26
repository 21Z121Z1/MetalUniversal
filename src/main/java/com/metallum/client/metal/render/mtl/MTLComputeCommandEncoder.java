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

    MTLComputeCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setComputePipelineState(final MemorySegment pipelineState) {
        MetalNativeBridge.MTLComputeCommandEncoder_setComputePipelineState(handle(), pipelineState);
    }

    public void setBuffer(final MemorySegment buffer, final long offset, final int index) {
        MetalNativeBridge.MTLComputeCommandEncoder_setBuffer(handle(), buffer, offset, index);
    }

    public void setTexture(final MemorySegment texture, final int index) {
        MetalNativeBridge.MTLComputeCommandEncoder_setTexture(handle(), texture, index);
    }

    public void setSamplerState(final MemorySegment sampler, final int index) {
        MetalNativeBridge.MTLComputeCommandEncoder_setSamplerState(handle(), sampler, index);
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
