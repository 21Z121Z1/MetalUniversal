package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.capture.AttachmentProbe;
import com.metallum.client.validation.capture.FileValidationCaptureService;
import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.CapturePoint;
import com.metallum.client.validation.contract.CapturePointKind;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.storage.ValidationStorageBudget;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderExpectationTest {
    private static final ResourceIdentity RGBA8 = new ResourceIdentity(
            "color0", 1L, 1L, "metal-texture-1", "RGBA8_UNORM", 2, 1, 1, 0, 1, 3
    );

    @Test
    void exactExpectationSupportsMaskedBytes() {
        CapturedResource actual = resource("color0", RGBA8, "RGBA8_UNORM", 4, new byte[]{1, 2, 3, 7, 4, 5, 6, 8});
        ExactExpectation expectation = new ExactExpectation(
                new byte[]{1, 2, 3, 0, 4, 5, 6, 0},
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0}
        );
        assertTrue(expectation.evaluate(actual, context(0)).passed());
    }

    @Test
    void numericExpectationDecodesFp16AndBounds() {
        byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0x3c00)
                .putShort((short) 0xc000)
                .putShort((short) 0x3800)
                .putShort((short) 0x0000)
                .array();
        ResourceIdentity motionIdentity = new ResourceIdentity(
                "motion", 2L, 1L, "metal-texture-2", "RG16_FLOAT", 2, 1, 1, 0, 1, 3
        );
        CapturedResource actual = resource("motion", motionIdentity, "RG16_FLOAT", 4, bytes);
        NumericExpectation expectation = new NumericExpectation(
                new double[]{1.0, -2.0, 0.5, 0.0}, 1.0e-4, 1.0e-4
        );
        ExpectationResult result = expectation.evaluate(actual, context(0));
        assertTrue(result.passed(), result.message() + " " + result.metrics());
    }

    @Test
    void invariantRejectsNonFiniteFloatAttachment() {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x7fc00000).array();
        ResourceIdentity depthIdentity = new ResourceIdentity(
                "depth", 3L, 1L, "metal-texture-3", "R32_FLOAT", 1, 1, 1, 0, 1, 3
        );
        CapturedResource actual = resource("depth", depthIdentity, "R32_FLOAT", 4, bytes);
        InvariantExpectation finite = new InvariantExpectation(
                "finite", (value, ignored) -> {
                    ByteBuffer data = ByteBuffer.wrap(value.bytes()).order(ByteOrder.LITTLE_ENDIAN);
                    return Float.isFinite(data.getFloat());
                }
        );
        assertFalse(finite.evaluate(actual, context(0)).passed());
    }

    @Test
    void imageExpectationUsesPerChannelTolerance() {
        CapturedResource actual = resource("color0", RGBA8, "RGBA8_UNORM", 4,
                new byte[]{10, 20, 30, (byte) 255, 40, 50, 60, (byte) 255});
        ImageExpectation expectation = new ImageExpectation(
                new byte[]{11, 20, 30, (byte) 255, 40, 52, 60, (byte) 255}, 2
        );
        assertTrue(expectation.evaluate(actual, context(0)).passed());
    }

    @Test
    void imageExpectationNormalizesBgraAndBottomLeftOrigin() {
        ResourceIdentity bgraIdentity = new ResourceIdentity(
                "color0", 5L, 1L, "metal-texture-5", "BGRA8_UNORM", 1, 2, 1, 0, 1, 3
        );
        // Raw actual rows are bottom-left: bottom pixel is blue, top pixel is red.
        CapturedResource actual = resource("color0", bgraIdentity, "BGRA8_UNORM", 4,
                new byte[]{(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255});
        ImageExpectation expectation = new ImageExpectation(
                new byte[]{(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255},
                4,
                0,
                false,
                new ImageNormalization(
                        ImageNormalization.ChannelOrder.BGRA,
                        ImageNormalization.Orientation.BOTTOM_LEFT,
                        ImageNormalization.ColorSpace.LINEAR
                ),
                new ImageNormalization(
                        ImageNormalization.ChannelOrder.RGBA,
                        ImageNormalization.Orientation.TOP_LEFT,
                        ImageNormalization.ColorSpace.LINEAR
                )
        );
        ExpectationResult result = expectation.evaluate(actual, context(0));
        assertTrue(result.passed(), result.message() + " " + result.metrics());
        assertEquals("BGRA", result.metrics().get("actualChannelOrder"));
        assertEquals("BOTTOM_LEFT", result.metrics().get("actualOrientation"));
    }

    @Test
    void imageExpectationDeclaresAndConvertsSrgbToLinear() {
        ResourceIdentity srgbIdentity = new ResourceIdentity(
                "color0", 6L, 1L, "metal-texture-6", "RGBA8_SRGB", 1, 1, 1, 0, 1, 3
        );
        CapturedResource actual = resource("color0", srgbIdentity, "RGBA8_SRGB", 4,
                new byte[]{(byte) 128, (byte) 128, (byte) 128, (byte) 255});
        ImageExpectation expectation = new ImageExpectation(
                new byte[]{55, 55, 55, (byte) 255},
                4,
                1,
                false,
                ImageNormalization.canonicalSrgb(4),
                ImageNormalization.canonicalLinear(4)
        );
        assertTrue(expectation.evaluate(actual, context(0)).passed());
    }

    @Test
    void temporalExpectationComparesASequenceAfterWarmup() {
        TemporalExpectation expectation = new TemporalExpectation(1, 0.0);
        CapturedResource first = resource("color0", RGBA8, "RGBA8_UNORM", 4, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        CapturedResource second = resource("color0", RGBA8, "RGBA8_UNORM", 4, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertTrue(expectation.evaluate(first, context(0)).passed());
        assertTrue(expectation.evaluate(second, context(1)).passed());
        assertEquals(0.0, expectation.evaluate(second, context(2)).metrics().get("meanAbsoluteByteDelta"));
    }

    @Test
    void fileCaptureWritesRawExpectedDiffAndStructuredResult() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-");
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-test")) {
            CapturedResource actual = resource("color0", RGBA8, "RGBA8_UNORM", 4,
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            CapturePoint point = new CapturePoint(4L, "synthetic/mrt", CapturePointKind.AFTER_PASS, -1);
            service.requestCapture(
                    point,
                    List.of(AttachmentProbe.of("color0", RGBA8, AttachmentSemantic.COLOR,
                            CaptureFormat.fromFormat("RGBA8_UNORM", 4))),
                    List.of(ExpectationSpec.forResource(
                            "color0-exact", "color0", new ExactExpectation(actual.bytes())
                    ))
            );
            service.completeCapture(
                    point,
                    List.of(actual),
                    List.of(ExpectationSpec.forResource(
                            "color0-exact", "color0", new ExactExpectation(actual.bytes())
                    ))
            );
            assertEquals(1, service.completedCaptures());
            assertEquals(0, service.failedCaptures());
        }
        assertTrue(Files.find(output, 6, (path, attributes) -> path.getFileName().toString().equals("actual.bin")).findAny().isPresent());
        String results = Files.readString(output.resolve("results.json"));
        assertTrue(results.contains("\"schemaVersion\": 1"));
        assertTrue(results.contains("color0-exact"));
    }

    @Test
    void capturePayloadDefaultFollowsTheSharedStorageBudget() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-shared-budget-");
        long sharedLimit = 64L * 1024L;
        ValidationStorageBudget budget = ValidationStorageBudget.shared(output, sharedLimit);
        try (FileValidationCaptureService ignored = new FileValidationCaptureService(
                output, "capture-shared-budget", null, budget)) {
            // Construction writes results.json, whose manifest records the
            // effective payload limit used by the capture scheduler.
        }
        String results = Files.readString(output.resolve("results.json"));
        assertTrue(results.contains("\"maxCaptureBytes\": " + sharedLimit));
    }

    @Test
    void fileCaptureKeepsFloatEvidenceRawWithoutInventingAVisualPng() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-float-");
        ResourceIdentity motionIdentity = new ResourceIdentity(
                "motion", 4L, 1L, "metal-texture-4", "RG16_FLOAT", 2, 1, 1, 0, 1, 3
        );
        CapturedResource motion = resource(
                "motion", motionIdentity, "RG16_FLOAT", 4, new byte[]{0, 60, 0, 0, 0, 60, 0, 0}
        );
        CapturePoint point = new CapturePoint(7L, "metallum/motion", CapturePointKind.AFTER_PASS, -1);
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-float")) {
            service.requestCapture(
                    point,
                    List.of(AttachmentProbe.of("motion", motionIdentity, AttachmentSemantic.MOTION,
                            CaptureFormat.fromFormat("RG16_FLOAT", 4))),
                    List.of()
            );
            service.completeCapture(point, List.of(motion), List.of());
        }
        assertTrue(Files.find(output, 8, (path, attributes) -> path.getFileName().toString().equals("actual.bin"))
                .findAny().isPresent());
        assertFalse(Files.find(output, 8, (path, attributes) -> path.getFileName().toString().equals("actual.png"))
                .findAny().isPresent());
    }

    @Test
    void fileCaptureFailsWhenRequestedAttachmentIsMissing() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-missing-");
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-missing")) {
            CapturePoint point = new CapturePoint(5L, "synthetic/mrt", CapturePointKind.AFTER_PASS, -1);
            service.requestCapture(
                    point,
                    List.of(AttachmentProbe.of("color0", RGBA8, AttachmentSemantic.COLOR,
                            CaptureFormat.fromFormat("RGBA8_UNORM", 4))),
                    List.of()
            );
            service.completeCapture(point, List.of(), List.of());

            assertEquals(1, service.completedCaptures());
            assertEquals(1, service.failedCaptures());
            assertEquals("failed", service.status());
        }
        assertTrue(Files.readString(output.resolve("results.json")).contains("missing requested attachment"));
    }

    @Test
    void fileCaptureFailsWhenReadbackUsesAReallocatedResource() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-generation-");
        ResourceIdentity reallocated = new ResourceIdentity(
                "color0", 1L, 2L, "metal-texture-new", "RGBA8_UNORM", 2, 1, 1, 0, 1, 3
        );
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-generation")) {
            CapturedResource actual = resource("color0", reallocated, "RGBA8_UNORM", 4,
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            CapturePoint point = new CapturePoint(6L, "synthetic/mrt", CapturePointKind.AFTER_PASS, -1);
            service.requestCapture(
                    point,
                    List.of(AttachmentProbe.of("color0", RGBA8, AttachmentSemantic.COLOR,
                            CaptureFormat.fromFormat("RGBA8_UNORM", 4))),
                    List.of()
            );
            service.completeCapture(point, List.of(actual), List.of());

            assertEquals(1, service.failedCaptures());
        }
    }

    @Test
    void fileCaptureRejectsAnEmptyProbeListInsteadOfCreatingAFalseRequest() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-empty-probes-");
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-empty-probes")) {
            service.requestCapture(
                    new CapturePoint(8L, "synthetic/empty", CapturePointKind.AFTER_PASS, -1),
                    List.of(),
                    List.of()
            );

            assertEquals(0, service.requestedCaptures());
            assertEquals(0, service.pendingCaptures());
            assertEquals(1, service.failedCaptures());
            assertEquals("failed", service.status());
        }
    }

    @Test
    void fileCaptureRejectsDuplicateCompletionAndKeepsTheFirstResult() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-duplicate-");
        CapturedResource actual = resource("color0", RGBA8, "RGBA8_UNORM", 4,
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        CapturePoint point = new CapturePoint(9L, "synthetic/duplicate", CapturePointKind.AFTER_PASS, -1);
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-duplicate")) {
            service.requestCapture(
                    point,
                    List.of(AttachmentProbe.of("color0", RGBA8, AttachmentSemantic.COLOR,
                            CaptureFormat.fromFormat("RGBA8_UNORM", 4))),
                    List.of()
            );
            service.completeCapture(point, List.of(actual), List.of());
            service.completeCapture(point, List.of(actual), List.of());

            assertEquals(1, service.completedCaptures());
            assertEquals(1, service.failedCaptures());
            assertEquals(0, service.pendingCaptures());
            assertEquals("failed", service.status());
        }
    }

    @Test
    void closingWithPendingCaptureProducesAFailureAndNoPendingState() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-pending-");
        try (FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-pending")) {
            service.requestCapture(
                    new CapturePoint(10L, "synthetic/pending", CapturePointKind.AFTER_PASS, -1),
                    List.of(AttachmentProbe.of("color0", RGBA8, AttachmentSemantic.COLOR,
                            CaptureFormat.fromFormat("RGBA8_UNORM", 4))),
                    List.of()
            );
            assertEquals(1, service.pendingCaptures());
        }

        String results = Files.readString(output.resolve("results.json"));
        assertTrue(results.contains("capture service closed with pending requests"));
        assertTrue(results.contains("\"pendingCaptures\": 0"));
        assertTrue(results.contains("\"failedCaptures\": 1"));
    }

    @Test
    void duplicateProbeNamesFailInsteadOfSilentlyReplacingTheFirstProbe() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-duplicate-probes-");
        try (FileValidationCaptureService service = new FileValidationCaptureService(
                output, "capture-duplicate-probes"
        )) {
            AttachmentProbe probe = AttachmentProbe.of(
                    "color0", RGBA8, AttachmentSemantic.COLOR,
                    CaptureFormat.fromFormat("RGBA8_UNORM", 4)
            );
            service.requestCapture(
                    new CapturePoint(11L, "synthetic/duplicate-probes", CapturePointKind.AFTER_PASS, -1),
                    List.of(probe, probe),
                    List.of()
            );
            assertEquals(0, service.requestedCaptures());
            assertEquals(1, service.failedCaptures());
            assertEquals("failed", service.status());
        }
    }

    @Test
    void completionAfterCloseIsRecordedAsALifecycleFailure() throws Exception {
        var output = Files.createTempDirectory("render-contract-capture-late-");
        CapturedResource actual = resource(
                "color0", RGBA8, "RGBA8_UNORM", 4,
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8}
        );
        CapturePoint point = new CapturePoint(12L, "synthetic/late", CapturePointKind.AFTER_PASS, -1);
        FileValidationCaptureService service = new FileValidationCaptureService(output, "capture-late");
        service.close();
        service.completeCapture(point, List.of(actual), List.of());

        assertEquals(1, service.lateCompletions());
        assertEquals(1, service.failedCaptures());
        assertEquals("failed", service.status());
        assertTrue(Files.readString(output.resolve("results.json")).contains("\"lateCompletions\": 1"));
    }

    private static CapturedResource resource(
            final String name,
            final ResourceIdentity identity,
            final String formatName,
            final int bytesPerTexel,
            final byte[] bytes
    ) {
        return new CapturedResource(
                name,
                identity,
                CaptureFormat.fromFormat(formatName, bytesPerTexel),
                identity.width(),
                identity.height(),
                bytes
        );
    }

    private static ExpectationContext context(final long frame) {
        return new ExpectationContext(
                new CapturePoint(frame, "synthetic/test", CapturePointKind.AFTER_PASS, -1),
                null,
                Map.of(),
                Map.of()
        );
    }
}
