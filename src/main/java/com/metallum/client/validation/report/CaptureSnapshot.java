package com.metallum.client.validation.report;

import com.metallum.client.validation.capture.CapturedResource;

/** One ordered pass/producer attachment sample used for first-divergence localization. */
public record CaptureSnapshot(
        long frameId,
        int sequence,
        String semanticPassId,
        int producerIndex,
        String resource,
        CapturedResource value
) {
    public CaptureSnapshot {
        if (frameId < 0L || sequence < 0 || semanticPassId == null || semanticPassId.isBlank()
                || producerIndex < -1 || resource == null || resource.isBlank() || value == null) {
            throw new IllegalArgumentException("Invalid capture snapshot");
        }
    }
}
