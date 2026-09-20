package com.metallum.client.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, allocation-free-on-record terrain work event ring for Minecraft 26.3 P0 telemetry.
 *
 * <p>The hot path writes primitive fields into a fixed-size ring. Snapshot materialization is
 * deliberately separated from recording so build/upload/draw paths do not allocate an event object
 * or perform file I/O. A slot monitor provides simple publication safety across worker and render
 * threads; telemetry is opt-in, so correctness and bounded memory take priority over speculative
 * lock-free machinery.</p>
 */
public final class TerrainWorkEventRecorder {
    public enum Stage {
        DATA_READY,
        QUEUED,
        BUILD_START,
        BUILD_END,
        UPLOAD_QUEUED,
        GPU_ENCODED,
        GPU_DEPENDENCY_READY,
        PUBLISHED,
        FIRST_VALID_DRAW,
        GPU_COMPLETED,
        CANCELLED,
        RETIRED
    }

    public record WorkKey(
            long worldEpoch,
            long sectionId,
            long geometryRevision,
            long lightingRevision,
            long materialGeneration
    ) {
        public WorkKey {
            requireUnsigned("worldEpoch", worldEpoch);
            requireUnsigned("geometryRevision", geometryRevision);
            requireUnsigned("lightingRevision", lightingRevision);
            requireUnsigned("materialGeneration", materialGeneration);
        }
    }

    public record Event(
            long sequence,
            WorkKey key,
            Stage stage,
            long monotonicNanos,
            long bytes,
            String reason,
            String domain,
            long frameIndex,
            long meshGeneration
    ) {
        public boolean hasFrameIndex() {
            return frameIndex >= 0L;
        }

        public boolean hasMeshGeneration() {
            return meshGeneration >= 0L;
        }
    }

    public record Snapshot(
            long observationStartNanos,
            long observationEndNanos,
            long claimedEvents,
            long droppedEvents,
            boolean overflowed,
            List<Event> events
    ) {
        public Snapshot {
            events = List.copyOf(events);
        }
    }

    public static final int DEFAULT_CAPACITY = 8192;
    public static final int MAX_CAPACITY = 1 << 20;
    public static final long NO_FRAME = -1L;
    public static final long NO_MESH_GENERATION = -1L;

    private final int capacity;
    private final Object[] slotGuards;
    private final long[] sequence;
    private final long[] worldEpoch;
    private final long[] sectionId;
    private final long[] geometryRevision;
    private final long[] lightingRevision;
    private final long[] materialGeneration;
    private final byte[] stage;
    private final long[] monotonicNanos;
    private final long[] bytes;
    private final String[] reason;
    private final String[] domain;
    private final long[] frameIndex;
    private final long[] meshGeneration;
    private final AtomicLong cursor = new AtomicLong();
    private final long observationStartNanos;

    public TerrainWorkEventRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public TerrainWorkEventRecorder(final int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1, " + MAX_CAPACITY + "]: " + capacity);
        }
        this.capacity = capacity;
        this.slotGuards = new Object[capacity];
        Arrays.setAll(this.slotGuards, ignored -> new Object());
        this.sequence = new long[capacity];
        Arrays.fill(this.sequence, -1L);
        this.worldEpoch = new long[capacity];
        this.sectionId = new long[capacity];
        this.geometryRevision = new long[capacity];
        this.lightingRevision = new long[capacity];
        this.materialGeneration = new long[capacity];
        this.stage = new byte[capacity];
        this.monotonicNanos = new long[capacity];
        this.bytes = new long[capacity];
        this.reason = new String[capacity];
        this.domain = new String[capacity];
        this.frameIndex = new long[capacity];
        Arrays.fill(this.frameIndex, NO_FRAME);
        this.meshGeneration = new long[capacity];
        Arrays.fill(this.meshGeneration, NO_MESH_GENERATION);
        this.observationStartNanos = System.nanoTime();
    }

    public int capacity() {
        return capacity;
    }

    public long claimedEvents() {
        return cursor.get();
    }

    public long record(
            final WorkKey key,
            final Stage eventStage,
            final long eventNanos,
            final long eventBytes,
            final String eventReason,
            final String eventDomain,
            final long eventFrameIndex,
            final long eventMeshGeneration
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(eventStage, "eventStage");
        Objects.requireNonNull(eventReason, "eventReason");
        Objects.requireNonNull(eventDomain, "eventDomain");
        if (eventNanos < 0L) {
            throw new IllegalArgumentException("eventNanos must be non-negative");
        }
        if (eventBytes < 0L) {
            throw new IllegalArgumentException("eventBytes must be non-negative");
        }
        if (eventReason.isEmpty()) {
            throw new IllegalArgumentException("eventReason must not be empty");
        }
        if (eventDomain.isEmpty()) {
            throw new IllegalArgumentException("eventDomain must not be empty");
        }
        if (eventFrameIndex < NO_FRAME) {
            throw new IllegalArgumentException("eventFrameIndex must be >= -1");
        }
        if (eventMeshGeneration < NO_MESH_GENERATION) {
            throw new IllegalArgumentException("eventMeshGeneration must be >= -1");
        }
        if ((eventStage == Stage.PUBLISHED || eventStage == Stage.FIRST_VALID_DRAW)
                && eventMeshGeneration == NO_MESH_GENERATION) {
            throw new IllegalArgumentException(eventStage + " requires meshGeneration");
        }
        if (eventStage == Stage.FIRST_VALID_DRAW && eventFrameIndex == NO_FRAME) {
            throw new IllegalArgumentException("FIRST_VALID_DRAW requires frameIndex");
        }

        final long eventSequence = cursor.getAndIncrement();
        final int slot = (int) Math.floorMod(eventSequence, capacity);
        synchronized (slotGuards[slot]) {
            // Publish the sequence last. Snapshot readers holding the same monitor can therefore
            // never pair a sequence from one event with fields from another wrap of this slot.
            this.worldEpoch[slot] = key.worldEpoch();
            this.sectionId[slot] = key.sectionId();
            this.geometryRevision[slot] = key.geometryRevision();
            this.lightingRevision[slot] = key.lightingRevision();
            this.materialGeneration[slot] = key.materialGeneration();
            this.stage[slot] = (byte) eventStage.ordinal();
            this.monotonicNanos[slot] = eventNanos;
            this.bytes[slot] = eventBytes;
            this.reason[slot] = eventReason;
            this.domain[slot] = eventDomain;
            this.frameIndex[slot] = eventFrameIndex;
            this.meshGeneration[slot] = eventMeshGeneration;
            this.sequence[slot] = eventSequence;
        }
        return eventSequence;
    }

    public Snapshot snapshot() {
        final long endNanos = System.nanoTime();
        final long endSequenceExclusive = cursor.get();
        final long firstSequence = Math.max(0L, endSequenceExclusive - capacity);
        final long overwritten = Math.max(0L, endSequenceExclusive - capacity);
        long unstableOrMissing = 0L;
        final int expectedSize = (int) Math.min((long) capacity, endSequenceExclusive);
        final List<Event> events = new ArrayList<>(expectedSize);

        for (long expectedSequence = firstSequence; expectedSequence < endSequenceExclusive; expectedSequence++) {
            final int slot = (int) Math.floorMod(expectedSequence, capacity);
            synchronized (slotGuards[slot]) {
                if (sequence[slot] != expectedSequence) {
                    unstableOrMissing++;
                    continue;
                }
                final WorkKey key = new WorkKey(
                        worldEpoch[slot],
                        sectionId[slot],
                        geometryRevision[slot],
                        lightingRevision[slot],
                        materialGeneration[slot]
                );
                events.add(new Event(
                        expectedSequence,
                        key,
                        Stage.values()[Byte.toUnsignedInt(stage[slot])],
                        monotonicNanos[slot],
                        bytes[slot],
                        reason[slot],
                        domain[slot],
                        frameIndex[slot],
                        meshGeneration[slot]
                ));
            }
        }

        final long dropped = saturatedAdd(overwritten, unstableOrMissing);
        return new Snapshot(
                observationStartNanos,
                endNanos,
                endSequenceExclusive,
                dropped,
                dropped != 0L,
                events
        );
    }

    private static void requireUnsigned(final String name, final long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static long saturatedAdd(final long left, final long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
