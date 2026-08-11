package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies the exception-safe contract used by the live shadow-state restore. */
final class IrisMetalShadowStateSnapshotTest {
    @Test
    void restorationContinuesAfterFailureAndPreservesSuppressedFailures() {
        AtomicInteger completed = new AtomicInteger();
        IllegalStateException first = new IllegalStateException("first restore failure");
        IllegalArgumentException second = new IllegalArgumentException("second restore failure");

        Throwable failure = IrisMetalShadowStateSnapshot.runRestoreSteps(List.of(
                completed::incrementAndGet,
                () -> {
                    throw first;
                },
                completed::incrementAndGet,
                () -> {
                    throw second;
                },
                completed::incrementAndGet
        ));

        assertSame(first, failure);
        assertEquals(3, completed.get(), "later restoration steps must still run");
        assertEquals(1, failure.getSuppressed().length);
        assertSame(second, failure.getSuppressed()[0]);
    }

    @Test
    void successfulRestorationHasNoFailure() {
        AtomicInteger completed = new AtomicInteger();

        Throwable failure = IrisMetalShadowStateSnapshot.runRestoreSteps(List.of(
                completed::incrementAndGet,
                completed::incrementAndGet
        ));

        assertNull(failure);
        assertEquals(2, completed.get());
    }
}
