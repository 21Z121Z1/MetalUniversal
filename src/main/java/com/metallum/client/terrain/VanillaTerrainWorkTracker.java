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

    private final TerrainWorkEventRecorder recorder;
    private final AtomicLong worldEpoch = new AtomicLong(INITIAL_EPOCH);
    private final AtomicLong materialGeneration = new AtomicLong(INITIAL_REVISION);
    private final AtomicLong nextMeshGeneration = new AtomicLong(INITIAL_REVISION);
    private final AtomicLong nextFrameIndex = new AtomicLong();
    private final ConcurrentHashMap<Long, SectionRevision> sectionRevisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, WorkContext> latestBySection = new ConcurrentHashMap<>();
    private final Map<Object, WorkContext> regionContexts =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Object, WorkContext> meshContexts =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final ThreadLocal<WorkContext> activeBuild = new ThreadLocal<>();

    public VanillaTerrainWorkTracker(final TerrainWorkEventRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
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
    public void markDirty(final long sectionId, final DirtyKind kind) {
        Objects.requireNonNull(kind, "kind");
        SectionRevision revision = sectionRevisions.computeIfAbsent(
                sectionId,
                ignored -> new SectionRevision(INITIAL_REVISION, INITIAL_REVISION)
        );
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
    public void beginWork(
            final long sectionId,
            final Object regionIdentity,
            final boolean queued,
            final long nowNanos
    ) {
        Objects.requireNonNull(regionIdentity, "regionIdentity");
        SectionRevision revision = sectionRevisions.computeIfAbsent(
                sectionId,
                ignored -> new SectionRevision(INITIAL_REVISION, INITIAL_REVISION)
        );
        TerrainWorkEventRecorder.WorkKey key = new TerrainWorkEventRecorder.WorkKey(
                worldEpoch.get(),
                sectionId,
                revision.geometry.get(),
                revision.lighting.get(),
                materialGeneration.get()
        );
        WorkContext context = new WorkContext(key);
        WorkContext previous = latestBySection.put(sectionId, context);
        if (previous != null && previous != context && !previous.isTerminal()) {
            previous.cancel(recorder, nowNanos, "superseded-before-build");
        }
        synchronized (regionContexts) {
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
    public boolean beginBuild(final Object regionIdentity, final long nowNanos) {
        Objects.requireNonNull(regionIdentity, "regionIdentity");
        final WorkContext context;
        synchronized (regionContexts) {
            context = regionContexts.remove(regionIdentity);
        }
        if (context == null || context.isTerminal()) {
            activeBuild.remove();
            return false;
        }
        activeBuild.set(context);
        record(context, TerrainWorkEventRecorder.Stage.BUILD_START, nowNanos, 0L,
                "section-compiler-enter", TerrainWorkEventRecorder.NO_FRAME,
                TerrainWorkEventRecorder.NO_MESH_GENERATION);
        return true;
    }

    public void endBuild(final long nowNanos) {
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
    public void bindConstructedMesh(final Object meshIdentity) {
        Objects.requireNonNull(meshIdentity, "meshIdentity");
        WorkContext context = activeBuild.get();
        if (context == null || context.isTerminal()) {
            return;
        }
        synchronized (meshContexts) {
            meshContexts.put(meshIdentity, context);
        }
    }

    public void uploadQueued(
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
    public void gpuEncoded(
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
     * <p>For non-empty meshes the constructed mesh identity is authoritative. For the shared EMPTY
     * sentinel the active build context is authoritative; if neither is available the event is
     * rejected rather than attributed to the latest section work by guesswork.</p>
     */
    public DrawToken publish(
            final long sectionId,
            final Object meshIdentity,
            final long nowNanos,
            final String reason
    ) {
        WorkContext context = contextForMesh(meshIdentity);
        if (context == null) {
            WorkContext active = activeBuild.get();
            if (active != null && active.key.sectionId() == sectionId) {
                context = active;
            }
        }
        if (context == null || context.isTerminal()) {
            return null;
        }
        long generation = context.publish(nextMeshGeneration, recorder, nowNanos, reason);
        clearActiveBuildIfMatches(context);
        if (generation < 0L) {
            return null;
        }
        return new DrawToken(context.key, generation);
    }

    public DrawToken drawTokenForMesh(final Object meshIdentity) {
        WorkContext context = contextForMesh(meshIdentity);
        if (context == null) {
            return null;
        }
        long generation = context.meshGeneration.get();
        return generation < 0L || context.cancelled.get()
                ? null
                : new DrawToken(context.key, generation);
    }

    /**
     * Reports actual draw authority. Repeated passes/layers are coalesced to the first draw for
     * this exact published mesh generation.
     */
    public void firstValidDraw(final DrawToken token, final long frameIndex, final long nowNanos) {
        if (token == null || frameIndex < 0L) {
            return;
        }
        WorkContext context = latestContextForToken(token);
        if (context == null || context.cancelled.get()) {
            return;
        }
        if (context.firstDraw.compareAndSet(false, true)) {
            record(context, TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW, nowNanos, 0L,
                    "draw-authority", frameIndex, token.meshGeneration());
        }
    }

    /**
     * Cancels not-yet-published work, or retires a published mesh, at a real section reset.
     */
    public void invalidateSection(final long sectionId, final long nowNanos, final String reason) {
        WorkContext context = latestBySection.remove(sectionId);
        if (context == null) {
            return;
        }
        if (context.meshGeneration.get() >= 0L) {
            context.retire(recorder, nowNanos, reason);
        } else {
            context.cancel(recorder, nowNanos, reason);
        }
        removeContextMappings(context);
        clearActiveBuildIfMatches(context);
    }

    /**
     * Full compiled-geometry invalidation generation. This may advance more often than a resource
     * pack reload, but never less often than the caller-observed invalidation point.
     */
    public long advanceMaterialGeneration() {
        return materialGeneration.updateAndGet(VanillaTerrainWorkTracker::incrementGeneration);
    }

    /**
     * Switches world epoch and invalidates every outstanding context. Call only at the exact
     * LevelExtractor world transition hook, not on camera/view changes.
     */
    public long advanceWorldEpoch(final long nowNanos) {
        List<WorkContext> contexts = new ArrayList<>(latestBySection.values());
        for (WorkContext context : contexts) {
            if (context.meshGeneration.get() >= 0L) {
                context.retire(recorder, nowNanos, "world-epoch-change");
            } else {
                context.cancel(recorder, nowNanos, "world-epoch-change");
            }
        }
        latestBySection.clear();
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

    public TerrainWorkEventRecorder.Snapshot snapshot() {
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
                meshGeneration
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
        private final AtomicBoolean buildEnded = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();
        private final AtomicBoolean firstDraw = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicLong meshGeneration = new AtomicLong(TerrainWorkEventRecorder.NO_MESH_GENERATION);

        private WorkContext(final TerrainWorkEventRecorder.WorkKey key) {
            this.key = key;
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
                        generation
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
                        TerrainWorkEventRecorder.NO_MESH_GENERATION
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
                        generation
                );
            }
        }
    }
}
