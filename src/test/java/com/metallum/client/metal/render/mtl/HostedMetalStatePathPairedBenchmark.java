package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.HostedMetalBenchmarkFixture;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.MetalRenderStatePacketBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-JVM paired benchmark for the two state-submission mechanisms themselves.
 *
 * <p>Both paths use the same process, device, queue, render target, critical FFM
 * setters and native encoder ABI. The packet path explicitly serializes the same
 * five state changes into {@link MetalRenderStatePacket}; the legacy path invokes
 * the corresponding native setters directly. Pair order alternates every sample,
 * so JIT/host load drift affects both sides locally instead of being confounded
 * with separate Gradle test workers.</p>
 */
@EnabledOnOs(OS.MAC)
final class HostedMetalStatePathPairedBenchmark {
    private static final int WARMUP_PAIRS = 8;
    private static final int RECORDED_PAIRS = 32;
    private static final int ITERATIONS_PER_SAMPLE = 2_048;
    private static final int STATE_OPS_PER_ITERATION = 5;
    private static final int PACKET_CAPACITY = 256;

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

            long[] legacySamples = new long[RECORDED_PAIRS];
            long[] packetSamples = new long[RECORDED_PAIRS];
            double[] pairedDeltaPercent = new double[RECORDED_PAIRS];
            double[] legacyFirstDeltas = new double[RECORDED_PAIRS / 2];
            double[] packetFirstDeltas = new double[RECORDED_PAIRS / 2];
            int legacyFirstIndex = 0;
            int packetFirstIndex = 0;

            for (int pair = 0; pair < RECORDED_PAIRS; pair++) {
                long legacy;
                long packet;
                if ((pair & 1) == 0) {
                    legacy = runLegacySample(fixture);
                    packet = runPacketSample(fixture);
                } else {
                    packet = runPacketSample(fixture);
                    legacy = runLegacySample(fixture);
                }
                legacySamples[pair] = legacy;
                packetSamples[pair] = packet;
                double delta = percentChange(packet, legacy);
                pairedDeltaPercent[pair] = delta;
                if ((pair & 1) == 0) {
                    legacyFirstDeltas[legacyFirstIndex++] = delta;
                } else {
                    packetFirstDeltas[packetFirstIndex++] = delta;
                }
            }

            writeResult(
                    legacySamples,
                    packetSamples,
                    pairedDeltaPercent,
                    legacyFirstDeltas,
                    packetFirstDeltas
            );
        }
    }

    private static long runLegacySample(final HostedMetalBenchmarkFixture fixture) {
        MTLRenderCommandEncoder encoder = fixture.makeRenderEncoder();
        MemorySegment handle = encoder.handle();
        long start = System.nanoTime();
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
        long elapsed = System.nanoTime() - start;
        fixture.submitAndWait();
        return elapsed;
    }

    private static long runPacketSample(final HostedMetalBenchmarkFixture fixture) {
        MTLRenderCommandEncoder encoder = fixture.makeRenderEncoder();
        MemorySegment handle = encoder.handle();
        MetalRenderStatePacket packet = new MetalRenderStatePacket(PACKET_CAPACITY);
        long elapsed;
        try {
            long start = System.nanoTime();
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
            elapsed = System.nanoTime() - start;
            assertTrue(packet.active(), "packet ABI failed and replayed legacy setters");
        } finally {
            packet.close();
        }
        fixture.submitAndWait();
        return elapsed;
    }

    private static void writeResult(
            final long[] legacySamples,
            final long[] packetSamples,
            final double[] pairedDeltas,
            final double[] legacyFirstDeltas,
            final double[] packetFirstDeltas
    ) throws IOException {
        long[] legacySorted = legacySamples.clone();
        long[] packetSorted = packetSamples.clone();
        double[] pairedSorted = pairedDeltas.clone();
        double[] legacyFirstSorted = legacyFirstDeltas.clone();
        double[] packetFirstSorted = packetFirstDeltas.clone();
        Arrays.sort(legacySorted);
        Arrays.sort(packetSorted);
        Arrays.sort(pairedSorted);
        Arrays.sort(legacyFirstSorted);
        Arrays.sort(packetFirstSorted);

        double legacyMedian = median(legacySorted);
        double packetMedian = median(packetSorted);
        double pairedMedianDelta = median(pairedSorted);
        double opsPerSample = ITERATIONS_PER_SAMPLE * (double) STATE_OPS_PER_ITERATION;

        Path output = Path.of("build", "hosted-metal-perf", "in-jvm-paired.json");
        Files.createDirectories(output.getParent());
        String json = String.format(
                Locale.ROOT,
                """
                {
                  \"schema\": 1,
                  \"benchmark\": \"render-state-path-in-jvm-paired\",
                  \"interpretation\": \"negative delta means packet mode is faster\",
                  \"warmup_pairs\": %d,
                  \"recorded_pairs\": %d,
                  \"iterations_per_sample\": %d,
                  \"state_ops_per_iteration\": %d,
                  \"packet_capacity\": %d,
                  \"legacy_samples_ns\": %s,
                  \"packet_samples_ns\": %s,
                  \"paired_delta_percent\": %s,
                  \"legacy_median_ns\": %.3f,
                  \"packet_median_ns\": %.3f,
                  \"legacy_p95_ns\": %d,
                  \"packet_p95_ns\": %d,
                  \"legacy_median_ns_per_state_op\": %.6f,
                  \"packet_median_ns_per_state_op\": %.6f,
                  \"paired_median_delta_percent\": %.6f,
                  \"paired_median_improvement_percent\": %.6f,
                  \"legacy_first_median_delta_percent\": %.6f,
                  \"packet_first_median_delta_percent\": %.6f
                }
                """,
                WARMUP_PAIRS,
                RECORDED_PAIRS,
                ITERATIONS_PER_SAMPLE,
                STATE_OPS_PER_ITERATION,
                PACKET_CAPACITY,
                jsonArray(legacySamples),
                jsonArray(packetSamples),
                jsonArray(pairedDeltas),
                legacyMedian,
                packetMedian,
                percentileNearestRank(legacySorted, 0.95),
                percentileNearestRank(packetSorted, 0.95),
                legacyMedian / opsPerSample,
                packetMedian / opsPerSample,
                pairedMedianDelta,
                -pairedMedianDelta,
                median(legacyFirstSorted),
                median(packetFirstSorted)
        );
        Files.writeString(output, json);
        System.out.printf(
                Locale.ROOT,
                "HOSTED_METAL_IN_JVM_PAIRED legacy_median_ns=%.0f packet_median_ns=%.0f paired_delta=%+.3f%% paired_improvement=%+.3f%% legacy_first=%+.3f%% packet_first=%+.3f%%%n",
                legacyMedian,
                packetMedian,
                pairedMedianDelta,
                -pairedMedianDelta,
                median(legacyFirstSorted),
                median(packetFirstSorted)
        );
    }

    private static double percentChange(final long candidate, final long baseline) {
        return (candidate / (double) baseline - 1.0) * 100.0;
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
}
