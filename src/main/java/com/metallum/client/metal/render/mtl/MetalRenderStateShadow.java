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
    private static final boolean REUSE = Boolean.getBoolean("metallum.opt.reuseEncoderState");
    // At most one idle CPU shadow per encoding thread; concurrent encoders never share it.
    private static final ThreadLocal<MetalRenderStateShadow> IDLE = new ThreadLocal<>();

    static MetalRenderStateShadow acquire() {
        MetalRenderStateShadow shadow = REUSE ? IDLE.get() : null;
        if (shadow == null) return new MetalRenderStateShadow();
        IDLE.set(null);
        return shadow;
    }

    void recycle() {
        if (!REUSE) return;
        invalidateAll();
        if (IDLE.get() == null) IDLE.set(this);
    }

    enum BufferUpdate {
        SKIP,
        OFFSET_ONLY,
        FULL_BIND
    }

    private static final int MAX_STAGE_BITS = 8;
    private static final int DEFAULT_MAX_BINDINGS = 64;

    private final int maxBindings;
    private int generation = 1;
    private final int[][] bufferGeneration;
    private final long[][] bufferAddress;
    private final long[][] bufferOffset;
    private final int[][] textureGeneration;
    private final long[][] textureAddress;
    private final int[][] samplerGeneration;
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
        this.bufferGeneration = new int[MAX_STAGE_BITS][maxBindings];
        this.bufferAddress = new long[MAX_STAGE_BITS][maxBindings];
        this.bufferOffset = new long[MAX_STAGE_BITS][maxBindings];
        this.textureGeneration = new int[MAX_STAGE_BITS][maxBindings];
        this.textureAddress = new long[MAX_STAGE_BITS][maxBindings];
        this.samplerGeneration = new int[MAX_STAGE_BITS][maxBindings];
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
            if (bufferGeneration[bit][slot] != generation || bufferAddress[bit][slot] != address) {
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
            bufferGeneration[bit][slot] = generation;
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
            if (bufferGeneration[bit][slot] != generation || bufferOffset[bit][slot] != offset) {
                allKnownAndEqual = false;
                break;
            }
        }
        if (allKnownAndEqual) {
            return false;
        }
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) != 0 && bufferGeneration[bit][slot] == generation) {
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
            if (textureGeneration[bit][slot] != generation || textureAddress[bit][slot] != address) {
                allEqual = false;
                break;
            }
        }
        if (allEqual) {
            return false;
        }
        for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
            if ((stageMask & (1 << bit)) != 0) {
                textureGeneration[bit][slot] = generation;
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
            if (textureGeneration[bit][slot] != generation
                    || textureAddress[bit][slot] != textureValue
                    || samplerGeneration[bit][slot] != generation
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
                textureGeneration[bit][slot] = generation;
                textureAddress[bit][slot] = textureValue;
                samplerGeneration[bit][slot] = generation;
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
        // Most encoders bind only a few slots. Retiring their CPU shadow must
        // not scan all stage/binding arrays on every pass.
        if (generation == Integer.MAX_VALUE) {
            for (int bit = 0; bit < MAX_STAGE_BITS; bit++) {
                java.util.Arrays.fill(bufferGeneration[bit], 0);
                java.util.Arrays.fill(textureGeneration[bit], 0);
                java.util.Arrays.fill(samplerGeneration[bit], 0);
            }
            generation = 1;
        } else {
            generation++;
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
