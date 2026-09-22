package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainPublicationTransactionTest {
    @Test
    void invalidationCannotInterleaveBetweenAdmissionAndOriginalExchange() throws Exception {
        var guard = guard(true, 4);
        var task = new Task();
        Object old = new Object(), candidate = new Object();
        guard.registerTask(task, 1);
        guard.runTask(task, null, () -> { guard.bindMeshFromActiveTask(candidate); return null; });
        var admitted = new CountDownLatch(1);
        var exchangeAllowed = new CountDownLatch(1);
        var invalidationStarted = new CountDownLatch(1);
        var invalidationThread = new AtomicReference<Thread>();
        var current = new AtomicReference<>(old);
        var order = new CopyOnWriteArrayList<String>();
        var invalidated = new AtomicBoolean();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publication = executor.submit(() -> guard.publish(1, candidate, () -> {
                admitted.countDown();
                await(exchangeAllowed);
                order.add("exchange");
                return current.getAndSet(candidate);
            }));
            var invalidation = executor.submit(() -> {
                await(admitted);
                invalidationThread.set(Thread.currentThread());
                invalidationStarted.countDown();
                guard.markDirty(1);
                order.add("invalidate");
                invalidated.set(true);
            });
            try {
                assertTrue(invalidationStarted.await(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!invalidated.get() && invalidationThread.get().getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(invalidated.get(), "generation changed after admission but before the exchange");
                assertEquals(Thread.State.BLOCKED, invalidationThread.get().getState());
                assertSame(old, current.get());
            } finally {
                exchangeAllowed.countDown();
            }
            assertSame(old, publication.get(10, TimeUnit.SECONDS));
            invalidation.get(10, TimeUnit.SECONDS);
            assertSame(candidate, current.get());
            assertEquals(List.of("exchange", "invalidate"), order);
        } finally {
            exchangeAllowed.countDown();
        }
    }

    @Test
    void compilationDoesNotHoldPublicationMonitorAndStaleResultIsNeverExchanged() throws Exception {
        var guard = guard(true, 4);
        var task = new Task();
        Object candidate = new Object();
        var compiling = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        guard.registerTask(task, 2);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> guard.runTask(task, new Object(), () -> {
                compiling.countDown();
                await(resume);
                return guard.publish(2, candidate, () -> fail("stale result reached original publisher"));
            }));
            try {
                assertTrue(compiling.await(10, TimeUnit.SECONDS));
                // This must complete while the original compilation is paused.
                guard.markDirty(2);
                assertTrue(task.cancelled);
            } finally {
                resume.countDown();
            }
            assertSame(candidate, result.get(10, TimeUnit.SECONDS));
            assertEquals(1, guard.snapshot().rejectedStalePublications());
        } finally {
            resume.countDown();
        }
    }

    @Test
    void exceptionalCompileCannotDonateWorkerOwnershipToLaterMeshes() {
        for (boolean fatal : new boolean[]{false, true}) {
            var guard = guard(true, 4);
            var task = new Task();
            guard.registerTask(task, 3);
            Throwable expected = fatal ? new AssertionError("compile") : new IllegalStateException("compile");
            var calls = new AtomicInteger();
            Throwable actual = assertThrows(Throwable.class, () -> guard.runTask(task, "cancelled", () -> {
                calls.incrementAndGet();
                if (expected instanceof Error error) throw error;
                throw (RuntimeException) expected;
            }));
            assertSame(expected, actual);
            assertEquals(1, calls.get());
            guard.bindMeshFromActiveTask(new Object());
            assertEquals(0, guard.snapshot().trackedMeshes(), "exception left active worker ownership behind");
            assertTrue(guard.snapshot().active(), "known task failure is not an evidence-capacity failure");
        }
    }

    @Test
    void cancelledWorkNeverInvokesOriginalAndNormalWorkReturnsItsExactResult() {
        var guard = guard(true, 4);
        var stale = new Task();
        guard.registerTask(stale, 4);
        guard.markDirty(4);
        Object cancelled = new Object();
        assertSame(cancelled, guard.runTask(stale, cancelled, () -> fail("cancelled task compiled")));
        var current = new Task();
        guard.registerTask(current, 4);
        Object result = new Object();
        var calls = new AtomicInteger();
        assertSame(result, guard.runTask(current, cancelled, () -> { calls.incrementAndGet(); return result; }));
        assertEquals(1, calls.get());
        guard.bindMeshFromActiveTask(new Object());
        assertEquals(0, guard.snapshot().trackedMeshes());
    }

    @Test
    void untrackedDirtyNotificationsCannotEvictOrDisableLiveOwnership() {
        var guard = guard(true, 1);
        var task = new Task();
        Object candidate = new Object(), old = new Object();
        guard.registerTask(task, 5);
        guard.runTask(task, null, () -> { guard.bindMeshFromActiveTask(candidate); return null; });
        for (int section = 100; section < 100_100; section++) {
            guard.markDirty(section);
            guard.invalidateSectionLifetime(section);
        }
        assertTrue(guard.active());
        assertEquals(1, guard.snapshot().sectionVersionEntries());
        assertEquals(0, guard.snapshot().failOpenCount());
        assertFalse(task.cancelled);
        assertSame(old, guard.publish(5, candidate, () -> old));
    }

    @Test
    void publisherFailureIsPropagatedUnchangedAndDoesNotKeepTheMonitor() throws Exception {
        var guard = guard(true, 4);
        var task = new Task();
        Object candidate = new Object();
        guard.registerTask(task, 6);
        guard.runTask(task, null, () -> { guard.bindMeshFromActiveTask(candidate); return null; });
        var expected = new IllegalArgumentException("exchange failure");
        var calls = new AtomicInteger();
        assertSame(expected, assertThrows(IllegalArgumentException.class,
                () -> guard.publish(6, candidate, () -> { calls.incrementAndGet(); throw expected; })));
        assertEquals(1, calls.get());
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> guard.markDirty(6)).get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, guard.snapshot().trackedMeshes());
    }

    @Test
    void disabledGuardPreservesOriginalReturnExceptionAndCallbackCardinality() {
        var guard = guard(false, 1);
        Object old = new Object(), candidate = new Object();
        var calls = new AtomicInteger();
        assertSame(old, guard.publish(7, candidate, () -> { calls.incrementAndGet(); return old; }));
        var expected = new AssertionError("baseline");
        assertSame(expected, assertThrows(AssertionError.class,
                () -> guard.runTask(new Task(), old, () -> { calls.incrementAndGet(); throw expected; })));
        assertEquals(2, calls.get());
        assertEquals(0, guard.snapshot().registeredTasks());
        assertFalse(guard.snapshot().failOpen());
    }

    private static TerrainPublicationGenerationGuard<Task> guard(boolean enabled, int sections) {
        return new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(enabled, sections, 4, 4),
                new TerrainPublicationGenerationGuard.TaskOps<>() {
                    public boolean isCancelled(Task task) { return task.cancelled; }
                    public void cancel(Task task) { task.cancelled = true; }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("test coordination timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", failure);
        }
    }

    private static final class Task { volatile boolean cancelled; }
}
