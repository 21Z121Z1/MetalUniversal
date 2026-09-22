package com.metallum.client.metal.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, opt-in observations. Frame IDs are diagnostic joins, never render semantics. */
public final class FrameEvidenceRecorder {
    private static final String[] NATIVE_COUNTER_NAMES = {
            "renderEncoders", "computeEncoders", "blitEncoders", "directDraws", "indirectDraws"
    };
    private final int capacity;
    private final LongSupplier clock;
    private List<Frame> frames = new ArrayList<>();
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private int segmentFrameLimit;
    private int segmentQueueLimit;
    private long receiptRetentionNs;
    private long segmentSequence;
    private long exportedSegments;
    private long exportedFrames;
    private long censoredSegments;
    private long lateCompletionUpdates;
    private long lateReceiptUpdates;
    private boolean accepting = true;
    private final ThreadLocal<Frame> current = new ThreadLocal<>();
    private final Map<Long, Submission> presentations = new LinkedHashMap<>();
    private final LinkedHashSet<Long> pendingPresentations = new LinkedHashSet<>();
    private long sequence;
    private long submissionSequence;
    private long droppedFrames;
    private final boolean explicitWindow;
    private JsonObject profile;
    private long windowStart;
    private long windowEnd;
    private long warmupNanos;
    private boolean windowClosed;
    private long outsideWindowFrames;
    private long epoch = 1;
    private long windowEpoch = 1;

    public FrameEvidenceRecorder(int capacity, LongSupplier clock) {
        this(capacity, clock, false);
    }

    public FrameEvidenceRecorder(int capacity, LongSupplier clock, boolean explicitWindow) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = clock;
        this.explicitWindow = explicitWindow;
    }

    /** Optional bounded export; configure before recording. The recorder remains the only ID owner. */
    public synchronized void enableSegments(int frameLimit, int queueLimit, long retentionNs) {
        if (sequence != 0 || segmentFrameLimit != 0 || frameLimit < 1 || frameLimit > capacity
                || queueLimit < 1 || queueLimit > 8 || retentionNs <= 0) {
            throw new IllegalArgumentException("invalid bounded segment configuration");
        }
        segmentFrameLimit = frameLimit;
        segmentQueueLimit = queueLimit;
        receiptRetentionNs = retentionNs;
    }

    private void sealActive(long now) {
        if (frames.isEmpty() || segments.size() >= segmentQueueLimit) return;
        segmentSequence = Math.incrementExact(segmentSequence);
        segments.addLast(new Segment(segmentSequence, now, frames));
        frames = new ArrayList<>();
    }

    private static boolean settled(List<Frame> selected) {
        for (Frame frame : selected) {
            if (!frame.ended) return false;
            for (Submission submission : frame.submissions) {
                if (!submission.completed) return false;
                if (submission.presentationRequested && submission.success && !submission.presentationResolved) return false;
            }
        }
        return true;
    }

    /**
     * Detach a finished segment on the writer thread. Late callbacks remain joined while it
     * waits in the bounded queue. Expired or shutdown-pending work is explicitly censored.
     * No resource is retained, no GPU/display wait is introduced, and JSON rows are built
     * only after detaching. Root/recursive scopes never cross a segment boundary.
     */
    public synchronized SegmentSnapshot pollSegment(JsonObject identity, boolean shutdown) {
        if (segmentFrameLimit == 0) throw new IllegalStateException("segmented export is disabled");
        if (shutdown) accepting = false;
        if (segments.isEmpty() && shutdown) sealActive(clock.getAsLong());
        Segment segment = segments.peekFirst();
        if (segment == null) return null;
        boolean complete = settled(segment.frames);
        if (!complete && !shutdown && clock.getAsLong() - segment.sealedAt < receiptRetentionNs) return null;
        segments.removeFirst();
        String censoring = complete ? "" : shutdown ? "shutdown-right-censored" : "callback-retention-expired";
        if (!complete) censoredSegments++;
        for (Frame frame : segment.frames) {
            frame.exported = true;
            frame.exportCensoringReason = censoring;
            for (Submission submission : frame.submissions) {
                long id = submission.nativePresentationId;
                if (id > 0) {
                    presentations.remove(id, submission);
                    pendingPresentations.remove(id);
                }
            }
        }
        exportedSegments++;
        exportedFrames += segment.frames.size();
        // A small immutable header is copied under the lock, not a per-frame JSON document.
        JsonObject header = snapshotOf(identity, List.of());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("id", segment.id);
        metadata.addProperty("firstFrameId", segment.frames.getFirst().id);
        metadata.addProperty("lastFrameId", segment.frames.getLast().id);
        metadata.addProperty("frameCount", segment.frames.size());
        metadata.addProperty("censoringReason", censoring);
        header.add("segment", metadata);
        return new SegmentSnapshot(header, segment.frames);
    }

    /** Constant-size checkpoint metadata; an archive never keeps a growing list of segment manifests. */
    public synchronized JsonObject archiveState(JsonObject identity) {
        if (segmentFrameLimit == 0) throw new IllegalStateException("segmented export is disabled");
        JsonObject root = snapshotOf(identity, List.of());
        JsonObject retention = new JsonObject();
        retention.addProperty("frameCapacityPerBuffer", capacity);
        retention.addProperty("segmentFrameTarget", segmentFrameLimit);
        retention.addProperty("pendingSegmentLimit", segmentQueueLimit);
        retention.addProperty("receiptRetentionNs", receiptRetentionNs);
        retention.addProperty("queuedSegments", segments.size());
        retention.addProperty("retainedFrames", frames.size() + segments.stream().mapToInt(x -> x.frames.size()).sum());
        retention.addProperty("exportedSegments", exportedSegments);
        retention.addProperty("exportedFrames", exportedFrames);
        retention.addProperty("censoredSegments", censoredSegments);
        retention.addProperty("lateCompletionUpdates", lateCompletionUpdates);
        retention.addProperty("lateReceiptUpdates", lateReceiptUpdates);
        root.add("retention", retention);
        return root;
    }

    private record Segment(long id, long sealedAt, List<Frame> frames) { }

    public static final class SegmentSnapshot {
        private final JsonObject header;
        private final List<Frame> frames;
        private SegmentSnapshot(JsonObject header, List<Frame> frames) {
            this.header = header;
            this.frames = frames;
        }
        /** Writer-owned, detached data only. Never call this from a render or completion callback. */
        public JsonObject toJson() {
            JsonObject result = header.deepCopy();
            addFrames(result, frames);
            return result;
        }
    }

    /** One predeclared finite window per process; no reset across in-flight submissions. */
    public synchronized void armWindow(JsonObject profile, long warmupNs, long sampleNs) {
        armWindowAt(profile, warmupNs, sampleNs, clock.getAsLong());
    }

    /** Share one sampled clock anchor with other observers; publish no partial declaration. */
    public synchronized void armWindowAt(JsonObject profile, long warmupNs, long sampleNs, long anchorNs) {
        if (!explicitWindow || this.profile != null || profile == null
                || (current.get() != null && current.get().retained) || warmupNs < 0 || sampleNs <= 0) {
            throw new IllegalStateException("invalid window declaration");
        }
        long start = Math.addExact(anchorNs, warmupNs);
        long end = Math.addExact(start, sampleNs);
        JsonObject declaredProfile = profile.deepCopy();
        // All potentially failing work precedes publication under this monitor.
        windowEpoch = epoch;
        warmupNanos = warmupNs;
        windowStart = start;
        windowEnd = end;
        this.profile = declaredProfile;
    }

    public synchronized boolean windowComplete() { return windowClosed; }
    public synchronized void advanceEpoch() { epoch = Math.incrementExact(epoch); }
    public boolean retainingCurrentFrame() {
        Frame frame = current.get();
        return frame != null && frame.retained;
    }

    public synchronized void beginFrame(boolean renderLevel) {
        Frame previous = current.get();
        long id = Math.incrementExact(sequence);
        sequence = id;
        long now = clock.getAsLong();
        if (profile != null && now >= windowEnd) windowClosed = true;
        // Nested scopes inherit membership: never capture a child without its parent.
        boolean eligible = accepting && (!explicitWindow || (previous != null ? previous.retained
                : profile != null && now >= windowStart && now < windowEnd));
        boolean retained = eligible && frames.size() < capacity;
        if (eligible && !retained) {
            droppedFrames = Math.incrementExact(droppedFrames);
        }
        if (!eligible) outsideWindowFrames++;
        // Minecraft can render loading screens recursively while an outer frame runs tasks.
        Frame frame = new Frame(id, now, renderLevel, previous, retained, epoch);
        if (retained) frames.add(frame);
        current.set(frame);
    }

    public synchronized void endFrame() {
        Frame frame = current.get();
        if (frame == null) return;
        if (frame.exported) {
            lateCompletionUpdates++;
            if (frame.parent == null) current.remove();
            else current.set(frame.parent);
            return;
        }
        frame.cpuNanos = Math.subtractExact(clock.getAsLong(), frame.start);
        frame.ended = true;
        if (profile != null && clock.getAsLong() >= windowEnd) windowClosed = true;
        if (frame.depth != 0) frame.failure = "open-abi-call-at-frame-end";
        if (frame.parent == null) {
            current.remove();
            if (segmentFrameLimit > 0 && frames.size() >= segmentFrameLimit) sealActive(clock.getAsLong());
        } else current.set(frame.parent);
    }

    public void producer(String producer) {
        Frame frame = current.get();
        if (frame != null && frame.retained && !frame.exported) frame.producers.add(producer);
    }

    /** Joins the terrain recorder's existing batch index to this source frame, never by time. */
    public void terrainBatchEncoded(long terrainFrameIndex) {
        Frame frame = current.get();
        if (frame == null || !frame.retained || frame.exported) return;
        if (terrainFrameIndex < 0) {
            frame.failure = "invalid-terrain-batch-index";
        } else if (frame.terrainBatchIndices.contains(terrainFrameIndex)) {
            return; // The same batch is rendered once per layer (and possibly more than one pass).
        } else if (frame.terrainBatchIndices.size() >= 64) {
            frame.failure = "terrain-batch-evidence-overflow";
        } else {
            frame.terrainBatchIndices.add(terrainFrameIndex);
        }
    }

    public void context(JsonObject context) {
        Frame frame = current.get();
        if (frame != null && frame.retained && !frame.exported) frame.context = context.deepCopy();
    }

    /** Identity is captured on first observed buffer use, never inferred from GPU sample order. */
    public synchronized Submission commandBuffer(long nativeSubmitIndex) {
        Frame frame = current.get();
        if (frame == null || !frame.retained || frame.exported) return null;
        if (frame.submissions.size() >= 256) {
            frame.failure = "submission-evidence-overflow";
            return null;
        }
        submissionSequence = Math.incrementExact(submissionSequence);
        Submission submission = new Submission(frame, submissionSequence, nativeSubmitIndex);
        frame.submissions.add(submission);
        return submission;
    }

    public synchronized void submitted(Submission submission) {
        if (submission == null) return;
        if (submission.frame.exported) { lateCompletionUpdates++; return; }
        if (submission.submitted) submission.frame.failure = "duplicate-submit";
        if (submission.frame != current.get()) submission.frame.failure = "cross-frame-command-buffer";
        submission.submitted = true;
    }

    /** The existing native ticket identifies scheduled presentation, not a displayed frame. */
    public synchronized void presentationRequested(Submission submission, long nativeId) {
        if (submission == null) return;
        if (submission.frame.exported) { lateCompletionUpdates++; return; }
        if (submission.presentationRequested || submission.submitted || nativeId < 0) {
            submission.frame.failure = "invalid-presentation-request";
            return;
        }
        if (submission.frame != current.get()) submission.frame.failure = "cross-frame-presentation";
        submission.presentationRequested = true;
        submission.nativePresentationId = nativeId;
        indexPresentation(submission);
    }

    /** Completion can supply the Metal 4 ID, which does not exist at encode time. */
    public synchronized void nativePresentationId(Submission submission, long nativeId) {
        if (submission == null || nativeId == 0) return;
        if (submission.frame.exported) { lateCompletionUpdates++; return; }
        if (nativeId < 0 || !submission.presentationRequested
                || (submission.nativePresentationId > 0 && submission.nativePresentationId != nativeId)) {
            submission.frame.failure = "mismatched-native-presentation-id";
            return;
        }
        submission.nativePresentationId = nativeId;
        indexPresentation(submission);
    }

    private void indexPresentation(Submission submission) {
        long id = submission.nativePresentationId;
        if (id <= 0) return;
        Submission previous = presentations.putIfAbsent(id, submission);
        if (previous != null && previous != submission) {
            previous.frame.failure = submission.frame.failure = "duplicate-native-presentation-id";
        } else if (previous == null) pendingPresentations.add(id);
    }

    public synchronized long[] presentationIds() {
        return pendingPresentations.stream().mapToLong(Long::longValue).toArray();
    }

    /** Export-time snapshot of callbacks already received; GPU drain is not a display wait. */
    public synchronized void presented(long[] identifiers, double[] timestamps) {
        if (timestamps == null) return; // Older native module; do not fabricate zero timestamps.
        if (identifiers.length != timestamps.length) throw new IllegalArgumentException("Presentation evidence length mismatch");
        Map<Long, Double> byId = new java.util.HashMap<>();
        for (int index = 0; index < identifiers.length; index++) {
            if (byId.put(identifiers[index], timestamps[index]) != null)
                throw new IllegalArgumentException("duplicate presentation receipt identity");
        }
        for (var entry : byId.entrySet()) {
                Submission submission = presentations.get(entry.getKey());
                if (submission == null) {
                    if (entry.getValue() != 0) lateReceiptUpdates++;
                    continue;
                }
                Frame frame = submission.frame;
                double timestamp = entry.getValue();
                if (submission.presentationResolved) {
                    if (timestamp > 0 && Double.compare(timestamp, submission.presentedTimeSeconds) != 0)
                        frame.failure = "conflicting-presented-timestamp";
                    continue; // A later eviction/pending snapshot cannot erase an observed callback.
                }
                if (Double.isFinite(timestamp) && timestamp > 0) {
                    submission.presentationResolved = true;
                    submission.presentedTimeSeconds = timestamp;
                    submission.presentedUnavailableReason = "";
                    pendingPresentations.remove(entry.getKey());
                } else {
                    submission.presentedUnavailableReason = timestamp == 0 ? "presented-callback-pending"
                            : timestamp == -1 ? "presentation-cancelled-or-failed"
                            : timestamp == -2 ? "invalid-presented-timestamp"
                            : timestamp == -3 ? "native-evidence-not-retained"
                            : timestamp == -4 ? "drawable-not-presented" : "invalid-native-evidence";
                    if (timestamp != 0) {
                        submission.presentationResolved = true;
                        pendingPresentations.remove(entry.getKey());
                    }
                }
        }
    }

    public synchronized void completed(Submission submission, boolean success, double start, double end) {
        if (submission == null) return;
        if (submission.frame.exported) { lateCompletionUpdates++; return; }
        if (submission.completed || !submission.submitted) submission.frame.failure = "invalid-completion";
        submission.completed = true;
        submission.success = success;
        if (success && Double.isFinite(start) && Double.isFinite(end) && start > 0 && end > start) {
            submission.gpuNanos = Math.round((end - start) * 1_000_000_000.0);
        }
    }

    public synchronized void nativeEncoding(Submission submission, long[] counters) {
        if (submission == null || counters == null) return;
        if (submission.frame.exported) { lateCompletionUpdates++; return; }
        if (counters.length != NATIVE_COUNTER_NAMES.length || java.util.Arrays.stream(counters).anyMatch(n -> n < 0)) {
            submission.frame.failure = "invalid-native-encoding-counters";
            return;
        }
        submission.nativeEncoding = counters.clone();
    }

    public synchronized void drawableWait(Submission submission, long nanos) {
        if (submission == null || nanos == -1) return;
        if (submission.frame.exported) { lateCompletionUpdates++; return; }
        if (nanos < -1) {
            submission.frame.failure = "invalid-drawable-wait";
            return;
        }
        submission.drawableWaitNanos = nanos;
    }

    /** Keeps the exact ABI MethodType, including primitive/void returns and exceptional exits. */
    public MethodHandle instrument(String symbol, MethodHandle target) {
        try {
            var lookup = MethodHandles.lookup();
            MethodHandle enter = lookup.findVirtual(FrameEvidenceRecorder.class, "enter",
                    MethodType.methodType(void.class, String.class)).bindTo(this).bindTo(symbol);
            MethodHandle exit = lookup.findVirtual(FrameEvidenceRecorder.class, "exit",
                    MethodType.methodType(void.class, Throwable.class)).bindTo(this);
            Class<?> result = target.type().returnType();
            MethodHandle cleanup = result == void.class ? exit : MethodHandles.foldArguments(
                    MethodHandles.dropArguments(MethodHandles.identity(result), 0, Throwable.class), exit);
            return MethodHandles.foldArguments(MethodHandles.tryFinally(target, cleanup), enter);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot instrument ABI " + symbol, exception);
        }
    }

    private void enter(String symbol) {
        Frame frame = current.get();
        if (frame == null || !frame.retained || frame.exported) return; // Worker/startup calls are outside this frame scope.
        if (frame.depth == frame.started.length) {
            frame.failure = "abi-nesting-overflow";
            frame.depth++;
            return;
        }
        if (frame.depth > frame.started.length) { frame.depth++; return; }
        int depth = frame.depth++;
        frame.calls[depth] = frame.abi.computeIfAbsent(symbol, ignored -> new Counter());
        frame.children[depth] = 0L;
        frame.started[depth] = clock.getAsLong();
    }

    private void exit(Throwable failure) {
        Frame frame = current.get();
        if (frame == null || !frame.retained || frame.exported) return;
        int depth = --frame.depth;
        if (depth >= frame.started.length) return;
        long elapsed = Math.subtractExact(clock.getAsLong(), frame.started[depth]);
        Counter counter = frame.calls[depth];
        counter.calls = Math.incrementExact(counter.calls);
        counter.inclusive = Math.addExact(counter.inclusive, elapsed);
        counter.exclusive = Math.addExact(counter.exclusive, elapsed - frame.children[depth]);
        if (failure != null) counter.failures = Math.incrementExact(counter.failures);
        if (depth > 0) frame.children[depth - 1] = Math.addExact(frame.children[depth - 1], elapsed);
    }

    public synchronized JsonObject snapshot(JsonObject identity) {
        if (segmentFrameLimit != 0) throw new IllegalStateException("use the segmented archive, not a partial legacy snapshot");
        return snapshotOf(identity, frames);
    }

    private JsonObject snapshotOf(JsonObject identity, List<Frame> selected) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("identity", identity.deepCopy());
        root.addProperty("scope", "Minecraft.renderFrame/render-thread/main-command-queue");
        root.addProperty("droppedFrames", droppedFrames);
        if (explicitWindow) {
            JsonObject window = new JsonObject();
            window.addProperty("epoch", windowEpoch);
            window.addProperty("clock", "java-System.nanoTime");
            window.addProperty("membership", "root-source-start-half-open; nested-inherits-parent");
            window.addProperty("armed", profile != null);
            window.addProperty("closed", windowClosed);
            window.addProperty("startNs", windowStart);
            window.addProperty("endNs", windowEnd);
            window.addProperty("warmupNs", warmupNanos);
            window.addProperty("outsideWindowFrames", outsideWindowFrames);
            window.add("profile", profile == null ? JsonNull.INSTANCE : profile.deepCopy());
            root.add("window", window);
        }
        addFrames(root, selected);
        root.addProperty("nativeEncodingScope", "partial: ordinary draw bridges, main render/blit/compute creation, clear helpers and ordinary presentation; excludes MetalFX and GPU-scene/ICB internal work");
        JsonObject unavailable = new JsonObject();
        unavailable.addProperty("gpuFrameNs", "per-command-buffer service durations are not frame critical-path time");
        unavailable.addProperty("nativeEncodeNs", "ABI wall duration includes dispatch, waits and native work");
        unavailable.addProperty("nativeInternalEncoderAndDrawCounts", "only the nativeEncodingScope paths are counted; uninstrumented helper/MetalFX/ICB work is not zero");
        unavailable.addProperty("psoSwitchesAndResourceBindingChanges", "ABI calls do not prove effective native state changes");
        unavailable.addProperty("workerAbiNs", "worker calls cannot be assigned to a render-thread frame");
        root.addProperty("terrainScope", "terrainBatchIndices join TerrainWorkReport frameIndex only when vanillaWorkEvents is enabled; layer-return encoding evidence, not per-mesh GPU completion or visible presentation");
        unavailable.addProperty("terrainLatency", "use generation-keyed terrain-work-epoch reports joined by terrainBatchIndices; per-mesh GPU completion and presentation remain unavailable");
        unavailable.addProperty("memoryAndCopyBytes", "no frame-scoped allocation/copy authority connected");
        unavailable.addProperty("shaderCompileBlockingNs", "compile ABI time does not cover Java translation/cache work");
        root.addProperty("presentationScope", "ordinary source cohort; exact native ID joined to CAMetalDrawable.presentedTime seconds; latest 65536 native tickets retained; copied callbacks survive eviction; pending at export remains unavailable");
        unavailable.addProperty("generatedFrames", "MetalFX frame-generation presentation uses a separate timeline");
        unavailable.addProperty("inputToPhotonNs", "drawable presentedTime is not an input or scanout measurement");
        unavailable.addProperty("systemDeadline", "ordinary path has no authoritative DisplayLink deadline");
        root.add("unavailable", unavailable);
        return root;
    }

    private static void addFrames(JsonObject root, List<Frame> selected) {
        JsonArray rows = new JsonArray();
        for (Frame frame : selected) {
            JsonObject row = new JsonObject();
            row.addProperty("frameId", frame.id);
            row.addProperty("sourceStartNs", frame.start);
            row.addProperty("epoch", frame.epoch);
            row.addProperty("parentFrameId", frame.parent == null ? 0L : frame.parent.id);
            row.addProperty("renderLevel", frame.renderLevel);
            row.addProperty("cpuFrameNs", frame.cpuNanos);
            row.addProperty("ended", frame.ended);
            row.addProperty("failure", frame.failure);
            row.addProperty("exportCensoringReason", frame.exportCensoringReason);
            row.add("context", frame.context.deepCopy());
            JsonArray producers = new JsonArray();
            frame.producers.forEach(producers::add);
            row.add("producerEntries", producers);
            JsonArray terrainBatches = new JsonArray();
            frame.terrainBatchIndices.forEach(terrainBatches::add);
            row.add("terrainBatchIndices", terrainBatches);
            JsonObject abi = new JsonObject();
            frame.abi.forEach((symbol, counter) -> {
                JsonObject value = new JsonObject();
                value.addProperty("calls", counter.calls);
                value.addProperty("inclusiveNs", counter.inclusive);
                value.addProperty("exclusiveNs", counter.exclusive);
                value.addProperty("failures", counter.failures);
                abi.add(symbol, value);
            });
            row.add("abi", abi);
            JsonArray submissions = new JsonArray();
            for (Submission submission : frame.submissions) {
                JsonObject value = new JsonObject();
                value.addProperty("submissionId", submission.id);
                value.addProperty("nativeSubmitIndex", submission.nativeSubmitIndex);
                value.addProperty("presentationRequested", submission.presentationRequested);
                value.add("nativePresentationId", submission.nativePresentationId > 0
                        ? new JsonPrimitive(submission.nativePresentationId) : JsonNull.INSTANCE);
                value.addProperty("presentationIdUnavailableReason", submission.nativePresentationId > 0 ? ""
                        : !submission.presentationRequested ? "no-presentation-request" : "native-present-id-not-returned");
                value.add("presentedTimeSeconds", submission.presentedTimeSeconds > 0
                        ? new JsonPrimitive(submission.presentedTimeSeconds) : JsonNull.INSTANCE);
                value.addProperty("presentedUnavailableReason", !submission.presentationRequested ? "no-presentation-request"
                        : submission.nativePresentationId <= 0 ? "native-present-id-unavailable" : submission.presentedUnavailableReason);
                value.addProperty("presentationResolved", submission.presentationResolved);
                value.addProperty("submitted", submission.submitted);
                value.addProperty("completed", submission.completed);
                value.addProperty("success", submission.success);
                value.add("drawableWaitNs", submission.drawableWaitNanos >= 0
                        ? new JsonPrimitive(submission.drawableWaitNanos) : JsonNull.INSTANCE);
                value.addProperty("drawableWaitUnavailableReason", submission.drawableWaitNanos >= 0 ? ""
                        : "not-observed-or-native-unavailable");
                if (submission.nativeEncoding == null) {
                    value.add("nativeEncoding", JsonNull.INSTANCE);
                } else {
                    JsonObject counters = new JsonObject();
                    for (int index = 0; index < NATIVE_COUNTER_NAMES.length; index++) {
                        counters.addProperty(NATIVE_COUNTER_NAMES[index], submission.nativeEncoding[index]);
                    }
                    value.add("nativeEncoding", counters);
                }
                value.add("gpuServiceNs", submission.gpuNanos > 0
                        ? new JsonPrimitive(submission.gpuNanos) : JsonNull.INSTANCE);
                value.addProperty("gpuUnavailableReason", submission.gpuNanos > 0 ? ""
                        : !submission.completed ? "completion-pending"
                        : !submission.success ? "command-buffer-failed" : "gpu-timestamp-unavailable");
                submissions.add(value);
            }
            row.add("commandBuffers", submissions);
            rows.add(row);
        }
        root.add("frames", rows);
    }

    private static final class Frame {
        final long id;
        final long start;
        final long epoch;
        final boolean renderLevel;
        final Frame parent;
        final boolean retained;
        final Map<String, Counter> abi;
        final LinkedHashSet<String> producers;
        final LinkedHashSet<Long> terrainBatchIndices;
        final List<Submission> submissions;
        final long[] started;
        final long[] children;
        final Counter[] calls;
        int depth;
        long cpuNanos;
        boolean ended;
        volatile boolean exported;
        String exportCensoringReason = "";
        String failure = "";
        JsonObject context;
        Frame(long id, long start, boolean renderLevel, Frame parent, boolean retained, long epoch) {
            this.id = id; this.start = start; this.renderLevel = renderLevel;
            this.epoch = epoch;
            this.parent = parent; this.retained = retained;
            abi = retained ? new LinkedHashMap<>() : null;
            producers = retained ? new LinkedHashSet<>() : null;
            terrainBatchIndices = retained ? new LinkedHashSet<>() : null;
            submissions = retained ? new ArrayList<>() : null;
            started = retained ? new long[32] : null;
            children = retained ? new long[32] : null;
            calls = retained ? new Counter[32] : null;
            context = retained ? new JsonObject() : null;
        }
    }

    private static final class Counter {
        long calls;
        long inclusive;
        long exclusive;
        long failures;
    }

    public static final class Submission {
        private final Frame frame;
        private final long id;
        private final long nativeSubmitIndex;
        private boolean submitted;
        private boolean completed;
        private boolean success;
        private long gpuNanos;
        private long[] nativeEncoding;
        private boolean presentationRequested;
        private boolean presentationResolved;
        private long nativePresentationId;
        private double presentedTimeSeconds;
        private long drawableWaitNanos = -1;
        private String presentedUnavailableReason = "native-evidence-unavailable";
        private Submission(Frame frame, long id, long nativeSubmitIndex) {
            this.frame = frame; this.id = id; this.nativeSubmitIndex = nativeSubmitIndex;
        }
    }
}
