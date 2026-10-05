package com.metallum.client.startup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupPerformanceTest {
    @AfterEach
    void clear() {
        System.clearProperty(StartupPerformance.ASYNC_CRASH_PRELOAD);
        System.clearProperty(StartupPerformance.PARALLEL_BLOCK_STATE_CACHE);
    }

    @Test
    void startupOptimizationsAreOptIn() {
        assertFalse(StartupPerformance.asyncCrashPreloadEnabled());
        assertFalse(StartupPerformance.parallelBlockStateCacheEnabled());

        System.setProperty(StartupPerformance.ASYNC_CRASH_PRELOAD, "true");
        System.setProperty(StartupPerformance.PARALLEL_BLOCK_STATE_CACHE, "true");

        assertTrue(StartupPerformance.asyncCrashPreloadEnabled());
        assertTrue(StartupPerformance.parallelBlockStateCacheEnabled());
    }
}
