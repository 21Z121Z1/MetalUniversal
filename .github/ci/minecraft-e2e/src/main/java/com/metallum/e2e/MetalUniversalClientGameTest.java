package com.metallum.e2e;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@SuppressWarnings("UnstableApiUsage")
public final class MetalUniversalClientGameTest implements FabricClientGameTest {
    private static final String SCREENSHOT_NAME = "metaluniversal-hosted-world";

    @Override
    public void runTest(ClientGameTestContext context) {
        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"));
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

            Path screenshot = context.takeScreenshot(SCREENSHOT_NAME);
            require(Files.isRegularFile(screenshot), "Client GameTest did not create screenshot: " + screenshot);
            require(fileSize(screenshot) > 0, "Client GameTest screenshot is empty: " + screenshot);

            writeEvidence(
                    evidenceDir.resolve("runtime-evidence.json"),
                    backend,
                    metallumLoaded,
                    sodiumLoaded,
                    irisLoaded,
                    worldLoaded,
                    chunkRenderTicks,
                    screenshot.toAbsolutePath().normalize()
            );
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not stat screenshot " + path, exception);
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
            Path screenshot
    ) {
        String json = """
                {
                  "schema": 1,
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
                  "screenshot": "%s",
                  "completedAt": "%s"
                }
                """.formatted(
                escape(backend),
                metallumLoaded,
                sodiumLoaded,
                irisLoaded,
                worldLoaded,
                chunkRenderTicks,
                escape(screenshot.toString()),
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
}
