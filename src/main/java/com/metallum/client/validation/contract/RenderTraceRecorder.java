package com.metallum.client.validation.contract;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.validation.storage.ValidationStorageBudget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, backend-neutral logical pass recorder. */
public final class RenderTraceRecorder implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    // A Minecraft frame can contain tens of thousands of producers. Compact
    // JSON keeps the manifest inspectable without spending the byte budget on
    // whitespace; the structured fields and schema remain unchanged.
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final Path outputDirectory;
    private final Path manifestPath;
    private final String runId;
    private final String gitCommit;
    private final int maxFrames;
    private final int maxPasses;
    private final int maxProducers;
    private final long maxManifestBytes;
    private final int manifestFlushFrameInterval;
    private final boolean captureProducerDetails;
    private final ProducerCapturePolicy producerCapturePolicy;
    private final ValidationStorageBudget storageBudget;
    private final Map<Long, PassState> openPasses = new LinkedHashMap<>();
    private final List<RenderPassRecord> completedPasses = new ArrayList<>();
    private final Set<Long> frameIds = new HashSet<>();
    private final Map<Long, Integer> frameSequences = new HashMap<>();
    private final Map<String, Long> nextGenerationBySemantic = new HashMap<>();
    private final Map<ResourceKey, ResourceIdentity> resourceIdentities = new LinkedHashMap<>();
    private final Map<String, ResourceIdentity> resourceHistory = new LinkedHashMap<>();
    private final List<ResourceLifecycleEvent> resourceLifecycleEvents = new ArrayList<>();
    private long nextPassToken = 1L;
    private long currentFrame = 0L;
    private long lastManifestFlushFrame = -1L;
    private long producerCount;
    private int droppedEvents;
    private int forcedClosedPassCount;
    private int invalidPassReferenceCount;
    private boolean producerBudgetExceeded;
    private String status = "active";
    private boolean manifestBudgetExceeded;
    private long manifestRequiredBytes;
    private String manifestFailureReason;
    private boolean manifestFinalized;
    private boolean closed;

    public RenderTraceRecorder(final Path outputDirectory, final String runId) {
        this(
                outputDirectory,
                runId,
                System.getProperty("metallum.validation.sourceCommit", "unknown"),
                integerProperty("metallum.renderContract.maxFrames", 2048),
                integerProperty("metallum.renderContract.maxPasses", 100_000),
                integerProperty("metallum.renderContract.maxProducers", 1_000_000),
                null
        );
    }

    public RenderTraceRecorder(
            final Path outputDirectory,
            final String runId,
            final String gitCommit,
            final int maxFrames,
            final int maxPasses,
            final int maxProducers
    ) {
        this(outputDirectory, runId, gitCommit, maxFrames, maxPasses, maxProducers, null);
    }

    public RenderTraceRecorder(
            final Path outputDirectory,
            final String runId,
            final String gitCommit,
            final int maxFrames,
            final int maxPasses,
            final int maxProducers,
            final ValidationStorageBudget storageBudget
    ) {
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.manifestPath = this.outputDirectory.resolve("pass-manifest.json");
        this.runId = requireId(runId, "runId");
        this.gitCommit = gitCommit == null || gitCommit.isBlank() ? "unknown" : gitCommit;
        this.maxManifestBytes = longProperty(
                "metallum.renderContract.maxManifestBytes", 64L * 1024L * 1024L
        );
        this.storageBudget = storageBudget == null
                ? ValidationStorageBudget.shared(this.outputDirectory)
                : storageBudget;
        if (maxFrames <= 0 || maxPasses <= 0 || maxProducers <= 0 || maxManifestBytes <= 0L) {
            throw new IllegalArgumentException("Render contract budgets must be positive");
        }
        this.maxFrames = maxFrames;
        this.maxPasses = maxPasses;
        this.maxProducers = maxProducers;
        this.manifestFlushFrameInterval = integerProperty(
                "metallum.renderContract.manifestFlushFrameInterval", 16
        );
        if (manifestFlushFrameInterval <= 0) {
            throw new IllegalArgumentException("Manifest flush interval must be positive");
        }
        this.producerCapturePolicy = ProducerCapturePolicy.fromSystemProperties(true);
        this.captureProducerDetails = producerCapturePolicy.enabled();
        try {
            Files.createDirectories(this.outputDirectory);
            writeManifest();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize render contract output", exception);
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public synchronized void beginFrame(final long frameId) {
        ensureOpen();
        if (frameId < 0L) {
            throw new IllegalArgumentException("frameId must not be negative");
        }
        if (frameIds.size() >= maxFrames && !frameIds.contains(frameId)) {
            droppedEvents++;
            return;
        }
        currentFrame = frameId;
        frameIds.add(frameId);
        frameSequences.putIfAbsent(frameId, 0);
        manifestFinalized = false;
    }

    public synchronized void endFrame(final long frameId) {
        if (closed) {
            return;
        }
        if (frameId != currentFrame) {
            droppedEvents++;
        }
        if (lastManifestFlushFrame < 0L
                || frameId == 0L
                || frameId < lastManifestFlushFrame
                || frameId - lastManifestFlushFrame >= manifestFlushFrameInterval) {
            writeManifestUnchecked();
            lastManifestFlushFrame = frameId;
        }
    }

    /** Writes the latest manifest immediately for a terminal completion check. */
    public synchronized void flushManifest() {
        if (closed) {
            return;
        }
        writeManifestUnchecked();
        lastManifestFlushFrame = currentFrame;
    }

    public synchronized long beginPass(
            final String semanticPassId,
            final PassType type,
            final List<AttachmentBindingRecord> colorAttachments,
            final AttachmentBindingRecord depthAttachment,
            final AttachmentBindingRecord stencilAttachment,
            final ViewportRecord viewport,
            final ScissorRecord scissor,
            final String pipelineId,
            final List<String> shaderIds,
            final Map<String, String> metadata
    ) {
        ensureOpen();
        if (manifestBudgetExceeded || "failed".equals(status)) {
            droppedEvents++;
            return -1L;
        }
        if (!frameIds.contains(currentFrame)) {
            beginFrame(currentFrame);
        }
        if (completedPasses.size() + openPasses.size() >= maxPasses) {
            droppedEvents++;
            return -1L;
        }
        String passId = semanticPassId == null || semanticPassId.isBlank()
                ? "unclassified/" + shortHash(type + ":" + metadata)
                : semanticPassId;
        int sequence = frameSequences.merge(currentFrame, 1, Integer::sum) - 1;
        TraceIdentity traceIdentity = new TraceIdentity(
                runId,
                currentFrame,
                sequence,
                passId,
                -1,
                commandBufferSubmissionId(metadata)
        );
        long token = nextPassToken++;
        openPasses.put(token, new PassState(
                token,
                currentFrame,
                sequence,
                passId,
                type,
                colorAttachments,
                depthAttachment,
                stencilAttachment,
                viewport,
                scissor,
                pipelineId,
                shaderIds,
                metadata,
                traceIdentity
        ));
        manifestFinalized = false;
        return token;
    }

    public synchronized void updatePipeline(final long passToken, final String pipelineId) {
        PassState pass = openPasses.get(passToken);
        if (pass != null) {
            pass.pipelineId = pipelineId == null || pipelineId.isBlank() ? "unbound" : pipelineId;
            manifestFinalized = false;
        } else {
            markInvalidPassReference(passToken);
        }
    }

    public synchronized void updateShaders(final long passToken, final List<String> shaderIds) {
        PassState pass = openPasses.get(passToken);
        if (pass != null) {
            pass.shaderIds = shaderIds == null ? List.of() : List.copyOf(shaderIds);
            manifestFinalized = false;
        } else {
            markInvalidPassReference(passToken);
        }
    }

    public synchronized void updateScissor(final long passToken, final ScissorRecord scissor) {
        PassState pass = openPasses.get(passToken);
        if (pass != null) {
            pass.scissor = scissor == null ? ScissorRecord.disabled() : scissor;
            manifestFinalized = false;
        } else {
            markInvalidPassReference(passToken);
        }
    }

    public synchronized void updateAttachmentStoreActions(
            final long passToken,
            final Map<Integer, String> colorStoreActions,
            final String depthStoreAction
    ) {
        PassState pass = openPasses.get(passToken);
        if (pass != null) {
            pass.updateAttachmentStoreActions(colorStoreActions, depthStoreAction);
            manifestFinalized = false;
        } else {
            markInvalidPassReference(passToken);
        }
    }

    public synchronized TraceIdentity traceIdentity(final long passToken) {
        PassState pass = openPasses.get(passToken);
        return pass == null ? null : pass.traceIdentity;
    }

    public synchronized void recordProducer(
            final long passToken,
            final ProducerType producerType,
            final String pipelineId,
            final Map<String, String> parameters,
            final Map<String, String> boundResources,
            final List<String> writtenAttachments
    ) {
        PassState pass = openPasses.get(passToken);
        if (pass == null) {
            droppedEvents++;
            markInvalidPassReference(passToken);
            return;
        }
        if (producerCount >= maxProducers) {
            droppedEvents++;
            producerBudgetExceeded = true;
            pass.producerDetailsTruncated = true;
            manifestFinalized = false;
            return;
        }
        int producerIndex = pass.producerCount++;
        pass.producerTypeCounts.merge(producerType, 1, Integer::sum);
        producerCount++;
        if (producerCapturePolicy.captures(pass.semanticPassId, producerIndex, pass.producers.size())) {
            pass.producers.add(new ProducerRecord(
                    producerIndex,
                    producerType,
                    pipelineId == null ? pass.pipelineId : pipelineId,
                    pass.shaderIds,
                    parameters,
                    boundResources,
                    pass.viewport,
                    pass.scissor,
                    writtenAttachments,
                    pass.traceIdentity.forProducer(producerIndex)
            ));
        } else if (producerCapturePolicy.enabled() && producerCapturePolicy.matchesPass(pass.semanticPassId)) {
            pass.producerDetailsTruncated = true;
        }
        manifestFinalized = false;
    }

    public synchronized void endPass(final long passToken) {
        PassState pass = openPasses.remove(passToken);
        if (pass == null) {
            markInvalidPassReference(passToken);
            return;
        }
        completedPasses.add(pass.toRecord(captureProducerDetails, producerCapturePolicy, false));
        manifestFinalized = false;
    }

    public synchronized ResourceIdentity identifyResource(
            final String semanticName,
            final long runtimeId,
            final String debugId,
            final String format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevel,
            final int sampleCount,
            final int usage
    ) {
        ensureOpen();
        String resolvedSemanticName = resolveResourceSemanticName(
                semanticName, runtimeId, debugId, format, width, height, depthOrLayers,
                mipLevel, sampleCount, usage
        );
        String normalizedHandle = debugId == null || debugId.isBlank()
                ? "debug-" + runtimeId
                : debugId;
        for (ResourceIdentity active : resourceIdentities.values()) {
            if (active.runtimeId() == runtimeId
                    && active.semanticName().equals(resolvedSemanticName)
                    && active.nativeHandleHashOrDebugId().equals(normalizedHandle)
                    && active.format().equals(format)
                    && active.width() == width
                    && active.height() == height
                    && active.depthOrLayers() == depthOrLayers
                    && active.mipLevel() == mipLevel
                    && active.sampleCount() == sampleCount
                    && active.usage() == usage) {
                return active;
            }
        }
        long generation = nextGenerationBySemantic.merge(resolvedSemanticName, 1L, Long::sum);
        return identifyResourceWithGeneration(
                resolvedSemanticName,
                runtimeId,
                generation,
                normalizedHandle,
                format,
                width,
                height,
                depthOrLayers,
                mipLevel,
                sampleCount,
                usage
        );
    }

    /**
     * Records a renderer-owned allocation identity without allocating a
     * generation in the validation layer. Production Metal resources use
     * this entry point; the legacy overload above remains for synthetic
     * validation fixtures.
     */
    public synchronized ResourceIdentity identifyAllocation(
            final String semanticName,
            final long allocationId,
            final long generation,
            final String debugId,
            final String format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevel,
            final int sampleCount,
            final int usage
    ) {
        ensureOpen();
        if (allocationId <= 0L || generation <= 0L) {
            throw new IllegalArgumentException("Renderer allocation identity values must be positive");
        }
        String resolvedSemanticName = resolveResourceSemanticName(
                semanticName, allocationId, debugId, format, width, height, depthOrLayers,
                mipLevel, sampleCount, usage
        );
        String normalizedHandle = debugId == null || debugId.isBlank()
                ? "debug-" + allocationId
                : debugId;
        nextGenerationBySemantic.merge(resolvedSemanticName, generation, Math::max);
        return identifyResourceWithGeneration(
                resolvedSemanticName,
                allocationId,
                generation,
                normalizedHandle,
                format,
                width,
                height,
                depthOrLayers,
                mipLevel,
                sampleCount,
                usage
        );
    }

    private ResourceIdentity identifyResourceWithGeneration(
            final String resolvedSemanticName,
            final long runtimeId,
            final long generation,
            final String normalizedHandle,
            final String format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevel,
            final int sampleCount,
            final int usage
    ) {
        ResourceKey key = new ResourceKey(
                resolvedSemanticName,
                runtimeId,
                generation,
                normalizedHandle,
                format,
                width,
                height,
                depthOrLayers,
                mipLevel,
                sampleCount,
                usage
        );
        ResourceIdentity existing = resourceIdentities.get(key);
        if (existing != null) {
            return existing;
        }
        ResourceIdentity identity = new ResourceIdentity(
                resolvedSemanticName,
                runtimeId,
                generation,
                normalizedHandle,
                format,
                width,
                height,
                depthOrLayers,
                mipLevel,
                sampleCount,
                usage
        );
        resourceIdentities.put(key, identity);
        resourceHistory.put(identity.stableKey(), identity);
        resourceLifecycleEvents.add(new ResourceLifecycleEvent("ALLOCATE", identity));
        return identity;
    }

    /**
     * Ends the current allocation represented by the supplied resource
     * description. The next lookup of the same runtime/debug handle and shape
     * receives a new generation. Historical identities remain in the manifest
     * so completed passes can still be interpreted after resize or teardown.
     */
    public synchronized void invalidateResource(
            final String semanticName,
            final long runtimeId,
            final String debugId,
            final String format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevel,
            final int sampleCount,
            final int usage
    ) {
        ensureOpen();
        String resolvedSemanticName = resolveResourceSemanticName(
                semanticName, runtimeId, debugId, format, width, height, depthOrLayers,
                mipLevel, sampleCount, usage
        );
        String normalizedHandle = debugId == null || debugId.isBlank()
                ? "debug-" + runtimeId
                : debugId;
        ResourceIdentity removed = null;
        for (var iterator = resourceIdentities.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            ResourceIdentity active = entry.getValue();
            if (active.runtimeId() == runtimeId
                    && active.semanticName().equals(resolvedSemanticName)
                    && active.nativeHandleHashOrDebugId().equals(normalizedHandle)
                    && active.format().equals(format)
                    && active.width() == width
                    && active.height() == height
                    && active.depthOrLayers() == depthOrLayers
                    && active.mipLevel() == mipLevel
                    && active.sampleCount() == sampleCount
                    && active.usage() == usage) {
                removed = active;
                iterator.remove();
                break;
            }
        }
        if (removed != null) {
            resourceLifecycleEvents.add(new ResourceLifecycleEvent("INVALIDATE", removed));
            manifestFinalized = false;
        }
    }

    /** Ends a previously returned identity without reconstructing its key. */
    public synchronized void invalidateResource(final ResourceIdentity identity) {
        ensureOpen();
        if (identity == null) return;
        ResourceIdentity removed = null;
        for (var iterator = resourceIdentities.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (entry.getValue().equals(identity)) {
                removed = entry.getValue();
                iterator.remove();
                break;
            }
        }
        if (removed != null) {
            resourceLifecycleEvents.add(new ResourceLifecycleEvent("INVALIDATE", removed));
            manifestFinalized = false;
        }
    }

    /** Ends every active view/mip identity belonging to one native allocation. */
    public synchronized void invalidateResourceAllocations(
            final long runtimeId,
            final String debugId
    ) {
        ensureOpen();
        if (runtimeId <= 0L) return;
        String normalizedHandle = debugId == null || debugId.isBlank()
                ? "debug-" + runtimeId
                : debugId;
        List<ResourceIdentity> removed = new ArrayList<>();
        for (var iterator = resourceIdentities.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            ResourceIdentity identity = entry.getValue();
            if (identity.runtimeId() == runtimeId
                    && identity.nativeHandleHashOrDebugId().equals(normalizedHandle)) {
                removed.add(identity);
                iterator.remove();
            }
        }
        for (ResourceIdentity identity : removed) {
            resourceLifecycleEvents.add(new ResourceLifecycleEvent("INVALIDATE", identity));
        }
        if (!removed.isEmpty()) {
            manifestFinalized = false;
        }
    }

    public synchronized List<RenderPassRecord> completedPasses() {
        return List.copyOf(completedPasses);
    }

    public synchronized int droppedEvents() {
        return droppedEvents;
    }

    public synchronized int forcedClosedPassCount() {
        return forcedClosedPassCount;
    }

    public synchronized int invalidPassReferenceCount() {
        return invalidPassReferenceCount;
    }

    public synchronized boolean producerBudgetExceeded() {
        return producerBudgetExceeded;
    }

    /**
     * Integrity gate usable while the client is still rendering. A live
     * recorder is intentionally not manifest-complete until close(), but a
     * running validation may still settle captures before shutdown.
     */
    public synchronized boolean traceIntegrityHealthy() {
        return !"failed".equals(status)
                && !"incomplete".equals(status)
                && forcedClosedPassCount == 0
                && invalidPassReferenceCount == 0
                && !producerBudgetExceeded;
    }

    public synchronized boolean manifestComplete() {
        return closed
                && !"failed".equals(status)
                && openPasses.isEmpty()
                && !manifestBudgetExceeded
                && droppedEvents == 0
                && forcedClosedPassCount == 0
                && invalidPassReferenceCount == 0
                && !producerBudgetExceeded;
    }

    public synchronized int frameCount() {
        return frameIds.size();
    }

    public synchronized int openPasses() {
        return openPasses.size();
    }

    public synchronized String status() {
        return status;
    }

    public synchronized boolean producerDetailsCaptured() {
        return captureProducerDetails;
    }

    public synchronized boolean manifestFinalized() {
        return manifestFinalized && !manifestBudgetExceeded && Files.isRegularFile(manifestPath);
    }

    public synchronized void markFailed() {
        status = "failed";
        writeManifestUnchecked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        for (Long token : List.copyOf(openPasses.keySet())) {
            PassState pass = openPasses.remove(token);
            if (pass != null) {
                forcedClosedPassCount++;
                completedPasses.add(pass.toRecord(captureProducerDetails, producerCapturePolicy, true));
            }
        }
        closed = true;
        if (!"failed".equals(status)) {
            status = manifestComplete() ? "passed" : "incomplete";
        }
        writeManifestUnchecked();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Render contract recorder is closed");
        }
    }

    private void markInvalidPassReference(final long passToken) {
        if (passToken >= 0L) {
            invalidPassReferenceCount++;
            status = "failed";
            manifestFinalized = false;
        }
    }

    private void writeManifestUnchecked() {
        try {
            writeManifest();
        } catch (IOException exception) {
            status = "failed";
            throw new IllegalStateException("Could not write render contract manifest", exception);
        }
    }

    private void writeManifest() throws IOException {
        manifestFinalized = false;
        if (manifestBudgetExceeded) {
            writeManifestFailureSummary(manifestRequiredBytes, manifestFailureReason);
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("runId", runId);
        root.addProperty("gitCommit", gitCommit);
        root.addProperty("status", status);
        root.addProperty("manifestComplete", manifestComplete());
        root.addProperty("frameCount", frameIds.size());
        root.addProperty("passCount", completedPasses.size() + openPasses.size());
        root.addProperty("resourceCount", resourceHistory.size());
        root.addProperty("droppedEvents", droppedEvents);
        root.addProperty("forcedClosedPassCount", forcedClosedPassCount);
        root.addProperty("invalidPassReferenceCount", invalidPassReferenceCount);
        root.addProperty("producerBudgetExceeded", producerBudgetExceeded);
        root.addProperty("producerCount", producerCount);
        root.addProperty("producerDetailsCaptured", captureProducerDetails);
        root.addProperty("producerCapturePolicy", producerCapturePolicy.descriptor());
        JsonObject limits = new JsonObject();
        limits.addProperty("maxFrames", maxFrames);
        limits.addProperty("maxPasses", maxPasses);
        limits.addProperty("maxProducers", maxProducers);
        limits.addProperty("maxManifestBytes", maxManifestBytes);
        root.add("limits", limits);
        root.add("storageBudget", GSON.toJsonTree(storageBudget.snapshot()));
        root.add("frames", GSON.toJsonTree(frameIds.stream().sorted().toList()));
        root.add("resources", GSON.toJsonTree(resourceHistory.values()));
        root.add("resourceLifecycle", GSON.toJsonTree(resourceLifecycleEvents));
        root.add("passes", GSON.toJsonTree(completedPasses));
        JsonArray open = new JsonArray();
        for (PassState pass : openPasses.values()) {
            open.add(GSON.toJsonTree(pass.toRecord(captureProducerDetails, producerCapturePolicy, false)));
        }
        root.add("openPasses", open);
        byte[] bytes = (GSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxManifestBytes) {
            manifestBudgetExceeded = true;
            status = "failed";
            droppedEvents++;
            manifestRequiredBytes = bytes.length;
            manifestFailureReason = "render contract manifest byte budget exceeded";
            writeManifestFailureSummary(bytes.length, "render contract manifest byte budget exceeded");
            return;
        }
        try {
            storageBudget.writeBytes(manifestPath, bytes);
            manifestFinalized = true;
        } catch (ValidationStorageBudget.StorageBudgetExceededException exception) {
            manifestBudgetExceeded = true;
            status = "failed";
            droppedEvents++;
            manifestRequiredBytes = bytes.length;
            manifestFailureReason = "render contract manifest could not fit the shared artifact budget";
            storageBudget.recordFailure(
                    "render contract manifest could not fit the shared artifact budget",
                    bytes.length,
                    storageBudget.artifactBytes() + bytes.length
            );
            writeManifestFailureSummary(bytes.length, "render contract manifest could not fit the shared artifact budget");
        }
    }

    private void writeManifestFailureSummary(final long requiredBytes, final String reason) {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", SCHEMA_VERSION);
        summary.addProperty("runId", runId);
        summary.addProperty("gitCommit", gitCommit);
        summary.addProperty("status", "failed");
        summary.addProperty("manifestComplete", false);
        summary.addProperty("manifestFailureReason", reason == null ? "unknown" : reason);
        summary.addProperty("requiredManifestBytes", requiredBytes);
        summary.addProperty("frameCount", frameIds.size());
        summary.addProperty("passCount", completedPasses.size() + openPasses.size());
        summary.addProperty("resourceCount", resourceHistory.size());
        summary.addProperty("droppedEvents", droppedEvents);
        summary.addProperty("forcedClosedPassCount", forcedClosedPassCount);
        summary.addProperty("invalidPassReferenceCount", invalidPassReferenceCount);
        summary.addProperty("producerBudgetExceeded", producerBudgetExceeded);
        summary.addProperty("producerCount", producerCount);
        summary.addProperty("producerDetailsCaptured", captureProducerDetails);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxFrames", maxFrames);
        limits.addProperty("maxPasses", maxPasses);
        limits.addProperty("maxProducers", maxProducers);
        limits.addProperty("maxManifestBytes", maxManifestBytes);
        summary.add("limits", limits);
        summary.add("storageBudget", GSON.toJsonTree(storageBudget.snapshot()));
        summary.add("frames", GSON.toJsonTree(frameIds.stream().sorted().toList()));
        summary.add("resources", GSON.toJsonTree(resourceHistory.values()));
        summary.add("resourceLifecycle", GSON.toJsonTree(resourceLifecycleEvents));
        summary.add("passes", new JsonArray());
        summary.add("openPasses", new JsonArray());
        try {
            storageBudget.writeCriticalString(manifestPath, GSON.toJson(summary) + "\n");
        } catch (IOException ignored) {
            // The completion gate remains closed; the terminal run-state and
            // storage failure marker are the next bounded evidence paths.
        }
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

    private static String requireId(final String value, final String field) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " must match [A-Za-z0-9._-]+");
        }
        return value;
    }

    private static String shortHash(final String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String resolveResourceSemanticName(
            final String semanticName,
            final long runtimeId,
            final String debugId,
            final String format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevel,
            final int sampleCount,
            final int usage
    ) {
        return semanticName == null || semanticName.isBlank()
                ? "unclassified/" + shortHash(
                        String.valueOf(runtimeId) + ':' + String.valueOf(debugId) + ':' + String.valueOf(format)
                                + ':' + width + 'x' + height + 'x' + depthOrLayers
                                + ':' + mipLevel + ':' + sampleCount + ':' + usage
                )
                : semanticName;
    }

    private record ResourceKey(
            String semanticName,
            long runtimeId,
            long generation,
            String nativeHandleHashOrDebugId,
            String format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevel,
            int sampleCount,
            int usage
    ) {
    }

    private record ResourceLifecycleEvent(String action, ResourceIdentity resource) {
    }

    private static final class PassState {
        private final long token;
        private final long frameId;
        private final int sequence;
        private final String semanticPassId;
        private final PassType type;
        private final List<AttachmentBindingRecord> colorAttachments;
        private AttachmentBindingRecord depthAttachment;
        private final AttachmentBindingRecord stencilAttachment;
        private final ViewportRecord viewport;
        private ScissorRecord scissor;
        private String pipelineId;
        private List<String> shaderIds;
        private final List<ProducerRecord> producers = new ArrayList<>();
        private int producerCount;
        private final Map<ProducerType, Integer> producerTypeCounts = new EnumMap<>(ProducerType.class);
        private final Map<String, String> metadata;
        private final TraceIdentity traceIdentity;

        private PassState(
                final long token,
                final long frameId,
                final int sequence,
                final String semanticPassId,
                final PassType type,
                final List<AttachmentBindingRecord> colorAttachments,
                final AttachmentBindingRecord depthAttachment,
                final AttachmentBindingRecord stencilAttachment,
                final ViewportRecord viewport,
                final ScissorRecord scissor,
                final String pipelineId,
                final List<String> shaderIds,
                final Map<String, String> metadata,
                final TraceIdentity traceIdentity
        ) {
            this.token = token;
            this.frameId = frameId;
            this.sequence = sequence;
            this.semanticPassId = semanticPassId;
            this.type = type;
            this.colorAttachments = colorAttachments == null
                    ? new ArrayList<>()
                    : new ArrayList<>(colorAttachments);
            this.depthAttachment = depthAttachment;
            this.stencilAttachment = stencilAttachment;
            this.viewport = viewport == null ? new ViewportRecord(0, 0, 0, 0) : viewport;
            this.scissor = scissor == null ? ScissorRecord.disabled() : scissor;
            this.pipelineId = pipelineId == null || pipelineId.isBlank() ? "unbound" : pipelineId;
            this.shaderIds = shaderIds == null ? List.of() : List.copyOf(shaderIds);
            this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            this.traceIdentity = traceIdentity;
        }

        private boolean producerDetailsTruncated;

        private void updateAttachmentStoreActions(
                final Map<Integer, String> colorStoreActions,
                final String depthStoreAction
        ) {
            if (colorStoreActions != null && !colorStoreActions.isEmpty()) {
                for (int index = 0; index < colorAttachments.size(); index++) {
                    AttachmentBindingRecord attachment = colorAttachments.get(index);
                    String storeAction = colorStoreActions.get(attachment.slot());
                    if (storeAction != null) {
                        colorAttachments.set(index, new AttachmentBindingRecord(
                                attachment.slot(),
                                attachment.resource(),
                                attachment.semantic(),
                                attachment.loadAction(),
                                storeAction,
                                attachment.writable()
                        ));
                    }
                }
            }
            if (depthAttachment != null && depthStoreAction != null) {
                depthAttachment = new AttachmentBindingRecord(
                        depthAttachment.slot(),
                        depthAttachment.resource(),
                        depthAttachment.semantic(),
                        depthAttachment.loadAction(),
                        depthStoreAction,
                        depthAttachment.writable()
                );
            }
        }

        private RenderPassRecord toRecord(
                final boolean producerDetailsCaptured,
                final ProducerCapturePolicy producerCapturePolicy,
                final boolean forcedClose
        ) {
            Map<String, String> recordMetadata = new LinkedHashMap<>(metadata);
            recordMetadata.put("producerCount", Integer.toString(producerCount));
            boolean detailsSelected = producerDetailsCaptured && producerCapturePolicy.matchesPass(semanticPassId);
            boolean detailsComplete = producerCapturePolicy.completeForPass(
                    semanticPassId, producerCount, producerDetailsTruncated
            );
            boolean detailsCaptured = producerDetailsCaptured && (detailsComplete || !producers.isEmpty());
            recordMetadata.put("producerDetailsSelected", Boolean.toString(detailsSelected));
            recordMetadata.put("producerDetailsCaptured", Boolean.toString(detailsCaptured));
            recordMetadata.put("producerDetailsComplete", Boolean.toString(detailsComplete));
            recordMetadata.put("producerDetailsTruncated", Boolean.toString(producerDetailsTruncated));
            recordMetadata.put("forcedClose", Boolean.toString(forcedClose));
            recordMetadata.put("producerCapturePolicy", producerCapturePolicy.descriptor());
            recordMetadata.put("traceRunId", traceIdentity.runId());
            recordMetadata.put("traceFrameId", Long.toString(traceIdentity.frameId()));
            recordMetadata.put("tracePassSequence", Integer.toString(traceIdentity.passSequence()));
            recordMetadata.put("traceSemanticPassId", traceIdentity.semanticPassId());
            recordMetadata.put("traceProducerIndex", Integer.toString(traceIdentity.producerIndex()));
            recordMetadata.put(
                    "traceCommandBufferSubmissionId",
                    Long.toString(traceIdentity.commandBufferSubmissionId())
            );
            if (!producerTypeCounts.isEmpty()) {
                StringBuilder counts = new StringBuilder();
                for (Map.Entry<ProducerType, Integer> entry : producerTypeCounts.entrySet()) {
                    if (counts.length() > 0) counts.append(',');
                    counts.append(entry.getKey().name()).append('=').append(entry.getValue());
                }
                recordMetadata.put("producerTypeCounts", counts.toString());
            }
            return new RenderPassRecord(
                    frameId,
                    sequence,
                    semanticPassId,
                    type,
                    List.copyOf(colorAttachments),
                    depthAttachment,
                    stencilAttachment,
                    viewport,
                    scissor,
                    pipelineId,
                    shaderIds,
                    producers,
                    recordMetadata,
                    traceIdentity
            );
        }
    }

    private static long commandBufferSubmissionId(final Map<String, String> metadata) {
        if (metadata == null) {
            return -1L;
        }
        String value = metadata.get("commandBufferSubmissionId");
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
