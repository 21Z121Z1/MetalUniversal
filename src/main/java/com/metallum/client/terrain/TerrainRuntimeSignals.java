package com.metallum.client.terrain;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.performance.IrisMetalFrameBudgetController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/** Reads only platform/JDK signals that are available at the current integration boundary. */
public final class TerrainRuntimeSignals {
    private TerrainRuntimeSignals() {
    }

    public static TerrainSchedulingController.FrameInputs sample(
            final TerrainSchedulingController controller
    ) {
        long cpuNanos = controller.latestCpuFrameNanos();
        long gpuNanos = MetalGpuTimingRecorder.latestGpuNanos();
        IrisMetalFrameBudgetController frameBudget = IrisMetalFrameBudgetController.runtime();
        // A slow previous frame must not create a larger background-work budget.
        // Use the configured display cadence when the deadline controller is active.
        long frameNanos = frameBudget.isEnabled()
                ? frameBudget.estimatedFramePeriodNanos()
                : cpuNanos > 0L
                ? cpuNanos
                : TerrainSchedulingController.TARGET_FRAME_NANOS;
        return new TerrainSchedulingController.FrameInputs(
                frameNanos,
                cpuNanos,
                gpuNanos,
                0,
                0,
                0,
                thermalState(),
                memoryPressure()
        );
    }

    public static int thermalState() {
        try {
            return MetalNativeBridge.metallum_system_thermal_state();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Uses the JDK's real heap and operating-system memory counters. */
    public static double memoryPressure() {
        double heapPressure = 0.0;
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long heapLimit = heap.getMax() > 0L ? heap.getMax() : heap.getCommitted();
            if (heapLimit > 0L) {
                heapPressure = (double) heap.getUsed() / (double) heapLimit;
            }
        } catch (Throwable ignored) {
        }

        double systemPressure = 0.0;
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
                long total = operatingSystem.getTotalMemorySize();
                long free = operatingSystem.getFreeMemorySize();
                if (total > 0L && free >= 0L && free <= total) {
                    systemPressure = 1.0 - (double) free / (double) total;
                }
            }
        } catch (Throwable ignored) {
        }
        return Math.max(0.0, Math.min(1.0, Math.max(heapPressure, systemPressure)));
    }
}
