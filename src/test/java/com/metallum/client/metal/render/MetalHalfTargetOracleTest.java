package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed IEEE-754 binary16 encodings keep the GPU calibration oracle independent. */
final class MetalHalfTargetOracleTest {
    @Test
    void towardZeroConversionKeepsExactValuesAndHandlesBothSigns() {
        assertEquals((short)0x3240,MetalMotionProducerGpuTest.halfTowardZeroBits(.1953125F));
        assertEquals((short)0xb3c0,MetalMotionProducerGpuTest.halfTowardZeroBits(-.2421875F));
        assertEquals((short)0x323f,MetalMotionProducerGpuTest.halfTowardZeroBits(Math.nextDown(.1953125F)));
        assertEquals((short)0xb3bf,MetalMotionProducerGpuTest.halfTowardZeroBits(Math.nextUp(-.2421875F)));
        assertEquals((short)0x5000,MetalMotionProducerGpuTest.halfTowardZeroBits(32F));
        assertEquals((short)0x4fff,MetalMotionProducerGpuTest.halfTowardZeroBits(Math.nextDown(32F)));
    }

    @Test
    void measuredConversionModeDeterminesTheAcceptedBoundaryBits() {
        assertTrue(MetalMotionProducerGpuTest.calibratedHalfMatches(.1953125F,(short)0x3240,false));
        assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(.1953125F,(short)0x323f,false));
        assertTrue(MetalMotionProducerGpuTest.calibratedHalfMatches(.1953125F,(short)0x323f,true));
        assertTrue(MetalMotionProducerGpuTest.calibratedHalfMatches(-.2421875F,(short)0xb3bf,true));
        for(boolean towardZero:new boolean[]{false,true}) {
            assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(.1953125F,(short)0x323e,towardZero));
            assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(.1953125F,(short)0x3241,towardZero));
            assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(-.2421875F,(short)0xb3be,towardZero));
            assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(-.2421875F,(short)0xb3c1,towardZero));
        }
    }

    @Test
    void zeroAndReversedMotionCannotPassEitherConversionMode() {
        for(boolean towardZero:new boolean[]{false,true})for(float expected:new float[]{.1953125F,-.2421875F,.0078125F,-.0078125F}) {
            assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(expected,(short)0,towardZero));
            assertFalse(MetalMotionProducerGpuTest.calibratedHalfMatches(expected,Float.floatToFloat16(-expected),towardZero));
        }
    }
}
