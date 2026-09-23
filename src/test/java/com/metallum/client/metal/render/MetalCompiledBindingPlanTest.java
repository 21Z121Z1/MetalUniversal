package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalCompiledBindingPlanTest {
    @Test
    void compilesResourceRecordsIntoDenseSlots() {
        MetalCompiledBindingPlan plan = MetalCompiledBindingPlan.compile(List.of(
                new FakeBinding(Kind.UNIFORM_BUFFER, "Globals", 3, 1),
                new FakeBinding(Kind.SAMPLED_IMAGE, "Sampler0", 5, 2),
                new FakeBinding(Kind.STORAGE_BUFFER, "iris_ssbo/7/Particles", 9, 3)
        ));

        MetalBindingToken globals = MetalBindingTokenRegistry.resolve("Globals");
        MetalBindingToken sampler = MetalBindingTokenRegistry.resolve("Sampler0");
        MetalBindingToken storage = MetalBindingTokenRegistry.resolve("iris_ssbo/7/Particles");

        assertEquals(3, plan.bindingCount());
        assertEquals(0, plan.slotFor(globals));
        assertEquals(1, plan.slotFor(sampler));
        assertEquals(2, plan.slotFor(storage));
        assertSame(storage, plan.token(2));
        assertEquals(9, plan.physicalBindingIndex(2));
        assertEquals(3, plan.stageMask(2));
        assertEquals("STORAGE_BUFFER", plan.resourceKind(2));
        assertEquals(7, storage.logicalStorageBinding());
        assertEquals(-1, plan.slotFor(MetalBindingTokenRegistry.resolve("not-in-plan")));
    }

    @Test
    void duplicateResourceNamesFailAtPipelineCompilation() {
        assertThrows(IllegalStateException.class, () -> MetalCompiledBindingPlan.compile(List.of(
                new FakeBinding(Kind.UNIFORM_BUFFER, "Globals", 0, 1),
                new FakeBinding(Kind.UNIFORM_BUFFER, new String("Globals"), 1, 2)
        )));
    }

    private enum Kind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        STORAGE_BUFFER
    }

    private record FakeBinding(Kind kind, String name, int bindingIndex, int stageMask) {
    }
}
