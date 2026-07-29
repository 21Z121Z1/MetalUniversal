package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ReactiveMaskPipelineTest {
    @Test
    void missingReceiptFailsClosed() {
        ReactiveMaskPipeline pipeline = new ReactiveMaskPipeline();
        FrameStamp stamp = new FrameStamp(1L, 1L);
        pipeline.beginFrame(stamp);
        pipeline.recordCameraDepth();

        assertThrows(IllegalStateException.class, () -> pipeline.finish(stamp));
    }

    @Test
    void absentReactiveWriterFailsClosedInsteadOfMasqueradingAsEmptyContent() {
        ReactiveMaskPipeline pipeline = new ReactiveMaskPipeline();
        FrameStamp stamp = new FrameStamp(2L, 1L);
        pipeline.beginFrame(stamp);
        pipeline.recordCameraDepth();
        pipeline.recordUnsupported(ProducerDomain.DYNAMIC_CONTENT, 1);
        pipeline.recordFirstPerson(false);
        pipeline.recordTransparency(false);
        pipeline.recordParticlesWeather(false);
        pipeline.recordModdedRenderers(false);

        List<ProducerReceipt> receipts = pipeline.finish(stamp);
        assertReceipt(receipts, ProducerDomain.TRANSPARENCY, ProducerCoverage.UNSUPPORTED, 0);
        assertReceipt(receipts, ProducerDomain.PARTICLES_WEATHER, ProducerCoverage.UNSUPPORTED, 0);
        assertReceipt(receipts, ProducerDomain.MODDED_RENDERERS, ProducerCoverage.REAL_MOTION, 0);
    }

    @Test
    void aDynamicWriterFailureRemainsUnsupportedAfterAnotherDrawEncodes() {
        MetalEntityMotionCapture.beginFrame();
        MetalEntityMotionCapture.recordMotionDrawSkip("attachments-unavailable");
        MetalEntityMotionCapture.recordMotionDrawEncoded(null, null);

        ReactiveMaskPipeline pipeline = new ReactiveMaskPipeline();
        FrameStamp stamp = new FrameStamp(4L, 1L);
        pipeline.beginFrame(stamp);
        pipeline.recordCameraDepth();
        pipeline.recordDynamicDiagnostics(MetalEntityMotionCapture.diagnostics());
        pipeline.recordFirstPerson(false);
        pipeline.recordTransparency(true);
        pipeline.recordParticlesWeather(true);
        pipeline.recordModdedRenderers(false);

        assertReceipt(
                pipeline.finish(stamp),
                ProducerDomain.DYNAMIC_CONTENT,
                ProducerCoverage.UNSUPPORTED,
                1
        );
    }

    @Test
    void confirmedUnsupportedProviderWinsWhenReceiptsMerge() {
        ReactiveMaskPipeline pipeline = new ReactiveMaskPipeline();
        FrameStamp stamp = new FrameStamp(3L, 1L);
        pipeline.beginFrame(stamp);
        pipeline.recordCameraDepth();
        pipeline.recordUnsupported(ProducerDomain.DYNAMIC_CONTENT, 0);
        pipeline.recordFirstPerson(false);
        pipeline.recordTransparency(true);
        pipeline.recordParticlesWeather(true);
        pipeline.recordModdedRenderers(false);
        pipeline.recordModdedRenderers(true);

        assertReceipt(
                pipeline.finish(stamp),
                ProducerDomain.MODDED_RENDERERS,
                ProducerCoverage.UNSUPPORTED,
                1
        );
    }

    private static void assertReceipt(
            final List<ProducerReceipt> receipts,
            final ProducerDomain domain,
            final ProducerCoverage coverage,
            final int samples
    ) {
        ProducerReceipt receipt = receipts.stream()
                .filter(candidate -> candidate.domain() == domain)
                .findFirst()
                .orElseThrow();
        assertEquals(coverage, receipt.coverage());
        assertEquals(samples, receipt.samples());
    }
}
