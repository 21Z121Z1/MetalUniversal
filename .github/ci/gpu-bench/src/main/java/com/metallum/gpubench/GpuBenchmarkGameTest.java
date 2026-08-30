package com.metallum.gpubench;

import com.metallum.client.metal.render.MetalGpuTimingRecorder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("UnstableApiUsage")
public final class GpuBenchmarkGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        require(FabricLoader.getInstance().isModLoaded("metallum"), "MetalUniversal is not loaded");
        require(FabricLoader.getInstance().isModLoaded("sodium"), "Sodium is not loaded");
        require(FabricLoader.getInstance().isModLoaded("iris"), "Iris runtime dependency is not loaded");

        int warmupTicks = positiveProperty("metallum.ci.gpuWarmupTicks", 40);
        int sampleTicks = positiveProperty("metallum.ci.gpuSampleTicks", 100);
        Path evidenceDir = Path.of(System.getProperty(
                "metallum.ci.gpuEvidenceDir", "build/evidence"
        )).toAbsolutePath().normalize();
        try {
            Files.createDirectories(evidenceDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create GPU benchmark evidence directory", exception);
        }

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int chunkRenderTicks = singleplayer.getClientLevel().waitForChunksRender();
            String backend = context.computeOnClient(
                    client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            );
            require("Metal".equalsIgnoreCase(backend),
                    "Expected Metal backend, observed " + backend);
            require(chunkRenderTicks > 0,
                    "waitForChunksRender returned an invalid tick count: " + chunkRenderTicks);

            // Warm the world, renderer, shader/MSL caches and command-buffer pools before
            // clearing the timing recorder. Heavy readback and render-contract tracing are
            // intentionally absent from this performance-only proxy lane.
            context.waitTicks(warmupTicks);
            MetalGpuTimingRecorder.reset();

            long sampleStarted = System.nanoTime();
            context.waitTicks(sampleTicks);
            long sampleElapsedNanos = System.nanoTime() - sampleStarted;

            List<MetalGpuTimingRecorder.Sample> raw = MetalGpuTimingRecorder.snapshot();
            List<Double> millis = new ArrayList<>(raw.size());
            for (MetalGpuTimingRecorder.Sample sample : raw) {
                double value = sample.milliseconds();
                if (value > 0.0 && Double.isFinite(value)) {
                    millis.add(value);
                }
            }
            Collections.sort(millis);
            require(millis.size() >= 30,
                    "GPU timing window produced too few completed command buffers: " + millis.size());

            double p50 = percentile(millis, 0.50);
            double p90 = percentile(millis, 0.90);
            double p95 = percentile(millis, 0.95);
            double p99 = percentile(millis, 0.99);
            double mean = millis.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double elapsedSeconds = sampleElapsedNanos / 1_000_000_000.0;
            double completedGpuFramesPerSecond = millis.size() / elapsedSeconds;

            // Preserve every completed command-buffer timing only after the measured
            // window has ended. This makes the hosted runner useful to an automated
            // bottleneck finder without adding file I/O or JSON work to the render path.
            writeFrameTrace(evidenceDir.resolve("gpu-frame-trace.jsonl"), raw);
            writeEvidence(
                    evidenceDir.resolve("gpu-benchmark.json"),
                    backend,
                    chunkRenderTicks,
                    warmupTicks,
                    sampleTicks,
                    sampleElapsedNanos,
                    millis.size(),
                    mean,
                    p50,
                    p90,
                    p95,
                    p99,
                    completedGpuFramesPerSecond
            );
        }
    }

    private static int positiveProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException("must be positive");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid positive integer for " + name + ": " + value, exception);
        }
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.size() == 1) return sorted.getFirst();
        double position = (sorted.size() - 1) * fraction;
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        if (low == high) return sorted.get(low);
        double weight = position - low;
        return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
    }

    private static void writeFrameTrace(Path path, List<MetalGpuTimingRecorder.Sample> samples) {
        StringBuilder jsonl = new StringBuilder(Math.max(1, samples.size()) * 192);
        int ordinal = 0;
        for (MetalGpuTimingRecorder.Sample sample : samples) {
            double milliseconds = sample.milliseconds();
            if (!(milliseconds > 0.0)
                    || !Double.isFinite(milliseconds)
                    || !Double.isFinite(sample.gpuStartTime())
                    || !Double.isFinite(sample.gpuEndTime())) {
                continue;
            }
            jsonl.append(String.format(
                    Locale.ROOT,
                    "{\"schema\":1,\"authority\":\"hosted-metal3-proxy-screening\","
                            + "\"ordinal\":%d,\"submitIndex\":%d,"
                            + "\"gpuStartTimeSeconds\":%.9f,\"gpuEndTimeSeconds\":%.9f,"
                            + "\"gpuMillis\":%.6f}%n",
                    ordinal++,
                    sample.submitIndex(),
                    sample.gpuStartTime(),
                    sample.gpuEndTime(),
                    milliseconds
            ));
        }
        try {
            Files.writeString(path, jsonl, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write GPU frame trace " + path, exception);
        }
    }

    private static void writeEvidence(
            Path path,
            String backend,
            int chunkRenderTicks,
            int warmupTicks,
            int sampleTicks,
            long sampleElapsedNanos,
            int sampleCount,
            double mean,
            double p50,
            double p90,
            double p95,
            double p99,
            double completedGpuFramesPerSecond
    ) {
        String json = String.format(
                Locale.ROOT,
                "{\n" +
                        "  \"schema\": 1,\n" +
                        "  \"authority\": \"hosted-metal3-proxy-screening\",\n" +
                        "  \"backend\": \"%s\",\n" +
                        "  \"minecraft\": \"26.2\",\n" +
                        "  \"sodiumLoaded\": true,\n" +
                        "  \"irisRuntimeLoaded\": true,\n" +
                        "  \"irisSemantic\": false,\n" +
                        "  \"chunkRenderTicks\": %d,\n" +
                        "  \"warmupTicks\": %d,\n" +
                        "  \"sampleTicks\": %d,\n" +
                        "  \"sampleElapsedNanos\": %d,\n" +
                        "  \"gpuSampleCount\": %d,\n" +
                        "  \"gpuFrameTraceFile\": \"gpu-frame-trace.jsonl\",\n" +
                        "  \"gpuFrameTraceSchema\": 1,\n" +
                        "  \"gpuMeanMillis\": %.6f,\n" +
                        "  \"gpuP50Millis\": %.6f,\n" +
                        "  \"gpuP90Millis\": %.6f,\n" +
                        "  \"gpuP95Millis\": %.6f,\n" +
                        "  \"gpuP99Millis\": %.6f,\n" +
                        "  \"completedGpuFramesPerSecond\": %.3f,\n" +
                        "  \"availableProcessors\": %d,\n" +
                        "  \"options\": {\n" +
                        "    \"deferredStore\": %s,\n" +
                        "    \"deferredColorStore\": %s,\n" +
                        "    \"blitBatch\": %s,\n" +
                        "    \"encoderStateShadow\": %s\n" +
                        "  }\n" +
                        "}\n",
                escape(backend),
                chunkRenderTicks,
                warmupTicks,
                sampleTicks,
                sampleElapsedNanos,
                sampleCount,
                mean,
                p50,
                p90,
                p95,
                p99,
                completedGpuFramesPerSecond,
                Runtime.getRuntime().availableProcessors(),
                boolProperty("metallum.opt.deferredStore"),
                boolProperty("metallum.opt.deferredColorStore"),
                boolProperty("metallum.opt.blitBatch"),
                boolProperty("metallum.opt.encoderStateShadow")
        );
        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write GPU benchmark evidence " + path, exception);
        }
    }

    private static String boolProperty(String name) {
        return Boolean.toString(Boolean.parseBoolean(System.getProperty(name, "false")));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
