package com.metallum.client.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generation-aware state machine for vanilla 26.3 terrain observability.
 *
 * <p>This class deliberately has no Minecraft or Mixin types. Version mutation, build, upload,
 * publication and draw-authority hooks adapt into this state machine; the state machine never
 * reaches back into game objects to infer policy. Unknown or missing identities therefore produce
 * no event instead of a guessed event.</p>
 *
 * <p>Lifecycle mutation and evidence admission share this tracker's monitor. This makes checking
 * revisions and recording publication atomic with dirty/reset/world transitions, including empty
 * results published by workers. No game callbacks, GPU waits or file I/O run under this lock.</p>
 */
public final class VanillaTerrainWorkTracker {
    public enum DirtyKind {
        GEOMETRY,
        LIGHTING,
        GEOMETRY_AND_LIGHTING
    }

    /**
     * Immutable authority token captured only after a mesh generation has been published.
     * A draw hook may report FIRST_VALID_DRAW only with one of these tokens.
     */
    public record DrawToken(TerrainWorkEventRecorder.WorkKey key, long meshGeneration) {
        public DrawToken {
            Objects.requireNonNull(key, "key");
            if (meshGeneration < 0L) {
                throw new IllegalArgumentException("meshGeneration must be non-negative");
            }
        }
    }

    private static final long INITIAL_REVISION = 1L;
    private static final long INITIAL_EPOCH = 1L;
    private static final int DEFAULT_MAX_TRACKED_SECTIONS = 32768;
    private static final int MAX_TRACKED_SECTIONS_LIMIT = 1 << 20;

    private final TerrainWorkEventRecorder recorder;
    private final int maxTrackedSections;
    private final AtomicLong worldEpoch = new AtomicLong(INITIAL_EPOCH);
    private final AtomicLong materialGeneration = new AtomicLong(INITIAL_REVISION);
    private final AtomicLong nextMeshGeneration = new AtomicLong(INITIAL_REVISION);
    private final AtomicLong nextFrameIndex = new AtomicLong();
    private final AtomicLong nextWorkId = new AtomicLong(INITIAL_REVISION);
    private final ConcurrentHashMap<Long, SectionRevision> sectionRevisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, WorkContext> latestBySection = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, WorkContext> publishedBySection = new ConcurrentHashMap<>();
    private final Map<Object, WorkContext> regionContexts =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Object, WorkContext> meshContexts =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final ThreadLocal<WorkContext> activeBuild = new ThreadLocal<>();

    public VanillaTerrainWorkTracker(final TerrainWorkEventRecorder recorder) {
        this(recorder, DEFAULT_MAX_TRACKED_SECTIONS);
    }

    VanillaTerrainWorkTracker(final TerrainWorkEventRecorder recorder, final int maxTrackedSections) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        if (maxTrackedSections < 1 || maxTrackedSections > MAX_TRACKED_SECTIONS_LIMIT) {
            throw new IllegalArgumentException("maxTrackedSections out of range: " + maxTrackedSections);
        }
        this.maxTrackedSections = maxTrackedSections;
    }

    public long worldEpoch() {
        return worldEpoch.get();
    }

    public long materialGeneration() {
        return materialGeneration.get();
    }

    public long nextFrameIndex() {
        return nextFrameIndex.getAndIncrement();
    }

    /**
     * Marks a real render-input mutation. Callers choose the narrowest kind they can prove.
     * Unknown mutation sources must conservatively use GEOMETRY_AND_LIGHTING.
     */
    public synchronized void markDirty(final long sectionId, final DirtyKind kind) {
        Objects.requireNonNull(kind, "kind");
        SectionRevision revision = sectionRevisions.get(sectionId);
        if (revision == null) {
            if (!admitSectionIdentity(sectionId)) {
                recorder.markDropped();
                return;
            }
            revision = sectionRevisions.computeIfAbsent(
                    sectionId,
                    ignored -> new SectionRevision(INITIAL_REVISION, INITIAL_REVISION)
            );
        }
        switch (kind) {
            case GEOMETRY -> revision.geometry.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
            case LIGHTING -> revision.lighting.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
            case GEOMETRY_AND_LIGHTING -> {
                revision.geometry.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
                revision.lighting.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
            }
        }
    }

    /**
     * Starts a work item from a safe immutable render-region snapshot.
     *
     * @param regionIdentity exact immutable snapshot identity; compared by object identity
     * @param queued true for async dispatcher work, false for synchronous near-player work
     */
    public synchronized void beginWork(
            final long sectionId,
            final Object regionIdentity,
            final boolean queued,
            final long nowNanos
    ) {
        Objects.requireNonNull(regionIdentity, "regionIdentity");
        SectionRevision revision = sectionRevisions.get(sectionId);
        if (revision == null) {
            if (!admitSectionIdentity(sectionId)) {
                recorder.markDropped();
                return;
            }
            revision = sectionRevisions.computeIfAbsent(
                    sectionId,
                    ignored -> new SectionRevision(INITIAL_REVISION, INITIAL_REVISION)
            );
        }
        TerrainWorkEventRecorder.WorkKey key = new TerrainWorkEventRecorder.WorkKey(
                worldEpoch.get(),
                sectionId,
                revision.geometry.get(),
                revision.lighting.get(),
                materialGeneration.get()
        );
        WorkContext context = new WorkContext(
                key,
                nextWorkId.getAndUpdate(VanillaTerrainWorkTracker::incrementGeneration)
        );
        WorkContext previous = latestBySection.put(sectionId, context);
        if (previous != null && previous != context && !previous.isTerminal()) {
            previous.cancel(recorder, nowNanos, "superseded-before-build");
            if (previous.isTerminal()) {
                removeContextMappings(previous);
            }
        }
        synchronized (regionContexts) {
            if (!regionContexts.containsKey(regionIdentity) && regionContexts.size() >= maxTrackedSections) {
                latestBySection.remove(sectionId, context);
                recorder.markDropped();
                return;
            }
            WorkContext displaced = regionContexts.put(regionIdentity, context);
            if (displaced != null && displaced != context && !displaced.isTerminal()) {
                displaced.cancel(recorder, nowNanos, "snapshot-identity-reused");
            }
        }
        record(context, TerrainWorkEventRecorder.Stage.DATA_READY, nowNanos, 0L,
                "immutable-region-ready", TerrainWorkEventRecorder.NO_FRAME,
                TerrainWorkEventRecorder.NO_MESH_GENERATION);
        if (queued) {
            record(context, TerrainWorkEventRecorder.Stage.QUEUED, nowNanos, 0L,
                    "dispatcher-queued", TerrainWorkEventRecorder.NO_FRAME,
                    TerrainWorkEventRecorder.NO_MESH_GENERATION);
        }
    }

    /**
     * Claims a previously admitted snapshot on the worker actually compiling it.
     * Returns false when the region is unknown/stale; callers must not synthesize a key.
     */
    public synchronized boolean beginBuild(final Object regionIdentity, final long nowNanos) {
        Objects.requireNonNull(regionIdentity, "regionIdentity");
        final WorkContext context;
        synchronized (regionContexts) {
            context = regionContexts.remove(regionIdentity);
        }
        if (!admitCurrentWork(context, nowNanos)) {
            activeBuild.remove();
            return false;
        }
        activeBuild.set(context);
        record(context, TerrainWorkEventRecorder.Stage.BUILD_START, nowNanos, 0L,
                "section-compiler-enter", TerrainWorkEventRecorder.NO_FRAME,
                TerrainWorkEventRecorder.NO_MESH_GENERATION);
        return true;
    }

    public synchronized void endBuild(final long nowNanos) {
        WorkContext context = activeBuild.get();
        if (context == null || context.isTerminal()) {
            return;
        }
        if (context.buildEnded.compareAndSet(false, true)) {
            record(context, TerrainWorkEventRecorder.Stage.BUILD_END, nowNanos, 0L,
                    "section-compiler-return", TerrainWorkEventRecorder.NO_FRAME,
                    TerrainWorkEventRecorder.NO_MESH_GENERATION);
        }
    }

    /**
     * Associates the just-constructed CompiledSectionMesh with the active compiler context.
     * The thread-local remains until staging or direct publication consumes it, which is needed
     * for EMPTY publication where Minecraft substitutes a shared sentinel for the constructed mesh.
     */
    public synchronized void bindConstructedMesh(final Object meshIdentity) {
        Objects.requireNonNull(meshIdentity, "meshIdentity");
        WorkContext context = activeBuild.get();
        if (context == null || context.isTerminal()) {
            return;
        }
        synchronized (meshContexts) {
            // An upload callback may run while another build context is active on this thread.
            // It must never steal an already associated mesh from its original work item.
            if (!meshContexts.containsKey(meshIdentity) && meshContexts.size() >= maxTrackedSections) {
                recorder.markDropped();
                return;
            }
            meshContexts.putIfAbsent(meshIdentity, context);
        }
    }

    public synchronized void uploadQueued(
            final Object meshIdentity,
            final long bytes,
            final String reason,
            final long nowNanos
    ) {
        WorkContext context = contextForMesh(meshIdentity);
        if (context == null || context.isTerminal()) {
            clearActiveBuildIfMatches(context);
            return;
        }
        record(context, TerrainWorkEventRecorder.Stage.UPLOAD_QUEUED, nowNanos, bytes,
                reason, TerrainWorkEventRecorder.NO_FRAME,
                TerrainWorkEventRecorder.NO_MESH_GENERATION);
        clearActiveBuildIfMatches(context);
    }

    /**
     * The current vanilla upload callback runs after uploader.copyTo has been encoded. This is
     * GPU_ENCODED only; it intentionally does not claim GPU completion or dependency readiness.
     */
    public synchronized void gpuEncoded(
            final Object meshIdentity,
            final long bytes,
            final String reason,
            final long nowNanos
    ) {
        WorkContext context = contextForMesh(meshIdentity);
        if (context == null || context.isTerminal()) {
            return;
        }
        record(context, TerrainWorkEventRecorder.Stage.GPU_ENCODED, nowNanos, bytes,
                reason, TerrainWorkEventRecorder.NO_FRAME,
                TerrainWorkEventRecorder.NO_MESH_GENERATION);
    }

    /**
     * Records the exact generation made visible by RenderSection.setSectionMesh.
     *
     * <p>The constructed mesh identity is authoritative. Shared EMPTY sentinels must use
     * {@link #publishEmpty(long, long, String)} explicitly; an unknown mesh is not an empty mesh.</p>
     */
    public synchronized DrawToken publish(
            final long sectionId,
            final Object meshIdentity,
            final long nowNanos,
            final String reason
    ) {
        return publishContext(sectionId, contextForMesh(meshIdentity), nowNanos, reason);
    }

    /** Only the vanilla hook that recognizes CompiledSectionMesh.EMPTY may use this boundary. */
    public synchronized DrawToken publishEmpty(final long sectionId, final long nowNanos, final String reason) {
        WorkContext context = activeBuild.get();
        DrawToken token = publishContext(sectionId, context, nowNanos, reason);
        if (token != null && context != null) {
            context.empty.set(true);
            recorder.record(
                    context.key,
                    TerrainWorkEventRecorder.Stage.DRAW_NOT_REQUIRED,
                    nowNanos,
                    0L,
                    "empty-mesh-no-draw",
                    "main",
                    TerrainWorkEventRecorder.NO_FRAME,
                    token.meshGeneration(),
                    context.workId
            );
        }
        return token;
    }

    private DrawToken publishContext(
            final long sectionId, final WorkContext context, final long nowNanos, final String reason
    ) {
        if (context == null || context.key.sectionId() != sectionId
                || !admitCurrentWork(context, nowNanos) || !context.buildEnded.get()) {
            return null;
        }
        long generation = context.publish(nextMeshGeneration, recorder, nowNanos, reason);
        if (generation >= 0L) {
            WorkContext previousPublished = publishedBySection.put(sectionId, context);
            if (previousPublished != null && previousPublished != context && previousPublished.empty.get()) {
                terminateContext(previousPublished, nowNanos, "empty-result-replaced");
            }
        }
        clearActiveBuildIfMatches(context);
        if (generation < 0L) {
            return null;
        }
        return new DrawToken(context.key, generation);
    }

    public synchronized DrawToken drawTokenForMesh(final Object meshIdentity) {
        WorkContext context = contextForMesh(meshIdentity);
        if (context == null) {
            return null;
        }
        long generation = context.meshGeneration.get();
        return generation < 0L || context.isTerminal() || !hasCurrentRevision(context)
                ? null
                : new DrawToken(context.key, generation);
    }

    /**
     * Reports actual draw authority. Repeated passes/layers are coalesced to the first draw for
     * this exact published mesh generation.
     */
    public synchronized void firstValidDraw(final DrawToken token, final long frameIndex, final long nowNanos) {
        if (token == null || frameIndex < 0L) {
            return;
        }
        WorkContext context = latestContextForToken(token);
        if (context == null || context.isTerminal() || !hasCurrentRevision(context)) {
            return;
        }
        if (context.firstDraw.compareAndSet(false, true)) {
            record(context, TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW, nowNanos, 0L,
                    "draw-authority", frameIndex, token.meshGeneration());
        }
    }

    public synchronized void retireMesh(final Object meshIdentity, final long nowNanos, final String reason) {
        WorkContext context = contextForMesh(meshIdentity);
        if (context == null) {
            return;
        }
        terminateContext(context, nowNanos, reason);
    }

    /**
     * Cancels not-yet-published work, or retires a published mesh, at a real section reset.
     */
    public synchronized void invalidateSection(final long sectionId, final long nowNanos, final String reason) {
        // A replacement build can coexist with an older published mesh still used for drawing.
        // Reset invalidates both owners, not only the most recently admitted work item.
        java.util.Set<WorkContext> contexts = Collections.newSetFromMap(new IdentityHashMap<>());
        WorkContext latest = latestBySection.get(sectionId);
        if (latest != null) {
            contexts.add(latest);
        }
        WorkContext published = publishedBySection.get(sectionId);
        if (published != null) {
            contexts.add(published);
        }
        for (WorkContext context : meshContexts.values()) {
            if (context.key.sectionId() == sectionId) {
                contexts.add(context);
            }
        }
        for (WorkContext context : contexts) {
            terminateContext(context, nowNanos, reason);
        }
    }

    /**
     * Full compiled-geometry invalidation generation. This may advance more often than a resource
     * pack reload, but never less often than the caller-observed invalidation point.
     */
    public synchronized long advanceMaterialGeneration() {
        return materialGeneration.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
    }

    /**
     * Switches world epoch and invalidates every outstanding context. Call only at the exact
     * LevelExtractor world transition hook, not on camera/view changes.
     */
    public synchronized long advanceWorldEpoch(final long nowNanos) {
        List<WorkContext> contexts = new ArrayList<>();
        contexts.addAll(latestBySection.values());
        contexts.addAll(publishedBySection.values());
        synchronized (regionContexts) {
            contexts.addAll(regionContexts.values());
        }
        synchronized (meshContexts) {
            contexts.addAll(meshContexts.values());
        }
        java.util.Set<WorkContext> uniqueContexts =
                Collections.newSetFromMap(new IdentityHashMap<>());
        uniqueContexts.addAll(contexts);
        for (WorkContext context : uniqueContexts) {
            if (context.meshGeneration.get() >= 0L) {
                context.retire(recorder, nowNanos, "world-epoch-change");
            } else {
                context.cancel(recorder, nowNanos, "world-epoch-change");
            }
        }
        latestBySection.clear();
        publishedBySection.clear();
        sectionRevisions.clear();
        synchronized (regionContexts) {
            regionContexts.clear();
        }
        synchronized (meshContexts) {
            meshContexts.clear();
        }
        activeBuild.remove();
        return worldEpoch.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
    }

    public synchronized TerrainWorkEventRecorder.Snapshot snapshot() {
        return recorder.snapshot();
    }

    private WorkContext contextForMesh(final Object meshIdentity) {
        if (meshIdentity == null) {
            return null;
        }
        synchronized (meshContexts) {
            return meshContexts.get(meshIdentity);
        }
    }

    private boolean admitSectionIdentity(final long sectionId) {
        if (sectionRevisions.containsKey(sectionId) || sectionRevisions.size() < maxTrackedSections) {
            return true;
        }

        // Keep observation bounded without perturbing vanilla scheduling. Evict only identities
        // with no active work and no published result; otherwise fail the observation closed.
        for (Long candidate : new ArrayList<>(sectionRevisions.keySet())) {
            if (candidate == sectionId
                    || latestBySection.containsKey(candidate)
                    || publishedBySection.containsKey(candidate)) {
                continue;
            }
            if (sectionRevisions.remove(candidate) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCurrentRevision(final WorkContext context) {
        TerrainWorkEventRecorder.WorkKey key = context.key;
        SectionRevision revision = sectionRevisions.get(key.sectionId());
        return key.worldEpoch() == worldEpoch.get()
                && key.materialGeneration() == materialGeneration.get()
                && revision != null
                && key.geometryRevision() == revision.geometry.get()
                && key.lightingRevision() == revision.lighting.get();
    }

    private boolean admitCurrentWork(final WorkContext context, final long nowNanos) {
        if (context == null || context.isTerminal()) {
            return false;
        }
        if (!hasCurrentRevision(context)) {
            if (context.meshGeneration.get() < 0L) {
                terminateContext(context, nowNanos, "stale-work-revision");
            }
            // Published geometry may still be drawn by vanilla until replacement. Keep its
            // ownership for the real release hook, but never label a stale draw as first-valid.
            return false;
        }
        return true;
    }

    private void terminateContext(final WorkContext context, final long nowNanos, final String reason) {
        if (context.meshGeneration.get() >= 0L) {
            context.retire(recorder, nowNanos, reason);
        } else {
            context.cancel(recorder, nowNanos, reason);
        }
        latestBySection.remove(context.key.sectionId(), context);
        publishedBySection.remove(context.key.sectionId(), context);
        removeContextMappings(context);
        clearActiveBuildIfMatches(context);
    }

    private WorkContext latestContextForToken(final DrawToken token) {
        WorkContext context = latestBySection.get(token.key().sectionId());
        if (context != null
                && context.key.equals(token.key())
                && context.meshGeneration.get() == token.meshGeneration()) {
            return context;
        }
        synchronized (meshContexts) {
            for (WorkContext candidate : meshContexts.values()) {
                if (candidate.key.equals(token.key())
                        && candidate.meshGeneration.get() == token.meshGeneration()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void removeContextMappings(final WorkContext context) {
        synchronized (regionContexts) {
            regionContexts.entrySet().removeIf(entry -> entry.getValue() == context);
        }
        synchronized (meshContexts) {
            meshContexts.entrySet().removeIf(entry -> entry.getValue() == context);
        }
    }

    private void clearActiveBuildIfMatches(final WorkContext context) {
        if (context != null && activeBuild.get() == context) {
            activeBuild.remove();
        }
    }

    private void record(
            final WorkContext context,
            final TerrainWorkEventRecorder.Stage stage,
            final long nowNanos,
            final long bytes,
            final String reason,
            final long frameIndex,
            final long meshGeneration
    ) {
        recorder.record(
                context.key,
                stage,
                nowNanos,
                bytes,
                reason,
                "main",
                frameIndex,
                meshGeneration,
                context.workId
        );
    }

    private static long incrementGeneration(final long current) {
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("terrain generation counter exhausted");
        }
        return current + 1L;
    }

    private static final class SectionRevision {
        private final AtomicLong geometry;
        private final AtomicLong lighting;

        private SectionRevision(final long geometry, final long lighting) {
            this.geometry = new AtomicLong(geometry);
            this.lighting = new AtomicLong(lighting);
        }
    }

    private final class WorkContext {
        private final TerrainWorkEventRecorder.WorkKey key;
        private final long workId;
        private final AtomicBoolean buildEnded = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();
        private final AtomicBoolean firstDraw = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicBoolean empty = new AtomicBoolean();
        private final AtomicLong meshGeneration = new AtomicLong(TerrainWorkEventRecorder.NO_MESH_GENERATION);

        private WorkContext(final TerrainWorkEventRecorder.WorkKey key, final long workId) {
            this.key = key;
            this.workId = workId;
        }

        private boolean isTerminal() {
            return cancelled.get() || retired.get();
        }

        private long publish(
                final AtomicLong generationSource,
                final TerrainWorkEventRecorder eventRecorder,
                final long nowNanos,
                final String reason
        ) {
            if (isTerminal()) {
                return -1L;
            }
            if (published.compareAndSet(false, true)) {
                long generation = generationSource.getAndUpdate(VanillaTerrainWorkTracker::incrementGeneration);
                meshGeneration.set(generation);
                eventRecorder.record(
                        key,
                        TerrainWorkEventRecorder.Stage.PUBLISHED,
                        nowNanos,
                        0L,
                        reason,
                        "main",
                        TerrainWorkEventRecorder.NO_FRAME,
                        generation,
                        workId
                );
            }
            return meshGeneration.get();
        }

        private void cancel(
                final TerrainWorkEventRecorder eventRecorder,
                final long nowNanos,
                final String reason
        ) {
            if (meshGeneration.get() >= 0L || retired.get()) {
                return;
            }
            if (cancelled.compareAndSet(false, true)) {
                eventRecorder.record(
                        key,
                        TerrainWorkEventRecorder.Stage.CANCELLED,
                        nowNanos,
                        0L,
                        reason,
                        "main",
                        TerrainWorkEventRecorder.NO_FRAME,
                        TerrainWorkEventRecorder.NO_MESH_GENERATION,
                        workId
                );
            }
        }

        private void retire(
                final TerrainWorkEventRecorder eventRecorder,
                final long nowNanos,
                final String reason
        ) {
            long generation = meshGeneration.get();
            if (generation < 0L || cancelled.get()) {
                return;
            }
            if (retired.compareAndSet(false, true)) {
                eventRecorder.record(
                        key,
                        TerrainWorkEventRecorder.Stage.RETIRED,
                        nowNanos,
                        0L,
                        reason,
                        "main",
                        TerrainWorkEventRecorder.NO_FRAME,
                        generation,
                        workId
                );
            }
        }
    }
}
