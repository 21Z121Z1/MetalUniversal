package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Contract expressed as a resource invariant rather than a golden byte array. */
public final class InvariantExpectation implements Expectation {
    private final String name;
    private final BiPredicate<CapturedResource, ExpectationContext> predicate;

    public InvariantExpectation(
            final String name,
            final BiPredicate<CapturedResource, ExpectationContext> predicate
    ) {
        if (name == null || name.isBlank() || predicate == null) {
            throw new IllegalArgumentException("Invalid invariant expectation");
        }
        this.name = name;
        this.predicate = Objects.requireNonNull(predicate);
    }

    @Override
    public ExpectationResult evaluate(final CapturedResource actual, final ExpectationContext context) {
        boolean passed;
        try {
            passed = predicate.test(actual, context);
        } catch (RuntimeException exception) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("exception", exception.toString());
            return ExpectationResult.fail("invariant", name + " raised an exception", metrics);
        }
        return passed
                ? ExpectationResult.pass("invariant", name + " satisfied", Map.of("invariant", name))
                : ExpectationResult.fail("invariant", name + " violated", Map.of("invariant", name));
    }
}
