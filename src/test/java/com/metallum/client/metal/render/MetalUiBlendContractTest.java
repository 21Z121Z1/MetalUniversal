package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalUiBlendContractTest {
    private static MetalUiBlendContract.Equation equation(String source,String dest) {
        return new MetalUiBlendContract.Equation(source,dest,"ADD");
    }
    @Test void straightSourceNeedsFullWritesAndIndependentAlphaSourceOver() {
        var rgb=equation("SRC_ALPHA","ONE_MINUS_SRC_ALPHA");var alpha=equation("ONE","ONE_MINUS_SRC_ALPHA");
        assertTrue(new MetalUiBlendContract.State(true,rgb,alpha).boundedStraightSourceOver());
        assertFalse(new MetalUiBlendContract.State(false,rgb,alpha).boundedStraightSourceOver());
        assertFalse(new MetalUiBlendContract.State(true,rgb,rgb).boundedStraightSourceOver());
        assertFalse(new MetalUiBlendContract.State(true,null,null).boundedStraightSourceOver());
        assertFalse(new MetalUiBlendContract.State(true,equation("ONE","ONE"),alpha).boundedStraightSourceOver());
        assertFalse(new MetalUiBlendContract.State(true,equation("ONE_MINUS_DST_COLOR","ONE_MINUS_SRC_COLOR"),alpha).boundedStraightSourceOver());
    }
}
