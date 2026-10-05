package com.metallum.client.startup;

import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.util.MemoryReserve;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/** Opt-in startup work that preserves the produced game state. */
public final class StartupPerformance {
    public static final String ASYNC_CRASH_PRELOAD = "metallum.opt.asyncCrashReportPreload";
    public static final String PARALLEL_BLOCK_STATE_CACHE = "metallum.opt.parallelBlockStateCache";

    private static final List<BlockState> DEFERRED_STATES = new ArrayList<>();

    private StartupPerformance() {}

    public static boolean asyncCrashPreloadEnabled() {
        return Boolean.getBoolean(ASYNC_CRASH_PRELOAD);
    }

    public static boolean parallelBlockStateCacheEnabled() {
        return Boolean.getBoolean(PARALLEL_BLOCK_STATE_CACHE);
    }

    public static void preloadCrashReportAsync() {
        MemoryReserve.allocate();
        Thread thread = new Thread(() -> {
            try {
                new CrashReport("startup preload", new Throwable()).getFriendlyReport(ReportType.CRASH);
            } catch (Throwable ignored) {
                // Preloading is opportunistic. Real crash reporting remains authoritative.
            }
        }, "MetalUniversal crash-report preload");
        thread.setDaemon(true);
        thread.start();
    }

    public static void deferBlockState(BlockState state) {
        DEFERRED_STATES.add(state);
    }

    public static void finishBlockStateCaches() {
        if (!parallelBlockStateCacheEnabled()) {
            DEFERRED_STATES.clear();
            return;
        }
        if (DEFERRED_STATES.isEmpty()) return;

        List<BlockState> states = List.copyOf(DEFERRED_STATES);
        DEFERRED_STATES.clear();

        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.submit(() -> states.parallelStream().forEach(BlockState::initCache)).join();
        } finally {
            pool.shutdown();
        }
    }
}
