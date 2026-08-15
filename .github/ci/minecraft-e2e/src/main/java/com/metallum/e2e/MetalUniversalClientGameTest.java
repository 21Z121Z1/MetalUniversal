package com.metallum.e2e;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@SuppressWarnings("UnstableApiUsage")
public final class MetalUniversalClientGameTest implements FabricClientGameTest {
    private static final String SCREENSHOT_NAME = "metaluniversal-hosted-world";
    private static final String RENDER_CONTRACT_RUNTIME =
            "com.metallum.client.validation.contract.RenderContractRuntime";
    private static final int METAL_CAPTURE_SAMPLES = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(evidenceDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create CI evidence directory " + evidenceDir, exception);
        }

        boolean metallumLoaded = FabricLoader.getInstance().isModLoaded("metallum");
        boolean sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        boolean irisLoaded = FabricLoader.getInstance().isModLoaded("iris");

        require(metallumLoaded, "MetalUniversal mod was not loaded in the production client");
        require(sodiumLoaded, "Sodium was not loaded in the production client");
        require(irisLoaded, "Iris was not loaded in the production client");

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int chunkRenderTicks = singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(40);

            String backend = context.computeOnClient(
                    client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            );
            boolean worldLoaded = context.computeOnClient(client -> client.level != null);

            require(worldLoaded, "Minecraft client has no loaded level after Client GameTest world creation");
            require(
                    "Metal".equalsIgnoreCase(backend),
                    "Expected MetalUniversal Metal backend, observed graphics backend: " + backend
            );

            // The ordinary Fabric screenshot is retained as a diagnostic only. GitHub's hosted
            // WindowServer does not reliably scan out CAMetalLayer drawables, so authoritative
            // evidence is sampled from MetalUniversal's pre-present source texture through its
            // production RenderContractRuntime GPU texture -> buffer readback path.
            Path metalCaptureRoot = evidenceDir.resolve("metal-framebuffer");
            startRenderContract(metalCaptureRoot);
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");

            List<CaptureSample> samples = new ArrayList<>();
            for (long frameId = 1; frameId <= METAL_CAPTURE_SAMPLES; frameId++) {
                RenderContractSnapshot before = renderContractSnapshot();
                beginRenderContractFrame(frameId);
                try {
                    requestFinalDrawableCapture(frameId);
                    waitForCaptureCompletion(context, before.completedCaptures() + 1, frameId, 100);
                } finally {
                    endRenderContractFrame(frameId);
                }

                Path png = findFrameArtifact(metalCaptureRoot, frameId, "actual.png");
                Path raw = findFrameArtifact(metalCaptureRoot, frameId, "actual.bin");
                require(Files.isRegularFile(png), "Missing Metal framebuffer PNG for frame " + frameId);
                require(Files.isRegularFile(raw), "Missing Metal framebuffer raw readback for frame " + frameId);
                CaptureSample sample = inspectCapture(frameId, png, raw);
                samples.add(sample);

                // Sampling is deliberately spaced. This rejects the possibility that a single
                // transitional frame (world load, resize, GUI hand-off) is mistaken for the
                // renderer's steady-state output.
                context.waitTicks(4);
            }

            CaptureSample selected = selectBestCapture(samples);
            require(selected != null, "Metal framebuffer sampling produced no captures");
            Path canonicalMetalFramebuffer = evidenceDir.resolve("metal-framebuffer.png");
            try {
                Files.copy(selected.png(), canonicalMetalFramebuffer, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not stage selected Metal framebuffer", exception);
            }
            writeCaptureSamples(evidenceDir.resolve("metal-framebuffer-samples.json"), samples, selected.frameId());

            RenderContractSnapshot contractSnapshot = renderContractSnapshot();
            closeRenderContract();

            Path windowScreenshot = context.takeScreenshot(SCREENSHOT_NAME);
            require(Files.isRegularFile(windowScreenshot),
                    "Client GameTest did not create diagnostic window screenshot: " + windowScreenshot);
            require(fileSize(windowScreenshot) > 0,
                    "Client GameTest diagnostic window screenshot is empty: " + windowScreenshot);

            writeEvidence(
                    evidenceDir.resolve("runtime-evidence.json"),
                    backend,
                    metallumLoaded,
                    sodiumLoaded,
                    irisLoaded,
                    worldLoaded,
                    chunkRenderTicks,
                    windowScreenshot.toAbsolutePath().normalize(),
                    canonicalMetalFramebuffer.toAbsolutePath().normalize(),
                    contractSnapshot,
                    samples.size(),
                    selected
            );

            require(contractSnapshot.completedCaptures() == METAL_CAPTURE_SAMPLES,
                    "Expected " + METAL_CAPTURE_SAMPLES + " completed Metal framebuffer captures: " + contractSnapshot);
            require(contractSnapshot.failedCaptures() == 0,
                    "Metal render-contract reported failed captures: " + contractSnapshot);
            require(contractSnapshot.pendingCaptures() == 0,
                    "Metal render-contract still has pending captures: " + contractSnapshot);
            require(contractSnapshot.droppedCaptures() == 0,
                    "Metal render-contract dropped framebuffer captures: " + contractSnapshot);
            require(selected.nonBlackPixels() > 0 && selected.distinctRgb() > 1,
                    "All sampled Metal framebuffers were black/constant; best sample=" + selected);
        } finally {
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");
            closeRenderContractQuietly();
        }
    }

    private static void startRenderContract(Path output) {
        try {
            Files.createDirectories(output);
            System.setProperty("metallum.renderContract.enabled", "true");
            System.setProperty("metallum.renderContract.maxCaptures", Integer.toString(METAL_CAPTURE_SAMPLES + 4));
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            Method start = runtime.getMethod("start", Path.class, String.class);
            start.invoke(null, output, "minecraft-client-gametest");
        } catch (IOException | ClassNotFoundException | NoSuchMethodException
                 | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not start Metal render-contract framebuffer capture", exception);
        }
    }

    private static void beginRenderContractFrame(long frameId) {
        invokeRenderContractFrameMethod("beginFrame", frameId);
    }

    private static void endRenderContractFrame(long frameId) {
        invokeRenderContractFrameMethod("endFrame", frameId);
    }

    private static void requestFinalDrawableCapture(long frameId) {
        invokeRenderContractFrameMethod("requestFinalDrawableCapture", frameId);
    }

    private static void invokeRenderContractFrameMethod(String name, long frameId) {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            runtime.getMethod(name, long.class).invoke(null, frameId);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Could not invoke RenderContractRuntime." + name + "(" + frameId + ")", exception);
        }
    }

    private static void waitForCaptureCompletion(
            ClientGameTestContext context,
            int expectedCompletedCaptures,
            long frameId,
            int maxTicks
    ) {
        for (int tick = 0; tick < maxTicks; tick++) {
            RenderContractSnapshot snapshot = renderContractSnapshot();
            if (snapshot.failedCaptures() > 0 || snapshot.droppedCaptures() > 0) {
                throw new IllegalStateException(
                        "Metal framebuffer capture failed while waiting for frame " + frameId + ": " + snapshot
                );
            }
            if (snapshot.completedCaptures() >= expectedCompletedCaptures && snapshot.pendingCaptures() == 0) {
                return;
            }
            context.waitTicks(1);
        }
        throw new IllegalStateException(
                "Timed out waiting for Metal framebuffer frame " + frameId
                        + "; snapshot=" + renderContractSnapshot()
        );
    }

    private static Path findFrameArtifact(Path root, long frameId, String fileName) {
        Path frameRoot = root.resolve("render-contract")
                .resolve("frames")
                .resolve("frame-%06d".formatted(frameId));
        if (!Files.isDirectory(frameRoot)) {
            throw new IllegalStateException("Render-contract frame directory is missing: " + frameRoot);
        }
        try (Stream<Path> paths = Files.walk(frameRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Could not find " + fileName + " for Metal framebuffer frame " + frameId
                    ));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect Metal framebuffer frame " + frameId, exception);
        }
    }

    private static CaptureSample inspectCapture(long frameId, Path png, Path raw) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(raw);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Metal framebuffer bytes " + raw, exception);
        }
        require(bytes.length > 0 && bytes.length % 4 == 0,
                "Unexpected RGBA8 Metal framebuffer byte count for frame " + frameId + ": " + bytes.length);

        long nonZeroBytes = 0;
        long nonBlackPixels = 0;
        double sumLuma = 0.0;
        double sumLumaSquared = 0.0;
        Set<Integer> distinct = new HashSet<>();
        int pixels = bytes.length / 4;

        for (int offset = 0; offset < bytes.length; offset += 4) {
            int r = bytes[offset] & 0xff;
            int g = bytes[offset + 1] & 0xff;
            int b = bytes[offset + 2] & 0xff;
            int a = bytes[offset + 3] & 0xff;
            if (r != 0) nonZeroBytes++;
            if (g != 0) nonZeroBytes++;
            if (b != 0) nonZeroBytes++;
            if (a != 0) nonZeroBytes++;
            if (r > 4 || g > 4 || b > 4) nonBlackPixels++;
            if (distinct.size() < 8192) {
                distinct.add((r << 16) | (g << 8) | b);
            }
            double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            sumLuma += luma;
            sumLumaSquared += luma * luma;
        }

        double mean = sumLuma / pixels;
        double variance = Math.max(0.0, sumLumaSquared / pixels - mean * mean);
        double stddev = Math.sqrt(variance);
        return new CaptureSample(
                frameId,
                png.toAbsolutePath().normalize(),
                raw.toAbsolutePath().normalize(),
                bytes.length,
                nonZeroBytes,
                nonBlackPixels,
                distinct.size(),
                mean,
                stddev
        );
    }

    private static CaptureSample selectBestCapture(List<CaptureSample> samples) {
        CaptureSample best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (CaptureSample sample : samples) {
            double score = sample.lumaStddev() * 1_000_000.0
                    + sample.distinctRgb() * 1_000.0
                    + sample.nonBlackPixels();
            if (score > bestScore) {
                bestScore = score;
                best = sample;
            }
        }
        return best;
    }

    private static void writeCaptureSamples(Path path, List<CaptureSample> samples, long selectedFrameId) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema\": 1,\n  \"selectedFrameId\": ").append(selectedFrameId)
                .append(",\n  \"samples\": [\n");
        for (int i = 0; i < samples.size(); i++) {
            CaptureSample sample = samples.get(i);
            json.append("    {\n")
                    .append("      \"frameId\": ").append(sample.frameId()).append(",\n")
                    .append("      \"byteCount\": ").append(sample.byteCount()).append(",\n")
                    .append("      \"nonZeroBytes\": ").append(sample.nonZeroBytes()).append(",\n")
                    .append("      \"nonBlackPixels\": ").append(sample.nonBlackPixels()).append(",\n")
                    .append("      \"distinctRgb\": ").append(sample.distinctRgb()).append(",\n")
                    .append("      \"meanLuma\": ").append(String.format(java.util.Locale.ROOT, "%.6f", sample.meanLuma())).append(",\n")
                    .append("      \"lumaStddev\": ").append(String.format(java.util.Locale.ROOT, "%.6f", sample.lumaStddev())).append(",\n")
                    .append("      \"png\": \"").append(escape(sample.png().toString())).append("\",\n")
                    .append("      \"raw\": \"").append(escape(sample.raw().toString())).append("\"\n")
                    .append("    }");
            if (i + 1 < samples.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write Metal framebuffer sample summary " + path, exception);
        }
    }

    private static RenderContractSnapshot renderContractSnapshot() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            Object snapshot = runtime.getMethod("snapshot").invoke(null);
            Class<?> snapshotClass = snapshot.getClass();
            return new RenderContractSnapshot(
                    (boolean) snapshotClass.getMethod("enabled").invoke(snapshot),
                    (String) snapshotClass.getMethod("status").invoke(snapshot),
                    (int) snapshotClass.getMethod("requestedCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("completedCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("failedCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("pendingCaptures").invoke(snapshot),
                    (int) snapshotClass.getMethod("droppedCaptures").invoke(snapshot)
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Could not inspect Metal render-contract capture state", exception);
        }
    }

    private static void closeRenderContract() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            runtime.getMethod("close").invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Could not close Metal render-contract capture", exception);
        }
    }

    private static void closeRenderContractQuietly() {
        try {
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            runtime.getMethod("close").invoke(null);
        } catch (ReflectiveOperationException ignored) {
            // Best-effort cleanup after the test has already produced its primary failure.
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not stat evidence file " + path, exception);
        }
    }

    private static void writeEvidence(
            Path path,
            String backend,
            boolean metallumLoaded,
            boolean sodiumLoaded,
            boolean irisLoaded,
            boolean worldLoaded,
            int chunkRenderTicks,
            Path windowScreenshot,
            Path metalFramebuffer,
            RenderContractSnapshot contractSnapshot,
            int sampledFrames,
            CaptureSample selected
    ) {
        String json = """
                {
                  "schema": 3,
                  "backend": "%s",
                  "minecraft": "26.2",
                  "productionRuntime": true,
                  "clientGameTest": true,
                  "metallumLoaded": %s,
                  "sodiumLoaded": %s,
                  "irisLoaded": %s,
                  "worldLoaded": %s,
                  "chunksRendered": true,
                  "chunkRenderTicks": %d,
                  "windowScreenshot": "%s",
                  "metalFramebuffer": "%s",
                  "metalFramebufferSource": "MetalUniversal pre-present source texture GPU readback",
                  "sampledMetalFrames": %d,
                  "selectedMetalFrameId": %d,
                  "selectedMetalFrameNonZeroBytes": %d,
                  "selectedMetalFrameNonBlackPixels": %d,
                  "selectedMetalFrameDistinctRgb": %d,
                  "selectedMetalFrameMeanLuma": %.6f,
                  "selectedMetalFrameLumaStddev": %.6f,
                  "renderContractRequestedCaptures": %d,
                  "renderContractCompletedCaptures": %d,
                  "renderContractFailedCaptures": %d,
                  "renderContractDroppedCaptures": %d,
                  "completedAt": "%s"
                }
                """.formatted(
                escape(backend),
                metallumLoaded,
                sodiumLoaded,
                irisLoaded,
                worldLoaded,
                chunkRenderTicks,
                escape(windowScreenshot.toString()),
                escape(metalFramebuffer.toString()),
                sampledFrames,
                selected.frameId(),
                selected.nonZeroBytes(),
                selected.nonBlackPixels(),
                selected.distinctRgb(),
                selected.meanLuma(),
                selected.lumaStddev(),
                contractSnapshot.requestedCaptures(),
                contractSnapshot.completedCaptures(),
                contractSnapshot.failedCaptures(),
                contractSnapshot.droppedCaptures(),
                escape(Instant.now().toString())
        );

        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write runtime evidence " + path, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record RenderContractSnapshot(
            boolean enabled,
            String status,
            int requestedCaptures,
            int completedCaptures,
            int failedCaptures,
            int pendingCaptures,
            int droppedCaptures
    ) {
    }

    private record CaptureSample(
            long frameId,
            Path png,
            Path raw,
            long byteCount,
            long nonZeroBytes,
            long nonBlackPixels,
            int distinctRgb,
            double meanLuma,
            double lumaStddev
    ) {
    }
}
