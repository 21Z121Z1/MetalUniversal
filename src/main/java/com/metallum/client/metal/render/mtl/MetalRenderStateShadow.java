package com.metallum.client.metal.render.mtl;

import java.lang.foreign.MemorySegment;

/**
 * Allocation-free shadow of state that is sticky on one native
 * {@code MTLRenderCommandEncoder}.
 *
 * <p>The design mirrors MobileGL's command-buffer-local dynamic-state shadow:
 * repeated Java calls are classified before they cross FFM. State is tracked
 * per shader stage bit, so a combined vertex+fragment bind correctly seeds
 * both individual stage shadows.</p>
 */
final class MetalRenderStateShadow {
    enum BufferUpdate {
        SKIP,
        OFFSET_ONLY,
        FULL_BIND
    }

    private static final int MAX_STAGE_BITS = 8;
    private static final int DEFAULT_MAX_BINDINGS = 64;

    private final int maxBindings;
    private final boolean[][] bufferValid;
    private final long[][] bufferAddress;
    private final long[][] bufferOffset;
    private final boolean[][] textureValid;
    private final long[][] textureAddress;
    private final boolean[][] samplerValid;
    private final long[][] samplerAddress;

    private boolean pipelineValid;
    private long pipelineAddress;
    private boolean depthStencilValid;
    private long depthStencilAddress;
    private boolean depthBiasValid;
    private int depthBiasBits;
    private int slopeScaleBits;
    private int depthBiasClampBits;
    private boolean windingValid;
    private int winding;
    private boolean cullModeValid;
    private long cullMode;
    private boolean fillModeValid;
    private int fillMode;
    private boolean scissorValid;
    private long scissorX;
    private long scissorY;
    private long scissorWidth;
    private long scissorHeight;

    MetalRenderStateShadow() {
        this(resolveMaxBindings());
    }

    MetalRenderStateShadow(final int maxBindings) {
        if (maxBindings <= 0) {
            throw new IllegalArgumentException("maxBindings must be positive");
        }
        this.maxBindings = maxBindings;
        this.bufferValid = new boolean[MAX_STAGE_BITS][maxBindings];
        this.bufferAddress = new long[MAX_STAGE_BITS][maxBindings];
        this.bufferOffset = new long[MAX_STAGE_BITS][maxBindings];
        this.textureValid = new boolean[MAX_STAGE_BITS][maxBindings];
        this.textureAddress = new long[MAX_STAGE_BITS][maxBindings];
        this.samplerValid = new boolean[MAX_STAGE_BITS][maxBindings];
        this.samplerAddress = new long[MAX_STAGE_BITS][maxBindings];
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

    boolean setDepthStencil(final MemorySegment depthStencil) {
        long address = address(depthStencil);
        if (depthStencilValid && depthStencilAddress == address) {
            return false;
        }
        depthStencilValid = true;
        depthStencilAddress = address;
        return true;
    }

    boolean setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        int depthBits = Float.floatToRawIntBits(depthBias);
        int slopeBits = Float.floatToRawIntBits(slopeScale);
        int clampBits = Float.floatToRawIntBits(clamp);
        if (depthBiasValid
                && depthBiasBits == depthBits
                && slopeScaleBits == slopeBits
                && depthBiasClampBits == clampBits) {
            return false;
        }
        depthBiasValid = true;
        depthBiasBits = depthBits;
        slopeScaleBits = slopeBits;
        depthBiasClampBits = clampBits;
        return true;
    }

    boolean setWinding(final int value) {
        if (windingValid && winding == value) {
            return false;
        }
        windingValid = true;
        winding = value;
        return true;
    }

    boolean setCullMode(final long value) {
        if (cullModeValid && cullMode == value) {
            return false;
        }
        cullModeValid = true;
        cullMode = value;
        return true;
    }

    boolean setFillMode(final int value) {
        if (fillModeValid && fillMode == value) {
            return false;
        }
        fillModeValid = true;
        fillMode = value;
        return true;
    }

    BufferUpdate classifyBuffer(
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        if (!trackable(index, stageMask)) {
            return BufferUpdate.FULL_BIND;
        }
        long address = address(buffer);
        boolean everyStageHasSameBuffer = true;
        boolean everyStageHasSameOffset = true;
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) == 0) {
                continue;
            }
            int slot = (int) index;
            if (!bufferValid[bit][slot] || bufferAddress[bit][slot] != address) {
                everyStageHasSameBuffer = false;
                everyStageHasSameOffset = false;
                break;
            }
            if (bufferOffset[bit][slot] != offset) {
                everyStageHasSameOffset = false;
            }
        }
        if (everyStageHasSameOffset) {
            return BufferUpdate.SKIP;
        }
        // Metal's offset-only setters require the argument-table entry to
        // already contain an MTLBuffer. A recorded nil binding has address 0,
        // so changing its offset must remain a full setBuffer(nil, ...) update
        // rather than encoding set*BufferOffset against an empty slot.
        if (address == 0L) {
            return BufferUpdate.FULL_BIND;
        }
        return everyStageHasSameBuffer ? BufferUpdate.OFFSET_ONLY : BufferUpdate.FULL_BIND;
    }

    void recordBuffer(
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        if (!trackable(index, stageMask)) {
            return;
        }
        long address = address(buffer);
        int slot = (int) index;
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) == 0) {
                continue;
            }
            bufferValid[bit][slot] = true;
            bufferAddress[bit][slot] = address;
            bufferOffset[bit][slot] = offset;
        }
    }

    boolean setBufferOffset(final long offset, final long index, final int stageMask) {
        if (!trackable(index, stageMask)) {
            return true;
        }
        int slot = (int) index;
        boolean allKnownAndEqual = true;
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) == 0) {
                continue;
            }
            if (!bufferValid[bit][slot] || bufferOffset[bit][slot] != offset) {
                allKnownAndEqual = false;
                break;
            }
        }
        if (allKnownAndEqual) {
            return false;
        }
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) != 0 && bufferValid[bit][slot]) {
                bufferOffset[bit][slot] = offset;
            }
        }
        return true;
    }

    boolean setTexture(final MemorySegment texture, final long index, final int stageMask) {
        if (!trackable(index, stageMask)) {
            return true;
        }
        long address = address(texture);
        int slot = (int) index;
        boolean allEqual = true;
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) == 0) {
                continue;
            }
            if (!textureValid[bit][slot] || textureAddress[bit][slot] != address) {
                allEqual = false;
                break;
            }
        }
        if (allEqual) {
            return false;
        }
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) != 0) {
                textureValid[bit][slot] = true;
                textureAddress[bit][slot] = address;
            }
        }
        return true;
    }

    boolean setTextureAndSampler(
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        if (!trackable(index, stageMask)) {
            return true;
        }
        long textureValue = address(texture);
        long samplerValue = address(sampler);
        int slot = (int) index;
        boolean allEqual = true;
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) == 0) {
                continue;
            }
            if (!textureValid[bit][slot]
                    || textureAddress[bit][slot] != textureValue
                    || !samplerValid[bit][slot]
                    || samplerAddress[bit][slot] != samplerValue) {
                allEqual = false;
                break;
            }
        }
        if (allEqual) {
            return false;
        }
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) != 0) {
                textureValid[bit][slot] = true;
                textureAddress[bit][slot] = textureValue;
                samplerValid[bit][slot] = true;
                samplerAddress[bit][slot] = samplerValue;
            }
        }
        return true;
    }

    boolean setScissor(final long x, final long y, final long width, final long height) {
        if (scissorValid
                && scissorX == x
                && scissorY == y
                && scissorWidth == width
                && scissorHeight == height) {
            return false;
        }
        scissorValid = true;
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;
        return true;
    }

    void invalidateAll() {
        pipelineValid = false;
        depthStencilValid = false;
        depthBiasValid = false;
        windingValid = false;
        cullModeValid = false;
        fillModeValid = false;
        scissorValid = false;
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            java.util.Arrays.fill(bufferValid[bit], false);
            java.util.Arrays.fill(textureValid[bit], false);
            java.util.Arrays.fill(samplerValid[bit], false);
        }
    }

    private boolean trackable(final long index, final int stageMask) {
        return index >= 0
                && index < maxBindings
                && stageMask > 0
                && (stageMask >>> MAX_STAGE_BITS) == 0;
    }

    private static long address(final MemorySegment segment) {
        return segment == null ? 0L : segment.address();
    }
}
