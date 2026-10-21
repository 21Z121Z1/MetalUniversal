package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalStagedMotionExecutionTest {
    private static final RenderPipeline PARTICLE = RenderPipeline.builder().withLocation("test/particle")
            .withVertexShader("core/particle").withFragmentShader("core/particle")
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
    private final MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(
            81, 3, new Matrix4f(), new Matrix4f());

    @BeforeEach
    void begin() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        MetalPreviousVertexHistory.beginFrame();
        MetalEntityMotionCapture.beginParticleBatchBuild(sample);
    }

    @AfterEach
    void reset() {
        MetalEntityMotionCapture.beginFrame();
        MetalEntityMotionCapture.endModelBuild();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void allThreeOitLookupsRetainOwnerButOnlyAccumulateConsumesMotion() {
        try (var staged = new StagedVertexBuffer(() -> "motion test", 64)) {
            var draw = staged.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(draw, PARTICLE);
            for (OitStage stage : OitStage.values()) {
                var info = info();
                MetalEntityMotionCapture.transferExecute(draw, info);
                if (stage == OitStage.ACCUMULATE) {
                    assertSame(sample, MetalEntityMotionCapture.takeExecute(info, stage));
                    assertNotNull(MetalEntityMotionCapture.takePreviousVertexToken(info));
                } else {
                    assertNull(MetalEntityMotionCapture.takeExecute(info, stage));
                    assertNull(MetalEntityMotionCapture.takePreviousVertexToken(info));
                }
            }
            var coverage = MetalMotionDrawCoverage.snapshot();
            assertEquals(3, coverage.sourceDraws());
            assertEquals(1, coverage.ownedColorDraws());
            assertEquals(1, coverage.depthBoundsDraws());
            assertEquals(1, coverage.transmittanceDraws());
            assertEquals(1, coverage.accumulateDraws());
        }
    }

    @Test
    void repeatedExecuteInfoAndRepeatedLookupCannotReplayTheSameStagedDrawTwice() {
        try (var staged = new StagedVertexBuffer(() -> "motion test", 64)) {
            var draw = staged.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(draw, PARTICLE);
            var first = info();
            MetalEntityMotionCapture.transferExecute(draw, first);
            assertSame(sample, MetalEntityMotionCapture.takeExecute(first, OitStage.ACCUMULATE));
            assertNotNull(MetalEntityMotionCapture.takePreviousVertexToken(first));
            var next = info();
            MetalEntityMotionCapture.transferExecute(draw, next);
            assertNull(MetalEntityMotionCapture.takeExecute(next, OitStage.ACCUMULATE));
            assertNull(MetalEntityMotionCapture.takePreviousVertexToken(next));
            assertEquals(1, MetalMotionDrawCoverage.snapshot().duplicateColorDraws());
            assertEquals(1, MetalEntityMotionCapture.diagnostics().executesConsumed());
        }
    }

    @Test
    void endingStagedLifetimeDropsOwnershipAndTokens() {
        try (var staged = new StagedVertexBuffer(() -> "motion test", 64)) {
            var draw = staged.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(draw, PARTICLE);
            var pending = info();
            MetalEntityMotionCapture.transferExecute(draw, pending);
            MetalEntityMotionCapture.endStagedDraws(java.util.List.of(draw));
            assertNull(MetalEntityMotionCapture.takeExecute(pending));
            assertNull(MetalEntityMotionCapture.takePreviousVertexToken(pending));
            var stale = info();
            MetalEntityMotionCapture.transferExecute(draw, stale);
            assertNull(MetalEntityMotionCapture.takeExecute(stale));
        }
    }

    @Test
    void ordinaryParticleLookupIsConsumedOnceWithoutAnOitStage() {
        try (var staged = new StagedVertexBuffer(() -> "motion test", 64)) {
            var draw = staged.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(draw, PARTICLE);
            var info = info();
            MetalEntityMotionCapture.transferExecute(draw, info);
            assertSame(sample, MetalEntityMotionCapture.takeExecute(info));
            assertNotNull(MetalEntityMotionCapture.takePreviousVertexToken(info));
            assertEquals(1, MetalMotionDrawCoverage.snapshot().ownedColorDraws());
        }
    }

    @Test
    void retiringOneStagedBufferDoesNotEraseAnotherPreparedFramesOwner() {
        try (var a = new StagedVertexBuffer(() -> "world", 64);
             var b = new StagedVertexBuffer(() -> "other prepared frame", 64)) {
            var da = a.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            var db = b.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(da, PARTICLE);
            MetalEntityMotionCapture.attachDraw(db, PARTICLE);
            var first = info(); var other = info();
            MetalEntityMotionCapture.transferExecute(da, first);
            MetalEntityMotionCapture.transferExecute(db, other);
            MetalEntityMotionCapture.endStagedDraws(java.util.List.of(da));
            assertNull(MetalEntityMotionCapture.takeExecute(first));
            assertSame(sample, MetalEntityMotionCapture.takeExecute(other));
            assertNotNull(MetalEntityMotionCapture.takePreviousVertexToken(other));
        }
    }

    private static StagedVertexBuffer.ExecuteInfo info() {
        // These tests exercise only the real Minecraft draw/execute identities; GPU buffers are
        // deliberately absent. Shipping shader/readback and client hooks have separate CI tests.
        return new StagedVertexBuffer.ExecuteInfo(null, null, IndexType.SHORT, 0, 0, 6, PrimitiveTopology.QUADS);
    }
}
