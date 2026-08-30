package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MetalPresentationTelemetry;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** CI-only proof that Minecraft resource reload returns to a healthy Metal renderer. */
@SuppressWarnings("UnstableApiUsage")
public final class MetalReloadEvidenceGameTest implements FabricClientGameTest {
    private static final int RELOAD_TIMEOUT_TICKS = 600;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(20);

            CompletableFuture<?> reload = context.computeOnClient(client -> startResourceReload(client));
            int reloadTicks = waitForReload(context, reload);

            // Start the observation window only after the resource reload future is
            // complete. That proves the renderer can submit fresh presentation work
            // after reload rather than counting frames from the reload itself.
            context.computeOnClient(client -> {
                MetalDevice device = requireDevice();
                device.waitForSubmittedGpuWork();
                MetalPresentationTelemetry.reset();
                return Boolean.TRUE;
            });

            singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(30);

            ReloadSnapshot snapshot = context.computeOnClient(client -> {
                MetalDevice device = requireDevice();
                device.waitForSubmittedGpuWork();
                String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
                return new ReloadSnapshot(
                        backend,
                        client.level != null,
                        MetalPresentationTelemetry.snapshot()
                );
            });

            require("Metal".equalsIgnoreCase(snapshot.backend()),
                    "resource reload returned with a non-Metal backend: " + snapshot.backend());
            require(snapshot.worldLoaded(), "resource reload returned without a loaded client level");
            require(snapshot.presentation().completeAndSuccessful(),
                    "Metal presentation did not recover after resource reload: " + snapshot.presentation());

            writeEvidence(evidenceDir.resolve("reload-evidence.json"), reloadTicks, snapshot);
        }
    }

    private static CompletableFuture<?> startResourceReload(Object client) {
        try {
            Method method = client.getClass().getMethod("reloadResourcePacks");
            Object result = method.invoke(client);
            if (!(result instanceof CompletableFuture<?> future)) {
                throw new IllegalStateException(
                        "Minecraft.reloadResourcePacks() returned "
                                + (result == null ? "null" : result.getClass().getName())
                                + " instead of CompletableFuture"
                );
            }
            return future;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "Minecraft 26.2 no longer exposes reloadResourcePacks(); update the P0 reload oracle",
                    exception
            );
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not start Minecraft resource reload", exception);
        }
    }

    private static int waitForReload(ClientGameTestContext context, CompletableFuture<?> reload) {
        for (int tick = 0; tick < RELOAD_TIMEOUT_TICKS; tick++) {
            if (reload.isDone()) {
                joinReload(reload);
                return tick;
            }
            context.waitTicks(1);
        }
        throw new IllegalStateException(
                "Minecraft resource reload did not complete within " + RELOAD_TIMEOUT_TICKS + " ticks"
        );
    }

    private static void joinReload(CompletableFuture<?> reload) {
        try {
            reload.join();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Minecraft resource reload completed exceptionally", exception);
        }
    }

    private static MetalDevice requireDevice() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            throw new IllegalStateException("MetalDevice.current() is null after Minecraft resource reload");
        }
        return device;
    }

    private static void writeEvidence(Path path, int reloadTicks, ReloadSnapshot snapshot) {
        MetalPresentationTelemetry.Snapshot presentation = snapshot.presentation();
        String json = """
                {
                  "schema": 1,
                  "reloadMethod": "Minecraft.reloadResourcePacks",
                  "reloadCompleted": true,
                  "reloadTicks": %d,
                  "backendAfterReload": "%s",
                  "worldLoadedAfterReload": %s,
                  "postReloadPresentEncodeCalls": %d,
                  "postReloadPresentCommandBuffersSubmitted": %d,
                  "postReloadPresentCommandBuffersCompleted": %d,
                  "postReloadPresentCommandBuffersFailed": %d,
                  "postReloadPresentationHealthy": %s
                }
                """.formatted(
                reloadTicks,
                escape(snapshot.backend()),
                snapshot.worldLoaded(),
                presentation.encodeCalls(),
                presentation.submitted(),
                presentation.completed(),
                presentation.failed(),
                presentation.completeAndSuccessful()
        );
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write reload evidence " + path, exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record ReloadSnapshot(
            String backend,
            boolean worldLoaded,
            MetalPresentationTelemetry.Snapshot presentation
    ) {
    }
}
