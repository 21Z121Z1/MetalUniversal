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
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

@SuppressWarnings("UnstableApiUsage")
public final class MetalUniversalClientGameTest implements FabricClientGameTest {
    private static final String SCREENSHOT_NAME = "metaluniversal-hosted-world";
    private static final String RENDER_CONTRACT_RUNTIME =
            "com.metallum.client.validation.contract.RenderContractRuntime";

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
            context.waitTicks(20);

            String backend = context.computeOnClient(
                    client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            );
            boolean worldLoaded = context.computeOnClient(client -> client.level != null);

            require(worldLoaded, "Minecraft client has no loaded level after Client GameTest world creation");
            require(
                    "Metal".equalsIgnoreCase(backend),
                    "Expected MetalUniversal Metal backend, observed graphics backend: " + backend
            );

            // Fabric's ordinary screenshot ultimately observes the hosted window and is retained
            // as a diagnostic only. The authoritative screenshot below is captured from the
            // MetalUniversal pre-present source texture through the renderer's existing
            // RenderContractRuntime GPU readback path, so it does not depend on WindowServer
            // scan-out being available on GitHub's virtual macOS runner.
            Path metalCaptureRoot = evidenceDir.resolve("metal-framebuffer");
            startRenderContract(metalCaptureRoot);
            System.setProperty("metallum.renderContract.captureFinalDrawable", "true");
            try {
                // Keep the capture window deliberately short. Each present observed in this tick
                // is a real Metal source texture; the bounded capture service handles the
                // asynchronous texture -> shared-buffer readback for those frames.
                context.waitTicks(1);
            } finally {
                System.setProperty("metallum.renderContract.captureFinalDrawable", "false");
            }

            Path metalFramebuffer = waitForMetalFramebuffer(context, metalCaptureRoot, 100);
            require(Files.isRegularFile(metalFramebuffer),
                    "Metal render-contract did not produce an actual.png framebuffer: " + metalCaptureRoot);
            require(fileSize(metalFramebuffer) > 0,
                    "Metal render-contract framebuffer is empty: " + metalFramebuffer);
            RenderContractSnapshot contractSnapshot = renderContractSnapshot();
            require(contractSnapshot.completedCaptures() > 0,
                    "Metal render-contract completed no framebuffer captures: " + contractSnapshot);
            require(contractSnapshot.failedCaptures() == 0,
                    "Metal render-contract reported failed captures: " + contractSnapshot);
            require(contractSnapshot.pendingCaptures() == 0,
                    "Metal render-contract still has pending captures: " + contractSnapshot);
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
                    metalFramebuffer.toAbsolutePath().normalize(),
                    contractSnapshot
            );
        } finally {
            System.setProperty("metallum.renderContract.captureFinalDrawable", "false");
            closeRenderContractQuietly();
        }
    }

    private static void startRenderContract(Path output) {
        try {
            Files.createDirectories(output);
            System.setProperty("metallum.renderContract.enabled", "true");
            System.setProperty("metallum.renderContract.maxCaptures", "16");
            Class<?> runtime = Class.forName(RENDER_CONTRACT_RUNTIME);
            Method start = runtime.getMethod("start", Path.class, String.class);
            start.invoke(null, output, "minecraft-client-gametest");
        } catch (IOException | ClassNotFoundException | NoSuchMethodException
                 | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not start Metal render-contract framebuffer capture", exception);
        }
    }

    private static Path waitForMetalFramebuffer(
            ClientGameTestContext context,
            Path root,
            int maxTicks
    ) {
        for (int tick = 0; tick < maxTicks; tick++) {
            Path capture = findNewestActualPng(root);
            if (capture != null && fileSize(capture) > 0 && renderContractSnapshot().pendingCaptures() == 0) {
                return capture;
            }
            context.waitTicks(1);
        }
        throw new IllegalStateException(
                "Timed out waiting for Metal framebuffer readback under " + root
                        + "; snapshot=" + renderContractSnapshot()
        );
    }

    private static Path findNewestActualPng(Path root) {
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("actual.png"))
                    .max(Comparator.comparingLong(MetalUniversalClientGameTest::lastModifiedMillis))
                    .orElse(null);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect Metal framebuffer evidence under " + root, exception);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
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
            RenderContractSnapshot contractSnapshot
    ) {
        String json = """
                {
                  "schema": 2,
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
}
