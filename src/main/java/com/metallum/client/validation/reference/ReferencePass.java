package com.metallum.client.validation.reference;

import java.util.List;
import java.util.Map;

public record ReferencePass(
        long frameId,
        int sequence,
        String semanticPassId,
        String type,
        List<ReferenceAttachment> attachments,
        List<ReferenceProducer> producers,
        Map<String, String> metadata
) {
    public ReferencePass {
        if (frameId < 0L || sequence < 0 || semanticPassId == null || semanticPassId.isBlank()
                || type == null || type.isBlank()) {
            throw new IllegalArgumentException("Invalid reference pass");
        }
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        producers = producers == null ? List.of() : List.copyOf(producers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
