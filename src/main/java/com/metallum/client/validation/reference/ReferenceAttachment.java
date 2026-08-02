package com.metallum.client.validation.reference;

public record ReferenceAttachment(
        String semanticName,
        String format,
        int width,
        int height,
        int depthOrLayers,
        int sampleCount,
        String rawArtifact
) {
    public ReferenceAttachment {
        if (semanticName == null || semanticName.isBlank() || format == null || format.isBlank()
                || width <= 0 || height <= 0 || depthOrLayers <= 0 || sampleCount <= 0) {
            throw new IllegalArgumentException("Invalid reference attachment");
        }
    }
}
