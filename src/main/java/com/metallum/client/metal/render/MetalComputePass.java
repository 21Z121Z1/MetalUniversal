package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLComputeCommandEncoder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Mod-private compute pass over one {@code MTLComputeCommandEncoder}.
 *
 * <p>Created via {@link MetalCommandEncoder#createComputePass(String)}; the
 * pass owns the encoder until {@link #close()}. Because all backend resources
 * are hazard-untracked, ordering against surrounding render/blit work is
 * provided by the encoder-level global fence chain — a compute pass therefore
 * observes all previously encoded writes and publishes its own writes to the
 * next encoder (Iris {@code glMemoryBarrier} semantics collapse onto these
 * encoder boundaries; see docs/iris_on_metal_architecture.md).</p>
 *
 * <p>Binding indices follow the {@link MetalComputePipeline} contract:
 * buffer-class GLSL bindings map to {@code setBuffer(index)}, image/texture
 * bindings to {@code setTexture(index)}.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalComputePass implements AutoCloseable {
    private final MetalCommandEncoder owner;
    private final MTLComputeCommandEncoder encoder;
    @Nullable
    private MetalComputePipeline pipeline;
    private boolean closed;

    MetalComputePass(final MetalCommandEncoder owner, final MTLComputeCommandEncoder encoder) {
        this.owner = owner;
        this.encoder = encoder;
    }

    MetalComputePass setPipeline(final MetalComputePipeline pipeline) {
        ensureOpen();
        this.pipeline = pipeline;
        encoder.setComputePipelineState(pipeline.pipelineStateHandle());
        return this;
    }

    MetalComputePass bindBuffer(final int index, final MetalGpuBuffer buffer, final long offset) {
        ensureOpen();
        encoder.setBuffer(buffer.nativeHandle(), offset, index);
        return this;
    }

    MetalComputePass bindBuffer(final int index, final MetalGpuBuffer buffer) {
        return bindBuffer(index, buffer, 0L);
    }

    MetalComputePass bindTexture(final int index, final MetalGpuTexture texture) {
        ensureOpen();
        if (owner.hasPendingClear(texture)) {
            throw new IllegalStateException(
                    "Texture " + texture.getLabel() + " has an unflushed deferred clear registered after this"
                            + " compute pass opened; encode clears before creating the pass"
            );
        }
        encoder.setTexture(texture.nativeHandle(), index);
        return this;
    }

    MetalComputePass bindTextureView(final int index, final MetalGpuTextureView view) {
        ensureOpen();
        encoder.setTexture(view.nativeHandle(), index);
        return this;
    }

    MetalComputePass bindSampler(final int index, final MemorySegment samplerHandle) {
        ensureOpen();
        encoder.setSamplerState(samplerHandle, index);
        return this;
    }

    /**
     * Dispatches whole threadgroups using the pipeline's reflected
     * {@code local_size} (GL {@code glDispatchCompute} semantics: the caller
     * supplies group counts, not thread counts).
     */
    MetalComputePass dispatchGroups(final int groupsX, final int groupsY, final int groupsZ) {
        ensureOpen();
        MetalComputePipeline bound = requirePipeline();
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new IllegalArgumentException(
                    "Dispatch group counts must be positive: " + groupsX + "x" + groupsY + "x" + groupsZ
            );
        }
        encoder.dispatchThreadgroups(
                groupsX, groupsY, groupsZ,
                bound.threadgroupWidth(), bound.threadgroupHeight(), bound.threadgroupDepth()
        );
        return this;
    }

    /**
     * Dispatches enough threadgroups to cover the given thread grid (relative
     * dispatch: sizes are rounded up to whole groups).
     */
    MetalComputePass dispatchThreadsCovering(final int threadsX, final int threadsY, final int threadsZ) {
        MetalComputePipeline bound = requirePipeline();
        return dispatchGroups(
                Math.ceilDiv(threadsX, bound.threadgroupWidth()),
                Math.ceilDiv(threadsY, bound.threadgroupHeight()),
                Math.ceilDiv(threadsZ, bound.threadgroupDepth())
        );
    }

    /**
     * Indirect dispatch reading {@code MTLDispatchThreadgroupsIndirectArguments}
     * (three uint32 group counts — identical layout to GL's
     * {@code glDispatchComputeIndirect} argument block) at {@code offset}.
     */
    MetalComputePass dispatchIndirect(final MetalGpuBuffer argumentBuffer, final long offset) {
        ensureOpen();
        MetalComputePipeline bound = requirePipeline();
        encoder.dispatchThreadgroupsIndirect(
                argumentBuffer.nativeHandle(),
                offset,
                bound.threadgroupWidth(), bound.threadgroupHeight(), bound.threadgroupDepth()
        );
        return this;
    }

    private MetalComputePipeline requirePipeline() {
        if (pipeline == null) {
            throw new IllegalStateException("No compute pipeline bound before dispatch");
        }
        return pipeline;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Compute pass is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.endComputePass(encoder);
    }
}
