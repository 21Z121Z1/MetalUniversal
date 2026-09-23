package com.metallum.client.metal.render;

import java.nio.ByteBuffer;

/** Internal contract for dynamic buffers that can diff CPU-visible upload ranges. */
public interface MetalUploadDedupBuffer {
    /**
     * Returns the minimal half-open byte range in {@code data} that differs
     * from the buffer's current backing at {@code offset}. A non-dynamic or
     * otherwise uncomparable buffer returns the full range.
     */
    UploadRange metallum$diffUpload(long offset, ByteBuffer data);

    /** Changes whenever a dynamic buffer swaps to a different native backing. */
    long metallum$bindingVersion();

    record UploadRange(int start, int end) {
        public UploadRange {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid upload range " + start + ".." + end);
            }
        }

        public static UploadRange full(final int length) {
            return new UploadRange(0, Math.max(0, length));
        }

        public static UploadRange empty() {
            return new UploadRange(0, 0);
        }

        public int length() {
            return end - start;
        }

        public boolean isEmpty() {
            return start == end;
        }

        public boolean isFull(final int originalLength) {
            return start == 0 && end == originalLength;
        }
    }
}
