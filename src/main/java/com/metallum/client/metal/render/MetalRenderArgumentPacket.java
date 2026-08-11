package com.metallum.client.metal.render;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Reusable, fully validated input packet for one argument-buffer snapshot. */
final class MetalRenderArgumentPacket implements AutoCloseable {
    static final int MAGIC = 0x4D414247;
    static final int VERSION = 1;
    static final int HEADER_SIZE = 24;
    static final int ENTRY_SIZE = 48;
    static final int MAX_ENTRIES = 256;

    static final int KIND_BUFFER = 1;
    static final int KIND_TEXTURE = 2;
    static final int KIND_SAMPLER = 3;
    static final int FLAG_WRITABLE = 1;

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment storage = arena.allocate(
            HEADER_SIZE + (long) MAX_ENTRIES * ENTRY_SIZE,
            Long.BYTES
    );
    private int entryCount;

    MetalRenderArgumentPacket() {
        storage.set(ValueLayout.JAVA_INT, 0L, MAGIC);
        storage.set(ValueLayout.JAVA_INT, 4L, VERSION);
        storage.set(ValueLayout.JAVA_INT, 16L, ENTRY_SIZE);
        storage.set(ValueLayout.JAVA_INT, 20L, 0);
    }

    void reset() {
        entryCount = 0;
        storage.set(ValueLayout.JAVA_INT, 8L, HEADER_SIZE);
        storage.set(ValueLayout.JAVA_INT, 12L, 0);
    }

    void appendBuffer(
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final MemorySegment buffer,
            final long offset,
            final boolean writable
    ) {
        append(binding, KIND_BUFFER, buffer, offset, writable ? FLAG_WRITABLE : 0, false);
    }

    void appendTexture(
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final MemorySegment texture,
            final boolean writable
    ) {
        append(binding, KIND_TEXTURE, texture, 0L, writable ? FLAG_WRITABLE : 0, false);
    }

    void appendSampler(
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final MemorySegment sampler
    ) {
        append(binding, KIND_SAMPLER, sampler, 0L, 0, true);
    }

    int finish() {
        if (entryCount <= 0) {
            throw new IllegalStateException("Metal argument packet has no executable entries");
        }
        int bytes = Math.addExact(HEADER_SIZE, Math.multiplyExact(entryCount, ENTRY_SIZE));
        storage.set(ValueLayout.JAVA_INT, 8L, bytes);
        storage.set(ValueLayout.JAVA_INT, 12L, entryCount);
        return bytes;
    }

    int entryCount() {
        return entryCount;
    }

    MemorySegment storage() {
        return storage;
    }

    private void append(
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final int kind,
            final MemorySegment object,
            final long offset,
            final int flags,
            final boolean samplerIndex
    ) {
        if (object == null || object.address() == 0L) {
            throw new IllegalArgumentException("Metal argument object is null for " + binding.name());
        }
        int vertexIndex = samplerIndex
                ? binding.vertexSamplerArgumentIndex()
                : binding.vertexArgumentIndex();
        int fragmentIndex = samplerIndex
                ? binding.fragmentSamplerArgumentIndex()
                : binding.fragmentArgumentIndex();
        int stageMask = (vertexIndex >= 0 ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragmentIndex >= 0 ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        if (stageMask == 0) {
            throw new IllegalArgumentException(
                    "Resource " + binding.name() + " has no compiled argument id for kind " + kind
            );
        }
        if (entryCount >= MAX_ENTRIES) {
            throw new IllegalStateException(
                    "Metal argument layout exceeds packet capacity " + MAX_ENTRIES
            );
        }
        long base = HEADER_SIZE + (long) entryCount * ENTRY_SIZE;
        storage.set(ValueLayout.JAVA_INT, base, kind);
        storage.set(ValueLayout.JAVA_INT, base + 4L, stageMask);
        storage.set(ValueLayout.JAVA_INT, base + 8L, vertexIndex);
        storage.set(ValueLayout.JAVA_INT, base + 12L, fragmentIndex);
        storage.set(ValueLayout.JAVA_LONG, base + 16L, object.address());
        storage.set(ValueLayout.JAVA_LONG, base + 24L, offset);
        storage.set(ValueLayout.JAVA_INT, base + 32L, flags);
        storage.set(ValueLayout.JAVA_INT, base + 36L, 0);
        storage.set(ValueLayout.JAVA_LONG, base + 40L, 0L);
        entryCount++;
    }

    @Override
    public void close() {
        arena.close();
    }
}
