package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.HostedMetalBenchmarkFixture;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.MetalRenderStatePacketBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-JVM paired benchmark for the two state-submission mechanisms themselves.
 *
 * <p>Wall-clock is retained because it is frame-latency relevant, but current-thread
 * CPU time is the primary hosted-runner metric. GitHub's virtual CPU can be descheduled
 * by its host; thread CPU time removes that wait from a CPU-side Java/FFM hot-path
 * comparison while still accounting for native work executed by the render thread.</p>
 */
@EnabledOnOs(OS.MAC)
final class HostedMetalStatePathPairedBenchmark {
    private static final int WARMUP_PAIRS = 4;
    private static final int RECORDED_PAIRS = 20;
    private static final int ITERATIONS_PER_SAMPLE = 8_192;
    private static final int STATE_OPS_PER_ITERATION = 5;
    private static final int PACKET_CAPACITY = 256;
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();

    @Test
    void compareDirectCriticalFfmWithPacketDecodeInOneJvm() throws IOException {
        assertTrue(
                Boolean.getBoolean("metallum.perf.hosted"),
                "Hosted Metal performance benchmark requires -Dmetallum.perf.hosted=true"
        );
        assertTrue(
                MetalRenderStatePacketBridge.available(),
                "render-state packet native ABI is unavailable"
        );
        assertTrue(
                THREAD_BEAN.isCurrentThreadCpuTimeSupported(),
                "current-thread CPU time is unavailable on this runner"
        );
        if (!THREAD_BEAN.isThreadCpuTimeEnabled()) {
            THREAD_BEAN.setThreadCpuTimeEnabled(true);
        }
        assertTrue(THREAD_BEAN.getCurrentThreadCpuTime() >= 0L, "thread CPU clock is unavailable");

        try (HostedMetalBenchmarkFixture fixture = new HostedMetalBenchmarkFixture()) {
            for (int pair = 0; pair < WARMUP_PAIRS; pair++) {
                if ((pair & 1) == 0) {
                    runLegacySample(fixture);
                    runPacketSample(fixture);
                } else {
                    runPacketSample(fixture);
                    runLegacySample(fixture);
                }
            }

            long[] legacyWall = new long[RECORDED_PAIRS];
            long[] packetWall = new long[RECORDED_PAIRS];
            long[] legacyCpu = new long[RECORDED_PAIRS];
            long[] packetCpu = new long[RECORDED_PAIRS];
            double[] pairedWallDelta = new double[RECORDED_PAIRS];
            double[] pairedCpuDelta = new double[RECORDED_PAIRS];
            double[] legacyFirstCpuDeltas = new double[RECORDED_PAIRS / 2];
            double[] packetFirstCpuDeltas = new double[RECORDED_PAIRS / 2];
            int legacyFirstIndex = 0;
            int packetFirstIndex = 0;

            for (int pair = 0; pair < RECORDED_PAIRS; pair++) {
                Sample legacy;
                Sample packet;
                if ((pair & 1) == 0) {
                    legacy = runLegacySample(fixture);
                    packet = runPacketSample(fixture);
                } else {
                    packet = runPacketSample(fixture);
                    legacy = runLegacySample(fixture);
                }
                legacyWall[pair] = legacy.wallNs();
                packetWall[pair] = packet.wallNs();
                legacyCpu[pair] = legacy.cpuNs();
                packetCpu[pair] = packet.cpuNs();
                pairedWallDelta[pair] = percentChange(packet.wallNs(), legacy.wallNs());
                double cpuDelta = percentChange(packet.cpuNs(), legacy.cpuNs());
                pairedCpuDelta[pair] = cpuDelta;
                if ((pair & 1) == 0) {
                    legacyFirstCpuDeltas[legacyFirstIndex++] = cpuDelta;
                } else {
                    packetFirstCpuDeltas[packetFirstIndex++] = cpuDelta;
                }
            }

            writeResult(
                    legacyWall,
                    packetWall,
                    legacyCpu,
                    packetCpu,
                    pairedWallDelta,
                    pairedCpuDelta,
                    legacyFirstCpuDeltas,
                    packetFirstCpuDeltas
            );
        }
    }

    private static Sample runLegacySample(final HostedMetalBenchmarkFixture fixture) {
        MTLRenderCommandEncoder encoder = fixture.makeRenderEncoder();
        MemorySegment handle = encoder.handle();
        long wallStart = System.nanoTime();
        long cpuStart = THREAD_BEAN.getCurrentThreadCpuTime();
        for (int iteration = 0; iteration < ITERATIONS_PER_SAMPLE; iteration++) {
            boolean alternate = (iteration & 1) != 0;
            MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(
                    handle,
                    alternate ? 0.125F : 0.0F,
                    alternate ? 2.0F : 1.0F,
                    0.0F
            );
            MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(
                    handle,
                    alternate ? MTLWinding.CounterClockwise.value : MTLWinding.Clockwise.value
            );
            MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(
                    handle,
                    alternate ? MTLCullMode.Back.value : MTLCullMode.None.value
            );
            MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(
                    handle,
                    alternate ? MTLTriangleFillMode.Lines.value : MTLTriangleFillMode.Fill.value
            );
            MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(
                    handle,
                    alternate ? 1 : 0,
                    0,
                    fixture.width() - 1L,
                    fixture.height()
            );
        }
        encoder.endEncoding();
        long cpuElapsed = THREAD_BEAN.getCurrentThreadCpuTime() - cpuStart;
        long wallElapsed = System.nanoTime() - wallStart;
        fixture.submitAndWait();
        return new Sample(wallElapsed, cpuElapsed);
    }

    private static Sample runPacketSample(final HostedMetalBenchmarkFixture fixture) {
        MTLRenderCommandEncoder encoder = fixture.makeRenderEncoder();
        MemorySegment handle = encoder.handle();
        MetalRenderStatePacket packet = new MetalRenderStatePacket(PACKET_CAPACITY);
        Sample sample;
        try {
            long wallStart = System.nanoTime();
            long cpuStart = THREAD_BEAN.getCurrentThreadCpuTime();
            for (int iteration = 0; iteration < ITERATIONS_PER_SAMPLE; iteration++) {
                boolean alternate = (iteration & 1) != 0;
                packet.appendDepthBias(
                        handle,
                        alternate ? 0.125F : 0.0F,
                        alternate ? 2.0F : 1.0F,
                        0.0F
                );
                packet.appendWinding(
                        handle,
                        alternate ? MTLWinding.CounterClockwise.value : MTLWinding.Clockwise.value
                );
                packet.appendCullMode(
                        handle,
                        alternate ? MTLCullMode.Back.value : MTLCullMode.None.value
                );
                packet.appendFillMode(
                        handle,
                        alternate ? MTLTriangleFillMode.Lines.value : MTLTriangleFillMode.Fill.value
                );
                packet.appendScissor(
                        handle,
                        alternate ? 1 : 0,
                        0,
                        fixture.width() - 1L,
                        fixture.height()
                );
            }
            packet.flush(handle);
            encoder.endEncoding();
            long cpuElapsed = THREAD_BEAN.getCurrentThreadCpuTime() - cpuStart;
            long wallElapsed = System.nanoTime() - wallStart;
            sample = new Sample(wallElapsed, cpuElapsed);
            assertTrue(packet.active(), "packet ABI failed and replayed legacy setters");
        } finally {
            packet.close();
        }
        fixture.submitAndWait();
        return sample;
    }

    private static void writeResult(
            final long[] legacyWall,
            final long[] packetWall,
            final long[] legacyCpu,
            final long[] packetCpu,
            final double[] pairedWallDelta,
            final double[] pairedCpuDelta,
            final double[] legacyFirstCpuDeltas,
            final double[] packetFirstCpuDeltas
    ) throws IOException {
        long[] legacyWallSorted = sorted(legacyWall);
        long[] packetWallSorted = sorted(packetWall);
        long[] legacyCpuSorted = sorted(legacyCpu);
        long[] packetCpuSorted = sorted(packetCpu);
        double[] pairedWallSorted = sorted(pairedWallDelta);
        double[] pairedCpuSorted = sorted(pairedCpuDelta);
        double[] legacyFirstCpuSorted = sorted(legacyFirstCpuDeltas);
        double[] packetFirstCpuSorted = sorted(packetFirstCpuDeltas);

        double legacyWallMedian = median(legacyWallSorted);
        double packetWallMedian = median(packetWallSorted);
        double legacyCpuMedian = median(legacyCpuSorted);
        double packetCpuMedian = median(packetCpuSorted);
        double pairedWallMedianDelta = median(pairedWallSorted);
        double pairedCpuMedianDelta = median(pairedCpuSorted);
        double opsPerSample = ITERATIONS_PER_SAMPLE * (double) STATE_OPS_PER_ITERATION;

        Path output = Path.of("build", "hosted-metal-perf", "in-jvm-paired.json");
        Files.createDirectories(output.getParent());
        String json = String.format(
                Locale.ROOT,
                """
                {
                  \"schema\": 2,
                  \"benchmark\": \"render-state-path-in-jvm-paired\",
                  \"primary_metric\": \"current_thread_cpu_time\",
                  \"interpretation\": \"negative delta means packet mode is faster\",
                  \"warmup_pairs\": %d,
                  \"recorded_pairs\": %d,
                  \"iterations_per_sample\": %d,
                  \"state_ops_per_iteration\": %d,
                  \"packet_capacity\": %d,
                  \"legacy_wall_samples_ns\": %s,
                  \"packet_wall_samples_ns\": %s,
                  \"legacy_cpu_samples_ns\": %s,
                  \"packet_cpu_samples_ns\": %s,
                  \"paired_wall_delta_percent\": %s,
                  \"paired_cpu_delta_percent\": %s,
                  \"legacy_wall_median_ns\": %.3f,
                  \"packet_wall_median_ns\": %.3f,
                  \"legacy_wall_p95_ns\": %d,
                  \"packet_wall_p95_ns\": %d,
                  \"paired_wall_median_delta_percent\": %.6f,
                  \"legacy_cpu_median_ns\": %.3f,
                  \"packet_cpu_median_ns\": %.3f,
                  \"legacy_cpu_p95_ns\": %d,
                  \"packet_cpu_p95_ns\": %d,
                  \"legacy_cpu_median_ns_per_state_op\": %.6f,
                  \"packet_cpu_median_ns_per_state_op\": %.6f,
                  \"paired_cpu_median_delta_percent\": %.6f,
                  \"paired_cpu_median_improvement_percent\": %.6f,
                  \"legacy_first_cpu_median_delta_percent\": %.6f,
                  \"packet_first_cpu_median_delta_percent\": %.6f
                }
                """,
                WARMUP_PAIRS,
                RECORDED_PAIRS,
                ITERATIONS_PER_SAMPLE,
                STATE_OPS_PER_ITERATION,
                PACKET_CAPACITY,
                jsonArray(legacyWall),
                jsonArray(packetWall),
                jsonArray(legacyCpu),
                jsonArray(packetCpu),
                jsonArray(pairedWallDelta),
                jsonArray(pairedCpuDelta),
                legacyWallMedian,
                packetWallMedian,
                percentileNearestRank(legacyWallSorted, 0.95),
                percentileNearestRank(packetWallSorted, 0.95),
                pairedWallMedianDelta,
                legacyCpuMedian,
                packetCpuMedian,
                percentileNearestRank(legacyCpuSorted, 0.95),
                percentileNearestRank(packetCpuSorted, 0.95),
                legacyCpuMedian / opsPerSample,
                packetCpuMedian / opsPerSample,
                pairedCpuMedianDelta,
                -pairedCpuMedianDelta,
                median(legacyFirstCpuSorted),
                median(packetFirstCpuSorted)
        );
        Files.writeString(output, json);
        System.out.printf(
                Locale.ROOT,
                "HOSTED_METAL_IN_JVM_PAIRED cpu_delta=%+.3f%% cpu_improvement=%+.3f%% wall_delta=%+.3f%% cpu_ns_per_op_legacy=%.3f cpu_ns_per_op_packet=%.3f%n",
                pairedCpuMedianDelta,
                -pairedCpuMedianDelta,
                pairedWallMedianDelta,
                legacyCpuMedian / opsPerSample,
                packetCpuMedian / opsPerSample
        );
    }

    private static double percentChange(final long candidate, final long baseline) {
        return (candidate / (double) baseline - 1.0) * 100.0;
    }

    private static long[] sorted(final long[] values) {
        long[] result = values.clone();
        Arrays.sort(result);
        return result;
    }

    private static double[] sorted(final double[] values) {
        double[] result = values.clone();
        Arrays.sort(result);
        return result;
    }

    private static double median(final long[] sorted) {
        int middle = sorted.length / 2;
        if ((sorted.length & 1) != 0) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    private static double median(final double[] sorted) {
        int middle = sorted.length / 2;
        if ((sorted.length & 1) != 0) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    private static long percentileNearestRank(final long[] sorted, final double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[Math.min(index, sorted.length - 1)];
    }

    private static String jsonArray(final long[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(values[i]);
        }
        return result.append(']').toString();
    }

    private static String jsonArray(final double[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(String.format(Locale.ROOT, "%.6f", values[i]));
        }
        return result.append(']').toString();
    }

    private record Sample(long wallNs, long cpuNs) {
    }
}
