package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

/**
 * CPU-side command stream that can feed either native multi-draw or a future
 * MTLIndirectCommandBuffer encoder without changing draw grouping semantics.
 */
final class IrisMetalIndirectCommandStream {
    record IndexedDraw(
            int indexCount,
            int instanceCount,
            int firstIndex,
            int baseVertex,
            int firstInstance
    ) {
        IndexedDraw {
            if (indexCount < 0 || instanceCount < 0 || firstIndex < 0 || firstInstance < 0) {
                throw new IllegalArgumentException("Indirect indexed draw fields must be non-negative");
            }
        }
    }

    record Batch(
            String pipelineKey,
            String attachmentKey,
            long argumentLayoutHash,
            List<IndexedDraw> draws
    ) {
        Batch {
            Objects.requireNonNull(pipelineKey, "pipelineKey");
            Objects.requireNonNull(attachmentKey, "attachmentKey");
            draws = List.copyOf(draws);
            if (draws.isEmpty()) throw new IllegalArgumentException("Indirect batch must contain draws");
        }
    }

    /**
     * Copies Sodium's packed Vulkan command records without changing the
     * submission ABI.  The returned list is the immutable producer-side
     * authority; the Metal pass still submits the original buffer once.
     */
    static List<IndexedDraw> copyIndexedCommands(final long address, final int drawCount) {
        if (address == 0L || drawCount <= 0) {
            return List.of();
        }
        final int intsPerDraw = VkDrawIndexedIndirectCommand.SIZEOF / Integer.BYTES;
        final int intCount;
        try {
            intCount = Math.multiplyExact(drawCount, intsPerDraw);
        } catch (ArithmeticException exception) {
            return List.of();
        }
        try {
            java.nio.IntBuffer commands = MemoryUtil.memIntBuffer(address, intCount);
            java.util.ArrayList<IndexedDraw> result = new java.util.ArrayList<>(drawCount);
            for (int draw = 0; draw < drawCount; draw++) {
                int base = draw * intsPerDraw;
                result.add(new IndexedDraw(
                        commands.get(base),
                        commands.get(base + 1),
                        commands.get(base + 2),
                        commands.get(base + 3),
                        commands.get(base + 4)
                ));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            // A malformed or already-retired producer allocation must never
            // make the terrain batch disappear.  The legacy indirect call is
            // still allowed to run and owns the safe fallback.
            return List.of();
        }
    }

    private final List<Batch> batches = new ArrayList<>();
    private String pipelineKey;
    private String attachmentKey;
    private long argumentLayoutHash;
    private List<IndexedDraw> pending = new ArrayList<>();

    void append(
            final String nextPipelineKey,
            final String nextAttachmentKey,
            final long nextArgumentLayoutHash,
            final IndexedDraw draw
    ) {
        Objects.requireNonNull(draw, "draw");
        if (!pending.isEmpty()
                && (!Objects.equals(pipelineKey, nextPipelineKey)
                || !Objects.equals(attachmentKey, nextAttachmentKey)
                || argumentLayoutHash != nextArgumentLayoutHash)) {
            flush();
        }
        if (pending.isEmpty()) {
            pipelineKey = Objects.requireNonNull(nextPipelineKey, "pipelineKey");
            attachmentKey = Objects.requireNonNull(nextAttachmentKey, "attachmentKey");
            argumentLayoutHash = nextArgumentLayoutHash;
        }
        pending.add(draw);
    }

    void flush() {
        if (pending.isEmpty()) return;
        batches.add(new Batch(pipelineKey, attachmentKey, argumentLayoutHash, pending));
        pending = new ArrayList<>();
        pipelineKey = null;
        attachmentKey = null;
        argumentLayoutHash = 0L;
    }

    List<Batch> finish() {
        flush();
        return List.copyOf(batches);
    }

    void clear() {
        batches.clear();
        pending.clear();
        pipelineKey = null;
        attachmentKey = null;
        argumentLayoutHash = 0L;
    }
}
