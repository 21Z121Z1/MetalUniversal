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
import java.util.Locale;

@SuppressWarnings("UnstableApiUsage")
public final class Metal4MainRendererEvidenceGameTest implements FabricClientGameTest {
    @Override
    public void runTest(final ClientGameTestContext context) {
        String lane = requestedLane();
        if (lane.equals("off")) {
            return;
        }
        if (!lane.equals("baseline") && !lane.equals("candidate")) {
            throw new IllegalStateException("Unsupported P1 Metal 4 lane: " + lane);
        }
        if (!Metal4MainRendererTelemetry.enabled()) {
            throw new IllegalStateException("P1 requires metallum.hotpath.telemetry=true");
        }
        boolean candidate = lane.equals("candidate");
        Identity identity = requestedIdentity();

        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();

        DeviceState before = context.computeOnClient(client -> {
            MetalDevice device = requireDevice();
            device.waitForSubmittedGpuWork();
            MetalPresentationTelemetry.reset();
            return deviceState(device);
        });
        require(before.metal4Supported(), "P1 lane requires a device with Metal 4 support");
        require(before.residencySetEnabled(), "P1 lane requires explicit residency on the production queue");
        require(before.mainRendererEnabled() == candidate,
                "P1 lane did not configure the requested main-renderer state: " + before);
        require(before.telemetry().engaged() == candidate,
                "native main-renderer engagement disagrees with requested P1 lane: " + before);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(60);

            Result result = context.computeOnClient(client -> {
                MetalDevice device = requireDevice();
                device.waitForSubmittedGpuWork();
                DeviceState after = deviceState(device);
                return evaluate(
                        lane,
                        candidate,
                        before.telemetry(),
                        after,
                        MetalPresentationTelemetry.snapshot()
                );
            });

            require(result.metal4Supported(), "Metal 4 capability disappeared during P1 window: " + result);
            require(result.residencySetEnabled(), "residency set was not active during P1 window: " + result);
            require(result.mainRendererEnabled() == candidate,
                    "main-renderer state changed during P1 window: " + result);
            require(result.mainRendererEngaged() == candidate,
                    "native main-renderer engagement changed during P1 window: " + result);
            require(result.presentFrames() > 0L, "no presented frames in P1 measurement window");
            require(result.outstandingSubmissions() == 0L,
                    "submitted Metal 4 command buffers remained unretired after GPU drain: " + result);
            require(result.presentationHealthy(),
                    "ordinary Metal presentation lifecycle failed in P1 window: " + result);

            if (candidate) {
                require(result.mainRendererEngagementFraction() == 1.0,
                        "Metal 4 main renderer did not remain engaged: " + result);
                require(result.commandBufferBegins() > 0L,
                        "no Metal 4 command buffers began in P1 candidate window");
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
            } else {
                require(result.mainRendererEngagementFraction() == 0.0,
                        "P1 baseline unexpectedly engaged the main renderer: " + result);
                require(result.nativeBegun() == 0L && result.nativeSubmitted() == 0L,
                        "P1 baseline executed native main-renderer work: " + result);
                require(result.commandBufferBegins() == 0L && result.commitCalls() == 0L,
                        "P1 baseline recorded Java main-renderer work: " + result);
                require(result.commandAllocatorResets() == 0L,
                        "P1 baseline recorded main-renderer allocator resets: " + result);
            }

            writeEvidence(evidenceDir.resolve("metal4-main-renderer-evidence.json"), identity, result);
        }
    }

    private static String requestedLane() {
        String fallback = Boolean.getBoolean("metallum.ci.requireMetal4MainRenderer") ? "candidate" : "off";
        return System.getProperty("metallum.ci.p1Metal4Lane", fallback).trim().toLowerCase(Locale.ROOT);
    }

    private static Identity requestedIdentity() {
        return new Identity(
                identityValue("metallum.ci.p1SourceSha", 40),
                identityValue("metallum.ci.p1ProductionJarSha256", 64),
                identityValue("metallum.ci.p1NativeDylibSha256", 64)
        );
    }

    private static String identityValue(final String property, final int hexLength) {
        String value = System.getProperty(property, "unrecorded").trim().toLowerCase(Locale.ROOT);
        if (value.equals("unrecorded")) {
            return value;
        }
        if (value.length() != hexLength || !value.matches("[0-9a-f]+")) {
            throw new IllegalStateException("Invalid P1 identity property " + property + ": " + value);
        }
        return value;
    }

    private static DeviceState deviceState(final MetalDevice device) {
        return new DeviceState(
                device.commandQueue.metal4Supported(),
                device.commandQueue.residencySetEnabled(),
                device.metal4MainRendererEnabled(),
                Metal4MainRendererTelemetry.snapshot()
        );
    }

    private static Result evaluate(
            final String lane,
            final boolean candidate,
            final Metal4MainRendererTelemetry.Snapshot before,
            final DeviceState after,
            final MetalPresentationTelemetry.Snapshot presentation
    ) {
        Metal4MainRendererTelemetry.Snapshot telemetry = after.telemetry();
        long nativeBegun = delta(telemetry.nativeBegun(), before.nativeBegun(), "native begun");
        long nativeSubmitted = delta(telemetry.nativeSubmitted(), before.nativeSubmitted(), "native submitted");
        long allocatorResets = delta(
                telemetry.commandAllocatorResets(), before.commandAllocatorResets(), "allocator resets"
        );
        long slotWaitNanos = delta(telemetry.slotWaitNanos(), before.slotWaitNanos(), "slot wait nanos");
        long slotWaitCount = delta(telemetry.slotWaitCount(), before.slotWaitCount(), "slot wait count");
        long commandBufferBegins = delta(
                telemetry.commandBufferBegins(), before.commandBufferBegins(), "command-buffer begins"
        );
        long commitCalls = delta(telemetry.commitCalls(), before.commitCalls(), "commit calls");
        long frames = presentation.encodeCalls();
        double commandBuffersPerFrame = frames == 0L ? Double.NaN : (double) commandBufferBegins / frames;
        double commitCallsPerFrame = frames == 0L ? Double.NaN : (double) commitCalls / frames;
        return new Result(
                lane,
                after.metal4Supported(),
                after.residencySetEnabled(),
                after.mainRendererEnabled(),
                telemetry.engaged(),
                candidate ? 1.0 : 0.0,
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
                telemetry.outstandingSubmissions(),
                telemetry.argumentTableAllocationsDuringEncoding(),
                telemetry.computeTableOverflow(),
                telemetry.renderTableHighWater(),
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

    private static void writeEvidence(final Path path, final Identity identity, final Result result) {
        String json = """
                {
                  "schema": 3,
                  "status": "pass",
                  "lane": "%s",
                  "identity": {
                    "sourceSha": "%s",
                    "productionJarSha256": "%s",
                    "nativeDylibSha256": "%s"
                  },
                  "metal4Supported": %s,
                  "residencySetEnabled": %s,
                  "mainRendererEnabled": %s,
                  "mainRendererEngaged": %s,
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
                result.lane(),
                identity.sourceSha(),
                identity.productionJarSha256(),
                identity.nativeDylibSha256(),
                result.metal4Supported(),
                result.residencySetEnabled(),
                result.mainRendererEnabled(),
                result.mainRendererEngaged(),
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

    private record Identity(String sourceSha, String productionJarSha256, String nativeDylibSha256) {
    }

    private record DeviceState(
            boolean metal4Supported,
            boolean residencySetEnabled,
            boolean mainRendererEnabled,
            Metal4MainRendererTelemetry.Snapshot telemetry
    ) {
    }

    private record Result(
            String lane,
            boolean metal4Supported,
            boolean residencySetEnabled,
            boolean mainRendererEnabled,
            boolean mainRendererEngaged,
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
