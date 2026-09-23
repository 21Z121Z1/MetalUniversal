package com.metallum.client.validation.reference;

public record ReferenceExpectation(
        String id,
        String resourceSemanticName,
        String kind,
        String artifact
) {
    public ReferenceExpectation {
        if (id == null || id.isBlank() || resourceSemanticName == null || resourceSemanticName.isBlank()
                || kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Invalid reference expectation");
        }
    }
}
