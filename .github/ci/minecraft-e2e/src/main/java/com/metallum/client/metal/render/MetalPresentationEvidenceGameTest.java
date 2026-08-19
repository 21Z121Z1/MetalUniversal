package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MetalPresentationTelemetry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** CI-only proof that ordinary Minecraft presentation reaches GPU completion. */
@SuppressWarnings("UnstableApiUsage")
public final class MetalPresentationEvidenceGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();

        // Drain work from earlier Client GameTests before resetting the process-wide
        // counters so every completion observed below belongs to this test window.
        context.computeOnClient(client -> {
            MetalDevice device = requireDevice();
            device.waitForSubmittedGpuWork();
            MetalPresentationTelemetry.reset();
            return Boolean.TRUE;
        });

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(30);

            MetalPresentationTelemetry.Snapshot snapshot = context.computeOnClient(client -> {
                MetalDevice device = requireDevice();
                // submit() any current frame and drain all in-flight slots; the normal
                // InFlight.complete() path then queries completedSuccessfully().
                device.waitForSubmittedGpuWork();
                return MetalPresentationTelemetry.snapshot();
            });

            require(snapshot.enabled(), "presentation telemetry was not enabled");
            require(snapshot.encodeCalls() > 0L,
                    "no ordinary Metal drawable presentation was encoded");
            require(snapshot.submitted() == snapshot.encodeCalls(),
                    "present encode/submit counts diverged: " + snapshot);
            require(snapshot.completed() == snapshot.submitted(),
                    "not every submitted present command buffer completed successfully: " + snapshot);
            require(snapshot.failed() == 0L,
                    "Metal reported failed present command buffers: " + snapshot);
            require(snapshot.completeAndSuccessful(),
                    "presentation lifecycle is incomplete: " + snapshot);

            writeEvidence(evidenceDir.resolve("presentation-evidence.json"), snapshot);
        }
    }

    private static MetalDevice requireDevice() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            throw new IllegalStateException("MetalDevice.current() is null inside production Minecraft");
        }
        return device;
    }

    private static void writeEvidence(Path path, MetalPresentationTelemetry.Snapshot snapshot) {
        String json = """
                {
                  "schema": 1,
                  "telemetryEnabled": %s,
                  "presentEncodeCalls": %d,
                  "presentCommandBuffersSubmitted": %d,
                  "presentCommandBuffersCompleted": %d,
                  "presentCommandBuffersFailed": %d,
                  "completeAndSuccessful": %s
                }
                """.formatted(
                snapshot.enabled(),
                snapshot.encodeCalls(),
                snapshot.submitted(),
                snapshot.completed(),
                snapshot.failed(),
                snapshot.completeAndSuccessful()
        );
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write presentation evidence " + path, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
