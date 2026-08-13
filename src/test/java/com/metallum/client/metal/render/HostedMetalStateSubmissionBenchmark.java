package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCullMode;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLTriangleFillMode;
import com.metallum.client.metal.render.mtl.MTLWinding;
import com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hosted-runner performance experiment for the Java -> FFM -> Swift render-state path.
 *
 * <p>This deliberately uses a real MTLRenderCommandEncoder and submits every sampled
 * command buffer to Metal, while timing only CPU-side state encoding plus endEncoding.
 * GPU completion stays outside the timed interval so the result answers one narrow
 * question: does collapsing repeated state setters into MetalRenderStatePacket reduce
 * backend submission overhead on GitHub's macOS 26 Apple-paravirtual Metal device?</p>
 *
 * <p>The test is opt-in because it is a measurement harness, not a correctness gate.
 * Run it in separate JVMs with {@code metallum.opt.renderStatePacket=true/false}; the
 * packet-selection switches are static by design.</p>
 */
@EnabledOnOs(OS.MAC)
final class HostedMetalStateSubmissionBenchmark {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 16;
    private static final int WARMUP_SAMPLES = 8;
    private static final int RECORDED_SAMPLES = 24;
    private static final int ITERATIONS_PER_SAMPLE = 2_048;
    private static final int STATE_OPS_PER_ITERATION = 5;
    private static final int TEXTURE_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;

    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("metallum.perf.hosted"),
                "Hosted Metal performance benchmark must be explicitly enabled with -Dmetallum.perf.hosted=true"
        );
        assertFalse(
                Boolean.getBoolean("metallum.opt.renderCommandPacket"),
                "This benchmark isolates renderStatePacket; renderCommandPacket must be disabled"
        );

        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(
                MetalNativeBridge.isNullHandle(nativeDevice),
                "MTLCreateSystemDefaultDevice returned null"
        );
        ShaderSource source = (identifier, type) -> null;
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, false, false, false),
                nativeDevice,
                MemorySegment.NULL,
                "Hosted Metal state-submission benchmark",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void measureStatePacketAgainstLegacyFfmSubmission() throws IOException {
        boolean packetEnabled = !"false".equalsIgnoreCase(
                System.getProperty("metallum.opt.renderStatePacket", "true")
        );
        String mode = packetEnabled ? "packet" : "legacy";

        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "hosted-state-submission-target",
                TEXTURE_USAGE,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        )) {
            // Device construction can enqueue initialization uploads. Keep them
            // out of both warmup and recorded intervals.
            encoder.endEncoder();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            for (int i = 0; i < WARMUP_SAMPLES; i++) {
                runSample(texture, ITERATIONS_PER_SAMPLE);
            }

            MetalRenderStatePacketTelemetry.reset();
            long[] samples = new long[RECORDED_SAMPLES];
            for (int i = 0; i < RECORDED_SAMPLES; i++) {
                samples[i] = runSample(texture, ITERATIONS_PER_SAMPLE);
            }

            MetalRenderStatePacketTelemetry.Snapshot telemetry =
                    MetalRenderStatePacketTelemetry.snapshot();
            if (packetEnabled) {
                assertTrue(telemetry.packetCalls() > 0L, "packet mode encoded no native packets");
                assertTrue(telemetry.packetEntries() > 0L, "packet mode encoded no state entries");
                assertEquals(0L, telemetry.legacyReplays(), "packet mode fell back to legacy replay");
                assertTrue(
                        telemetry.collapsedSetterDowncalls() > 0L,
                        "packet mode did not collapse any setter downcalls"
                );
            } else {
                assertEquals(0L, telemetry.packetCalls(), "legacy mode unexpectedly encoded state packets");
                assertEquals(0L, telemetry.packetEntries(), "legacy mode unexpectedly recorded packet entries");
            }

            writeResult(mode, packetEnabled, samples, telemetry);
        }
    }

    private long runSample(final MetalGpuTexture texture, final int iterations) {
        encoder.endEncoder();
        MTLRenderCommandEncoder nativeEncoder = encoder.commandBuffer().makeRenderCommandEncoder(
                texture.nativeHandle(),
                MemorySegment.NULL,
                WIDTH,
                HEIGHT,
                0,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                1.0
        );

        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean alternate = (iteration & 1) != 0;
            nativeEncoder.setDepthBias(
                    alternate ? 0.125F : 0.0F,
                    alternate ? 2.0F : 1.0F,
                    0.0F
            );
            nativeEncoder.setFrontFacingWinding(
                    alternate ? MTLWinding.CounterClockwise : MTLWinding.Clockwise
            );
            nativeEncoder.setCullMode(alternate ? MTLCullMode.Back : MTLCullMode.None);
            nativeEncoder.setTriangleFillMode(
                    alternate ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill
            );
            nativeEncoder.setScissorRect(alternate ? 1 : 0, 0, WIDTH - 1, HEIGHT);
        }
        // State packets flush here. Keep endEncoding in the timed interval so
        // packet decode/application cost is measured rather than deferred away.
        nativeEncoder.endEncoding();
        long elapsed = System.nanoTime() - start;

        // A real GPU submission/readback-capable device is required, but queue
        // latency is intentionally outside the CPU submission measurement.
        encoder.submit();
        device.waitForSubmittedGpuWork();
        return elapsed;
    }

    private static void writeResult(
            final String mode,
            final boolean packetEnabled,
            final long[] samples,
            final MetalRenderStatePacketTelemetry.Snapshot telemetry
    ) throws IOException {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        double median = median(sorted);
        long p95 = percentileNearestRank(sorted, 0.95);
        long totalStateOps = (long) RECORDED_SAMPLES
                * ITERATIONS_PER_SAMPLE
                * STATE_OPS_PER_ITERATION;
        double nsPerStateOp = median
                / (ITERATIONS_PER_SAMPLE * (double) STATE_OPS_PER_ITERATION);

        Path output = Path.of("build", "hosted-metal-perf", "state-submission.json");
        Files.createDirectories(output.getParent());
        String json = String.format(
                Locale.ROOT,
                """
                {
                  \"schema\": 1,
                  \"benchmark\": \"render-state-submission\",
                  \"mode\": \"%s\",
                  \"render_state_packet_enabled\": %s,
                  \"warmup_samples\": %d,
                  \"recorded_samples\": %d,
                  \"iterations_per_sample\": %d,
                  \"state_ops_per_iteration\": %d,
                  \"total_state_ops\": %d,
                  \"samples_ns\": %s,
                  \"median_ns\": %.3f,
                  \"p95_ns\": %d,
                  \"median_ns_per_state_op\": %.6f,
                  \"telemetry\": {
                    \"packet_calls\": %d,
                    \"packet_entries\": %d,
                    \"legacy_replays\": %d,
                    \"legacy_replay_entries\": %d,
                    \"single_entry_bypasses\": %d,
                    \"capacity_flushes\": %d,
                    \"collapsed_setter_downcalls\": %d
                  }
                }
                """,
                mode,
                packetEnabled,
                WARMUP_SAMPLES,
                RECORDED_SAMPLES,
                ITERATIONS_PER_SAMPLE,
                STATE_OPS_PER_ITERATION,
                totalStateOps,
                jsonArray(samples),
                median,
                p95,
                nsPerStateOp,
                telemetry.packetCalls(),
                telemetry.packetEntries(),
                telemetry.legacyReplays(),
                telemetry.legacyReplayEntries(),
                telemetry.singleEntryBypasses(),
                telemetry.capacityFlushes(),
                telemetry.collapsedSetterDowncalls()
        );
        Files.writeString(output, json);
        System.out.printf(
                Locale.ROOT,
                "HOSTED_METAL_STATE_SUBMISSION mode=%s median_ns=%.0f p95_ns=%d ns_per_op=%.3f packet_calls=%d packet_entries=%d collapsed_downcalls=%d%n",
                mode,
                median,
                p95,
                nsPerStateOp,
                telemetry.packetCalls(),
                telemetry.packetEntries(),
                telemetry.collapsedSetterDowncalls()
        );
    }

    private static double median(final long[] sorted) {
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
}
