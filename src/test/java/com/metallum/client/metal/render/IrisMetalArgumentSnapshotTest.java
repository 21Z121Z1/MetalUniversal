package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalArgumentSnapshotTest {
    private static IrisMetalOptimizationPlan.ArgumentLayout layout() {
        return IrisMetalOptimizationPlan.ArgumentLayout.of(List.of(
                new IrisMetalOptimizationPlan.ArgumentSlot(
                        "ubo", IrisMetalOptimizationPlan.ArgumentSlot.Kind.BUFFER, 0, false),
                new IrisMetalOptimizationPlan.ArgumentSlot(
                        "color", IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE, 1, false),
                new IrisMetalOptimizationPlan.ArgumentSlot(
                        "color#sampler", IrisMetalOptimizationPlan.ArgumentSlot.Kind.SAMPLER, 1, false)
        ));
    }

    @Test
    void identicalBindingsDoNotAdvanceGeneration() {
        IrisMetalArgumentSnapshot snapshot = new IrisMetalArgumentSnapshot(layout());
        snapshot.bindBuffer(0, MemorySegment.ofAddress(0x1000), 64L);
        snapshot.bindTexture(1, MemorySegment.ofAddress(0x2000));
        snapshot.bindSampler(1, MemorySegment.ofAddress(0x3000));
        long generation = snapshot.generation();

        snapshot.bindBuffer(0, MemorySegment.ofAddress(0x1000), 64L);
        snapshot.bindTexture(1, MemorySegment.ofAddress(0x2000));
        snapshot.bindSampler(1, MemorySegment.ofAddress(0x3000));

        assertEquals(generation, snapshot.generation());
        assertTrue(snapshot.dirty());
        snapshot.markEncoded();
        assertFalse(snapshot.dirty());
    }

    @Test
    void ringDoesNotShareMutableSnapshotBetweenInflightSlots() {
        IrisMetalArgumentSnapshot.Ring ring = new IrisMetalArgumentSnapshot.Ring(layout(), 3);
        IrisMetalArgumentSnapshot first = ring.current();
        first.bindTexture(1, MemorySegment.ofAddress(0x2000));
        ring.advanceAfterSubmit();
        IrisMetalArgumentSnapshot second = ring.current();

        assertFalse(first == second);
        assertEquals(0L, second.generation());
        assertEquals(0L, second.texture(1).address());
    }
}
