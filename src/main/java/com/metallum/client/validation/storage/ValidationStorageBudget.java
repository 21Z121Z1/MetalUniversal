package com.metallum.client.validation.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local byte budget for one validation output root.
 *
 * <p>Every validation artifact, including rewritten manifests and reports, is
 * accounted by its final on-disk size. The registry makes the legacy
 * MetalFX writers and the render-contract writers share one budget when they
 * target the same run directory.</p>
 */
public final class ValidationStorageBudget {
    public static final long DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L;
    /** Default budget for a managed system-temporary validation run. */
    public static final long DEFAULT_TEMP_MAX_BYTES = 768L * 1024L * 1024L;
    private static final long MAX_CRITICAL_BYTES = 256L * 1024L;
    private static final String[] MANAGED_TEMP_PREFIXES = {
            "metallum-render-contract-",
            "metallum-validation-"
    };

    private static final Map<Path, ValidationStorageBudget> SHARED = new ConcurrentHashMap<>();

    private final Path root;
    private final long maxBytes;
    private final Map<Path, Long> accountedFiles = new LinkedHashMap<>();
    private final Map<Path, Long> criticalFiles = new LinkedHashMap<>();
    private long artifactBytes;
    private long criticalBytes;
    private boolean exceeded;
    private String failureReason;
    private boolean failureSummaryWritten;

    private ValidationStorageBudget(final Path root, final long maxBytes) {
        this.root = normalize(root);
        this.maxBytes = maxBytes;
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("Validation storage budget must be positive");
        }
        scanExistingFiles();
    }

    public static ValidationStorageBudget shared(final Path root) {
        Path normalized = normalize(root);
        long defaultMaxBytes = defaultMaxBytes(normalized);
        long maxBytes = longProperty(
                "metallum.renderContract.maxArtifactBytes",
                longProperty("metallum.validation.maxArtifactBytes", defaultMaxBytes)
        );
        return shared(normalized, maxBytes);
    }

    /**
     * Returns the default for this output root without applying any property
     * override. Managed temporary runs are deliberately smaller than retained
     * analysis output so repeated agent validation cannot fill the disk.
     */
    public static long defaultMaxBytes(final Path root) {
        return isManagedTemporaryRoot(root) ? DEFAULT_TEMP_MAX_BYTES : DEFAULT_MAX_BYTES;
    }

    public static ValidationStorageBudget shared(final Path root, final long maxBytes) {
        Path normalized = normalize(root);
        return SHARED.computeIfAbsent(normalized, ignored -> new ValidationStorageBudget(normalized, maxBytes));
    }

    public synchronized Path root() {
        return root;
    }

    public synchronized long maxBytes() {
        return maxBytes;
    }

    public synchronized long artifactBytes() {
        return artifactBytes;
    }

    public synchronized long remainingBytes() {
        return Math.max(0L, maxBytes - artifactBytes);
    }

    public synchronized boolean exceeded() {
        return exceeded;
    }

    public synchronized String failureReason() {
        return failureReason;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(maxBytes, artifactBytes, remainingBytes(), exceeded, failureReason);
    }

    /**
     * Writes a normal artifact only when the resulting root stays within the
     * budget. The path must remain below the configured validation root.
     */
    public synchronized void writeBytes(final Path path, final byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Path normalized = checkedPath(path);
        if (exceeded) {
            throw new StorageBudgetExceededException(
                    "Validation artifact budget is already exhausted for " + normalized
            );
        }
        long previous = previousSize(normalized);
        long next = artifactBytes - previous + bytes.length;
        if (next > maxBytes) {
            fail("validation artifact byte budget exceeded", bytes.length, next);
            throw new StorageBudgetExceededException(
                    "Validation artifact budget exceeded at " + normalized
                            + ": requested=" + bytes.length
                            + ", current=" + artifactBytes
                            + ", max=" + maxBytes
            );
        }
        Files.createDirectories(normalized.getParent());
        Files.write(normalized, bytes);
        accountedFiles.put(normalized, (long) bytes.length);
        Long previousCritical = criticalFiles.remove(normalized);
        if (previousCritical != null) {
            criticalBytes -= previousCritical;
        }
        artifactBytes = next;
    }

    public synchronized void writeString(final Path path, final String value) throws IOException {
        writeBytes(path, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes small terminal evidence after the normal artifact budget has been
     * exhausted. This finite reserve is for failure summaries and completion
     * state only; it is never a second capture budget.
     */
    public synchronized void writeCriticalBytes(final Path path, final byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Path normalized = checkedPath(path);
        long previous = previousSize(normalized);
        long previousCritical = criticalFiles.getOrDefault(normalized, 0L);
        long nextCritical = criticalBytes - previousCritical + bytes.length;
        if (nextCritical > MAX_CRITICAL_BYTES) {
            throw new IOException(
                    "Critical validation evidence reserve exceeded at " + normalized
                            + ": requested=" + bytes.length
                            + ", current=" + criticalBytes
                            + ", max=" + MAX_CRITICAL_BYTES
            );
        }
        Files.createDirectories(normalized.getParent());
        Files.write(normalized, bytes);
        accountedFiles.put(normalized, (long) bytes.length);
        criticalFiles.put(normalized, (long) bytes.length);
        criticalBytes = nextCritical;
        artifactBytes = artifactBytes - previous + bytes.length;
    }

    public synchronized void writeCriticalString(final Path path, final String value) throws IOException {
        writeCriticalBytes(path, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Records a compact failure marker once. It is intentionally best effort:
     * if the budget is already completely consumed, the structured status is
     * still available to the caller and no unbounded write is attempted.
     */
    public synchronized void recordFailure(final String reason, final long requestedBytes, final long projectedBytes) {
        fail(reason, requestedBytes, projectedBytes);
        if (failureSummaryWritten) {
            return;
        }
        String escaped = jsonEscape(failureReason == null ? "unknown" : failureReason);
        String summary = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"status\": \"failed\",\n"
                + "  \"reason\": \"" + escaped + "\",\n"
                + "  \"requestedBytes\": " + Math.max(0L, requestedBytes) + ",\n"
                + "  \"projectedBytes\": " + Math.max(0L, projectedBytes) + ",\n"
                + "  \"artifactBytes\": " + artifactBytes + ",\n"
                + "  \"maxArtifactBytes\": " + maxBytes + "\n"
                + "}\n";
        Path summaryPath = root.resolve("storage-budget-failure.json");
        try {
            byte[] bytes = summary.getBytes(StandardCharsets.UTF_8);
            writeCriticalBytes(summaryPath, bytes);
        } catch (IOException ignored) {
            // The status fields are the authoritative failure signal.
        }
        failureSummaryWritten = true;
    }

    private void fail(final String reason, final long requestedBytes, final long projectedBytes) {
        exceeded = true;
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = reason + " (requested=" + requestedBytes
                    + ", projected=" + projectedBytes + ", max=" + maxBytes + ")";
        }
    }

    private long previousSize(final Path path) {
        Long accounted = accountedFiles.get(path);
        if (accounted != null) {
            return accounted;
        }
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private Path checkedPath(final Path path) {
        Path normalized = normalize(Objects.requireNonNull(path, "path"));
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Validation artifact escapes output root: " + normalized);
        }
        return normalized;
    }

    private void scanExistingFiles() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        try {
                            long size = Files.size(path);
                            accountedFiles.put(path.toAbsolutePath().normalize(), size);
                            artifactBytes += size;
                        } catch (IOException ignored) {
                            // A concurrently removed file contributes no bytes.
                        }
                    });
        } catch (IOException exception) {
            exceeded = true;
            failureReason = "could not scan validation output root: " + exception.getMessage();
        }
        if (artifactBytes > maxBytes) {
            exceeded = true;
            failureReason = "existing validation artifacts exceed byte budget";
        }
    }

    private static Path normalize(final Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isManagedTemporaryRoot(final Path root) {
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        if (temporaryDirectory == null || temporaryDirectory.isBlank()) {
            return false;
        }
        Path tempRoot = normalize(Path.of(temporaryDirectory));
        Path candidate = normalize(root);
        while (candidate != null && candidate.startsWith(tempRoot)) {
            Path parent = candidate.getParent();
            if (tempRoot.equals(parent)) {
                String name = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
                for (String prefix : MANAGED_TEMP_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            if (candidate.equals(tempRoot)) {
                break;
            }
            candidate = parent;
        }
        return false;
    }

    private static long longProperty(final String name, final long fallback) {
        try {
            return Long.parseLong(System.getProperty(name, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String jsonEscape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public record Snapshot(
            long maxBytes,
            long artifactBytes,
            long remainingBytes,
            boolean exceeded,
            String failureReason
    ) {
    }

    public static final class StorageBudgetExceededException extends IOException {
        public StorageBudgetExceededException(final String message) {
            super(message);
        }
    }
}
