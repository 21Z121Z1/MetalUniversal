package com.metallum.terrainbench;

import com.metallum.client.terrain.TerrainSchedulingController;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@SuppressWarnings("UnstableApiUsage")
public final class TerrainBenchmarkGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        require(FabricLoader.getInstance().isModLoaded("metallum"), "MetalUniversal is not loaded");
        require(FabricLoader.getInstance().isModLoaded("sodium"), "Sodium is not loaded");

        Path evidenceDir = Path.of(System.getProperty(
                "metallum.ci.terrainEvidenceDir", "build/evidence"
        )).toAbsolutePath().normalize();
        try {
            Files.createDirectories(evidenceDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create terrain evidence directory", exception);
        }

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            long started = System.nanoTime();
            int chunkRenderTicks = singleplayer.getClientLevel().waitForChunksRender();
            long elapsedNanos = System.nanoTime() - started;

            String backend = context.computeOnClient(
                    client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            );
            require("Metal".equalsIgnoreCase(backend),
                    "Expected Metal backend, observed " + backend);
            require(chunkRenderTicks > 0,
                    "waitForChunksRender returned an invalid tick count: " + chunkRenderTicks);

            TerrainSchedulingController scheduling = TerrainSchedulingController.runtime();
            TerrainSchedulingController.Counters schedulerCounters = scheduling.counters();

            // Let the renderer drain work that was completed in the readiness tick before
            // writing evidence. This is deliberately short: unlike the correctness E2E,
            // this benchmark must not spend time on screenshots, reloads or presentation probes.
            context.waitTicks(2);

            writeEvidence(
                    evidenceDir.resolve("terrain-benchmark.json"),
                    backend,
                    chunkRenderTicks,
                    elapsedNanos,
                    scheduling.isEnabled(),
                    schedulerCounters
            );
        }
    }

    private static void writeEvidence(
            Path path,
            String backend,
            int ticks,
            long elapsedNanos,
            boolean schedulerEnabled,
            TerrainSchedulingController.Counters schedulerCounters
    ) {
        double elapsedMillis = elapsedNanos / 1_000_000.0;
        String json = String.format(
                Locale.ROOT,
                "{\n" +
                        "  \"schema\": 2,\n" +
                        "  \"backend\": \"%s\",\n" +
                        "  \"minecraft\": \"26.2\",\n" +
                        "  \"sodiumLoaded\": true,\n" +
                        "  \"chunksRendered\": true,\n" +
                        "  \"chunkRenderTicks\": %d,\n" +
                        "  \"chunkRenderElapsedNanos\": %d,\n" +
                        "  \"chunkRenderElapsedMillis\": %.3f,\n" +
                        "  \"availableProcessors\": %d,\n" +
                        "  \"terrainSchedulerEnabled\": %s,\n" +
                        "  \"terrainSchedulerFrames\": %d,\n" +
                        "  \"terrainSchedulerAdaptiveFrames\": %d,\n" +
                        "  \"terrainSchedulerPressureFrames\": %d\n" +
                        "}\n",
                escape(backend),
                ticks,
                elapsedNanos,
                elapsedMillis,
                Runtime.getRuntime().availableProcessors(),
                schedulerEnabled,
                schedulerCounters.frames(),
                schedulerCounters.adaptiveFrames(),
                schedulerCounters.pressureFrames()
        );
        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write terrain benchmark evidence " + path, exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
