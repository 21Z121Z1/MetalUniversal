package com.metallum.client.validation.contract;

public record CapturePoint(
        long frameId,
        String semanticPassId,
        CapturePointKind kind,
        int producerIndex,
        TraceIdentity traceIdentity
) {
    public CapturePoint(
            final long frameId,
            final String semanticPassId,
            final CapturePointKind kind,
            final int producerIndex
    ) {
        this(frameId, semanticPassId, kind, producerIndex, null);
    }

    public CapturePoint {
        if (frameId < 0L) {
            throw new IllegalArgumentException("frameId must not be negative");
        }
        if (semanticPassId == null || semanticPassId.isBlank()) {
            throw new IllegalArgumentException("semanticPassId must not be blank");
        }
        if (kind == null || producerIndex < -1) {
            throw new IllegalArgumentException("Invalid capture point");
        }
        if (traceIdentity != null
                && (traceIdentity.frameId() != frameId
                || !traceIdentity.semanticPassId().equals(semanticPassId)
                || traceIdentity.producerIndex() != producerIndex)) {
            throw new IllegalArgumentException("Capture point and trace identity disagree");
        }
    }
}
