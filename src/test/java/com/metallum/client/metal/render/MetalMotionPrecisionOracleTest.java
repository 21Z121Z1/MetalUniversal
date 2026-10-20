package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Binary16 calibration, independent of pipeline identities and the candidate surface-selection code. */
final class MetalMotionPrecisionOracleTest {
    @Test
    void existingStorageBudgetIncludesBoundaryRoundingButRejectsWrongFlow() {
        double negative=MetalMotionProducerGpuTest.halfRoundingBudgetPixels(-.125,64);
        double positive=MetalMotionProducerGpuTest.halfRoundingBudgetPixels(.25,64);
        assertEquals(1.0/512,negative,0);
        assertEquals(1.0/256,positive,0);
        assertTrue(Math.abs(Float.float16ToFloat((short)0xAFFF)-(-.125))*32<=negative);
        assertTrue(Math.abs(Float.float16ToFloat((short)0x33FF)-.25)*32<=positive);
        for(float wrong:new float[]{0,.25F,-.1248F})assertTrue(Math.abs(wrong-(-.125))*32>negative);
        for(float wrong:new float[]{0,-.125F,.2497F})assertTrue(Math.abs(wrong-.25)*32>positive);
    }
}
