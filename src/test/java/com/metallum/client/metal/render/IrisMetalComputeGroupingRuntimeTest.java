package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalComputeGroupingRuntimeTest {
    static {
        // Keep the test on the same opt-in path as the production grouping plan.
        System.setProperty("metallum.iris.experimental.computeGrouping", "true");
    }

    @BeforeAll
    static void clearThreadLocalState() {
        IrisMetalComputeGroupingRuntime.abort();
    }

    @AfterEach
    void reset() {
        IrisMetalComputeGroupingRuntime.abort();
    }

    @Test
    void normalGroupDefersOnlyTheNonFinalPass() {
        assertTrue(IrisMetalComputeGroupingRuntime.begin(independentComputes(), false));

        assertTrue(IrisMetalComputeGroupingRuntime.deferClose());
        assertTrue(IrisMetalComputeGroupingRuntime.mayReuseEncoder());
        assertFalse(IrisMetalComputeGroupingRuntime.deferClose());
        assertFalse(IrisMetalComputeGroupingRuntime.mayReuseEncoder());
    }

    @Test
    void abortClearsThePlanAfterASyntheticDispatchFailure() {
        assertTrue(IrisMetalComputeGroupingRuntime.begin(independentComputes(), false));
        assertTrue(IrisMetalComputeGroupingRuntime.deferClose());
        assertTrue(IrisMetalComputeGroupingRuntime.mayReuseEncoder());

        assertThrows(IllegalStateException.class, () -> {
            try {
                throw new IllegalStateException("synthetic compute encoder failure");
            } finally {
                IrisMetalComputeGroupingRuntime.abort();
            }
        });

        assertFalse(IrisMetalComputeGroupingRuntime.mayReuseEncoder());
    }

    private static List<FakeCompute> independentComputes() {
        return List.of(
                new FakeCompute("sourceA", "SAMPLED_TEXTURE"),
                new FakeCompute("sourceB", "SAMPLED_TEXTURE")
        );
    }

    private static final class FakeCompute {
        private final FakeReflection reflection;

        private FakeCompute(final String resourceName, final String resourceKind) {
            this.reflection = new FakeReflection(resourceName, resourceKind);
        }
    }

    private static final class FakeReflection {
        private final List<FakeResource> resources;

        private FakeReflection(final String resourceName, final String resourceKind) {
            this.resources = List.of(new FakeResource(resourceName, resourceKind));
        }

        @SuppressWarnings("unused")
        private List<FakeResource> resources() {
            return this.resources;
        }
    }

    private record FakeResource(String name, String kind) {
    }
}
