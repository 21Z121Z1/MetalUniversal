package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Compute command encoder participating in the backend's single-MTLFence
 * hazard chain. Experimental compute packets preserve setter/dispatch order
 * and flush before every synchronization or encoder-lifetime boundary.
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
        MemorySegment encoder = handle();
        if (commandPacket == null || !commandPacket.dispatch(
                encoder,
                groupsX,
                groupsY,
                groupsZ,
                threadsPerGroupX,
                threadsPerGroupY,
                threadsPerGroupZ
        )) {
            MetalNativeBridge.MTLComputeCommandEncoder_dispatchThreadgroups(
                    encoder,
                    groupsX,
                    groupsY,
                    groupsZ,
                    threadsPerGroupX,
                    threadsPerGroupY,
                    threadsPerGroupZ
            );
        }
    }

    public void dispatchThreadgroupsIndirect(
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        MemorySegment encoder = handle();
        if (commandPacket == null || !commandPacket.dispatchIndirect(
                encoder,
                indirectBuffer,
                indirectOffset,
                threadsPerGroupX,
                threadsPerGroupY,
                threadsPerGroupZ
        )) {
            MetalNativeBridge.MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
                    encoder,
                    indirectBuffer,
                    indirectOffset,
                    threadsPerGroupX,
                    threadsPerGroupY,
                    threadsPerGroupZ
            );
        }
    }

    public void updateFence(final MemorySegment fence) {
        MemorySegment encoder = handle();
        flushCommands(encoder);
        MetalNativeBridge.MTLComputeCommandEncoder_updateFence(encoder, fence);
    }

    public void waitForFence(final MemorySegment fence) {
        MemorySegment encoder = handle();
        flushCommands(encoder);
        MetalNativeBridge.MTLComputeCommandEncoder_waitForFence(encoder, fence);
    }

    @Override
    public void endEncoding() {
        if (!MetalNativeBridge.isNullHandle(this.handle)) {
            flushCommands(this.handle);
        }
        try {
            super.endEncoding();
        } finally {
            if (commandPacket != null) {
                commandPacket.close();
            }
        }
    }

    private void flushCommands(final MemorySegment encoder) {
        if (commandPacket != null) {
            commandPacket.flush(encoder);
        }
    }
}
