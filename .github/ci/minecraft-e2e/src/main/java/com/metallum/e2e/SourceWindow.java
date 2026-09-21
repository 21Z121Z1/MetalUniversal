package com.metallum.e2e;

import com.google.gson.JsonObject;
import java.util.Arrays;

/** Common off/on source-return metrics in the predeclared half-open stationary window. */
final class SourceWindow {
    static JsonObject summarize(long[] timestamps, int count, long start, long end) {
        if (end <= start || count < 0 || count > timestamps.length)
            throw new IllegalArgumentException("Invalid source window");
        int first = 0;
        while (first < count && timestamps[first] < start) first++;
        int last = first;
        while (last < count && timestamps[last] < end) last++;
        long[] intervals = new long[Math.max(0, last - first - 1)];
        for (int i = first + 1; i < last; i++) {
            long interval = timestamps[i] - timestamps[i - 1];
            if (interval <= 0) throw new IllegalStateException("Non-monotonic source timestamps");
            intervals[i - first - 1] = interval;
        }
        Arrays.sort(intervals);
        JsonObject value = new JsonObject();
        value.addProperty("boundary", "after GpuSurface.present; source return, not actual presentation");
        value.addProperty("clock", "System.nanoTime");
        value.addProperty("startNs", start);
        value.addProperty("endNs", end);
        value.addProperty("count", last - first);
        value.addProperty("fps", (last - first) * 1e9 / (end - start));
        value.addProperty("intervalCount", intervals.length);
        for (int percentile : new int[]{50, 95, 99}) {
            String key = "intervalP" + percentile + "Ms";
            if (intervals.length == 0) value.add(key, com.google.gson.JsonNull.INSTANCE);
            else value.addProperty(key, intervals[(int) Math.ceil(intervals.length * percentile / 100.0) - 1] / 1e6);
        }
        return value;
    }
}
