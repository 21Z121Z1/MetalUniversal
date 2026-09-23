package com.metallum.client.validation.expectation;

import java.util.Objects;

/** Names an expectation and binds it to one resource semantic name. */
public record ExpectationSpec(String id, String resourceSemanticName, Expectation expectation) {
    public ExpectationSpec {
        if (id == null || id.isBlank() || resourceSemanticName == null
                || resourceSemanticName.isBlank() || expectation == null) {
            throw new IllegalArgumentException("Invalid expectation spec");
        }
        Objects.requireNonNull(expectation);
    }

    public static ExpectationSpec forResource(
            final String id,
            final String resourceSemanticName,
            final Expectation expectation
    ) {
        return new ExpectationSpec(id, resourceSemanticName, expectation);
    }
}
