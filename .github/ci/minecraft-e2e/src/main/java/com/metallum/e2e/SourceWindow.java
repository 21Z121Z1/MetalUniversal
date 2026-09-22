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
    /** Constant-memory OFF/ON instrument. Bins are upper bounds, never exact quantiles. */
    static final class Accumulator {
        private static final long BIN_NS = 100_000; // 0.1 ms; identical in all observer modes.
        private final long[] bins = new long[8193];
        private final long start, end;
        private long count, previous, worst, intervals, invalid;
        private boolean closed;

        Accumulator(long start, long end) {
            if (end <= start) throw new IllegalArgumentException("Invalid source window");
            this.start = start;
            this.end = end;
        }

        void record(long now) {
            if (now >= end) { closed = true; return; }
            if (now < start) return;
            if (count > 0) {
                long interval = now - previous;
                if (interval <= 0) { invalid++; return; }
                worst = Math.max(worst, interval);
                // Ceiling with no addition overflow. Final bin explicitly represents overflow.
                int bin = (int) Math.min(bins.length - 1, (interval - 1) / BIN_NS + 1);
                bins[bin]++;
                intervals++;
            }
            previous = now;
            count++;
        }

        JsonObject finish() {
            var value = new JsonObject();
            value.addProperty("boundary", "after GpuSurface.present; source return, not actual presentation");
            value.addProperty("clock", "System.nanoTime");
            value.addProperty("membership", "half-open [startNs,endNs)");
            value.addProperty("startNs", start); value.addProperty("endNs", end);
            value.addProperty("count", count); value.addProperty("intervalCount", intervals);
            value.addProperty("fps", count * 1e9 / (end - start));
            value.addProperty("complete", closed); value.addProperty("invalidTimestamps", invalid);
            if (intervals == 0) {
                value.add("worstIntervalNs", com.google.gson.JsonNull.INSTANCE);
                value.addProperty("worstIntervalUnavailableReason", "no-intervals");
            } else value.addProperty("worstIntervalNs", worst);
            value.addProperty("quantileMethod", "fixed 0.1ms histogram upper bound; final bin is overflow");
            value.addProperty("quantileResolutionNs", BIN_NS);
            value.addProperty("overflowIntervals", bins[bins.length - 1]);
            for (int percentile : new int[]{500, 950, 990, 999}) {
                String key = "intervalP" + (percentile == 999 ? "999" : percentile / 10) + "UpperBoundMs";
                long rank = (long) Math.ceil(intervals * percentile / 1000.0);
                int bin = 0; long total = 0;
                while (bin < bins.length && total < rank) total += bins[bin++];
                if (intervals == 0 || (percentile == 999 && intervals < 10_000) || bin == bins.length) {
                    value.add(key, com.google.gson.JsonNull.INSTANCE);
                    value.addProperty(key + "UnavailableReason", intervals == 0 ? "no-intervals" :
                            percentile == 999 && intervals < 10_000 ? "insufficient-samples" : "histogram-range-exceeded");
                } else value.addProperty(key, (bin - 1) * BIN_NS / 1e6);
            }
            return value;
        }
    }

}
