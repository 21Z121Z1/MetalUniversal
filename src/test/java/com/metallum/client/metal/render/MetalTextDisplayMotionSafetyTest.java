package com.metallum.client.metal.render;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class MetalTextDisplayMotionSafetyTest {
    @Test
    void geometryIdentityTracksTextLayoutAndFlags() {
        Component text = Component.literal("motion");
        MetalDisplayMotionSafety.TextGeometry baseline =
                MetalDisplayMotionSafety.textGeometry(text, 96, (byte) 0x01);
        assertEquals(baseline, MetalDisplayMotionSafety.textGeometry(text, 96, (byte) 0x01));
        assertNotEquals(baseline, MetalDisplayMotionSafety.textGeometry(Component.literal("changed"), 96, (byte) 0x01));
        assertNotEquals(baseline, MetalDisplayMotionSafety.textGeometry(text, 97, (byte) 0x01));
        assertNotEquals(baseline, MetalDisplayMotionSafety.textGeometry(text, 96, (byte) 0x03));
    }
}
