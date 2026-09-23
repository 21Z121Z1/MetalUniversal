package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalSurfaceFrameStateTest {
    @Test void oneAcquireEncodeAndPresentPerFrame() {
        var surface = new MetalSurfaceFrameState();
        assertThrows(IllegalStateException.class, surface::acquire);
        surface.configured();
        surface.acquire();
        assertThrows(IllegalStateException.class, surface::acquire);
        assertThrows(IllegalStateException.class, surface::present);
        surface.encoded();
        assertThrows(IllegalStateException.class, surface::encoded);
        assertThrows(IllegalStateException.class, surface::configured);
        surface.present();
        assertThrows(IllegalStateException.class, surface::present);
        surface.configured();
        assertEquals(2, surface.epoch());
        surface.acquire();
        surface.encoded();
        surface.present();
    }

    @Test void closingWithAPendingFramePreventsReuseAndIsIdempotent() {
        var surface = new MetalSurfaceFrameState();
        surface.configured();
        surface.acquire();
        surface.encoded();
        surface.close();
        surface.close();
        assertThrows(IllegalStateException.class, surface::present);
        assertThrows(IllegalStateException.class, surface::configured);
        assertThrows(IllegalStateException.class, surface::acquire);
    }
}
