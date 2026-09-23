package com.metallum.client.validation.contract;

import java.util.List;
import java.util.Map;

public record ProducerRecord(
        int producerIndex,
        ProducerType producerType,
        String pipelineId,
        List<String> shaderIds,
        Map<String, String> parameters,
        Map<String, String> boundResources,
        ViewportRecord viewport,
        ScissorRecord scissor,
        List<String> writtenAttachments,
        TraceIdentity traceIdentity
) {
    public ProducerRecord(
            final int producerIndex,
            final ProducerType producerType,
            final String pipelineId,
            final List<String> shaderIds,
            final Map<String, String> parameters,
            final Map<String, String> boundResources,
            final ViewportRecord viewport,
            final ScissorRecord scissor,
            final List<String> writtenAttachments
    ) {
        this(
                producerIndex,
                producerType,
                pipelineId,
                shaderIds,
                parameters,
                boundResources,
                viewport,
                scissor,
                writtenAttachments,
                null
        );
    }

    public ProducerRecord {
        if (producerIndex < 0 || producerType == null) {
            throw new IllegalArgumentException("Invalid producer record");
        }
        pipelineId = pipelineId == null ? "unbound" : pipelineId;
        shaderIds = shaderIds == null ? List.of() : List.copyOf(shaderIds);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        boundResources = boundResources == null ? Map.of() : Map.copyOf(boundResources);
        writtenAttachments = writtenAttachments == null ? List.of() : List.copyOf(writtenAttachments);
        viewport = viewport == null ? new ViewportRecord(0, 0, 0, 0) : viewport;
        scissor = scissor == null ? ScissorRecord.disabled() : scissor;
    }
}
