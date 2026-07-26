package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalFxReactiveTuningTest {
    @Test
    void parseUnitFloatClampsAndFallsBack() {
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat(null, 0.35F));
        assertEquals(0.5F, MetalFxConfig.parseUnitFloat("0.5", 0.35F));
        assertEquals(1.0F, MetalFxConfig.parseUnitFloat("7", 0.35F));
        assertEquals(0.0F, MetalFxConfig.parseUnitFloat("-3", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("NaN", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("leaves", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("Infinity", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("  0.35  ", 0.9F));
    }
}
