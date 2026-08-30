package com.metallum.client.validation.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.CapturePoint;
import com.metallum.client.validation.expectation.ExpectationContext;
import com.metallum.client.validation.expectation.ExpectationResult;
import com.metallum.client.validation.expectation.ExpectationSpec;
import com.metallum.client.validation.storage.ValidationStorageBudget;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded file-backed capture sink. GPU ownership ends before this service is
 * called: callers pass completed bytes and this class owns only CPU evidence.
 */
public final class FileValidationCaptureService implements ValidationCaptureService {
    public static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private final Path outputDirectory;
    private final Path framesDirectory;
    private final Path resultsPath;
    private final String runId;
    private final String gitCommit;
    private final int maxCaptures;
    private final long maxCaptureBytes;
    private final int maxPending;
    private final ValidationStorageBudget storageBudget;
    private final Map<CaptureKey, PendingCapture> pending = new LinkedHashMap<>();
    private final Map<String, CapturedResource> previousResources = new LinkedHashMap<>();
    private final List<JsonObject> captureResults = new ArrayList<>();
    private long capturedBytes;
    private int completed;
    private int requested;
    private int failed;
    private int dropped;
    private int lateCompletions;
    private String status = "active";
    private boolean closed;

    public FileValidationCaptureService(final Path outputDirectory, final String runId) {
        this(outputDirectory, runId, null);
    }

    public FileValidationCaptureService(
            final Path outputDirectory,
            final String runId,
            final Object ignoredRecorder
    ) {
        this(outputDirectory, runId, ignoredRecorder, null);
    }

    public FileValidationCaptureService(
            final Path outputDirectory,
            final String runId,
            final Object ignoredRecorder,
            final ValidationStorageBudget storageBudget
    ) {
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.framesDirectory = this.outputDirectory.resolve("frames");
        this.resultsPath = this.outputDirectory.resolve("results.json");
        this.runId = requireRunId(runId);
        this.gitCommit = System.getProperty("metallum.validation.sourceCommit", "unknown");
        this.storageBudget = storageBudget == null
                ? ValidationStorageBudget.shared(this.outputDirectory)
                : storageBudget;
        this.maxCaptures = integerProperty("metallum.renderContract.maxCaptures", 4096);
        this.maxCaptureBytes = longProperty(
                "metallum.renderContract.maxCaptureBytes",
                longProperty("metallum.renderContract.maxBytes", this.storageBudget.maxBytes())
        );
        this.maxPending = integerProperty("metallum.renderContract.maxPending", 128);
        if (maxCaptures <= 0 || maxCaptureBytes <= 0L || maxPending <= 0) {
            throw new IllegalArgumentException("Capture budgets must be positive");
        }
        try {
            Files.createDirectories(framesDirectory);
            writeResults();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize capture output", exception);
        }
    }

    @Override
    public synchronized void requestCapture(
            final CapturePoint point,
            final List<AttachmentProbe> probes,
            final List<ExpectationSpec> expectations
    ) {
        ensureOpen();
        Objects.requireNonNull(point, "point");
        CaptureKey key = CaptureKey.from(point);
        if (pending.containsKey(key) || hasCompleted(key)) {
            failRequest(point, "duplicate capture point");
            return;
        }
        if (storageBudget.exceeded()) {
            dropped++;
            failRequest(point, "validation storage budget exceeded: " + storageBudget.failureReason());
            return;
        }
        if (completed + pending.size() >= maxCaptures || pending.size() >= maxPending) {
            dropped++;
            failRequest(point, "capture budget exceeded");
            return;
        }
        if (probes == null || probes.stream().filter(Objects::nonNull).findAny().isEmpty()) {
            failRequest(point, "capture request must contain at least one attachment probe");
            return;
        }
        Map<String, AttachmentProbe> byName = new LinkedHashMap<>();
        for (AttachmentProbe probe : probes) {
            if (probe == null) continue;
            if (byName.put(probe.semanticName(), probe) != null) {
                failRequest(point, "duplicate attachment probe: " + probe.semanticName());
                return;
            }
        }
        pending.put(key, new PendingCapture(
                point,
                List.copyOf(byName.values()),
                expectations == null ? List.of() : List.copyOf(expectations)
        ));
        requested++;
    }

    @Override
    public synchronized void completeCapture(
            final CapturePoint point,
            final List<CapturedResource> resources,
            final List<ExpectationSpec> expectations
    ) {
        Objects.requireNonNull(point, "point");
        if (closed) {
            lateCompletions++;
            failed++;
            status = "failed";
            appendFailure(point, "capture completed after service was closed", Map.of(
                    "lateCompletion", true
            ));
            writeResultsUnchecked();
            return;
        }
        CaptureKey key = CaptureKey.from(point);
        PendingCapture request = pending.remove(key);
        if (request == null) {
            failRequest(point, hasCompleted(key)
                    ? "capture completed more than once"
                    : "capture completed without a prior request");
            return;
        }
        List<CapturedResource> actuals = resources == null ? List.of() : List.copyOf(resources);
        long requestBytes = actuals.stream().mapToLong(resource -> resource.bytes().length).sum();
        if (capturedBytes + requestBytes > maxCaptureBytes) {
            failed++;
            status = "failed";
            appendFailure(point, "capture payload byte budget exceeded", Map.of(
                    "requestBytes", requestBytes,
                    "maxCaptureBytes", maxCaptureBytes
            ));
            writeResultsUnchecked();
            return;
        }
        capturedBytes += requestBytes;
        List<String> resourceValidationErrors = validateResources(request.probes(), actuals);
        boolean passed = resourceValidationErrors.isEmpty();
        JsonObject captureJson = baseCaptureJson(point);
        JsonArray resourcesJson = new JsonArray();
        for (CapturedResource resource : actuals) {
            List<ExpectationSpec> specs = matchingExpectations(
                    request.expectations().isEmpty() ? expectations : request.expectations(),
                    resource.semanticName()
            );
            Path resourceDirectory = resourceDirectory(point, resource.semanticName());
            try {
                Files.createDirectories(resourceDirectory);
                storageBudget.writeBytes(resourceDirectory.resolve("actual.bin"), resource.bytes());
                JsonObject resourceJson = writeResourceArtifacts(
                        point,
                        resource,
                        specs,
                        resourceDirectory
                );
                resourcesJson.add(resourceJson);
                passed &= resourcePassed(resourceJson);
                previousResources.put(resource.semanticName(), resource.copy());
            } catch (IOException | RuntimeException exception) {
                passed = false;
                resourcesJson.add(resourceFailureJson(resource, exception));
            }
        }
        captureJson.add("resources", resourcesJson);
        if (!resourceValidationErrors.isEmpty()) {
            captureJson.add("resourceValidationErrors", GSON.toJsonTree(resourceValidationErrors));
        }
        captureJson.addProperty("status", passed ? "passed" : "failed");
        captureResults.add(captureJson);
        completed++;
        if (!passed) {
            failed++;
            status = "failed";
        }
        writeResultsUnchecked();
    }

    private static List<String> validateResources(
            final List<AttachmentProbe> probes,
            final List<CapturedResource> actuals
    ) {
        Map<String, AttachmentProbe> requestedByName = new LinkedHashMap<>();
        for (AttachmentProbe probe : probes) {
            requestedByName.put(probe.semanticName(), probe);
        }
        Map<String, CapturedResource> actualByName = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (CapturedResource actual : actuals) {
            CapturedResource previous = actualByName.put(actual.semanticName(), actual);
            if (previous != null) {
                errors.add("duplicate actual attachment: " + actual.semanticName());
            }
        }
        for (AttachmentProbe probe : probes) {
            CapturedResource actual = actualByName.get(probe.semanticName());
            if (actual == null) {
                errors.add("missing requested attachment: " + probe.semanticName()
                        + " expected=" + probe.resource().stableKey());
                continue;
            }
            if (!probe.resource().equals(actual.resource())) {
                errors.add("resource identity mismatch: " + probe.semanticName()
                        + " expected=" + probe.resource().stableKey()
                        + " actual=" + actual.resource().stableKey());
            }
            if (!probe.captureFormat().equals(actual.captureFormat())) {
                errors.add("capture format mismatch: " + probe.semanticName()
                        + " expected=" + probe.captureFormat().name()
                        + " actual=" + actual.captureFormat().name());
            }
            if (probe.resource().width() != actual.width() || probe.resource().height() != actual.height()) {
                errors.add("capture dimensions mismatch: " + probe.semanticName()
                        + " expected=" + probe.resource().width() + "x" + probe.resource().height()
                        + " actual=" + actual.width() + "x" + actual.height());
            }
        }
        for (String actualName : actualByName.keySet()) {
            if (!requestedByName.containsKey(actualName)) {
                errors.add("unexpected actual attachment: " + actualName);
            }
        }
        return List.copyOf(errors);
    }

    @Override
    public synchronized void cancelPending(final String reason) {
        if (pending.isEmpty()) return;
        String message = reason == null || reason.isBlank() ? "capture cancelled" : reason;
        for (PendingCapture capture : List.copyOf(pending.values())) {
            appendFailure(capture.point(), message, Map.of("pending", true));
            failed++;
        }
        pending.clear();
        status = "failed";
        writeResultsUnchecked();
    }

    @Override
    public synchronized int pendingCaptures() {
        return pending.size();
    }

    @Override
    public synchronized int completedCaptures() {
        return completed;
    }

    public synchronized int requestedCaptures() {
        return requested;
    }

    @Override
    public synchronized int failedCaptures() {
        return failed;
    }

    public synchronized int droppedCaptures() {
        return dropped;
    }

    public synchronized int lateCompletions() {
        return lateCompletions;
    }

    public synchronized String status() {
        return status;
    }

    /** Marks all future report output failed without changing existing evidence. */
    public synchronized void markFailed() {
        if (closed) return;
        status = "failed";
        writeResultsUnchecked();
    }

    public synchronized long capturedBytes() {
        return capturedBytes;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (!pending.isEmpty()) cancelPending("capture service closed with pending requests");
        closed = true;
        if (!"failed".equals(status)) status = dropped == 0 ? "passed" : "incomplete";
        writeResultsUnchecked();
    }

    public synchronized List<JsonObject> captureResults() {
        return List.copyOf(captureResults);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Validation capture service is closed");
        }
    }

    private ExpectationResult evaluate(
            final ExpectationSpec spec,
            final CapturedResource resource,
            final CapturePoint point
    ) {
        ExpectationContext context = new ExpectationContext(
                point,
                outputDirectory,
                previousResources,
                Map.of("runId", runId)
        );
        return spec.expectation().evaluate(resource, context);
    }

    private JsonObject writeResourceArtifacts(
            final CapturePoint point,
            final CapturedResource resource,
            final List<ExpectationSpec> specs,
            final Path resourceDirectory
    ) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("semanticName", resource.semanticName());
        result.addProperty("resourceId", resource.resource().stableKey());
        result.add("resource", GSON.toJsonTree(resource.resource()));
        result.add("captureFormat", GSON.toJsonTree(resource.captureFormat()));
        result.addProperty("width", resource.width());
        result.addProperty("height", resource.height());
        result.addProperty("actual", relative(resourceDirectory.resolve("actual.bin")));
        result.addProperty("status", "captured");
        if (point.traceIdentity() != null) {
            result.add("traceIdentity", GSON.toJsonTree(point.traceIdentity()));
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("schemaVersion", SCHEMA_VERSION);
        metadata.addProperty("runId", runId);
        metadata.addProperty("gitCommit", gitCommit);
        metadata.addProperty("frameId", point.frameId());
        metadata.addProperty("semanticPassId", point.semanticPassId());
        metadata.addProperty("capturePoint", point.kind().name());
        metadata.addProperty("producerIndex", point.producerIndex());
        if (point.traceIdentity() != null) {
            metadata.add("traceIdentity", GSON.toJsonTree(point.traceIdentity()));
        }
        metadata.add("resource", GSON.toJsonTree(resource.resource()));
        metadata.add("captureFormat", GSON.toJsonTree(resource.captureFormat()));
        storageBudget.writeString(
                resourceDirectory.resolve("metadata.json"),
                GSON.toJson(metadata) + "\n"
        );
        result.addProperty("metadata", relative(resourceDirectory.resolve("metadata.json")));
        writePngIfSupported(resourceDirectory.resolve("actual.png"), resource);
        if (Files.exists(resourceDirectory.resolve("actual.png"))) {
            result.addProperty("actualPng", relative(resourceDirectory.resolve("actual.png")));
        }
        JsonArray expectationsJson = new JsonArray();
        for (ExpectationSpec spec : specs) {
            ExpectationResult expectationResult = evaluate(spec, resource, point);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", spec.id());
            entry.addProperty("resourceSemanticName", spec.resourceSemanticName());
            entry.add("result", GSON.toJsonTree(expectationResult));
            byte[] expected = spec.expectation().expectedBytes();
            if (expected != null) {
                Path expectedPath = resourceDirectory.resolve("expected-" + sanitize(spec.id()) + ".bin");
                storageBudget.writeBytes(expectedPath, expected);
                entry.addProperty("expected", relative(expectedPath));
                if (expected.length == resource.bytes().length) {
                    Path expectedPng = resourceDirectory.resolve("expected-" + sanitize(spec.id()) + ".png");
                    writePngIfSupported(
                            expectedPng,
                            new CapturedResource(
                                    resource.semanticName(), resource.resource(), resource.captureFormat(),
                                    resource.width(), resource.height(), expected
                            )
                    );
                    if (Files.exists(expectedPng)) {
                        entry.addProperty("expectedPng", relative(expectedPng));
                    }
                    byte[] diff = absoluteDiff(resource.bytes(), expected);
                    Path diffPath = resourceDirectory.resolve("diff-" + sanitize(spec.id()) + ".bin");
                    storageBudget.writeBytes(diffPath, diff);
                    entry.addProperty("diff", relative(diffPath));
                    writePngIfSupported(
                            resourceDirectory.resolve("diff-" + sanitize(spec.id()) + ".png"),
                            new CapturedResource(
                                    resource.semanticName(), resource.resource(), resource.captureFormat(),
                                    resource.width(), resource.height(), diff
                            )
                    );
                }
            }
            expectationsJson.add(entry);
        }
        result.add("expectations", expectationsJson);
        Path metricsPath = resourceDirectory.resolve("metrics.json");
        storageBudget.writeString(metricsPath, GSON.toJson(expectationsJson) + "\n");
        result.addProperty("metrics", relative(metricsPath));
        return result;
    }

    private static boolean resourcePassed(final JsonObject resourceJson) {
        if (!resourceJson.has("expectations")) return true;
        for (var element : resourceJson.getAsJsonArray("expectations")) {
            JsonObject expectation = element.getAsJsonObject();
            if (!expectation.getAsJsonObject("result").get("passed").getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    private static byte[] absoluteDiff(final byte[] actual, final byte[] expected) {
        byte[] result = new byte[actual.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Math.abs((actual[index] & 0xff) - (expected[index] & 0xff));
        }
        return result;
    }

    private void writePngIfSupported(final Path path, final CapturedResource resource) throws IOException {
        // PNG is a diagnostic visualization only. Never reinterpret FP16/FP32,
        // integer, depth, or stencil texels as RGBA bytes; their authoritative
        // evidence stays in actual.bin plus the format metadata.
        if (resource.captureFormat().componentType() != CaptureFormat.ComponentType.UINT8
                || !resource.captureFormat().normalized()
                || resource.captureFormat().depth()
                || resource.captureFormat().stencil()) {
            return;
        }
        int bytesPerTexel = resource.captureFormat().bytesPerTexel();
        if (bytesPerTexel != 1 && bytesPerTexel != 3 && bytesPerTexel != 4) return;
        byte[] bytes = resource.bytes();
        BufferedImage image = new BufferedImage(resource.width(), resource.height(), BufferedImage.TYPE_INT_ARGB);
        int offset = 0;
        for (int y = 0; y < resource.height(); y++) {
            for (int x = 0; x < resource.width(); x++) {
                int red;
                int green;
                int blue;
                int alpha = 0xff;
                if (bytesPerTexel == 1) {
                    red = green = blue = bytes[offset] & 0xff;
                } else {
                    red = bytes[offset] & 0xff;
                    green = bytes[offset + 1] & 0xff;
                    blue = bytes[offset + 2] & 0xff;
                    if (bytesPerTexel == 4) alpha = bytes[offset + 3] & 0xff;
                }
                image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
                offset += bytesPerTexel;
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (ImageIO.write(image, "png", encoded)) {
            storageBudget.writeBytes(path, encoded.toByteArray());
        }
    }

    private JsonObject baseCaptureJson(final CapturePoint point) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", SCHEMA_VERSION);
        result.addProperty("runId", runId);
        result.addProperty("gitCommit", gitCommit);
        result.addProperty("frameId", point.frameId());
        result.addProperty("semanticPassId", point.semanticPassId());
        result.addProperty("capturePoint", point.kind().name());
        result.addProperty("producerIndex", point.producerIndex());
        result.addProperty("status", "captured");
        if (point.traceIdentity() != null) {
            result.add("traceIdentity", GSON.toJsonTree(point.traceIdentity()));
        }
        return result;
    }

    private JsonObject resourceFailureJson(final CapturedResource resource, final Exception exception) {
        JsonObject result = new JsonObject();
        result.addProperty("semanticName", resource.semanticName());
        result.addProperty("status", "failed");
        result.addProperty("error", exception.toString());
        return result;
    }

    private void appendFailure(final CapturePoint point, final String message, final Map<String, Object> metrics) {
        JsonObject failure = baseCaptureJson(point);
        failure.addProperty("status", "failed");
        failure.addProperty("error", message);
        failure.add("metrics", GSON.toJsonTree(metrics));
        captureResults.add(failure);
    }

    private void failRequest(final CapturePoint point, final String reason) {
        failed++;
        status = "failed";
        appendFailure(point, reason, Map.of());
        writeResultsUnchecked();
    }

    private boolean hasCompleted(final CaptureKey key) {
        return captureResults.stream()
                .anyMatch(result -> key.frameId == result.get("frameId").getAsLong()
                        && key.semanticPassId.equals(result.get("semanticPassId").getAsString())
                        && key.kind.equals(result.get("capturePoint").getAsString())
                        && key.producerIndex == result.get("producerIndex").getAsInt()
                        && key.traceKey.equals(traceKey(result)));
    }

    private List<ExpectationSpec> matchingExpectations(
            final List<ExpectationSpec> expectations,
            final String semanticName
    ) {
        if (expectations == null || expectations.isEmpty()) return List.of();
        return expectations.stream()
                .filter(Objects::nonNull)
                .filter(spec -> spec.resourceSemanticName().equals(semanticName)
                        || "*".equals(spec.resourceSemanticName()))
                .toList();
    }

    private Path resourceDirectory(final CapturePoint point, final String semanticName) {
        String frame = String.format(java.util.Locale.ROOT, "frame-%06d", point.frameId());
        String pass = point.traceIdentity() == null
                ? sanitize(point.semanticPassId())
                : String.format(
                        java.util.Locale.ROOT,
                        "pass-%06d-%s",
                        point.traceIdentity().passSequence(),
                        sanitize(point.semanticPassId())
                );
        String producer = point.producerIndex() < 0
                ? point.kind().name().toLowerCase(java.util.Locale.ROOT)
                : "producer-" + point.producerIndex();
        return framesDirectory.resolve(frame).resolve(pass).resolve(producer).resolve(sanitize(semanticName));
    }

    private String relative(final Path path) {
        return outputDirectory.relativize(path.toAbsolutePath().normalize()).toString();
    }

    private void writeResultsUnchecked() {
        try {
            writeResults();
        } catch (ValidationStorageBudget.StorageBudgetExceededException exception) {
            status = "failed";
            storageBudget.recordFailure(
                    "capture results could not fit the shared artifact budget",
                    exception.getMessage() == null ? 0L : exception.getMessage().length(),
                    storageBudget.artifactBytes()
            );
        } catch (IOException exception) {
            status = "failed";
            throw new IllegalStateException("Could not write capture results", exception);
        }
    }

    private void writeResults() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("runId", runId);
        root.addProperty("gitCommit", gitCommit);
        root.addProperty("status", status);
        root.addProperty("requestedCaptures", requested);
        root.addProperty("completedCaptures", completed);
        root.addProperty("failedCaptures", failed);
        root.addProperty("pendingCaptures", pending.size());
        root.addProperty("droppedCaptures", dropped);
        root.addProperty("lateCompletions", lateCompletions);
        root.addProperty("capturedBytes", capturedBytes);
        root.addProperty("maxCaptures", maxCaptures);
        root.addProperty("maxCaptureBytes", maxCaptureBytes);
        root.addProperty("artifactBytes", storageBudget.artifactBytes());
        root.addProperty("maxArtifactBytes", storageBudget.maxBytes());
        root.addProperty("storageBudgetExceeded", storageBudget.exceeded());
        if (storageBudget.failureReason() != null) {
            root.addProperty("storageFailureReason", storageBudget.failureReason());
        }
        root.add("captures", GSON.toJsonTree(captureResults));
        storageBudget.writeString(resultsPath, GSON.toJson(root) + "\n");
    }

    private static String sanitize(final String value) {
        String sanitized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private static String requireRunId(final String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("runId must match [A-Za-z0-9._-]+");
        }
        return value;
    }

    private static int integerProperty(final String name, final int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longProperty(final String name, final long fallback) {
        try {
            return Long.parseLong(System.getProperty(name, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String traceKey(final JsonObject result) {
        return result.has("traceIdentity") ? result.get("traceIdentity").toString() : "";
    }

    private static String traceKey(final com.metallum.client.validation.contract.TraceIdentity identity) {
        return identity == null ? "" : GSON.toJson(identity);
    }

    private record CaptureKey(long frameId, String semanticPassId, String kind, int producerIndex, String traceKey) {
        private static CaptureKey from(final CapturePoint point) {
            return new CaptureKey(
                    point.frameId(),
                    point.semanticPassId(),
                    point.kind().name(),
                    point.producerIndex(),
                    FileValidationCaptureService.traceKey(point.traceIdentity())
            );
        }
    }

    private record PendingCapture(
            CapturePoint point,
            List<AttachmentProbe> probes,
            List<ExpectationSpec> expectations
    ) {
    }
}
