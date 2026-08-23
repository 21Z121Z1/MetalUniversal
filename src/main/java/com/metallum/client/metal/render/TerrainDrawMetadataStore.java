package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;

/** Mutable only while Sodium fills one cached {@code MultiDrawBatch}. */
public final class TerrainDrawMetadataStore {
    private final ArrayList<TerrainDrawMetadata> entries = new ArrayList<>();
    private boolean invalid;

    void append(final TerrainDrawMetadata metadata) {
        if (metadata == null || metadata.ordinal() != entries.size()) {
            invalid = true;
            return;
        }
        entries.add(metadata);
    }

    void invalidate() {
        invalid = true;
    }

    /**
     * Binds the producer records to the exact five-field records copied from
     * Sodium's native command array.  The ordinal is explicit and contiguous;
     * no nearest/fuzzy matching is permitted.
     */
    List<TerrainDrawMetadata> freeze(
            final List<IrisMetalIndirectCommandStream.IndexedDraw> commands
    ) {
        if (invalid || commands == null || entries.size() != commands.size()) {
            throw new IllegalStateException("Sodium terrain draw metadata is incomplete");
        }
        for (int ordinal = 0; ordinal < commands.size(); ordinal++) {
            TerrainDrawMetadata metadata = entries.get(ordinal);
            if (metadata.ordinal() != ordinal || !metadata.arguments().equals(commands.get(ordinal))) {
                throw new IllegalStateException("Sodium terrain draw metadata does not match draw ordinal " + ordinal);
            }
        }
        return List.copyOf(entries);
    }

    int size() {
        return entries.size();
    }
}
