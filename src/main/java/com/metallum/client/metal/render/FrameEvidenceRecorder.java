package com.metallum.client.metal.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
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
    private final List<Frame> frames = new ArrayList<>();
    private final ThreadLocal<Frame> current = new ThreadLocal<>();
    private long sequence;
    private long submissionSequence;
    private long droppedFrames;

    public FrameEvidenceRecorder(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = clock;
    }

    public synchronized void beginFrame(boolean renderLevel) {
        Frame previous = current.get();
        long id = Math.incrementExact(sequence);
        sequence = id;
        boolean retained = frames.size() < capacity;
        if (!retained) {
            droppedFrames = Math.incrementExact(droppedFrames);
        }
        // Minecraft can render loading screens recursively while an outer frame runs tasks.
        Frame frame = new Frame(id, clock.getAsLong(), renderLevel, previous, retained);
        if (retained) frames.add(frame);
        current.set(frame);
    }

    public synchronized void endFrame() {
        Frame frame = current.get();
        if (frame == null) return;
        frame.cpuNanos = Math.subtractExact(clock.getAsLong(), frame.start);
        frame.ended = true;
        if (frame.depth != 0) frame.failure = "open-abi-call-at-frame-end";
        if (frame.parent == null) current.remove();
        else current.set(frame.parent);
    }

    public void producer(String producer) {
        Frame frame = current.get();
        if (frame != null && frame.retained) frame.producers.add(producer);
    }

    public void context(JsonObject context) {
        Frame frame = current.get();
        if (frame != null && frame.retained) frame.context = context.deepCopy();
    }

    /** Identity is captured at allocation, not guessed from a later GPU sample's position. */
    public synchronized Submission commandBuffer(long nativeSubmitIndex) {
        Frame frame = current.get();
        if (frame == null || !frame.retained) return null;
        submissionSequence = Math.incrementExact(submissionSequence);
        Submission submission = new Submission(frame, submissionSequence, nativeSubmitIndex);
        frame.submissions.add(submission);
        return submission;
    }

    public synchronized void submitted(Submission submission) {
        if (submission == null) return;
        if (submission.submitted) submission.frame.failure = "duplicate-submit";
        if (submission.frame != current.get()) submission.frame.failure = "cross-frame-command-buffer";
        submission.submitted = true;
    }

    /** The existing native ticket identifies scheduled presentation, not a displayed frame. */
    public synchronized void presentationRequested(Submission submission, long nativeId) {
        if (submission == null) return;
        if (submission.presentationRequested || submission.submitted || nativeId < 0) {
            submission.frame.failure = "invalid-presentation-request";
            return;
        }
        if (submission.frame != current.get()) submission.frame.failure = "cross-frame-presentation";
        submission.presentationRequested = true;
        submission.nativePresentationId = nativeId;
    }

    public synchronized void completed(Submission submission, boolean success, double start, double end) {
        if (submission == null) return;
        if (submission.completed || !submission.submitted) submission.frame.failure = "invalid-completion";
        submission.completed = true;
        submission.success = success;
        if (success && Double.isFinite(start) && Double.isFinite(end) && start > 0 && end > start) {
            submission.gpuNanos = Math.round((end - start) * 1_000_000_000.0);
        }
    }

    public synchronized void nativeEncoding(Submission submission, long[] counters) {
        if (submission == null || counters == null) return;
        if (counters.length != NATIVE_COUNTER_NAMES.length || java.util.Arrays.stream(counters).anyMatch(n -> n < 0)) {
            submission.frame.failure = "invalid-native-encoding-counters";
            return;
        }
        submission.nativeEncoding = counters.clone();
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
        if (frame == null || !frame.retained) return; // Worker/startup calls are outside this frame scope.
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
        if (frame == null || !frame.retained) return;
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
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("identity", identity.deepCopy());
        root.addProperty("scope", "Minecraft.renderFrame/render-thread/main-command-queue");
        root.addProperty("droppedFrames", droppedFrames);
        JsonArray rows = new JsonArray();
        for (Frame frame : frames) {
            JsonObject row = new JsonObject();
            row.addProperty("frameId", frame.id);
            row.addProperty("parentFrameId", frame.parent == null ? 0L : frame.parent.id);
            row.addProperty("renderLevel", frame.renderLevel);
            row.addProperty("cpuFrameNs", frame.cpuNanos);
            row.addProperty("ended", frame.ended);
            row.addProperty("failure", frame.failure);
            row.add("context", frame.context.deepCopy());
            JsonArray producers = new JsonArray();
            frame.producers.forEach(producers::add);
            row.add("producerEntries", producers);
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
                value.addProperty("submitted", submission.submitted);
                value.addProperty("completed", submission.completed);
                value.addProperty("success", submission.success);
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
        root.addProperty("nativeEncodingScope", "partial: ordinary draw bridges, main render/blit/compute creation, clear helpers and ordinary presentation; excludes MetalFX and GPU-scene/ICB internal work");
        JsonObject unavailable = new JsonObject();
        unavailable.addProperty("gpuFrameNs", "per-command-buffer service durations are not frame critical-path time");
        unavailable.addProperty("nativeEncodeNs", "ABI wall duration includes dispatch, waits and native work");
        unavailable.addProperty("nativeInternalEncoderAndDrawCounts", "only the nativeEncodingScope paths are counted; uninstrumented helper/MetalFX/ICB work is not zero");
        unavailable.addProperty("psoSwitchesAndResourceBindingChanges", "ABI calls do not prove effective native state changes");
        unavailable.addProperty("workerAbiNs", "worker calls cannot be assigned to a render-thread frame");
        unavailable.addProperty("terrainLatency", "use generation-keyed terrain-work-epoch reports; no timestamp proximity join");
        unavailable.addProperty("memoryAndCopyBytes", "no frame-scoped allocation/copy authority connected");
        unavailable.addProperty("shaderCompileBlockingNs", "compile ABI time does not cover Java translation/cache work");
        unavailable.addProperty("presentedTimeAndGeneratedFrames", "native presentation tickets identify scheduling; GPU completion does not prove display presentation");
        unavailable.addProperty("metal4NativePresentationId", "Metal 4 assigns its native ticket at commit and does not return it through the encode ABI");
        root.add("unavailable", unavailable);
        return root;
    }

    private static final class Frame {
        final long id;
        final long start;
        final boolean renderLevel;
        final Frame parent;
        final boolean retained;
        final Map<String, Counter> abi = new LinkedHashMap<>();
        final LinkedHashSet<String> producers = new LinkedHashSet<>();
        final List<Submission> submissions = new ArrayList<>();
        final long[] started = new long[32];
        final long[] children = new long[32];
        final Counter[] calls = new Counter[32];
        int depth;
        long cpuNanos;
        boolean ended;
        String failure = "";
        JsonObject context = new JsonObject();
        Frame(long id, long start, boolean renderLevel, Frame parent, boolean retained) {
            this.id = id; this.start = start; this.renderLevel = renderLevel;
            this.parent = parent; this.retained = retained;
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
        private long nativePresentationId;
        private Submission(Frame frame, long id, long nativeSubmitIndex) {
            this.frame = frame; this.id = id; this.nativeSubmitIndex = nativeSubmitIndex;
        }
    }
}
