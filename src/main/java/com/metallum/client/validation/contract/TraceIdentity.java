package com.metallum.client.validation.contract;

public record TraceIdentity(
        String runId,
        long frameId,
        int passSequence,
        String semanticPassId,
        int producerIndex,
        long commandBufferSubmissionId
) {
    public TraceIdentity {
        if (runId == null || runId.isBlank() || frameId < 0L || passSequence < 0
                || semanticPassId == null || semanticPassId.isBlank() || producerIndex < -1
                || commandBufferSubmissionId < -1L) {
            throw new IllegalArgumentException("Invalid trace identity");
        }
    }

    public TraceIdentity forProducer(final int nextProducerIndex) {
        return new TraceIdentity(
                runId,
                frameId,
                passSequence,
                semanticPassId,
                nextProducerIndex,
                commandBufferSubmissionId
        );
    }

    /** Stable label suitable for Metal debug groups and cross-language logs. */
    public String debugLabel() {
        return "metallum-trace[run=" + runId
                + ",frame=" + frameId
                + ",pass=" + passSequence
                + ",semantic=" + semanticPassId
                + ",producer=" + producerIndex
                + ",submit=" + commandBufferSubmissionId + "]";
    }
}
