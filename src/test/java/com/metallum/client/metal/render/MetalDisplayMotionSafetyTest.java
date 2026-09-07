package com.metallum.client.metal.render;

import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MetalDisplayMotionSafetyTest {
    @Test
    void emptyModelIdentityFailsClosed() {
        assertNull(MetalDisplayMotionSafety.itemGeometry(ItemDisplayContext.NONE, List.of()));
    }

    @Test
    void itemGeometryUsesVanillaModelIdentityAndDisplayContext() {
        Object model = new Object();
        Object selection = "selected-variant";
        MetalDisplayMotionSafety.ItemGeometry baseline = MetalDisplayMotionSafety.itemGeometry(
                ItemDisplayContext.NONE,
                List.of(model, selection)
        );

        assertEquals(
                baseline,
                MetalDisplayMotionSafety.itemGeometry(ItemDisplayContext.NONE, List.of(model, selection))
        );
        assertNotEquals(
                baseline,
                MetalDisplayMotionSafety.itemGeometry(ItemDisplayContext.GUI, List.of(model, selection))
        );
        assertNotEquals(
                baseline,
                MetalDisplayMotionSafety.itemGeometry(ItemDisplayContext.NONE, List.of(new Object(), selection))
        );
    }
}
