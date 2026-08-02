package com.metallum.client.metal.render;

import java.nio.ByteBuffer;

/** Internal contract for buffers that can suppress byte-identical uploads. */
public interface MetalUploadDedupBuffer {
    boolean metallum$matchesUpload(long offset, ByteBuffer data);

    void metallum$recordUpload(long offset, ByteBuffer data);
}
