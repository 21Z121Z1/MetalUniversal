package com.metallum.client.metal.render;

import com.metallum.client.validation.contract.PassType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IrisMetalAttachmentLastUseContractTest {
    @Test
    void lifetimeCarriesAnExactDeathPointIndependentOfLastWrite() {
        IrisMetalOptimizationPlan.AttachmentLifetime lifetime =
                new IrisMetalOptimizationPlan.AttachmentLifetime(
                        "allocation/1/generation/1/mip/0", 1L, 1L, 0,
                        2, 3, 7, 4, "SAMPLED_READ"
                );
        assertEquals(2, lifetime.firstUse());
        assertEquals(3, lifetime.lastWrite());
        assertEquals(7, lifetime.lastUse());
        assertEquals(4, lifetime.nextUse());
    }

    @Test
    void lifetimeRejectsDeathBeforeAReadOrWrite() {
        assertThrows(IllegalArgumentException.class, () ->
                new IrisMetalOptimizationPlan.AttachmentLifetime(
                        "allocation/1/generation/1/mip/0", 1L, 1L, 0,
                        2, 5, 4, 3, "SAMPLED_READ"
                ));
    }

    @Test
    void oldConstructorRemainsConservativeForFocusedFixtures() {
        IrisMetalOptimizationPlan.AttachmentLifetime lifetime =
                new IrisMetalOptimizationPlan.AttachmentLifetime(
                        "allocation/2/generation/1/mip/0", 2L, 1L, 0,
                        1, 2, 6, "SAMPLED_READ"
                );
        assertEquals(6, lifetime.lastUse());
    }
}
