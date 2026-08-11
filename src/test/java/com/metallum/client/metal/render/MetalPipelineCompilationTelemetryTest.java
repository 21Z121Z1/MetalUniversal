package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalPipelineCompilationTelemetryTest {
    @AfterEach
    void reset() {
        MetalPipelineCompilationTelemetry.reset();
    }

    @Test
    void recordsBoundedTypedAttemptsAndFailures() {
        MetalPipelineCompilationTelemetry.reset();
        MetalPipelineCompilationTelemetry.record("render:b", true, true, true);
        MetalPipelineCompilationTelemetry.record("compute:a", false, false, true);
        MetalPipelineCompilationTelemetry.record("ignored", true, false, false);

        MetalPipelineCompilationTelemetry.Snapshot snapshot =
                MetalPipelineCompilationTelemetry.snapshot();
        assertEquals(2L, snapshot.attempts());
        assertEquals(1L, snapshot.renderAttempts());
        assertEquals(1L, snapshot.computeAttempts());
        assertEquals(1L, snapshot.failures());
        assertEquals(java.util.List.of("compute:a", "render:b"), snapshot.identities());
    }

    @Test
    void resetDefinesAZeroRuntimeCompileWindow() {
        MetalPipelineCompilationTelemetry.record("warmup", true, true, true);
        MetalPipelineCompilationTelemetry.reset();
        MetalPipelineCompilationTelemetry.Snapshot snapshot =
                MetalPipelineCompilationTelemetry.snapshot();
        assertEquals(0L, snapshot.attempts());
        assertEquals(java.util.List.of(), snapshot.identities());
    }
}
