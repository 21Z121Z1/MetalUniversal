package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.REACTIVE_ONLY;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.REAL_MOTION;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.UNSUPPORTED;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.CAMERA_DEPTH;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.DYNAMIC_CONTENT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameSynthesisContractTest {
    @Test
    void frameStampRequiresPositiveIdentity() {
        assertDoesNotThrow(() -> new FrameSynthesisContract.FrameStamp(1L, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.FrameStamp(0L, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.FrameStamp(1L, 0L)
        );
    }

    @Test
    void producerCoverageRequiresExactlyOneReceiptPerDomain() {
        List<FrameSynthesisContract.ProducerReceipt> complete = completeCoverage(
                REAL_MOTION,
                REAL_MOTION
        );
        assertDoesNotThrow(() -> new FrameSynthesisContract.ProducerCoverageSet(complete));

        List<FrameSynthesisContract.ProducerReceipt> missing = new ArrayList<>(complete);
        missing.removeLast();
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.ProducerCoverageSet(missing)
        );

        List<FrameSynthesisContract.ProducerReceipt> duplicate = new ArrayList<>(complete);
        duplicate.add(complete.getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.ProducerCoverageSet(duplicate)
        );
    }

    @Test
    void temporalRejectsUnsupportedProducerDomain() {
        FrameSynthesisContract.ProducerCoverageSet coverage =
                new FrameSynthesisContract.ProducerCoverageSet(
                        completeCoverage(REAL_MOTION, UNSUPPORTED)
                );
        assertFalse(coverage.temporalEligible());
        assertFalse(coverage.frameGenerationEligible());
    }

    @Test
    void frameGenerationRequiresRealCameraAndDynamicMotion() {
        FrameSynthesisContract.ProducerCoverageSet accepted =
                new FrameSynthesisContract.ProducerCoverageSet(
                        completeCoverage(REAL_MOTION, REAL_MOTION)
                );
        assertTrue(accepted.temporalEligible());
        assertTrue(accepted.frameGenerationEligible());

        FrameSynthesisContract.ProducerCoverageSet cameraOnly =
                new FrameSynthesisContract.ProducerCoverageSet(
                        completeCoverage(REAL_MOTION, REACTIVE_ONLY)
                );
        assertTrue(cameraOnly.temporalEligible());
        assertFalse(cameraOnly.frameGenerationEligible());
    }

    @Test
    void realMotionReceiptRequiresSamples() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.ProducerReceipt(CAMERA_DEPTH, REAL_MOTION, 0)
        );
        assertDoesNotThrow(
                () -> new FrameSynthesisContract.ProducerReceipt(CAMERA_DEPTH, REACTIVE_ONLY, 0)
        );
    }

    @Test
    void cameraInputRejectsNonPhysicalValues() {
        assertDoesNotThrow(
                () -> new FrameSynthesisContract.CameraFrameInput(
                        70.0F,
                        0.05F,
                        1_000.0F,
                        16.0F / 9.0F,
                        1.0F / 60.0F
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.CameraFrameInput(
                        70.0F,
                        0.05F,
                        0.01F,
                        16.0F / 9.0F,
                        1.0F / 60.0F
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameSynthesisContract.CameraFrameInput(
                        70.0F,
                        0.05F,
                        1_000.0F,
                        16.0F / 9.0F,
                        0.0F
                )
        );
    }

    private static List<FrameSynthesisContract.ProducerReceipt> completeCoverage(
            final FrameSynthesisContract.ProducerCoverage cameraCoverage,
            final FrameSynthesisContract.ProducerCoverage dynamicCoverage
    ) {
        List<FrameSynthesisContract.ProducerReceipt> receipts = new ArrayList<>();
        for (FrameSynthesisContract.ProducerDomain domain
                : FrameSynthesisContract.ProducerDomain.values()) {
            FrameSynthesisContract.ProducerCoverage coverage = switch (domain) {
                case CAMERA_DEPTH -> cameraCoverage;
                case DYNAMIC_CONTENT -> dynamicCoverage;
                default -> REACTIVE_ONLY;
            };
            int samples = coverage == REAL_MOTION ? 1 : 0;
            receipts.add(new FrameSynthesisContract.ProducerReceipt(domain, coverage, samples));
        }
        return receipts;
    }
}
