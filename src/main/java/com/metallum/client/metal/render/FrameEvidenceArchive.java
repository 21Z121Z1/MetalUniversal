package com.metallum.client.metal.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.metallum.client.validation.storage.ValidationStorageBudget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/** Writer for the existing recorder. Bounded frame queues, constant-size hash-chain checkpoints. */
final class FrameEvidenceArchive {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final long MAX_SEGMENTS = 65_536;
    private final FrameEvidenceRecorder recorder;
    private final JsonObject identity;
    private final Path output;
    private final Path directory;
    private final Thread writer;
    private ValidationStorageBudget storage;
    private long committedSegments;
    private long committedFrames;
    private String previousDigest = "";
    private volatile boolean stopping;
    private volatile IOException failure;
    private volatile boolean cleanWriterExit;

    FrameEvidenceArchive(FrameEvidenceRecorder recorder, JsonObject identity, Path output) {
        this.recorder = recorder;
        this.identity = identity.deepCopy();
        this.output = output.toAbsolutePath().normalize();
        this.directory = this.output.resolveSibling(this.output.getFileName() + ".segments");
        writer = new Thread(this::run, "metallum-frame-evidence-export");
        writer.setDaemon(true);
        writer.start();
    }

    private void run() {
        try {
            // Never merge a new session into old evidence. A trial must have a fresh output path.
            Files.createDirectories(output.getParent());
            if (Files.exists(output)) throw new IOException("frame evidence output already exists: " + output);
            Files.createDirectory(directory);
            storage = ValidationStorageBudget.shared(output.getParent());
            checkpoint(false, "unvalidated", report -> { });
            while (!stopping) {
                flush(false);
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) {
                    if (!stopping) throw new IOException("evidence writer interrupted before finalization", interrupted);
                }
            }
            flush(true);
            cleanWriterExit = true;
        } catch (IOException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new IOException("evidence export failed", exception);
        }
    }

    private void flush(boolean shutdown) throws IOException {
        FrameEvidenceRecorder.SegmentSnapshot segment;
        while ((segment = recorder.pollSegment(identity, shutdown)) != null) {
            if (committedSegments >= MAX_SEGMENTS) throw new IOException("frame evidence segment limit exceeded");
            JsonObject report = segment.toJson(); // Detached frames: no recorder lock or render-thread serialization.
            JsonObject metadata = report.getAsJsonObject("segment");
            if (metadata.get("id").getAsLong() != committedSegments + 1) throw new IOException("segment identity gap");
            metadata.addProperty("previousSha256", previousDigest);
            byte[] bytes = (JSON.toJson(report) + "\n").getBytes(StandardCharsets.UTF_8);
            Path target = directory.resolve(String.format(java.util.Locale.ROOT, "%08d.json", committedSegments + 1));
            storage.writeAtomicBytes(target, bytes);
            previousDigest = sha256(bytes);
            committedSegments++;
            committedFrames += metadata.get("frameCount").getAsLong();
            checkpoint(false, "unvalidated", value -> { });
            if (stopping && !shutdown) return;
        }
    }

    /** File flush only. The caller has already stopped the producer and used the existing GPU drain. */
    void finish(String validationStatus, Consumer<JsonObject> completionMetadata) throws IOException {
        stopping = true;
        try { writer.join(30_000); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while finalizing frame evidence", interrupted);
        }
        if (writer.isAlive()) throw new IOException("frame evidence writer did not finish; checkpoint remains incomplete");
        if (failure != null) {
            if (storage != null) {
                storage.recordFailure(failure.toString(), 0, storage.artifactBytes());
            }
            // Do not replace a prior session or turn a failed export into a completed archive.
            throw failure;
        }
        if (!cleanWriterExit) throw new IOException("frame evidence writer terminated without a complete flush");
        checkpoint(true, validationStatus, completionMetadata);
    }

    private void checkpoint(boolean complete, String validationStatus, Consumer<JsonObject> completionMetadata) throws IOException {
        JsonObject report = recorder.archiveState(identity);
        report.addProperty("schemaVersion", 2);
        report.addProperty("validationStatus", validationStatus);
        report.addProperty("shutdownDrained", complete);
        JsonObject archive = new JsonObject();
        archive.addProperty("formatVersion", 1);
        archive.addProperty("directory", directory.getFileName().toString());
        archive.addProperty("committedSegments", committedSegments);
        archive.addProperty("committedFrames", committedFrames);
        archive.addProperty("lastSegmentSha256", previousDigest);
        archive.addProperty("complete", complete);
        archive.addProperty("snapshotReason", complete ? "shutdown-after-existing-GPU-drain" : "checkpoint-process-may-still-be-running-or-incomplete");
        archive.addProperty("clockPolicy", "source nanoTime and native presentedTime remain separate domains");
        report.add("archive", archive);
        completionMetadata.accept(report);
        storage.writeAtomicBytes(output, (JSON.toJson(report) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
