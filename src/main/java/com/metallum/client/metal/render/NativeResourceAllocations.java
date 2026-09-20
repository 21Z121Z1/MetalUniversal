package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;

/** Weak-live module-created resource allocation snapshot; never physical residency or a peak. */
public final class NativeResourceAllocations {
    public static final int HEADER_WORDS = 8;
    public static final int ROW_WORDS = 8;
    public static final int MAX_ROWS = 65_536;
    public static final String SCOPE = "module-created-live-metal-resources";

    private NativeResourceAllocations() { }

    public static Snapshot decode(long[] header, long[] words) {
        if (header.length != HEADER_WORDS || header[0] != 1
                || (header[1] != 0 && header[1] != 1) || header[2] != MAX_ROWS
                || header[7] < 0 || header[7] > MAX_ROWS
                || words.length != header[7] * ROW_WORDS) {
            throw new IllegalArgumentException("Invalid resource allocation snapshot ABI");
        }
        for (long value : header) {
            if (value < 0) throw new IllegalArgumentException("Negative allocation snapshot header");
        }
        var rows = new ArrayList<Row>((int) header[7]);
        for (int offset = 0; offset < words.length; offset += ROW_WORDS) {
            for (int index = 0; index < ROW_WORDS; index++) {
                if (words[offset + index] < 0) throw new IllegalArgumentException("Negative resource field");
            }
            if (words[offset + 4] > 1) throw new IllegalArgumentException("Invalid memoryless boolean");
            rows.add(new Row(words[offset], words[offset + 1], words[offset + 2],
                    words[offset + 3], words[offset + 4] == 1,
                    words[offset + 5], words[offset + 6], words[offset + 7]));
        }
        return new Snapshot(1, SCOPE, header[1] == 1, header[2], header[3], header[4],
                header[5], header[6], header[7], List.copyOf(rows));
    }

    public record Row(long resourceId, long kind, long allocatedBytes, long storageMode,
                      boolean memoryless, long reserved0, long reserved1, long reserved2) { }

    public record Snapshot(int schemaVersion, String scope, boolean enabled, long capacityRows,
                           long droppedRows, long invalidEvents, long createdResources,
                           long totalAllocatedBytes, long liveRowCount, List<Row> rows) { }
}
