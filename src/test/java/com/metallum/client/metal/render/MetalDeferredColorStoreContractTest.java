package com.metallum.client.metal.render;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalDeferredColorStoreContractTest {
    @Test
    void everyClearMaskIsAttachmentLocal() {
        for (int count = 1; count <= 8; count++) {
            for (int mask = 0; mask < (1 << count); mask++) {
                for (int slot = 0; slot < count; slot++) {
                    var view = MemorySegment.ofAddress(slot + 1);
                    boolean cleared = (mask & (1 << slot)) != 0;
                    assertEquals(cleared, MetalCommandEncoder.canDiscardColorStore(
                            cleared ? 1 : 0, view, view, true, true));
                }
            }
        }
    }

    @Test
    void missingIdentityDifferentViewOrPartialCoverageMustStore() {
        var view = MemorySegment.ofAddress(1);
        var otherView = MemorySegment.ofAddress(2);
        assertFalse(MetalCommandEncoder.canDiscardColorStore(1, null, view, true, true));
        assertFalse(MetalCommandEncoder.canDiscardColorStore(1, MemorySegment.NULL, MemorySegment.NULL, true, true));
        assertFalse(MetalCommandEncoder.canDiscardColorStore(1, view, otherView, true, true));
        assertFalse(MetalCommandEncoder.canDiscardColorStore(1, view, view, false, true));
        assertFalse(MetalCommandEncoder.canDiscardColorStore(1, view, view, true, false));
    }
}
