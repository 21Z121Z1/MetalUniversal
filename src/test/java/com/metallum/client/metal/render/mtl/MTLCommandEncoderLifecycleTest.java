package com.metallum.client.metal.render.mtl;

import org.junit.jupiter.api.Test;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class MTLCommandEncoderLifecycleTest {
    private static final class Encoder extends MTLCommandEncoder {
        final List<String> events = new ArrayList<>();
        boolean failFlush;
        boolean failEnd;
        Encoder() { super(MemorySegment.ofAddress(0x1234)); }
        @Override protected void beforeEndEncoding(MemorySegment handle) {
            events.add("flush");
            if (failFlush) throw new IllegalStateException("flush failed");
        }
        @Override protected void endNativeEncoding(MemorySegment handle) {
            events.add("end");
            if (failEnd) throw new IllegalStateException("end failed");
        }
        @Override protected void releaseCpuState() { events.add("cpu"); }
        @Override protected void releaseNativeHandle(MemorySegment handle) { events.add("release"); }
    }

    @Test void flushFailureStillEndsAndReleasesBothLifetimesExactlyOnce() {
        Encoder encoder = new Encoder();
        encoder.failFlush = true;
        assertThrows(IllegalStateException.class, encoder::endEncoding);
        encoder.endEncoding();
        assertEquals(List.of("flush", "end", "cpu", "release"), encoder.events);
        assertEquals(0L, encoder.handle.address());
    }

    @Test void failedRetainingCloseCannotLeakItsHandle() {
        Encoder encoder = new Encoder();
        encoder.failEnd = true;
        assertThrows(IllegalStateException.class, encoder::endEncodingRetainingHandle);
        assertEquals(List.of("flush", "end", "cpu", "release"), encoder.events);
        assertEquals(MemorySegment.NULL, encoder.endEncodingRetainingHandle());
    }

    @Test void successfulTransitionTransfersOnlyTheNativeLease() {
        Encoder encoder = new Encoder();
        assertEquals(0x1234L, encoder.endEncodingRetainingHandle().address());
        encoder.endEncoding();
        assertEquals(List.of("flush", "end", "cpu"), encoder.events);
    }
}
