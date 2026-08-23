package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalCommandEncoderStoreLivenessPolicyTest {
    @Test
    void requiresClearOnThisExactAttachment() {
        assertTrue(MetalCommandEncoder.canKillPriorColorStore(true, true),
                "same-slot CLEAR of the same attachment proves the predecessor store dead");
        assertFalse(MetalCommandEncoder.canKillPriorColorStore(false, true),
                "a sibling MRT CLEAR must not kill this slot when this slot LOADs");
        assertFalse(MetalCommandEncoder.canKillPriorColorStore(true, false),
                "CLEAR of another physical attachment cannot kill this predecessor store");
        assertFalse(MetalCommandEncoder.canKillPriorColorStore(false, false));
    }
}
