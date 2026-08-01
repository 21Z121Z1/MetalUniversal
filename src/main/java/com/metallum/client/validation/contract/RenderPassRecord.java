package com.metallum.client.validation.contract;

import java.util.List;
import java.util.Map;

public record RenderPassRecord(
        long frameId,
        int sequence,
        String semanticPassId,
        PassType type,
        List<AttachmentBindingRecord> colorAttachments,
        AttachmentBindingRecord depthAttachment,
        AttachmentBindingRecord stencilAttachment,
        ViewportRecord viewport,
        ScissorRecord scissor,
        String pipelineId,
        List<String> shaderIds,
        List<ProducerRecord> producers,
        Map<String, String> metadata,
        TraceIdentity traceIdentity
) {
    public RenderPassRecord(
            final long frameId,
            final int sequence,
            final String semanticPassId,
            final PassType type,
            final List<AttachmentBindingRecord> colorAttachments,
            final AttachmentBindingRecord depthAttachment,
            final AttachmentBindingRecord stencilAttachment,
            final ViewportRecord viewport,
            final ScissorRecord scissor,
            final String pipelineId,
            final List<String> shaderIds,
            final List<ProducerRecord> producers,
            final Map<String, String> metadata
    ) {
        this(
                frameId,
                sequence,
                semanticPassId,
                type,
                colorAttachments,
                depthAttachment,
                stencilAttachment,
                viewport,
                scissor,
                pipelineId,
                shaderIds,
                producers,
                metadata,
                null
        );
    }

    public RenderPassRecord {
        if (frameId < 0L || sequence < 0 || semanticPassId == null || semanticPassId.isBlank() || type == null) {
            throw new IllegalArgumentException("Invalid render pass record");
        }
        colorAttachments = colorAttachments == null ? List.of() : List.copyOf(colorAttachments);
        viewport = viewport == null ? new ViewportRecord(0, 0, 0, 0) : viewport;
        scissor = scissor == null ? ScissorRecord.disabled() : scissor;
        pipelineId = pipelineId == null ? "unbound" : pipelineId;
        shaderIds = shaderIds == null ? List.of() : List.copyOf(shaderIds);
        producers = producers == null ? List.of() : List.copyOf(producers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
