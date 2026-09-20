package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;

/** Native encoder creation/end counts, independent of optional GPU timestamp sampling. */
public final class NativeEncoderCounts {
    public static final int METADATA_WORDS = 8;
    public static final int ROW_WORDS = 13;
    public static final int MAX_ROWS = 65_536;
    public static final String SCOPE = "main-queue-native-encoders";

    private NativeEncoderCounts() { }

    /** The v1 C ABI consists solely of signed 64-bit words; no platform struct padding. */
    public static Snapshot decode(long[] metadata, long[] words) {
        if (metadata.length != METADATA_WORDS || metadata[0] != 1
                || (metadata[1] != 0 && metadata[1] != 1)
                || metadata[2] < 0 || metadata[2] > MAX_ROWS
                || metadata[7] < 0 || metadata[7] > metadata[2]
                || words.length != metadata[7] * ROW_WORDS) {
            throw new IllegalArgumentException("Invalid native encoder count ABI shape");
        }
        for (long value : metadata) {
            if (value < 0) throw new IllegalArgumentException("Negative encoder count metadata");
        }
        var rows = new ArrayList<Sample>((int) metadata[7]);
        for (int i = 0; i < words.length; i += ROW_WORDS) {
            rows.add(new Sample(words[i], words[i + 1], words[i + 2], words[i + 3],
                    words[i + 4], words[i + 5], words[i + 6], words[i + 7],
                    words[i + 8], words[i + 9], words[i + 10], words[i + 11], words[i + 12]));
        }
        return new Snapshot(1, metadata[1] == 1, metadata[2], metadata[3], metadata[4],
                metadata[5], metadata[6], metadata[7], SCOPE, rows);
    }

    public record Sample(long windowId, long frameId, long submitIndex, long backend,
                         long attempted, long created, long ended, long renderCreated,
                         long blitCreated, long computeCreated, long createFailures,
                         long unsupportedEncodes, long invalidEvents) { }

    public record Snapshot(int schemaVersion, boolean enabled, long capacityRows,
                           long droppedRows, long invalidEvents, long activeCommandBuffers,
                           long activeEncoders, long rowCount, String scope, List<Sample> rows) {
        public Snapshot { rows = List.copyOf(rows); }
    }
}
