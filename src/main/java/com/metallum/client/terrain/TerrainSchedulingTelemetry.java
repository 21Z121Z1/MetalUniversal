package com.metallum.client.terrain;

import com.metallum.Metallum;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Bounded, opt-in CSV output for same-scene terrain scheduling comparisons. */
public final class TerrainSchedulingTelemetry implements AutoCloseable {
    public static final int MAX_ROWS = 4096;
    public static final String CSV_HEADER = String.join(",",
            "frame_index",
            "adaptive",
            "pressure_level",
            "build_budget_nanos",
            "upload_budget_nanos",
            "backlog_jobs",
            "busy_threads",
            "total_threads",
            "thermal_state",
            "memory_pressure",
            "cpu_frame_nanos",
            "gpu_frame_nanos",
            "terrain_nanos",
            "build_submit_nanos",
            "upload_nanos",
            "submitted_tasks",
            "upload_results",
            "forward_boost",
            "turn_detected",
            "target_present_interval_nanos",
            "target_present_interval_measured",
            "target_present_interval_derived",
            "target_present_interval_provenance",
            "target_present_interval_fallback_reason",
            "measured_present_interval_nanos",
            "measured_present_interval_available",
            "measured_present_interval_provenance",
            "measured_present_interval_fallback_reason",
            "drawable_wait_nanos",
            "drawable_wait_available",
            "frames_in_flight",
            "frames_in_flight_available",
            "pacing_provenance",
            "pacing_fallback_reason"
    );

    private final Path path;
    private BufferedWriter writer;
    private int rows;
    private boolean failed;

    public TerrainSchedulingTelemetry(final Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public synchronized void append(final TerrainSchedulingController.FrameSnapshot snapshot) {
        if (failed || rows >= MAX_ROWS) {
            return;
        }
        try {
            ensureWriter();
            writer.write(snapshot.toCsv());
            writer.newLine();
            rows++;
            if ((rows & 63) == 0) {
                writer.flush();
            }
        } catch (IOException exception) {
            failed = true;
            closeWriter();
            Metallum.LOGGER.warn("Terrain scheduling CSV disabled after {} row(s): {}", rows, exception.toString());
        }
    }

    public synchronized int rowsWritten() {
        return rows;
    }

    @Override
    public synchronized void close() {
        closeWriter();
    }

    private void ensureWriter() throws IOException {
        if (writer != null) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        writer.write(CSV_HEADER);
        writer.newLine();
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException exception) {
            Metallum.LOGGER.debug("Could not close terrain scheduling CSV {}: {}", path, exception.toString());
        } finally {
            writer = null;
        }
    }

    static String csvDouble(final double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
