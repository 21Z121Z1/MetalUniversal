package com.metallum.client.validation.telemetry;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded diagnostic observations of vanilla world and packet stages.
 *
 * <p>Begin/end admission is intentionally separate from serialization.  The
 * recorder retains only primitive active-call state, and a closed measurement
 * window freezes both completed rows and active right-censored rows.  These
 * observations include warmup in process mode and are never a performance or
 * renderer-correctness acceptance metric.</p>
 */
public final class WorldStageRecorder {
    public static final int SCHEMA_VERSION = 2;
    private static final String EVIDENCE_CLASS = "diagnostic";
    private static final String CLOCK = "System.nanoTime";
    public static final int DEFAULT_CAPACITY = 16_384;
    public static final int MAX_CAPACITY = 65_536;
    public static final int MAX_ACTIVE_INVOCATIONS = 256;
    public static final int CONTEXT_CAPACITY = 128;
    public static final long UNAVAILABLE = -1L;
    public static final String MEASUREMENT_LIMITS =
            "bounded begin/end observations; active calls retained at snapshot; "
                    + "nested wall-clock intervals are inclusive";

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

        Stage(final String role, final String contextKind) {
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
    private final LongSupplier clock;
    private final long originNanos;
    private final long[] sequence;
    private final long[] invocationId;
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

    private final boolean[] active = new boolean[MAX_ACTIVE_INVOCATIONS];
    private final long[] activeToken = new long[MAX_ACTIVE_INVOCATIONS];
    private final byte[] activeStage = new byte[MAX_ACTIVE_INVOCATIONS];
    private final long[] activeContextId = new long[MAX_ACTIVE_INVOCATIONS];
    private final long[] activeThreadId = new long[MAX_ACTIVE_INVOCATIONS];
    private final long[] activeStartOffsetNanos = new long[MAX_ACTIVE_INVOCATIONS];
    private final long[] activeQueueBefore = new long[MAX_ACTIVE_INVOCATIONS];

    private final WeakReference<?>[] contexts = new WeakReference<?>[CONTEXT_CAPACITY];
    private final long[] contextIds = new long[CONTEXT_CAPACITY];
    private long nextContextId = 1L;
    private int contextSlots;
    private int eventCount;
    private long nextSequence = 1L;
    private long nextInvocationId = 1L;
    private long droppedEvents;
    private long invalidEvents;
    private long contextOverflowEvents;
    private long activeOverflowEvents;
    private long startedInvocations;
    private long finishedInvocations;

    private Window window;
    private Snapshot frozenSnapshot;

    public WorldStageRecorder() {
        this(configuredCapacity(), System::nanoTime);
    }

    public WorldStageRecorder(final int capacity) {
        this(capacity, System::nanoTime);
    }

    WorldStageRecorder(final int capacity, final LongSupplier clock) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1," + MAX_CAPACITY + "]");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.originNanos = clock.getAsLong();
        this.sequence = new long[capacity];
        this.invocationId = new long[capacity];
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

    public long originNanos() {
        return originNanos;
    }

    public int capacity() {
        return capacity;
    }

    /** Returns an identity id without retaining the context object. */
    public synchronized long contextId(final Object context) {
        if (context == null) return 0L;
        for (int i = 0; i < contextSlots; i++) {
            if (contexts[i].get() == context) return contextIds[i];
        }
        for (int i = 0; i < contextSlots; i++) {
            if (contexts[i].get() == null) return installContext(i, context);
        }
        if (contextSlots == CONTEXT_CAPACITY || nextContextId <= 0L) {
            contextOverflowEvents = saturatingIncrement(contextOverflowEvents);
            invalidEvents = saturatingIncrement(invalidEvents);
            return 0L;
        }
        return installContext(contextSlots++, context);
    }

    /**
     * Admits an invocation and returns its non-recycled token.  A zero token is
     * disabled evidence: callers must still invoke {@link #end(long, long,
     * boolean, long, long, int)} with it, but it can never become a valid row.
     */
    public synchronized long begin(final Stage stage, final long contextId,
                                   final long startNanos, final long queueBefore) {
        if (frozenSnapshot != null) return 0L;
        if (stage == null || contextId <= 0L || contextId >= nextContextId
                || !validGauge(queueBefore)) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return 0L;
        }
        final long startOffset;
        try {
            startOffset = Math.subtractExact(startNanos, originNanos);
        } catch (ArithmeticException overflow) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return 0L;
        }
        if (startOffset < 0L) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return 0L;
        }
        final long thread = Thread.currentThread().threadId();
        if (thread <= 0L || nextInvocationId <= 0L) {
            invalidEvents = saturatingIncrement(invalidEvents);
            activeOverflowEvents = saturatingIncrement(activeOverflowEvents);
            return 0L;
        }
        int slot = -1;
        for (int i = 0; i < active.length; i++) {
            if (!active[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            activeOverflowEvents = saturatingIncrement(activeOverflowEvents);
            return 0L;
        }
        final long token = nextInvocationId;
        nextInvocationId = token == Long.MAX_VALUE ? 0L : token + 1L;
        active[slot] = true;
        activeToken[slot] = token;
        activeStage[slot] = (byte) stage.ordinal();
        activeContextId[slot] = contextId;
        activeThreadId[slot] = thread;
        activeStartOffsetNanos[slot] = startOffset;
        activeQueueBefore[slot] = queueBefore;
        startedInvocations = saturatingIncrement(startedInvocations);
        return token;
    }

    /** Completes an admitted invocation, or rejects an invalid token/exit. */
    public synchronized void end(final long token, final long endNanos, final boolean completed,
                                 final long queueAfter, final long workCount, final int resultCode) {
        if (frozenSnapshot != null) return;
        final int slot = findActive(token);
        if (slot < 0) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long thread = Thread.currentThread().threadId();
        if (thread != activeThreadId[slot]) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long endOffset;
        try {
            endOffset = Math.subtractExact(endNanos, originNanos);
        } catch (ArithmeticException overflow) {
            clearActive(slot);
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        if (endOffset < activeStartOffsetNanos[slot] || !validGauge(queueAfter)
                || !validGauge(workCount) || resultCode < -1 || resultCode > 1) {
            clearActive(slot);
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long sequenceValue = nextSequence;
        nextSequence = sequenceValue == Long.MAX_VALUE ? 0L : sequenceValue + 1L;
        finishedInvocations = saturatingIncrement(finishedInvocations);
        if (eventCount >= capacity || sequenceValue <= 0L) {
            droppedEvents = saturatingIncrement(droppedEvents);
            clearActive(slot);
            return;
        }
        final int row = eventCount++;
        sequence[row] = sequenceValue;
        invocationId[row] = activeToken[slot];
        stage[row] = activeStage[slot];
        contextId[row] = activeContextId[slot];
        threadId[row] = activeThreadId[slot];
        startOffsetNanos[row] = activeStartOffsetNanos[slot];
        endOffsetNanos[row] = endOffset;
        this.completed[row] = completed;
        this.queueBefore[row] = activeQueueBefore[slot];
        this.queueAfter[row] = queueAfter;
        this.workCount[row] = workCount;
        this.resultCode[row] = (byte) resultCode;
        clearActive(slot);
    }

    /** Begins the one bounded measurement window, retaining active calls. */
    public synchronized void beginWindow(final String id, final long startFrameInclusive,
                                         final long nowNanos) {
        if (frozenSnapshot != null) {
            throw new IllegalStateException("measurement window is already closed");
        }
        if (window != null) {
            throw new IllegalStateException("measurement window already began");
        }
        if (id == null || id.isBlank() || id.length() > 160 || startFrameInclusive < 0L) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long offset = offset(nowNanos);
        if (offset < 0L) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        eventCount = 0;
        droppedEvents = 0L;
        nextSequence = 1L;
        startedInvocations = 0L;
        finishedInvocations = 0L;
        long activeAtStart = activeCount();
        window = new Window(id, startFrameInclusive, -1L, offset, -1L, activeAtStart, false);
    }

    /** Closes and freezes the one measurement window. */
    public synchronized void endWindow(final long endFrameExclusive, final long nowNanos) {
        if (frozenSnapshot != null || (window != null && window.closed())) {
            throw new IllegalStateException("measurement window is already closed");
        }
        if (window == null) {
            throw new IllegalStateException("measurement window has not begun");
        }
        if (endFrameExclusive <= window.startFrameInclusive()) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long offset = offset(nowNanos);
        if (offset <= window.startOffsetNanos()) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long snapshotNow = clock.getAsLong();
        if (snapshotNow < nowNanos) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        final long snapshotOffset = offset(snapshotNow);
        if (snapshotOffset < 0L) {
            invalidEvents = saturatingIncrement(invalidEvents);
            return;
        }
        window = new Window(window.id(), window.startFrameInclusive(), endFrameExclusive,
                window.startOffsetNanos(), offset, window.activeAtStart(), true);
        frozenSnapshot = snapshotLocked(snapshotOffset);
    }

    /** Returns an immutable snapshot; a closed window returns the frozen copy. */
    public synchronized Snapshot snapshot() {
        if (frozenSnapshot != null) return frozenSnapshot;
        return snapshotLocked(offset(clock.getAsLong()));
    }

    public synchronized Report report(final String sourceSha, final String trialId, final String status) {
        return new Report(SCHEMA_VERSION, EVIDENCE_CLASS, false,
                window == null ? "process-observation-including-warmup" : "measurement-window",
                CLOCK, "26.3", Objects.requireNonNull(sourceSha, "sourceSha"),
                Objects.requireNonNull(trialId, "trialId"), Objects.requireNonNull(status, "status"),
                MEASUREMENT_LIMITS, snapshot());
    }

    private Snapshot snapshotLocked(final long timestampOffset) {
        final ArrayList<Event> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new Event(sequence[i], invocationId[i], Stage.values()[stage[i]], contextId[i],
                    threadId[i], startOffsetNanos[i], endOffsetNanos[i], completed[i], queueBefore[i],
                    queueAfter[i], workCount[i], resultCode[i]));
        }
        final ArrayList<ActiveInvocation> activeRows = new ArrayList<>();
        for (int i = 0; i < active.length; i++) {
            if (active[i]) {
                activeRows.add(new ActiveInvocation(activeToken[i], Stage.values()[activeStage[i]],
                        activeContextId[i], activeThreadId[i], activeStartOffsetNanos[i], activeQueueBefore[i]));
            }
        }
        return new Snapshot(SCHEMA_VERSION, capacity, eventCount, droppedEvents, invalidEvents,
                contextOverflowEvents, liveContextCount(), startedInvocations, finishedInvocations,
                activeOverflowEvents, activeRows, events, window, timestampOffset);
    }

    private int findActive(final long token) {
        if (token <= 0L) return -1;
        for (int i = 0; i < active.length; i++) {
            if (active[i] && activeToken[i] == token) return i;
        }
        return -1;
    }

    private void clearActive(final int slot) {
        active[slot] = false;
        activeToken[slot] = 0L;
    }

    private int activeCount() {
        int count = 0;
        for (boolean value : active) if (value) count++;
        return count;
    }

    private long offset(final long timestamp) {
        try {
            return Math.subtractExact(timestamp, originNanos);
        } catch (ArithmeticException overflow) {
            return -1L;
        }
    }

    private long installContext(final int slot, final Object context) {
        final long id = nextContextId;
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
        for (int i = 0; i < contextSlots; i++) if (contexts[i].get() != null) live++;
        return live;
    }

    private static boolean validGauge(final long value) {
        return value == UNAVAILABLE || value >= 0L;
    }

    private static long saturatingIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static int configuredCapacity() {
        final String configured = System.getProperty("metallum.validation.worldStageCapacity");
        if (configured == null) return DEFAULT_CAPACITY;
        try {
            final long parsed = Long.parseLong(configured.trim());
            if (parsed < 1L || parsed > MAX_CAPACITY) {
                throw new IllegalArgumentException("world stage capacity must be in [1," + MAX_CAPACITY + "]");
            }
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("world stage capacity must be an integer", ignored);
        }
    }

    public record Event(long sequence, long invocationId, Stage stage, long contextId, long threadId,
                        long startOffsetNanos, long endOffsetNanos, boolean completed,
                        long queueBefore, long queueAfter, long workCount, int resultCode) {
        public String role() { return stage.role(); }
        public String contextKind() { return stage.contextKind(); }
    }

    public record ActiveInvocation(long invocationId, Stage stage, long contextId, long threadId,
                                   long startOffsetNanos, long queueBefore) { }

    public record Window(String id, long startFrameInclusive, long endFrameExclusive,
                         long startOffsetNanos, long endOffsetNanos, long activeAtStart,
                         boolean closed) { }

    public record Snapshot(int schemaVersion, int eventCapacity, long eventCount,
                           long droppedEvents, long invalidEvents, long contextOverflowEvents,
                           long liveContextCount, long startedInvocations, long finishedInvocations,
                           long activeOverflowEvents, List<ActiveInvocation> activeInvocations,
                           List<Event> events, Window window, long timestampOffsetNanos) {
        public Snapshot {
            activeInvocations = List.copyOf(activeInvocations);
            events = List.copyOf(events);
        }
    }

    public record Report(int schemaVersion, String evidenceClass, boolean performanceEligible,
                         String scope, String clock, String minecraftVersion, String sourceSha,
                         String trialId, String status, String measurementLimits, Snapshot snapshot) {
        public Report {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
            }
            evidenceClass = Objects.requireNonNull(evidenceClass, "evidenceClass");
            if (!EVIDENCE_CLASS.equals(evidenceClass)) {
                throw new IllegalArgumentException("evidenceClass must be diagnostic");
            }
            if (performanceEligible) {
                throw new IllegalArgumentException("world stage evidence is diagnostic-only");
            }
            scope = Objects.requireNonNull(scope, "scope");
            if (!"measurement-window".equals(scope)
                    && !"process-observation-including-warmup".equals(scope)) {
                throw new IllegalArgumentException("invalid world stage report scope");
            }
            clock = Objects.requireNonNull(clock, "clock");
            if (!CLOCK.equals(clock)) throw new IllegalArgumentException("clock must be System.nanoTime");
            minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            if (!"26.3".equals(minecraftVersion)) {
                throw new IllegalArgumentException("minecraftVersion must be 26.3");
            }
            sourceSha = Objects.requireNonNull(sourceSha, "sourceSha");
            if (!sourceSha.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("sourceSha must be a lowercase 40-hex commit");
            }
            trialId = Objects.requireNonNull(trialId, "trialId");
            if (trialId.isBlank() || trialId.length() > 160) {
                throw new IllegalArgumentException("trialId must be a non-empty string <= 160 characters");
            }
            status = Objects.requireNonNull(status, "status");
            if (status.isBlank() || status.length() > 128) {
                throw new IllegalArgumentException("status must be a non-empty string <= 128 characters");
            }
            measurementLimits = Objects.requireNonNull(measurementLimits, "measurementLimits");
            if (!MEASUREMENT_LIMITS.equals(measurementLimits)) {
                throw new IllegalArgumentException("measurementLimits do not match the bounded interval contract");
            }
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
