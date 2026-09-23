package com.metallum.client.validation.expectation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Structured result; a failed result must carry evidence, not only a boolean. */
public record ExpectationResult(
        boolean passed,
        String expectationType,
        String message,
        Map<String, Object> metrics
) {
    public ExpectationResult {
        expectationType = expectationType == null || expectationType.isBlank()
                ? "unknown" : expectationType;
        message = message == null ? "" : message;
        metrics = metrics == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metrics));
    }

    public static ExpectationResult pass(final String type, final String message, final Map<String, Object> metrics) {
        return new ExpectationResult(true, type, message, metrics);
    }

    public static ExpectationResult fail(final String type, final String message, final Map<String, Object> metrics) {
        return new ExpectationResult(false, type, message, metrics);
    }
}
