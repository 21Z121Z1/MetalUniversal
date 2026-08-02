package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import com.metallum.client.performance.DisplayDeadlineSnapshot;
import com.metallum.client.performance.FrameStutterRecorder;
import com.metallum.client.performance.FrameStutterReportWriter;
import com.metallum.client.performance.IrisMetalFrameBudgetController;
import com.metallum.client.terrain.TerrainSchedulingController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/** Captures one client render frame for terrain policy and stutter attribution. */
@Mixin(Minecraft.class)
public abstract class MinecraftTerrainSchedulingMixin {
    @Unique private long metallum$gcBeginNanos;

    @Inject(method = "renderFrame", at = @At("HEAD"), remap = false)
    private void metallum$beginTerrainCpuFrame(final boolean renderLevel, final CallbackInfo ci) {
        long now = System.nanoTime();
        TerrainSchedulingController terrain = TerrainSchedulingController.runtime();
        if (terrain.observesFrames()) {
            terrain.beginCpuFrame(now);
        }

        IrisMetalFrameBudgetController budget = IrisMetalFrameBudgetController.runtime();
        DisplayDeadlineSnapshot deadline = budget.beginFrame(now);
        FrameStutterRecorder recorder = FrameStutterRecorder.runtime();
        if (recorder.isEnabled()) {
            FrameStutterReportWriter.installRuntimeHook();
            metallum$gcBeginNanos = collectionTimeNanos();
            recorder.beginFrame(now, deadline);
        }
    }

    @Inject(method = "renderFrame", at = @At("RETURN"), remap = false)
    private void metallum$endTerrainCpuFrame(final boolean renderLevel, final CallbackInfo ci) {
        long now = System.nanoTime();
        TerrainSchedulingController terrain = TerrainSchedulingController.runtime();
        if (terrain.observesFrames()) {
            terrain.endCpuFrame(now);
        }
        long cpuNanos = terrain.latestCpuFrameNanos();
        long gpuNanos = MetalGpuTimingRecorder.latestGpuNanos();
        IrisMetalFrameBudgetController.runtime().endFrame(now, cpuNanos, gpuNanos);

        FrameStutterRecorder recorder = FrameStutterRecorder.runtime();
        if (recorder.isEnabled()) {
            long gcEnd = collectionTimeNanos();
            long gcPause = metallum$gcBeginNanos > 0L && gcEnd >= metallum$gcBeginNanos
                    ? gcEnd - metallum$gcBeginNanos
                    : 0L;
            recorder.endFrame(now, gpuNanos, gcPause, terrain.lastSnapshot());
            metallum$gcBeginNanos = 0L;
        }
    }

    @Unique
    private static long collectionTimeNanos() {
        long milliseconds = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = collector.getCollectionTime();
            if (value > 0L) {
                milliseconds = value > Long.MAX_VALUE - milliseconds
                        ? Long.MAX_VALUE
                        : milliseconds + value;
            }
        }
        return milliseconds > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE
                : milliseconds * 1_000_000L;
    }
}
