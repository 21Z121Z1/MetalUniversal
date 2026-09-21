package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalArgumentTablePatchTest {
    @Test
    void emitsOnlyDirtyEntriesInStableKindAndIndexOrder() {
        IrisMetalOptimizationPlan.ArgumentLayout layout = IrisMetalOptimizationPlan.ArgumentLayout.of(List.of(
                new IrisMetalOptimizationPlan.ArgumentSlot("ubo", IrisMetalOptimizationPlan.ArgumentSlot.Kind.BUFFER, 2, false),
                new IrisMetalOptimizationPlan.ArgumentSlot("tex", IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE, 1, false),
                new IrisMetalOptimizationPlan.ArgumentSlot("sampler", IrisMetalOptimizationPlan.ArgumentSlot.Kind.SAMPLER, 1, false)
        ));
        IrisMetalArgumentSnapshot snapshot = new IrisMetalArgumentSnapshot(layout);
        snapshot.bindTexture(1, MemorySegment.ofAddress(0x2000L));
        snapshot.bindBuffer(2, MemorySegment.ofAddress(0x1000L), 64L);
        snapshot.bindSampler(1, MemorySegment.ofAddress(0x3000L));

        IrisMetalArgumentTablePatch patch = IrisMetalArgumentTablePatch.from(snapshot);
        assertTrue(patch.admitted());
        assertTrue(patch.hasWork());
        assertEquals(3, patch.entries().size());
        assertEquals(IrisMetalArgumentTablePatch.Kind.BUFFER, patch.entries().get(0).kind());
        assertEquals(IrisMetalArgumentTablePatch.Kind.TEXTURE, patch.entries().get(1).kind());
        assertEquals(IrisMetalArgumentTablePatch.Kind.SAMPLER, patch.entries().get(2).kind());
        assertEquals(64L, patch.entries().get(0).offset());
        assertEquals(32 + 3 * 32, patch.byteCount());
    }

    @Test
    void cleanSnapshotHasNoWorkButRetainsLayoutAndGenerationIdentity() {
        IrisMetalOptimizationPlan.ArgumentLayout layout = IrisMetalOptimizationPlan.ArgumentLayout.of(List.of(
                new IrisMetalOptimizationPlan.ArgumentSlot("ubo", IrisMetalOptimizationPlan.ArgumentSlot.Kind.BUFFER, 0, false)
        ));
        IrisMetalArgumentSnapshot snapshot = new IrisMetalArgumentSnapshot(layout);
        IrisMetalArgumentTablePatch patch = IrisMetalArgumentTablePatch.from(snapshot);
        assertTrue(patch.admitted());
        assertFalse(patch.hasWork());
        assertEquals(layout.stableHash(), patch.layoutHash());
        assertEquals(0L, patch.snapshotGeneration());
    }

    @Test
    void rejectedPatchNeverReportsExecutableWork() {
        IrisMetalArgumentTablePatch patch = IrisMetalArgumentTablePatch.rejected("feature-disabled");
        assertFalse(patch.admitted());
        assertFalse(patch.hasWork());
        assertEquals("feature-disabled", patch.reason());
    }
}
