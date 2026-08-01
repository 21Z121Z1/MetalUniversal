package com.metallum.client.validation.reference;

import java.util.List;

public record ReferenceFrame(long frameId, List<ReferencePass> passes) {
    public ReferenceFrame {
        if (frameId < 0L) throw new IllegalArgumentException("frameId must not be negative");
        passes = passes == null ? List.of() : List.copyOf(passes);
    }
}
