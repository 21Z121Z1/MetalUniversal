package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

final class MetalBufferUploadTest {
    @Test
    @EnabledIfSystemProperty(named = "metallum.ci.encodingCost", matches = "true")
    void hostedUploadCopyCost() {
        for (int size : new int[] {64, 65536, 1048576}) {
            ByteBuffer previous = ByteBuffer.allocateDirect(size);
            ByteBuffer fresh = ByteBuffer.allocateDirect(size);
            for (int patchSize : new int[] {size / 16, size * 3 / 4}) {
                int offset = (size - patchSize) / 2;
                ByteBuffer patch = ByteBuffer.allocateDirect(patchSize);
                for (int i = 0; i < size; i++) previous.put(i, (byte) (i * 17));
                for (int i = 0; i < patchSize; i++) patch.put(i, (byte) (i * 3 + 1));
                int iterations = Math.max(1024, 16777216 / size);
                // Warm both paths before recording four ABBA blocks.
                for (int warmup = 0; warmup < 16; warmup++) {
                    for (int i = 0; i < iterations; i++) MetalBufferUpload.copy(previous, fresh, size, offset, patch,
                            (warmup & 1) == 0 && MetalBufferUpload.useRangeCopy(size, patchSize));
                }
                for (int block = 0; block < 4; block++) {
                    for (boolean candidate : new boolean[] {false, true, true, false}) {
                        long start = System.nanoTime();
                        for (int i = 0; i < iterations; i++) MetalBufferUpload.copy(previous, fresh, size, offset, patch,
                                candidate && MetalBufferUpload.useRangeCopy(size, patchSize));
                        long elapsed = System.nanoTime() - start;
                        for (int i = 0; i < size; i++) {
                            byte expected = i >= offset && i < offset + patchSize ? patch.get(i - offset) : previous.get(i);
                            assertEquals(expected, fresh.get(i));
                        }
                        System.out.println("HOSTED_UPLOAD_COPY_COST {\"size\":" + size + ",\"patchBytes\":" + patchSize
                                + ",\"block\":" + block + ",\"candidate\":" + candidate
                                + ",\"nsPerOperation\":" + ((double) elapsed / iterations) + "}");
                    }
                }
            }
        }
    }

    @Test void smallOrNarrowPatchesKeepTheSinglePreservationCopy() {
        assertFalse(MetalBufferUpload.useRangeCopy(64, 48));
        assertFalse(MetalBufferUpload.useRangeCopy(1048576, 65536));
        assertFalse(MetalBufferUpload.useRangeCopy(8192, 4095));
        assertTrue(MetalBufferUpload.useRangeCopy(8192, 4096));
        assertTrue(MetalBufferUpload.useRangeCopy(65536, 49152));
        assertFalse(MetalBufferUpload.useRangeCopy(65536, 65536));
    }

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
