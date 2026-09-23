package com.metallum.client.metal.render;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Per-program, per-in-flight-slot descriptor snapshot shared by the Metal 3
 * argument-buffer and Metal 4 argument-table implementations.
 *
 * <p>No mutable table is shared between draws. A caller obtains one slot,
 * updates only dirty entries, encodes it, then advances the slot after command
 * buffer submission.</p>
 */
final class IrisMetalArgumentSnapshot {
    private final IrisMetalOptimizationPlan.ArgumentLayout layout;
    private final MemorySegment[] buffers;
    private final long[] bufferOffsets;
    private final MemorySegment[] textures;
    private final MemorySegment[] samplers;
    private final BitSet dirtyBuffers;
    private final BitSet dirtyTextures;
    private final BitSet dirtySamplers;
    private long generation;

    IrisMetalArgumentSnapshot(final IrisMetalOptimizationPlan.ArgumentLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
        int maxBuffer = -1;
        int maxTexture = -1;
        int maxSampler = -1;
        for (IrisMetalOptimizationPlan.ArgumentSlot slot : layout.slots()) {
            switch (slot.kind()) {
                case BUFFER -> maxBuffer = Math.max(maxBuffer, slot.index());
                case TEXTURE -> maxTexture = Math.max(maxTexture, slot.index());
                case SAMPLER -> maxSampler = Math.max(maxSampler, slot.index());
            }
        }
        this.buffers = new MemorySegment[maxBuffer + 1];
        this.bufferOffsets = new long[maxBuffer + 1];
        this.textures = new MemorySegment[maxTexture + 1];
        this.samplers = new MemorySegment[maxSampler + 1];
        Arrays.fill(this.buffers, MemorySegment.NULL);
        Arrays.fill(this.textures, MemorySegment.NULL);
        Arrays.fill(this.samplers, MemorySegment.NULL);
        this.dirtyBuffers = new BitSet(this.buffers.length);
        this.dirtyTextures = new BitSet(this.textures.length);
        this.dirtySamplers = new BitSet(this.samplers.length);
    }

    IrisMetalOptimizationPlan.ArgumentLayout layout() {
        return layout;
    }

    long generation() {
        return generation;
    }

    void bindBuffer(final int index, final MemorySegment handle, final long offset) {
        requireIndex(index, buffers.length, "buffer");
        MemorySegment normalized = handle == null ? MemorySegment.NULL : handle;
        if (!sameHandle(buffers[index], normalized) || bufferOffsets[index] != offset) {
            buffers[index] = normalized;
            bufferOffsets[index] = offset;
            dirtyBuffers.set(index);
            generation++;
        }
    }

    void bindTexture(final int index, final MemorySegment handle) {
        requireIndex(index, textures.length, "texture");
        MemorySegment normalized = handle == null ? MemorySegment.NULL : handle;
        if (!sameHandle(textures[index], normalized)) {
            textures[index] = normalized;
            dirtyTextures.set(index);
            generation++;
        }
    }

    void bindSampler(final int index, final MemorySegment handle) {
        requireIndex(index, samplers.length, "sampler");
        MemorySegment normalized = handle == null ? MemorySegment.NULL : handle;
        if (!sameHandle(samplers[index], normalized)) {
            samplers[index] = normalized;
            dirtySamplers.set(index);
            generation++;
        }
    }

    BitSet dirtyBuffers() { return (BitSet) dirtyBuffers.clone(); }
    BitSet dirtyTextures() { return (BitSet) dirtyTextures.clone(); }
    BitSet dirtySamplers() { return (BitSet) dirtySamplers.clone(); }

    MemorySegment buffer(final int index) { return buffers[index]; }
    long bufferOffset(final int index) { return bufferOffsets[index]; }
    MemorySegment texture(final int index) { return textures[index]; }
    MemorySegment sampler(final int index) { return samplers[index]; }

    void markEncoded() {
        dirtyBuffers.clear();
        dirtyTextures.clear();
        dirtySamplers.clear();
    }

    boolean dirty() {
        return !dirtyBuffers.isEmpty() || !dirtyTextures.isEmpty() || !dirtySamplers.isEmpty();
    }

    private static void requireIndex(final int index, final int length, final String kind) {
        if (index < 0 || index >= length) {
            throw new IllegalArgumentException(kind + " argument index out of range: " + index + "/" + length);
        }
    }

    private static boolean sameHandle(final MemorySegment first, final MemorySegment second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.address() == second.address();
    }

    /** Triple-buffered ownership wrapper matching MetalCommandEncoder's in-flight slots. */
    static final class Ring {
        private final IrisMetalArgumentSnapshot[] slots;
        private int current;

        Ring(final IrisMetalOptimizationPlan.ArgumentLayout layout, final int inFlightSlots) {
            if (inFlightSlots <= 0) throw new IllegalArgumentException("inFlightSlots must be positive");
            this.slots = new IrisMetalArgumentSnapshot[inFlightSlots];
            for (int i = 0; i < inFlightSlots; i++) slots[i] = new IrisMetalArgumentSnapshot(layout);
        }

        IrisMetalArgumentSnapshot current() {
            return slots[current];
        }

        void advanceAfterSubmit() {
            current = (current + 1) % slots.length;
        }
    }
}
