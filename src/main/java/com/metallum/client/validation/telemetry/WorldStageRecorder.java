package com.metallum.client.validation.telemetry;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded, diagnostic-only observations of vanilla world and packet stages.
 *
 * <p>Recording is synchronized and snapshots are strong, immutable copies of one
 * recorder state.  A snapshot is therefore internally consistent, but it is only
 * a process observation: it includes warmup and does not identify a world epoch,
 * generation, or renderer policy.  The recorder retains no world or task object.
 * Event storage is primitive and fixed at construction; event recording allocates
 * no event objects.  Context registration allocates at most one weak reference per
 * live identity in its fixed registry.</p>
 */
public final class WorldStageRecorder {
    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_CAPACITY = 16_384;
    public static final int MAX_CAPACITY = 65_536;
    public static final int CONTEXT_CAPACITY = 128;
    public static final long UNAVAILABLE = -1L;
    public static final String MEASUREMENT_LIMITS =
            "activeInvocations=unavailable: post-invocation recording; snapshot can censor calls still active; "
                    + "rawScope=completed-and-failed-exits-observed";

    /** Stage role and receiver-kind labels are diagnostic schema, not identity. */
    public enum Stage {
        CLIENT_PACKETS("client-main", "client-process"),
        CHUNK_INSTALL("client-main", "client-level"),
        LIGHT_ENQUEUE("client-main", "client-level"),
        LIGHT_POLL("client-main", "client-level"),
        LIGHT_TASK("client-main", "client-level"),
        LIGHT_UPDATE("client-main", "client-level"),
        SERVER_PACKETS("integrated-server", "integrated-server"),
        SERVER_TICK("integrated-server", "integrated-server");

        private final String role;
        private final String contextKind;

        Stage(String role, String contextKind) {
            this.role = role;
            this.contextKind = contextKind;
        }

        public String role() {
            return role;
        }

        public String contextKind() {
            return contextKind;
        }
    }

    private final int capacity;
    private final long originNanos;
    private final long[] sequence;
    private final byte[] stage;
    private final long[] contextId;
    private final long[] threadId;
    private final long[] startOffsetNanos;
    private final long[] endOffsetNanos;
    private final boolean[] completed;
    private final long[] queueBefore;
    private final long[] queueAfter;
    private final long[] workCount;
    private final byte[] resultCode;

    private final WeakReference<?>[] contexts = new WeakReference<?>[CONTEXT_CAPACITY];
    private final long[] contextIds = new long[CONTEXT_CAPACITY];
    private long nextContextId = 1;
    private int contextSlots;
    private int eventCount;
    private long nextSequence = 1;
    private long droppedEvents;
    private long invalidEvents;
    private long contextOverflowEvents;

    /** Creates a recorder using the configured bounded capacity and System.nanoTime. */
    public WorldStageRecorder() {
        this(configuredCapacity(), System::nanoTime);
    }

    /** Creates a recorder with a bounded event capacity, using System.nanoTime. */
    public WorldStageRecorder(int capacity) {
        this(capacity, System::nanoTime);
    }

    /**
     * Testable constructor.  Production callers should use one of the constructors
     * above; the supplied clock must have the same monotonic, absolute semantics as
     * System.nanoTime().
     */
    WorldStageRecorder(int capacity, LongSupplier clock) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1," + MAX_CAPACITY + "]");
        }
        this.capacity = capacity;
        Objects.requireNonNull(clock, "clock");
        this.originNanos = clock.getAsLong();
        this.sequence = new long[capacity];
        this.stage = new byte[capacity];
        this.contextId = new long[capacity];
        this.threadId = new long[capacity];
        this.startOffsetNanos = new long[capacity];
        this.endOffsetNanos = new long[capacity];
        this.completed = new boolean[capacity];
        this.queueBefore = new long[capacity];
        this.queueAfter = new long[capacity];
        this.workCount = new long[capacity];
        this.resultCode = new byte[capacity];
    }

    /** Returns the absolute monotonic origin used for serialized offsets. */
    public long originNanos() {
        return originNanos;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Gets a stable id for an object identity without retaining the object.  Zero
     * denotes null or a registry overflow.  IDs never identify a world generation.
     */
    public synchronized long contextId(Object context) {
        if (context == null) {
            return 0L;
        }
        for (int i = 0; i < contextSlots; i++) {
            Object current = contexts[i].get();
            if (current == context) {
                return contextIds[i];
            }
        }
        // Reuse dead weak-reference slots before declaring the fixed registry full.
        for (int i = 0; i < contextSlots; i++) {
            if (contexts[i].get() == null) {
                return installContext(i, context);
            }
        }
        if (contextSlots == CONTEXT_CAPACITY || nextContextId <= 0) {
            contextOverflowEvents = saturatingIncrement(contextOverflowEvents);
            invalidEvents = saturatingIncrement(invalidEvents);
            return 0L;
        }
        return installContext(contextSlots++, context);
    }

    /**
     * Records one completed or failed stage.  All times are absolute nanoTime
     * values; serialized offsets are computed from the recorder origin.
     */
    public synchronized void record(Stage stage, long contextId, long startNanos, long endNanos,
                                     boolean completed, long queueBefore, long queueAfter,
                                     long workCount, int resultCode) {
        if (stage == null || contextId <= 0L
                || (nextContextId > 0L && contextId >= nextContextId)
                || (nextContextId <= 0L)
                || endNanos < startNanos
                || !validGauge(queueBefore) || !validGauge(queueAfter) || !validGauge(workCount)
                || resultCode < -1 || resultCode > 1) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long startOffset;
        final long endOffset;
        try {
            startOffset = Math.subtractExact(startNanos, originNanos);
            endOffset = Math.subtractExact(endNanos, originNanos);
        } catch (ArithmeticException overflow) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        if (startOffset < 0L || endOffset < startOffset) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        if (eventCount >= capacity) {
            droppedEvents = saturatingIncrement(droppedEvents);
            return;
        }
        if (nextSequence <= 0L) {
            invalidEvents = saturatingIncrement(invalidEvents);
            droppedEvents = saturatingIncrement(droppedEvents);
            return;
        }
        int slot = eventCount++;
        sequence[slot] = nextSequence;
        nextSequence = nextSequence == Long.MAX_VALUE ? 0L : nextSequence + 1L;
        this.stage[slot] = (byte) stage.ordinal();
        this.contextId[slot] = contextId;
        this.threadId[slot] = Thread.currentThread().threadId();
        startOffsetNanos[slot] = startOffset;
        endOffsetNanos[slot] = endOffset;
        this.completed[slot] = completed;
        this.queueBefore[slot] = queueBefore;
        this.queueAfter[slot] = queueAfter;
        this.workCount[slot] = workCount;
        this.resultCode[slot] = (byte) resultCode;
    }

    /** Returns a strong, immutable and internally consistent diagnostic snapshot. */
    public synchronized Snapshot snapshot() {
        ArrayList<Event> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new Event(sequence[i], Stage.values()[stage[i]], contextId[i], threadId[i],
                    startOffsetNanos[i], endOffsetNanos[i], completed[i], queueBefore[i],
                    queueAfter[i], workCount[i], resultCode[i]));
        }
        return new Snapshot(SCHEMA_VERSION, capacity, eventCount, droppedEvents, invalidEvents,
                contextOverflowEvents, liveContextCount(), events);
    }

    public synchronized Report report(String sourceSha, String trialId, String status) {
        validateReportText(sourceSha, "sourceSha", 40, 40);
        validateSha40(sourceSha);
        validateReportText(trialId, "trialId", 1, 160);
        validateReportText(status, "status", 1, 128);
        return new Report(SCHEMA_VERSION, "diagnostic", false,
                "process-observation-including-warmup", "System.nanoTime", "26.3",
                sourceSha, trialId, status, MEASUREMENT_LIMITS, snapshot());
    }

    private long installContext(int slot, Object context) {
        long id = nextContextId;
        if (id <= 0L) {
            contextOverflowEvents = saturatingIncrement(contextOverflowEvents);
            invalidEvents = saturatingIncrement(invalidEvents);
            return 0L;
        }
        contexts[slot] = new WeakReference<>(context);
        contextIds[slot] = id;
        nextContextId = id == Long.MAX_VALUE ? 0L : id + 1L;
        return id;
    }

    private int liveContextCount() {
        int live = 0;
        for (int i = 0; i < contextSlots; i++) {
            if (contexts[i].get() != null) {
                live++;
            }
        }
        return live;
    }

    private static boolean validGauge(long value) {
        return value == UNAVAILABLE || value >= 0L;
    }

    private static void validateReportText(String value, String name, int minimum, int maximum) {
        if (value == null || value.length() < minimum || value.length() > maximum || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank and bounded");
        }
    }

    private static void validateSha40(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f';
            if (!hex) {
                throw new IllegalArgumentException("sourceSha must be exactly 40 hexadecimal characters");
            }
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static int configuredCapacity() {
        String configured = System.getProperty("metallum.validation.worldStageCapacity");
        if (configured == null) {
            return DEFAULT_CAPACITY;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed < 1 || parsed > MAX_CAPACITY) {
                throw new IllegalArgumentException("worldStageCapacity must be in [1," + MAX_CAPACITY + "]");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("worldStageCapacity must be a bounded integer", exception);
        }
    }

    public record Event(long sequence, Stage stage, long contextId, long threadId,
                        long startOffsetNanos, long endOffsetNanos, boolean completed,
                        long queueBefore, long queueAfter, long workCount, int resultCode) {
        public String role() {
            return stage.role();
        }

        public String contextKind() {
            return stage.contextKind();
        }
    }

    public record Snapshot(int schemaVersion, int eventCapacity, long eventCount, long droppedEvents,
                           long invalidEvents, long contextOverflowEvents, long liveContextCount,
                           List<Event> events) {
        public Snapshot {
            events = List.copyOf(events);
        }
    }

    public record Report(int schemaVersion, String evidenceClass, boolean performanceEligible,
                         String scope, String clock, String minecraftVersion, String sourceSha,
                         String trialId, String status, String measurementLimits, Snapshot snapshot) {
        public Report {
            measurementLimits = Objects.requireNonNull(measurementLimits, "measurementLimits");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
