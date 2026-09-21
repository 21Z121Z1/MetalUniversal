package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MetalParticleBatchMotionTest {
    private static final MetalParticleBatchMotion.LayerSignature OPAQUE_AB =
            new MetalParticleBatchMotion.LayerSignature(
                    false, "minecraft:textures/atlas/particles.png",
                    "minecraft:pipeline/opaque_particle", "minecraft:core/particle",
                    List.of(11L, 22L)
            );
    private static final MetalParticleBatchMotion.LayerSignature OPAQUE_BA =
            new MetalParticleBatchMotion.LayerSignature(
                    false, "minecraft:textures/atlas/particles.png",
                    "minecraft:pipeline/opaque_particle", "minecraft:core/particle",
                    List.of(22L, 11L)
            );
    private static final MetalParticleBatchMotion.Signature SIG_AB =
            new MetalParticleBatchMotion.Signature(false, List.of(OPAQUE_AB));

    @AfterEach
    void reset() {
        MetalParticleBatchMotion.reset();
    }

    @Test
    void stableLayerAndParticleOrderReuseExactHistory() {
        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalParticleBatchMotion.beginSignatureBatch(SIG_AB);
        assertNotNull(first);
        assertFalse(first.hasPrevious());
        assertTrue(first.generation() < 0L);
        MetalParticleBatchMotion.commitSubmittedFrame();

        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample second = MetalParticleBatchMotion.beginSignatureBatch(SIG_AB);
        assertNotNull(second);
        assertEquals(first.objectId(), second.objectId());
        assertEquals(first.generation(), second.generation());
        assertTrue(second.hasPrevious());
    }

    @Test
    void sameCountButParticleOrderChangeAllocatesFreshGeneration() {
        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalParticleBatchMotion.beginSignatureBatch(SIG_AB);
        assertNotNull(first);
        MetalParticleBatchMotion.commitSubmittedFrame();

        MetalParticleBatchMotion.Signature reordered =
                new MetalParticleBatchMotion.Signature(false, List.of(OPAQUE_BA));
        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample changed = MetalParticleBatchMotion.beginSignatureBatch(reordered);
        assertNotNull(changed);
        assertNotEquals(first.generation(), changed.generation());
        assertFalse(changed.hasPrevious());
    }

    @Test
    void layerOrTransparencyChangeBreaksContinuity() {
        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalParticleBatchMotion.beginSignatureBatch(SIG_AB);
        assertNotNull(first);
        MetalParticleBatchMotion.commitSubmittedFrame();

        MetalParticleBatchMotion.LayerSignature otherAtlas =
                new MetalParticleBatchMotion.LayerSignature(
                        false, "minecraft:textures/atlas/blocks.png",
                        "minecraft:pipeline/opaque_particle", "minecraft:core/particle",
                        List.of(11L, 22L));
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginSignatureBatch(
                new MetalParticleBatchMotion.Signature(false, List.of(otherAtlas))).hasPrevious());

        MetalParticleBatchMotion.reset();
        MetalParticleBatchMotion.beginFrame();
        assertNotNull(MetalParticleBatchMotion.beginSignatureBatch(SIG_AB));
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginSignatureBatch(
                new MetalParticleBatchMotion.Signature(true, List.of(OPAQUE_AB))).hasPrevious());
    }

    @Test
    void discardedFrameDoesNotAdvanceButSubmittedGapBreaksContinuity() {
        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalParticleBatchMotion.beginSignatureBatch(SIG_AB);
        assertNotNull(first);
        MetalParticleBatchMotion.commitSubmittedFrame();

        MetalParticleBatchMotion.beginFrame();
        MetalParticleBatchMotion.Signature reordered =
                new MetalParticleBatchMotion.Signature(false, List.of(OPAQUE_BA));
        assertNotNull(MetalParticleBatchMotion.beginSignatureBatch(reordered));
        MetalParticleBatchMotion.discardFrame();

        MetalParticleBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample recovered = MetalParticleBatchMotion.beginSignatureBatch(SIG_AB);
        assertTrue(recovered.hasPrevious());
        assertEquals(first.generation(), recovered.generation());
        MetalParticleBatchMotion.commitSubmittedFrame();

        MetalParticleBatchMotion.beginFrame();
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginSignatureBatch(SIG_AB).hasPrevious());
    }

    @Test
    void particleObjectIdentityAllocatorIsPositiveAndUnique() {
        long first = MetalParticleBatchMotion.allocateParticleIdentity();
        long second = MetalParticleBatchMotion.allocateParticleIdentity();
        assertTrue(first > 0L);
        assertTrue(second > first);
    }
}
