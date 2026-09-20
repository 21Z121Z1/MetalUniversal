package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;

/** Raw native attachment facts. These rows are neither byte estimates nor physical bandwidth. */
public final class NativeAttachmentActions {
    public static final int METADATA_WORDS = 8;
    public static final int ROW_WORDS = 40;
    public static final int MAX_ROWS = 65_536;
    public static final String SCOPE = "main-queue-render-attachment-actions";

    private NativeAttachmentActions() { }

    /** Fixed signed Int64 ABI; shape validation deliberately does not assert complete coverage. */
    public static Snapshot decode(long[] metadata, long[] words) {
        if (metadata.length != METADATA_WORDS || metadata[0] != 1
                || (metadata[1] != 0 && metadata[1] != 1)
                || metadata[2] < 0 || metadata[2] > MAX_ROWS
                || metadata[7] < 0 || metadata[7] > metadata[2]
                || words.length != metadata[7] * ROW_WORDS) {
            throw new IllegalArgumentException("Invalid native attachment action ABI shape");
        }
        for (long value : metadata) {
            if (value < 0) throw new IllegalArgumentException("Negative attachment metadata");
        }
        var rows = new ArrayList<Row>((int) metadata[7]);
        for (int i = 0; i < words.length; i += ROW_WORDS) {
            for (int j = 0; j < ROW_WORDS; j++) {
                if (words[i + j] < (j == 5 ? -1 : 0)) {
                    throw new IllegalArgumentException("Negative attachment fact");
                }
            }
            rows.add(new Row(words[i], words[i + 1], words[i + 2], words[i + 3],
                    words[i + 4], words[i + 5], words[i + 6], words[i + 7],
                    words[i + 8], words[i + 9], words[i + 10], words[i + 11],
                    words[i + 12], words[i + 13], words[i + 14], words[i + 15],
                    words[i + 16], words[i + 17], words[i + 18], words[i + 19],
                    words[i + 20], words[i + 21], words[i + 22], words[i + 23],
                    words[i + 24], words[i + 25], words[i + 26], words[i + 27],
                    words[i + 28], words[i + 29], words[i + 30], words[i + 31],
                    words[i + 32], words[i + 33], words[i + 34], words[i + 35],
                    words[i + 36], words[i + 37], words[i + 38], words[i + 39]));
        }
        return new Snapshot(1, metadata[1] == 1, metadata[2], metadata[3], metadata[4],
                metadata[5], metadata[6], metadata[7], SCOPE, rows);
    }

    public record Row(long windowId, long frameId, long submitIndex, long backend,
                      long encoderSequence, long aspect, long slot, long pixelFormat,
                      long width, long height, long depth, long arrayLength,
                      long textureType, long sampleCount, long storageMode, long level,
                      long slice, long depthPlane, long renderTargetWidth, long renderTargetHeight,
                      long renderTargetArrayLength, long loadAction, long initialStoreAction,
                      long finalStoreAction, long resolvePixelFormat, long resolveWidth,
                      long resolveHeight, long resolveDepth, long resolveArrayLength,
                      long resolveTextureType, long resolveSampleCount, long resolveStorageMode,
                      long resolveLevel, long resolveSlice, long resolveDepthPlane,
                      long resolveFilter, long storeActionOptions, long ended, long errorBits,
                      long reserved) { }

    public record Snapshot(int schemaVersion, boolean enabled, long capacityRows,
                           long droppedRows, long invalidEvents, long activeRenderEncoders,
                           long createdRenderEncoders, long rowCount, String scope, List<Row> rows) {
        public Snapshot { rows = List.copyOf(rows); }
    }
}
