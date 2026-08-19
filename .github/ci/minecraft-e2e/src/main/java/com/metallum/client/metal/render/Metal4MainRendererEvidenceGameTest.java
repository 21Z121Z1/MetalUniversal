package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.Metal4MainRendererTelemetry;
import com.metallum.client.metal.render.mtl.MetalPresentationTelemetry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@SuppressWarnings("UnstableApiUsage")
public final class Metal4MainRendererEvidenceGameTest implements FabricClientGameTest {
    @Override
    public void runTest(final ClientGameTestContext context) {
        if (!Boolean.getBoolean("metallum.ci.requireMetal4MainRenderer")) {
            return;
        }
        if (!Metal4MainRendererTelemetry.enabled()) {
            throw new IllegalStateException("P1 requires metallum.hotpath.telemetry=true");
        }

        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();

        Metal4MainRendererTelemetry.Snapshot before = context.computeOnClient(client -> {
            MetalDevice device = requireDevice();
            device.waitForSubmittedGpuWork();
            MetalPresentationTelemetry.reset();
            return Metal4MainRendererTelemetry.snapshot();
        });
        require(before.engaged(), "Metal 4 main renderer did not engage before the P1 measurement window");

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(60);

            Result result = context.computeOnClient(client -> {
                MetalDevice device = requireDevice();
                device.waitForSubmittedGpuWork();
                return evaluate(
                        before,
                        Metal4MainRendererTelemetry.snapshot(),
                        MetalPresentationTelemetry.snapshot()
                );
            });

            require(result.mainRendererEngagementFraction() == 1.0,
                    "Metal 4 main renderer did not remain engaged: " + result);
            require(result.presentFrames() > 0L, "no presented frames in P1 measurement window");
            require(result.commandBufferBegins() > 0L, "no Metal 4 command buffers began in P1 window");
            require(result.commandBufferBegins() == result.nativeBegun(),
                    "Java/native command-buffer begin counters diverged: " + result);
            require(result.commitCalls() == result.nativeSubmitted(),
                    "Java/native command-buffer submit counters diverged: " + result);
            require(result.commandAllocatorResets() == result.commandBufferBegins(),
                    "allocator-reset derivation diverged from proven beginLease invariant: " + result);
            require(result.argumentTableAllocationsDuringEncoding() == 0L,
                    "argument-table allocation occurred during encoding: " + result);
            require(result.computeTableOverflow() == 0L,
                    "compute argument-table overflow occurred: " + result);
            require(result.renderTableHighWater() == 1L,
                    "render argument-table high-water changed: " + result);
            require(result.outstandingSubmissions() == 0L,
                    "submitted Metal 4 command buffers remained unretired after GPU drain: " + result);
            require(result.presentationHealthy(),
                    "ordinary Metal presentation lifecycle failed in P1 window: " + result);

            writeEvidence(evidenceDir.resolve("metal4-main-renderer-evidence.json"), result);
        }
    }

    private static Result evaluate(
            final Metal4MainRendererTelemetry.Snapshot before,
            final Metal4MainRendererTelemetry.Snapshot after,
            final MetalPresentationTelemetry.Snapshot presentation
    ) {
        require(after.engaged(), "Metal 4 main renderer disengaged during P1 window");
        long nativeBegun = delta(after.nativeBegun(), before.nativeBegun(), "native begun");
        long nativeSubmitted = delta(after.nativeSubmitted(), before.nativeSubmitted(), "native submitted");
        long allocatorResets = delta(
                after.commandAllocatorResets(), before.commandAllocatorResets(), "allocator resets"
        );
        long slotWaitNanos = delta(after.slotWaitNanos(), before.slotWaitNanos(), "slot wait nanos");
        long slotWaitCount = delta(after.slotWaitCount(), before.slotWaitCount(), "slot wait count");
        long commandBufferBegins = delta(
                after.commandBufferBegins(), before.commandBufferBegins(), "command-buffer begins"
        );
        long commitCalls = delta(after.commitCalls(), before.commitCalls(), "commit calls");
        long frames = presentation.encodeCalls();
        double commandBuffersPerFrame = frames == 0L ? Double.NaN : (double) commandBufferBegins / frames;
        double commitCallsPerFrame = frames == 0L ? Double.NaN : (double) commitCalls / frames;
        return new Result(
                1.0,
                frames,
                nativeBegun,
                nativeSubmitted,
                allocatorResets,
                slotWaitNanos,
                slotWaitCount,
                commandBufferBegins,
                commitCalls,
                commandBuffersPerFrame,
                commitCallsPerFrame,
                after.outstandingSubmissions(),
                after.argumentTableAllocationsDuringEncoding(),
                after.computeTableOverflow(),
                after.renderTableHighWater(),
                presentation.completeAndSuccessful()
        );
    }

    private static long delta(final long after, final long before, final String name) {
        if (after < before) {
            throw new IllegalStateException(
                    "Metal 4 counter regressed for " + name + ": " + before + " -> " + after
            );
        }
        return after - before;
    }

    private static MetalDevice requireDevice() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            throw new IllegalStateException("MetalDevice.current() is null inside production Minecraft");
        }
        return device;
    }

    private static void writeEvidence(final Path path, final Result result) {
        String json = """
                {
                  "schema": 1,
                  "status": "pass",
                  "mainRendererEngagementFraction": %.6f,
                  "presentFrames": %d,
                  "metrics": {
                    "metal4.commandAllocatorResets": %d,
                    "metal4.slotWaitNanos": %d,
                    "metal4.slotWaitCount": %d,
                    "metal4.commandBuffersPerFrame": %.9f,
                    "metal4.commitCallsPerFrame": %.9f,
                    "metal4.argumentTableAllocationsDuringEncoding": %d,
                    "metal4.computeTableOverflow": %d,
                    "metal4.renderTableHighWater": %d
                  },
                  "slotWaitSemantics": "conservative-upper-bound-under-three-unretired-submissions",
                  "rawWindow": {
                    "nativeBegun": %d,
                    "nativeSubmitted": %d,
                    "commandBufferBegins": %d,
                    "commitCalls": %d,
                    "outstandingSubmissionsAfterDrain": %d
                  },
                  "presentationHealthy": %s
                }
                """.formatted(
                result.mainRendererEngagementFraction(),
                result.presentFrames(),
                result.commandAllocatorResets(),
                result.slotWaitNanos(),
                result.slotWaitCount(),
                result.commandBuffersPerFrame(),
                result.commitCallsPerFrame(),
                result.argumentTableAllocationsDuringEncoding(),
                result.computeTableOverflow(),
                result.renderTableHighWater(),
                result.nativeBegun(),
                result.nativeSubmitted(),
                result.commandBufferBegins(),
                result.commitCalls(),
                result.outstandingSubmissions(),
                result.presentationHealthy()
        );
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write P1 Metal 4 evidence " + path, exception);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Result(
            double mainRendererEngagementFraction,
            long presentFrames,
            long nativeBegun,
            long nativeSubmitted,
            long commandAllocatorResets,
            long slotWaitNanos,
            long slotWaitCount,
            long commandBufferBegins,
            long commitCalls,
            double commandBuffersPerFrame,
            double commitCallsPerFrame,
            long outstandingSubmissions,
            long argumentTableAllocationsDuringEncoding,
            long computeTableOverflow,
            long renderTableHighWater,
            boolean presentationHealthy
    ) {
    }
}
