package com.metallum.client.validation.contract;

import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.capture.AttachmentProbe;
import com.metallum.client.validation.capture.FileValidationCaptureService;
import com.metallum.client.validation.expectation.ExpectationSpec;
import com.metallum.client.validation.storage.ValidationStorageBudget;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Process-local bridge from renderer code into the opt-in contract recorder. */
public final class RenderContractRuntime {
    private static volatile RenderTraceRecorder recorder;
    private static volatile FileValidationCaptureService captureService;
    private static volatile ValidationStorageBudget storageBudget;
    private static volatile boolean shutdownHookInstalled;
    private static volatile Snapshot lastSnapshot = Snapshot.disabled();
    private static volatile long currentFrameId = -1L;
    private static volatile long requestedFinalDrawableFrame = -1L;

    private RenderContractRuntime() {
    }

    public static boolean enabled() {
        return recorder != null;
    }

    public static boolean producerDetailsCaptured() {
        RenderTraceRecorder current = recorder;
        return current != null && current.producerDetailsCaptured();
    }

    public static synchronized void start(final Path validationOutput, final String requestedRunId) {
        if (!Boolean.parseBoolean(System.getProperty("metallum.renderContract.enabled", "false"))) {
            return;
        }
        close();
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? System.getProperty("metallum.renderContract.runId", "minecraft-current")
                : requestedRunId;
        ValidationStorageBudget budget = ValidationStorageBudget.shared(validationOutput);
        storageBudget = budget;
        recorder = new RenderTraceRecorder(
                validationOutput.resolve("render-contract"),
                runId,
                System.getProperty("metallum.validation.sourceCommit", "unknown"),
                integerProperty("metallum.renderContract.maxFrames", 2048),
                integerProperty("metallum.renderContract.maxPasses", 100_000),
                integerProperty("metallum.renderContract.maxProducers", 1_000_000),
                budget
        );
        captureService = new FileValidationCaptureService(
                validationOutput.resolve("render-contract"),
                runId,
                recorder,
                budget
        );
        currentFrameId = -1L;
        requestedFinalDrawableFrame = -1L;
        lastSnapshot = snapshotOf(recorder, captureService, true);
        if (!shutdownHookInstalled) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(RenderContractRuntime::close, "metallum-render-contract-close"));
        }
    }

    public static void beginFrame(final long frameId) {
        currentFrameId = frameId;
        RenderTraceRecorder current = recorder;
        if (current != null) {
            current.beginFrame(frameId);
        }
    }

    public static void endFrame(final long frameId) {
        RenderTraceRecorder current = recorder;
        if (current != null) {
            current.endFrame(frameId);
        }
    }

    /** Forces the latest logical pass state to disk before a terminal gate reads it. */
    public static synchronized void flushManifest() {
        RenderTraceRecorder current = recorder;
        if (current != null) {
            current.flushManifest();
        }
    }

    public static long currentFrameId() {
        return currentFrameId < 0L ? 0L : currentFrameId;
    }

    public static synchronized void requestFinalDrawableCapture(final long frameId) {
        if (recorder != null && frameId >= 0L) {
            requestedFinalDrawableFrame = frameId;
        }
    }

    public static synchronized boolean consumeFinalDrawableCapture(final long frameId) {
        if (recorder == null) {
            return false;
        }
        boolean capture = Boolean.parseBoolean(
                System.getProperty("metallum.renderContract.captureFinalDrawable", "false")
        ) || requestedFinalDrawableFrame == frameId;
        if (capture && requestedFinalDrawableFrame == frameId) {
            requestedFinalDrawableFrame = -1L;
        }
        return capture;
    }

    public static long beginRenderPass(
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
        RenderTraceRecorder current = recorder;
        return current == null ? -1L : current.beginPass(
                semanticPassId,
                type,
                colorAttachments,
                depthAttachment,
                stencilAttachment,
                viewport,
                scissor,
                pipelineId,
                shaderIds,
                metadata
        );
    }

    public static void updatePipeline(final long passToken, final String pipelineId) {
        RenderTraceRecorder current = recorder;
        if (current != null && passToken >= 0L) {
            current.updatePipeline(passToken, pipelineId);
        }
    }

    public static void updateShaders(final long passToken, final List<String> shaderIds) {
        RenderTraceRecorder current = recorder;
        if (current != null && passToken >= 0L) {
            current.updateShaders(passToken, shaderIds);
        }
    }

    public static void updateScissor(final long passToken, final ScissorRecord scissor) {
        RenderTraceRecorder current = recorder;
        if (current != null && passToken >= 0L) {
            current.updateScissor(passToken, scissor);
        }
    }

    public static TraceIdentity traceIdentity(final long passToken) {
        RenderTraceRecorder current = recorder;
        return current == null || passToken < 0L ? null : current.traceIdentity(passToken);
    }

    public static CapturePoint capturePointForPass(
            final long passToken,
            final CapturePointKind kind,
            final int producerIndex
    ) {
        TraceIdentity identity = traceIdentity(passToken);
        if (identity == null) {
            return null;
        }
        TraceIdentity captureIdentity = producerIndex < 0
                ? identity
                : identity.forProducer(producerIndex);
        return new CapturePoint(
                captureIdentity.frameId(),
                captureIdentity.semanticPassId(),
                kind,
                producerIndex,
                captureIdentity
        );
    }

    public static void recordProducer(
            final long passToken,
            final ProducerType producerType,
            final String pipelineId,
            final Map<String, String> parameters,
            final Map<String, String> boundResources,
            final List<String> writtenAttachments
    ) {
        RenderTraceRecorder current = recorder;
        if (current != null && passToken >= 0L) {
            current.recordProducer(
                    passToken,
                    producerType,
                    pipelineId,
                    parameters,
                    boundResources,
                    writtenAttachments
            );
        }
    }

    public static void endPass(final long passToken) {
        RenderTraceRecorder current = recorder;
        if (current != null && passToken >= 0L) {
            current.endPass(passToken);
        }
    }

    /** Records a logical transfer/resolve/mipmap/present operation without imposing a native encoder shape. */
    public static void recordTransfer(
            final PassType passType,
            final String semanticPassId,
            final ProducerType producerType,
            final List<ResourceIdentity> writtenResources,
            final Map<String, String> parameters,
            final Map<String, String> boundResources
    ) {
        if (!enabled()) {
            return;
        }
        List<AttachmentBindingRecord> attachments = new java.util.ArrayList<>();
        int width = 1;
        int height = 1;
        int slot = 0;
        if (writtenResources != null) {
            for (ResourceIdentity resource : writtenResources) {
                if (resource == null) continue;
                attachments.add(new AttachmentBindingRecord(
                        slot++, resource, AttachmentSemantic.STORAGE, "load", "store", true
                ));
                width = Math.max(width, resource.width());
                height = Math.max(height, resource.height());
            }
        }
        long token = beginRenderPass(
                semanticPassId,
                passType,
                attachments,
                null,
                null,
                new ViewportRecord(0, 0, width, height),
                ScissorRecord.disabled(),
                "unbound",
                List.of(),
                parameters == null ? Map.of() : parameters
        );
        recordProducer(token, producerType, "unbound", parameters, boundResources, writtenResources == null
                ? List.of()
                : writtenResources.stream().filter(java.util.Objects::nonNull).map(ResourceIdentity::stableKey).toList());
        endPass(token);
    }

    public static ResourceIdentity identifyResource(
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
        RenderTraceRecorder current = recorder;
        if (current == null) {
            return null;
        }
        return current.identifyResource(
                semanticName,
                runtimeId,
                debugId,
                format,
                width,
                height,
                depthOrLayers,
                mipLevel,
                sampleCount,
                usage
        );
    }

    /** Ends all contract identities associated with a released backend allocation. */
    public static void invalidateResourceAllocations(final long runtimeId, final String debugId) {
        RenderTraceRecorder current = recorder;
        if (current != null && runtimeId > 0L) {
            current.invalidateResourceAllocations(runtimeId, debugId);
        }
    }

    public static void recordReadback(
            final CapturePoint point,
            final String semanticName,
            final long runtimeId,
            final String debugId,
            final String formatName,
            final int bytesPerTexel,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevel,
            final int sampleCount,
            final int usage,
            final byte[] bytes,
            final List<ExpectationSpec> expectations
    ) {
        recordReadbacks(
                point,
                List.of(new ReadbackData(
                        semanticName,
                        runtimeId,
                        debugId,
                        formatName,
                        bytesPerTexel,
                        width,
                        height,
                        depthOrLayers,
                        mipLevel,
                        sampleCount,
                        usage,
                        bytes
                )),
                expectations
        );
    }

    public static void requestReadbacks(
            final CapturePoint point,
            final List<ReadbackRequest> readbacks,
            final List<ExpectationSpec> expectations
    ) {
        RenderTraceRecorder current = recorder;
        FileValidationCaptureService service = captureService;
        if (current == null || service == null || readbacks == null || readbacks.isEmpty()) {
            return;
        }
        List<AttachmentProbe> probes = new java.util.ArrayList<>();
        for (ReadbackRequest readback : readbacks) {
            ResourceIdentity resource = current.identifyResource(
                    readback.semanticName(),
                    readback.runtimeId(),
                    readback.debugId(),
                    readback.formatName(),
                    readback.width(),
                    readback.height(),
                    readback.depthOrLayers(),
                    readback.mipLevel(),
                    readback.sampleCount(),
                    readback.usage()
            );
            probes.add(AttachmentProbe.of(
                    readback.semanticName(),
                    resource,
                    readback.semantic(),
                    CaptureFormat.fromFormat(readback.formatName(), readback.bytesPerTexel())
            ));
        }
        service.requestCapture(point, probes, expectations == null ? List.of() : expectations);
    }

    public static void recordReadbacks(
            final CapturePoint point,
            final List<ReadbackData> readbacks,
            final List<ExpectationSpec> expectations
    ) {
        RenderTraceRecorder current = recorder;
        FileValidationCaptureService service = captureService;
        if (current == null || service == null || readbacks == null || readbacks.isEmpty()) {
            return;
        }
        List<CapturedResource> captured = new java.util.ArrayList<>();
        for (ReadbackData readback : readbacks) {
            ResourceIdentity resource = current.identifyResource(
                    readback.semanticName(),
                    readback.runtimeId(),
                    readback.debugId(),
                    readback.formatName(),
                    readback.width(),
                    readback.height(),
                    readback.depthOrLayers(),
                    readback.mipLevel(),
                    readback.sampleCount(),
                    readback.usage()
            );
            captured.add(new CapturedResource(
                    readback.semanticName(),
                    resource,
                    CaptureFormat.fromFormat(readback.formatName(), readback.bytesPerTexel()),
                    readback.width(),
                    readback.height(),
                    readback.bytes()
            ));
        }
        service.completeCapture(point, captured, expectations == null ? List.of() : expectations);
    }

    public record ReadbackData(
            String semanticName,
            long runtimeId,
            String debugId,
            String formatName,
            int bytesPerTexel,
            int width,
            int height,
            int depthOrLayers,
            int mipLevel,
            int sampleCount,
            int usage,
            byte[] bytes
    ) {
        public ReadbackData {
            if (semanticName == null || semanticName.isBlank() || runtimeId <= 0L
                    || debugId == null || debugId.isBlank() || formatName == null || formatName.isBlank()
                    || bytesPerTexel <= 0 || width <= 0 || height <= 0 || depthOrLayers <= 0
                    || mipLevel < 0 || sampleCount <= 0 || usage < 0 || bytes == null) {
                throw new IllegalArgumentException("Invalid validation readback");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public static synchronized void markFailed() {
        RenderTraceRecorder current = recorder;
        if (current != null) {
            current.markFailed();
        }
        FileValidationCaptureService service = captureService;
        if (service != null) {
            service.markFailed();
        }
    }

    public static synchronized Snapshot snapshot() {
        if (recorder == null || captureService == null) {
            return lastSnapshot;
        }
        lastSnapshot = snapshotOf(recorder, captureService, true);
        return lastSnapshot;
    }

    public static synchronized boolean completionGatePassed() {
        flushManifest();
        return snapshot().ready();
    }

    public static synchronized void close() {
        FileValidationCaptureService service = captureService;
        RenderTraceRecorder current = recorder;
        if (service == null && current == null) {
            return;
        }
        if (service != null) {
            service.close();
        }
        if (current != null) {
            current.close();
        }
        lastSnapshot = snapshotOf(current, service, false);
        captureService = null;
        recorder = null;
        storageBudget = null;
        requestedFinalDrawableFrame = -1L;
    }

    private static Snapshot snapshotOf(
            final RenderTraceRecorder current,
            final FileValidationCaptureService service,
            final boolean active
    ) {
        if (current == null || service == null) {
            return Snapshot.disabled();
        }
        int requested = service.requestedCaptures();
        int completed = service.completedCaptures();
        int failed = service.failedCaptures();
        int pending = service.pendingCaptures();
        int dropped = service.droppedCaptures();
        int passes = current.completedPasses().size();
        int droppedEvents = current.droppedEvents();
        ValidationStorageBudget.Snapshot storage = (storageBudget != null
                ? storageBudget
                : ValidationStorageBudget.shared(current.outputDirectory())).snapshot();
        boolean recorderHealthy = current.traceIntegrityHealthy();
        boolean traceComplete = active ? current.traceIntegrityHealthy() : current.manifestComplete();
        boolean captureServiceHealthy = !"failed".equals(service.status());
        boolean ready = requested > 0 && pending == 0 && failed == 0 && dropped == 0
                && droppedEvents == 0 && current.openPasses() == 0 && passes > 0
                && current.manifestFinalized() && traceComplete && !storage.exceeded()
                && recorderHealthy && captureServiceHealthy;
        String status = active
                ? (ready ? "ready" : failed > 0 || dropped > 0 || droppedEvents > 0
                || storage.exceeded() || !recorderHealthy || !captureServiceHealthy || !traceComplete
                ? "failed" : "active")
                : (ready && "passed".equals(current.status()) && "passed".equals(service.status())
                ? "passed" : "failed");
        return new Snapshot(
                true,
                status,
                ready,
                requested,
                completed,
                failed,
                pending,
                dropped,
                current.frameCount(),
                passes,
                droppedEvents,
                current.manifestFinalized(),
                storage.artifactBytes(),
                storage.maxBytes(),
                storage.exceeded(),
                storage.failureReason()
        );
    }

    private static int integerProperty(final String name, final int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record ReadbackRequest(
            String semanticName,
            long runtimeId,
            String debugId,
            String formatName,
            int bytesPerTexel,
            int width,
            int height,
            int depthOrLayers,
            int mipLevel,
            int sampleCount,
            int usage,
            com.metallum.client.validation.contract.AttachmentSemantic semantic
    ) {
        public ReadbackRequest {
            if (semanticName == null || semanticName.isBlank() || runtimeId <= 0L
                    || debugId == null || debugId.isBlank() || formatName == null || formatName.isBlank()
                    || bytesPerTexel <= 0 || width <= 0 || height <= 0 || depthOrLayers <= 0
                    || mipLevel < 0 || sampleCount <= 0 || usage < 0 || semantic == null) {
                throw new IllegalArgumentException("Invalid validation readback request");
            }
        }
    }

    public record Snapshot(
            boolean enabled,
            String status,
            boolean ready,
            int requestedCaptures,
            int completedCaptures,
            int failedCaptures,
            int pendingCaptures,
            int droppedCaptures,
            int frameCount,
            int passCount,
            int droppedEvents,
            boolean manifestFinalized,
            long artifactBytes,
            long maxArtifactBytes,
            boolean storageBudgetExceeded,
            String storageFailureReason
    ) {
        private static Snapshot disabled() {
            return new Snapshot(false, "disabled", true, 0, 0, 0, 0, 0, 0, 0, 0, false,
                    0L, 0L, false, null);
        }
    }
}
