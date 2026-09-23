package com.metallum.client.validation.fixture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.capture.FileValidationCaptureService;
import com.metallum.client.validation.contract.AttachmentBindingRecord;
import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.CapturePoint;
import com.metallum.client.validation.contract.CapturePointKind;
import com.metallum.client.validation.contract.PassType;
import com.metallum.client.validation.contract.ProducerType;
import com.metallum.client.validation.contract.RenderPassRecord;
import com.metallum.client.validation.contract.RenderTraceRecorder;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.contract.ScissorRecord;
import com.metallum.client.validation.contract.ViewportRecord;
import com.metallum.client.validation.expectation.ExactExpectation;
import com.metallum.client.validation.expectation.ExpectationSpec;
import com.metallum.client.validation.expectation.InvariantExpectation;
import com.metallum.client.validation.expectation.NumericExpectation;
import com.metallum.client.validation.expectation.TemporalExpectation;
import com.metallum.client.validation.report.DivergenceReport;
import com.metallum.client.validation.report.PassManifestComparator;
import com.metallum.client.validation.storage.ValidationStorageBudget;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic contract runner used by Gradle and CI. It validates the
 * expectation/report plumbing without claiming that CPU-produced bytes are a
 * Metal GPU result; real GPU coverage is supplied by the native integration
 * tasks that depend on this runner.
 */
public final class RenderContractSyntheticValidation {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RenderContractSyntheticValidation() {
    }

    public static void main(final String[] args) throws Exception {
        Path output = args.length == 0
                ? defaultOutputDirectory()
                : Path.of(args[0]);
        RenderContractCaseRegistry.CaseDefinition selectedCase = null;
        if (args.length > 1) {
            selectedCase = RenderContractCaseRegistry.load(Path.of("validation/render-contract/cases.json"))
                    .requireCase(args[1]);
        }
        Files.createDirectories(output);
        ValidationStorageBudget storage = ValidationStorageBudget.shared(output);
        List<String> backendModes = selectedCase == null
                ? List.of("metal3", "metal4")
                : selectedCase.backendModes();
        if (selectedCase != null) {
            List<String> unsupportedModes = backendModes.stream()
                    .filter(mode -> !"metal3".equals(mode) && !"metal4".equals(mode))
                    .distinct()
                    .toList();
            if (!unsupportedModes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Synthetic case " + selectedCase.name()
                                + " declares unsupported backend modes " + unsupportedModes
                                + "; they cannot be silently skipped"
                );
            }
        }
        List<RenderPassRecord> metal3 = backendModes.contains("metal3")
                ? runBackend(output.resolve("metal3"), "metal3", selectedCase, storage)
                : List.of();
        List<RenderPassRecord> metal4 = backendModes.contains("metal4")
                ? runBackend(output.resolve("metal4"), "metal4", selectedCase, storage)
                : List.of();
        if (metal3.isEmpty() && metal4.isEmpty()) {
            throw new IllegalArgumentException(
                    "Synthetic render-contract case selected no supported backend mode: "
                            + (selectedCase == null ? "all" : selectedCase.name())
            );
        }
        DivergenceReport comparison = PassManifestComparator.compare(metal3, metal4);
        if (!comparison.matched()) {
            throw new IllegalStateException("Metal 3/Metal 4 synthetic contract manifests diverged: " + comparison);
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", 1);
        summary.addProperty("runId", "synthetic-current");
        summary.addProperty(
                "gitCommit",
                System.getProperty("metallum.validation.sourceCommit", "unknown")
        );
        summary.addProperty("status", "passed");
        summary.addProperty("execution", "java-contract-model");
        summary.addProperty("case", selectedCase == null ? "all" : selectedCase.name());
        summary.addProperty("scenario", selectedCase == null ? "all" : selectedCase.scenario());
        summary.addProperty("gpuExecutionRequired", false);
        summary.addProperty("gpuExecutionPerformed", false);
        summary.addProperty("nativeGpuIntegrationTask", "renderContractNativeTest");
        summary.addProperty("nativeGpuIntegrationIsContractCapture", false);
        summary.addProperty("metal3Passes", metal3.size());
        summary.addProperty("metal4Passes", metal4.size());
        summary.add("comparison", GSON.toJsonTree(comparison));
        storage.writeString(
                output.resolve("synthetic-validation.json"), GSON.toJson(summary) + "\n"
        );
    }

    private static List<RenderPassRecord> runBackend(
            final Path output,
            final String backend,
            final RenderContractCaseRegistry.CaseDefinition selectedCase,
            final ValidationStorageBudget storage
    ) throws Exception {
        Files.createDirectories(output);
        RenderTraceRecorder recorder = new RenderTraceRecorder(
                output,
                "synthetic-" + backend,
                System.getProperty("metallum.validation.sourceCommit", "unknown"),
                32,
                128,
                512,
                storage
        );
        FileValidationCaptureService capture = new FileValidationCaptureService(
                output, "synthetic-" + backend, recorder, storage
        );
        try {
            int frame = 0;
            if (selectedCase == null) {
                frame = mrtBasic(recorder, capture, frame);
                frame = depthAndOcclusion(recorder, capture, frame);
                frame = blend(recorder, capture, frame);
                frame = viewportAndScissor(recorder, capture, frame);
                frame = computeToRender(recorder, capture, frame);
                frame = temporalPrefix(recorder, capture, frame);
                finalComposition(recorder, capture, frame);
            } else {
                switch (selectedCase.scenario()) {
                    case "synthetic_mrt_basic" -> mrtBasic(recorder, capture, frame);
                    case "synthetic_depth_occlusion" -> depthAndOcclusion(recorder, capture, frame);
                    case "synthetic_temporal_prefix" -> temporalPrefix(recorder, capture, frame);
                    case "metal_validation_timeline" -> throw new IllegalArgumentException(
                            "Case " + selectedCase.name()
                                    + " requires the Minecraft validation task and cannot run in the synthetic runner"
                    );
                    default -> throw new IllegalArgumentException(
                            "Unsupported synthetic render-contract scenario: " + selectedCase.scenario()
                    );
                }
            }
            if (capture.failedCaptures() != 0 || capture.pendingCaptures() != 0) {
                throw new IllegalStateException("Synthetic " + backend + " capture failed: "
                        + capture.failedCaptures() + " failures, " + capture.pendingCaptures() + " pending");
            }
            if (storage.exceeded() || "failed".equals(recorder.status())) {
                throw new IllegalStateException(
                        "Synthetic " + backend + " storage budget failed: " + storage.snapshot()
                );
            }
            return recorder.completedPasses();
        } finally {
            capture.close();
            recorder.close();
        }
    }

    private static Path defaultOutputDirectory() throws Exception {
        if (Boolean.getBoolean("metallum.renderContract.persist")) {
            return Path.of("build/render-contract/synthetic-current").toAbsolutePath().normalize();
        }
        return Files.createTempDirectory("metallum-render-contract-synthetic-")
                .toAbsolutePath().normalize();
    }

    private static int mrtBasic(
            final RenderTraceRecorder recorder,
            final FileValidationCaptureService capture,
            final int frame
    ) {
        recorder.beginFrame(frame);
        ResourceIdentity color0 = identity(recorder, "color0", frame, "RGBA8_UNORM", 2, 1, 4);
        ResourceIdentity color1 = identity(recorder, "color1", frame, "RGBA8_UNORM", 2, 1, 4);
        long pass = recorder.beginPass(
                "synthetic/mrt-basic", PassType.RENDER,
                List.of(binding(0, color0, AttachmentSemantic.COLOR, "clear"), binding(1, color1, AttachmentSemantic.COLOR, "clear")),
                null, null, new ViewportRecord(0, 0, 2, 1), ScissorRecord.disabled(),
                "sha256:synthetic-mrt", List.of("sha256:vertex", "sha256:fragment"), Map.of("backend-neutral", "true")
        );
        recorder.recordProducer(pass, ProducerType.CLEAR, "sha256:synthetic-mrt", Map.of(), Map.of(), List.of("color0", "color1"));
        recorder.recordProducer(pass, ProducerType.DRAW, "sha256:synthetic-mrt", Map.of("vertexCount", "3"), Map.of(), List.of("color0", "color1"));
        recorder.endPass(pass);
        byte[] first = new byte[]{16, 32, 48, (byte) 255, 16, 32, 48, (byte) 255};
        byte[] second = new byte[]{64, 80, 96, (byte) 255, 64, 80, 96, (byte) 255};
        completeBatch(
                capture,
                frame,
                "synthetic/mrt-basic",
                List.of(
                        captured(color0, "RGBA8_UNORM", first),
                        captured(color1, "RGBA8_UNORM", second)
                ),
                List.of(
                        ExpectationSpec.forResource("color0-exact", "color0", new ExactExpectation(first)),
                        ExpectationSpec.forResource("color1-exact", "color1", new ExactExpectation(second))
                )
        );
        recorder.endFrame(frame);
        return frame + 1;
    }

    private static int depthAndOcclusion(final RenderTraceRecorder recorder, final FileValidationCaptureService capture, final int frame) {
        recorder.beginFrame(frame);
        ResourceIdentity depth = identity(recorder, "depth", frame, "DEPTH32_FLOAT", 2, 1, 4);
        long pass = recorder.beginPass("synthetic/depth-occlusion", PassType.RENDER, List.of(),
                binding(0, depth, AttachmentSemantic.DEPTH, "clear"), null,
                new ViewportRecord(0, 0, 2, 1), ScissorRecord.disabled(), "sha256:depth", List.of(),
                Map.of("depthConvention", "reversed-z", "clearValue", "0.0"));
        recorder.recordProducer(pass, ProducerType.CLEAR, "sha256:depth", Map.of("depth", "0.0"), Map.of(), List.of("depth"));
        recorder.recordProducer(pass, ProducerType.DRAW, "sha256:depth", Map.of("depthCompare", "greater"), Map.of(), List.of("depth"));
        recorder.endPass(pass);
        byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.75f).putFloat(0.25f).array();
        complete(capture, frame, "synthetic/depth-occlusion", depth, "DEPTH32_FLOAT", bytes,
                new NumericExpectation(0.0, 1.0, 0.0, 0.0));
        recorder.endFrame(frame);
        return frame + 1;
    }

    private static int blend(final RenderTraceRecorder recorder, final FileValidationCaptureService capture, final int frame) {
        recorder.beginFrame(frame);
        ResourceIdentity color = identity(recorder, "blend-color", frame, "RGBA8_UNORM", 1, 1, 4);
        long pass = recorder.beginPass("synthetic/blend", PassType.RENDER, List.of(binding(0, color, AttachmentSemantic.COLOR, "clear")),
                null, null, new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "sha256:blend", List.of(),
                Map.of("blend", "src-alpha/one-minus-src-alpha"));
        recorder.recordProducer(pass, ProducerType.DRAW, "sha256:blend", Map.of("blendEnabled", "true"), Map.of(), List.of("blend-color"));
        recorder.endPass(pass);
        byte[] bytes = new byte[]{96, 48, 16, (byte) 255};
        complete(capture, frame, "synthetic/blend", color, "RGBA8_UNORM", bytes, new ExactExpectation(bytes));
        recorder.endFrame(frame);
        return frame + 1;
    }

    private static int viewportAndScissor(final RenderTraceRecorder recorder, final FileValidationCaptureService capture, final int frame) {
        recorder.beginFrame(frame);
        ResourceIdentity coverage = identity(recorder, "coverage", frame, "R8_UNORM", 2, 2, 1);
        long pass = recorder.beginPass("synthetic/viewport-scissor", PassType.RENDER,
                List.of(binding(0, coverage, AttachmentSemantic.COVERAGE, "clear")), null, null,
                new ViewportRecord(1, 0, 1, 2), new ScissorRecord(true, 1, 0, 1, 2),
                "sha256/viewport", List.of(), Map.of());
        recorder.recordProducer(pass, ProducerType.DRAW, "sha256/viewport", Map.of("pixelCenters", "edge"), Map.of(), List.of("coverage"));
        recorder.endPass(pass);
        byte[] bytes = new byte[]{0, (byte) 255, 0, (byte) 255};
        complete(capture, frame, "synthetic/viewport-scissor", coverage, "R8_UNORM", bytes, new ExactExpectation(bytes));
        recorder.endFrame(frame);
        return frame + 1;
    }

    private static int computeToRender(final RenderTraceRecorder recorder, final FileValidationCaptureService capture, final int frame) {
        recorder.beginFrame(frame);
        ResourceIdentity storage = identity(recorder, "compute-storage", frame, "R32_UINT", 1, 1, 4);
        long compute = recorder.beginPass("synthetic/compute", PassType.COMPUTE, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "sha256:compute", List.of(), Map.of());
        recorder.recordProducer(compute, ProducerType.DISPATCH, "sha256:compute", Map.of("groups", "1,1,1"),
                Map.of("storage", storage.stableKey()), List.of("compute-storage"));
        recorder.endPass(compute);
        long render = recorder.beginPass("synthetic/compute-render", PassType.RENDER,
                List.of(binding(0, storage, AttachmentSemantic.STORAGE, "load")), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "sha256:compute-render", List.of(), Map.of());
        recorder.recordProducer(render, ProducerType.DRAW, "sha256:compute-render", Map.of("dependency", "compute-storage"),
                Map.of("storage", storage.stableKey()), List.of("compute-storage"));
        recorder.endPass(render);
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(42).array();
        complete(capture, frame, "synthetic/compute-render", storage, "R32_UINT", bytes,
                new InvariantExpectation("compute wrote nonzero", (resource, ignored) -> ByteBuffer.wrap(resource.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt() == 42));
        recorder.endFrame(frame);
        return frame + 1;
    }

    private static int temporalPrefix(final RenderTraceRecorder recorder, final FileValidationCaptureService capture, int frame) {
        TemporalExpectation temporal = new TemporalExpectation(1, 0.0);
        for (int index = 0; index < 3; index++) {
            recorder.beginFrame(frame);
            ResourceIdentity history = identity(recorder, "temporal-output", 100L, "RGBA8_UNORM", 1, 1, 4);
            long pass = recorder.beginPass("synthetic/temporal-prefix", PassType.TEMPORAL, List.of(binding(0, history, AttachmentSemantic.TEMPORAL, index == 0 ? "clear" : "load")),
                    null, null, new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "sha256/temporal", List.of(),
                    Map.of("historyReset", Boolean.toString(index == 0), "prefixIndex", Integer.toString(index)));
            recorder.recordProducer(pass, index == 0 ? ProducerType.CLEAR : ProducerType.DRAW,
                    "sha256/temporal", Map.of("historyIndex", Integer.toString(index)), Map.of(), List.of("temporal-output"));
            recorder.endPass(pass);
            byte[] bytes = new byte[]{8, 16, 24, (byte) 255};
            complete(capture, frame, "synthetic/temporal-prefix", history, "RGBA8_UNORM", bytes,
                    temporal);
            recorder.endFrame(frame);
            frame++;
        }
        return frame;
    }

    private static void finalComposition(final RenderTraceRecorder recorder, final FileValidationCaptureService capture, final int frame) {
        recorder.beginFrame(frame);
        ResourceIdentity drawable = identity(recorder, "final-drawable", frame, "RGBA8_UNORM", 1, 1, 4);
        long pass = recorder.beginPass("synthetic/final-composition", PassType.PRESENT,
                List.of(binding(0, drawable, AttachmentSemantic.COLOR, "load")), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "sha256/present", List.of(),
                Map.of("captureRepresents", "PRE_PRESENT_DRAWABLE_CONTENT", "orientation", "top-left"));
        recorder.recordProducer(pass, ProducerType.PRESENT, "sha256/present", Map.of("colorSpace", "sRGB"), Map.of(), List.of("final-drawable"));
        recorder.endPass(pass);
        byte[] bytes = new byte[]{12, 34, 56, (byte) 255};
        complete(capture, frame, "synthetic/final-composition", drawable, "RGBA8_UNORM", bytes,
                new ExactExpectation(bytes));
        recorder.endFrame(frame);
    }

    private static void complete(
            final FileValidationCaptureService capture,
            final int frame,
            final String pass,
            final ResourceIdentity identity,
            final String format,
            final byte[] bytes,
            final com.metallum.client.validation.expectation.Expectation expectation
    ) {
        completeBatch(
                capture,
                frame,
                pass,
                List.of(captured(identity, format, bytes)),
                List.of(ExpectationSpec.forResource(identity.semanticName() + "-contract", identity.semanticName(), expectation))
        );
    }

    private static void completeBatch(
            final FileValidationCaptureService capture,
            final int frame,
            final String pass,
            final List<CapturedResource> resources,
            final List<ExpectationSpec> expectations
    ) {
        CapturePoint point = new CapturePoint(frame, pass, CapturePointKind.AFTER_PASS, -1);
        // The synthetic runner exercises the same request-before-copy lifecycle
        // as the native path; CPU bytes stand in only for the completed readback.
        capture.requestCapture(
                point,
                resources.stream().map(resource -> com.metallum.client.validation.capture.AttachmentProbe.of(
                        resource.semanticName(),
                        resource.resource(),
                        semantic(resource.semanticName()),
                        resource.captureFormat()
                )).toList(),
                expectations
        );
        capture.completeCapture(
                point,
                resources,
                expectations
        );
    }

    private static AttachmentSemantic semantic(final String name) {
        return switch (name) {
            case "depth" -> AttachmentSemantic.DEPTH;
            case "coverage" -> AttachmentSemantic.COVERAGE;
            case "temporal-output" -> AttachmentSemantic.TEMPORAL;
            case "compute-storage" -> AttachmentSemantic.STORAGE;
            default -> AttachmentSemantic.COLOR;
        };
    }

    private static CapturedResource captured(
            final ResourceIdentity identity,
            final String format,
            final byte[] bytes
    ) {
        return new CapturedResource(identity.semanticName(), identity,
                CaptureFormat.fromFormat(format, bytes.length / (identity.width() * identity.height())),
                identity.width(), identity.height(), bytes);
    }

    private static ResourceIdentity identity(
            final RenderTraceRecorder recorder,
            final String name,
            final long runtimeId,
            final String format,
            final int width,
            final int height,
            final int bytesPerTexel
    ) {
        return recorder.identifyResource(name, Math.max(1L, runtimeId), "synthetic-" + name, format, width, height, 1, 0, 1, 3);
    }

    private static AttachmentBindingRecord binding(
            final int slot,
            final ResourceIdentity resource,
            final AttachmentSemantic semantic,
            final String load
    ) {
        return new AttachmentBindingRecord(slot, resource, semantic, load, "store", true);
    }
}
