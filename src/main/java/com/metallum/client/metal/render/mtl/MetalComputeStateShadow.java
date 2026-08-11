package com.metallum.client.metal.render.mtl;

import java.lang.foreign.MemorySegment;

/** Sticky state shadow for one native {@code MTLComputeCommandEncoder}. */
final class MetalComputeStateShadow {
    private static final int DEFAULT_MAX_BINDINGS = 64;

    private final int maxBindings;
    private final boolean[] bufferValid;
    private final long[] bufferAddress;
    private final long[] bufferOffset;
    private final boolean[] textureValid;
    private final long[] textureAddress;
    private final boolean[] samplerValid;
    private final long[] samplerAddress;

    private boolean pipelineValid;
    private long pipelineAddress;

    MetalComputeStateShadow() {
        this(resolveMaxBindings());
    }

    MetalComputeStateShadow(final int maxBindings) {
        if (maxBindings <= 0) {
            throw new IllegalArgumentException("maxBindings must be positive");
        }
        this.maxBindings = maxBindings;
        this.bufferValid = new boolean[maxBindings];
        this.bufferAddress = new long[maxBindings];
        this.bufferOffset = new long[maxBindings];
        this.textureValid = new boolean[maxBindings];
        this.textureAddress = new long[maxBindings];
        this.samplerValid = new boolean[maxBindings];
        this.samplerAddress = new long[maxBindings];
    }

    private static int resolveMaxBindings() {
        int configured = Integer.getInteger("metallum.opt.maxShadowedBindings", DEFAULT_MAX_BINDINGS);
        return Math.max(8, Math.min(configured, 256));
    }

    boolean setPipeline(final MemorySegment pipeline) {
        long address = address(pipeline);
        if (pipelineValid && pipelineAddress == address) {
            return false;
        }
        pipelineValid = true;
        pipelineAddress = address;
        return true;
    }

    boolean setBuffer(final MemorySegment buffer, final long offset, final int index) {
        if (!trackable(index)) {
            return true;
        }
        long address = address(buffer);
        if (bufferValid[index]
                && bufferAddress[index] == address
                && bufferOffset[index] == offset) {
            return false;
        }
        bufferValid[index] = true;
        bufferAddress[index] = address;
        bufferOffset[index] = offset;
        return true;
    }

    boolean setTexture(final MemorySegment texture, final int index) {
        if (!trackable(index)) {
            return true;
        }
        long address = address(texture);
        if (textureValid[index] && textureAddress[index] == address) {
            return false;
        }
        textureValid[index] = true;
        textureAddress[index] = address;
        return true;
    }

    boolean setSampler(final MemorySegment sampler, final int index) {
        if (!trackable(index)) {
            return true;
        }
        long address = address(sampler);
        if (samplerValid[index] && samplerAddress[index] == address) {
            return false;
        }
        samplerValid[index] = true;
        samplerAddress[index] = address;
        return true;
    }

    private boolean trackable(final int index) {
        return index >= 0 && index < maxBindings;
    }

    private static long address(final MemorySegment segment) {
        return segment == null ? 0L : segment.address();
    }
}
