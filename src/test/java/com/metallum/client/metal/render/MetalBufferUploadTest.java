package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

final class MetalBufferUploadTest {
    @Test void partialCopiesMatchConservativePathWithoutChangingInputsOrPadding() {
        for (int start = 0; start < 11; start++) {
            for (int length = 0; length <= 11 - start; length++) {
                ByteBuffer old = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 99});
                ByteBuffer input = ByteBuffer.allocate(20);
                for (int i = 0; i < 20; i++) input.put(i, (byte)(40 + i));
                input.position(3).limit(3 + length);
                ByteBuffer baseline = ByteBuffer.allocate(12);
                ByteBuffer candidate = ByteBuffer.allocate(12);
                old.position(2).limit(9);
                MetalBufferUpload.copy(old, baseline, 11, start, input, false);
                MetalBufferUpload.copy(old, candidate, 11, start, input, true);
                assertEquals(baseline, candidate);
                assertEquals(0, candidate.get(11));
                assertEquals(2, old.position());
                assertEquals(9, old.limit());
                assertEquals(3, input.position());
                for (int i = 0; i < 11; i++) {
                    assertEquals(i >= start && i < start + length ? (byte)(43 + i - start) : (byte)i, candidate.get(i));
                }
            }
        }
    }

    @Test void malformedAndOverflowingSlicesAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> MetalBufferUpload.validate(10, 4, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> MetalBufferUpload.validate(10, -1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> MetalBufferUpload.validate(Long.MAX_VALUE, 10, Long.MAX_VALUE, 1));
    }
}
