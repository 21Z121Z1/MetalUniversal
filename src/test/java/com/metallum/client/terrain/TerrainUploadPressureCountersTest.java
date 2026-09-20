package com.metallum.client.terrain;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainUploadPressureCountersTest {
    @Test
    void separatesSuccessfulFailedAndExceptionalAttempts() {
        TerrainUploadPressureCounters counters = new TerrainUploadPressureCounters();

        counters.recordStagingAttempt(100L, 10L, true, true);
        counters.recordStagingAttempt(200L, 20L, false, true);
        counters.recordStagingAttempt(300L, 30L, false, false);

        TerrainUploadPressureCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(3L, snapshot.attempts());
        assertEquals(1L, snapshot.successes());
        assertEquals(1L, snapshot.failures());
        assertEquals(1L, snapshot.exceptions());
        assertEquals(600L, snapshot.requestedBytesIncludingRetries());
        assertEquals(60L, snapshot.attemptCpuNanos());
        assertEquals(50L, snapshot.failedAttemptCpuNanos());
    }

    @Test
    void countsRequestedBytesAgainForRetries() {
        TerrainUploadPressureCounters counters = new TerrainUploadPressureCounters();

        counters.recordStagingAttempt(128L, 3L, false, true);
        counters.recordStagingAttempt(128L, 4L, true, true);
        counters.recordStagingAttempt(128L, 5L, true, true);

        assertEquals(384L, counters.snapshot().requestedBytesIncludingRetries());
    }

    @Test
    void keepsCopyLockAndUploadMetricsIndependent() {
        TerrainUploadPressureCounters counters = new TerrainUploadPressureCounters();

        counters.recordCopyLockWait(7L);
        counters.recordCopyLockWait(11L);
        counters.recordUploadCall(13L, true);
        counters.recordUploadCall(17L, false);

        TerrainUploadPressureCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(2L, snapshot.copyLockAcquisitions());
        assertEquals(18L, snapshot.waitNanos());
        assertEquals(11L, snapshot.maxWaitNanos());
        assertEquals(18L, snapshot.copyLockWaitNanos());
        assertEquals(11L, snapshot.copyLockMaxWaitNanos());
        assertEquals(2L, snapshot.uploadCalls());
        assertEquals(1L, snapshot.uploadExceptions());
        assertEquals(30L, snapshot.uploadCpuNanos());
        assertEquals(0L, snapshot.attempts());
    }

    @Test
    void saturatesEveryCounterInsteadOfWrapping() {
        TerrainUploadPressureCounters counters = new TerrainUploadPressureCounters();

        counters.recordStagingAttempt(Long.MAX_VALUE, Long.MAX_VALUE, false, false);
        counters.recordStagingAttempt(1L, 1L, true, true);
        counters.recordCopyLockWait(Long.MAX_VALUE);
        counters.recordCopyLockWait(1L);
        counters.recordUploadCall(Long.MAX_VALUE, false);
        counters.recordUploadCall(1L, true);

        TerrainUploadPressureCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(2L, snapshot.attempts());
        assertEquals(1L, snapshot.successes());
        assertEquals(0L, snapshot.failures());
        assertEquals(1L, snapshot.exceptions());
        assertEquals(Long.MAX_VALUE, snapshot.requestedBytesIncludingRetries());
        assertEquals(Long.MAX_VALUE, snapshot.attemptCpuNanos());
        assertEquals(Long.MAX_VALUE, snapshot.failedAttemptCpuNanos());
        assertEquals(2L, snapshot.copyLockAcquisitions());
        assertEquals(Long.MAX_VALUE, snapshot.waitNanos());
        assertEquals(Long.MAX_VALUE, snapshot.maxWaitNanos());
        assertEquals(2L, snapshot.uploadCalls());
        assertEquals(1L, snapshot.uploadExceptions());
        assertEquals(Long.MAX_VALUE, snapshot.uploadCpuNanos());
    }

    @Test
    void rejectsNegativeInputs() {
        TerrainUploadPressureCounters counters = new TerrainUploadPressureCounters();

        assertThrows(IllegalArgumentException.class,
                () -> counters.recordStagingAttempt(-1L, 0L, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> counters.recordStagingAttempt(0L, -1L, true, true));
        assertThrows(IllegalArgumentException.class, () -> counters.recordCopyLockWait(-1L));
        assertThrows(IllegalArgumentException.class, () -> counters.recordUploadCall(-1L, true));
        assertEquals(0L, counters.snapshot().attempts());
    }

    @Test
    void accumulatesConcurrentRecorders() throws Exception {
        final int workers = 8;
        final int iterations = 1_000;
        TerrainUploadPressureCounters counters = new TerrainUploadPressureCounters();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int worker = 0; worker < workers; worker++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int iteration = 0; iteration < iterations; iteration++) {
                            counters.recordStagingAttempt(2L, 3L, true, true);
                            counters.recordCopyLockWait(5L);
                            counters.recordUploadCall(7L, true);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        long total = (long) workers * iterations;
        TerrainUploadPressureCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(total, snapshot.attempts());
        assertEquals(total, snapshot.successes());
        assertEquals(0L, snapshot.failures());
        assertEquals(0L, snapshot.exceptions());
        assertEquals(total * 2L, snapshot.requestedBytesIncludingRetries());
        assertEquals(total * 3L, snapshot.attemptCpuNanos());
        assertEquals(total, snapshot.copyLockAcquisitions());
        assertEquals(total * 5L, snapshot.waitNanos());
        assertEquals(5L, snapshot.maxWaitNanos());
        assertEquals(total, snapshot.uploadCalls());
        assertEquals(0L, snapshot.uploadExceptions());
        assertEquals(total * 7L, snapshot.uploadCpuNanos());
    }
}
