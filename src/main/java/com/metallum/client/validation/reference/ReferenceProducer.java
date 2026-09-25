package com.metallum.client.validation.reference;

import java.util.List;
import java.util.Map;

public record ReferenceProducer(
        int producerIndex,
        String producerType,
        String pipelineId,
        List<String> shaderIds,
        Map<String, String> parameters,
        List<String> writtenAttachments
) {
    public ReferenceProducer {
        if (producerIndex < 0 || producerType == null || producerType.isBlank()) {
            throw new IllegalArgumentException("Invalid reference producer");
        }
        shaderIds = shaderIds == null ? List.of() : List.copyOf(shaderIds);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        writtenAttachments = writtenAttachments == null ? List.of() : List.copyOf(writtenAttachments);
    }
}
