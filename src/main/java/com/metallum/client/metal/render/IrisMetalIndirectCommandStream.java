package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
