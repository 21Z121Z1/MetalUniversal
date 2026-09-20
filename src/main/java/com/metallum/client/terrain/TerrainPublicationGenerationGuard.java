package com.metallum.client.terrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-aware publication guard for vanilla 26.3 terrain work.
 *
 * <p>The guard never builds meshes or publishes game state. It captures immutable content versions
 * when vanilla creates a compile task, cancels work when those versions become obsolete, and
 * answers the single question needed at RenderSection.setSectionMesh: may this exact result still
 * replace the section's current mesh? Unknown state fails open to vanilla behavior and permanently
 * disables mutation for the current guard instance.</p>
 */
public final class TerrainPublicationGenerationGuard<T> {
    public enum PublicationDecision {
        BASELINE_ALLOW,
        ALLOW_CURRENT,
        REJECT_STALE
    }

    public enum FailOpenReason {
        NONE,
        SECTION_CAPACITY,
        TASK_CAPACITY,
        MESH_CAPACITY,
        UNKNOWN_TASK,
        UNKNOWN_PUBLICATION,
        MESH_REBOUND
    }

    public interface TaskOps<T> {
        boolean isCancelled(T task);
        void cancel(T task);
    }

    public record Config(
            boolean requested,
            int maxTrackedSections,
            int maxTrackedTasks,
            int maxTrackedMeshes
    ) {
        public Config {
            if (maxTrackedSections < 1 || maxTrackedSections > 1_048_576) {
                throw new IllegalArgumentException("maxTrackedSections out of range: " + maxTrackedSections);
            }
            if (maxTrackedTasks < 1 || maxTrackedTasks > 1_048_576) {
                throw new IllegalArgumentException("maxTrackedTasks out of range: " + maxTrackedTasks);
            }
            if (maxTrackedMeshes < 1 || maxTrackedMeshes > 1_048_576) {
                throw new IllegalArgumentException("maxTrackedMeshes out of range: " + maxTrackedMeshes);
            }
        }

        /** Backward-compatible form: section metadata uses the task bound. */
        public Config(final boolean requested, final int maxTrackedTasks, final int maxTrackedMeshes) {
            this(requested, maxTrackedTasks, maxTrackedTasks, maxTrackedMeshes);
        }
    }

    public record ContentVersion(
            long worldEpoch,
            long sectionId,
            long geometryRevision,
            long lightingRevision,
            long materialGeneration
    ) {
        public ContentVersion {
            if (worldEpoch < 1L || geometryRevision < 1L || lightingRevision < 1L || materialGeneration < 1L) {
                throw new IllegalArgumentException("terrain content generations must be positive");
            }
        }
    }

    public record Snapshot(
            boolean requested,
            boolean active,
            boolean failOpen,
            FailOpenReason failOpenReason,
            long worldEpoch,
            long materialGeneration,
            int sectionVersionEntries,
            int trackedTasks,
            int trackedMeshes,
            long registeredTasks,
            long cancelledObsoleteTasks,
            long boundMeshes,
            long allowedPublications,
            long rejectedStalePublications,
            long baselinePublicationsAfterFailOpen,
            long failOpenCount,
            long sectionCapacityFailOpenCount,
            long taskCapacityFailOpenCount,
            long meshCapacityFailOpenCount,
            long unknownTaskFailOpenCount,
            long unknownPublicationFailOpenCount,
            long meshReboundFailOpenCount
    ) {
    }

    private static final long INITIAL_GENERATION = 1L;

    private final Config config;
    private final TaskOps<T> taskOps;
    // Access-order keeps the cold-path eviction deterministic without touching vanilla ownership.
    private final Map<Long, SectionVersion> sectionVersions = new LinkedHashMap<>(16, 0.75F, true);
    private final IdentityHashMap<T, WorkToken<T>> taskTokens = new IdentityHashMap<>();
    private final IdentityHashMap<Object, WorkToken<T>> meshTokens = new IdentityHashMap<>();
    private final ThreadLocal<WorkToken<T>> activeTask = new ThreadLocal<>();

    private long worldEpoch = INITIAL_GENERATION;
    private long materialGeneration = INITIAL_GENERATION;
    // Never reset this within the guard lifetime: an evicted/recreated section must not reuse a
    // revision held by a stale worker-local token.
    private long sectionRevisionSequence;
    private boolean failOpen;
    private FailOpenReason failOpenReason = FailOpenReason.NONE;

    private long registeredTasks;
    private long cancelledObsoleteTasks;
    private long boundMeshes;
    private long allowedPublications;
    private long rejectedStalePublications;
    private long baselinePublicationsAfterFailOpen;
    private long failOpenCount;
    private long sectionCapacityFailOpenCount;
    private long taskCapacityFailOpenCount;
    private long meshCapacityFailOpenCount;
    private long unknownTaskFailOpenCount;
    private long unknownPublicationFailOpenCount;
    private long meshReboundFailOpenCount;

    public TerrainPublicationGenerationGuard(final Config config, final TaskOps<T> taskOps) {
        this.config = Objects.requireNonNull(config, "config");
        this.taskOps = Objects.requireNonNull(taskOps, "taskOps");
    }

    public synchronized boolean active() {
        return config.requested() && !failOpen;
    }

    public void registerTask(final T task, final long sectionId) {
        Objects.requireNonNull(task, "task");
        synchronized (this) {
            if (!active()) {
                return;
            }
            // Vanilla createCompileTask() cancels the prior task before creating its replacement.
            // Remove those cancelled task-only identities eagerly; any already-staged mesh keeps
            // its own token in meshTokens and can still be rejected at late publication.
            taskTokens.entrySet().removeIf(entry -> taskOps.isCancelled(entry.getKey()));
            if (!taskTokens.containsKey(task) && taskTokens.size() >= config.maxTrackedTasks()) {
                failOpenLocked(FailOpenReason.TASK_CAPACITY);
                return;
            }
            ContentVersion version = captureCurrentVersionLocked(sectionId);
            if (version == null) {
                return;
            }
            taskTokens.put(task, new WorkToken<>(task, version));
            registeredTasks = saturatedIncrement(registeredTasks);
        }
    }

    /**
     * Starts execution for a registered task. False means the caller should return vanilla's
     * CANCELLED result before expensive compilation.
     */
    public boolean enterTask(final T task) {
        Objects.requireNonNull(task, "task");
        // A previous task that terminated exceptionally must never donate its token to later work
        // on the same worker thread. Normal exits also clear this slot.
        activeTask.remove();
        final WorkToken<T> token;
        final boolean current;
        synchronized (this) {
            if (!active()) {
                return true;
            }
            token = taskTokens.get(task);
            if (token == null) {
                // Cancelled tasks are a known terminal state even if their bounded identity
                // entry has already been pruned. Failing open here would let obsolete work run.
                if (taskOps.isCancelled(task)) {
                    return false;
                }
                failOpenLocked(FailOpenReason.UNKNOWN_TASK);
                return true;
            }
            current = isCurrentLocked(token) && !taskOps.isCancelled(task);
            if (current) {
                activeTask.set(token);
            } else {
                taskTokens.remove(task);
            }
        }
        if (!current) {
            cancelTask(task);
        }
        return current;
    }

    public void exitTask(final T task) {
        Objects.requireNonNull(task, "task");
        WorkToken<T> token = activeTask.get();
        if (token != null && token.task() == task) {
            activeTask.remove();
        }
    }

    /** Binds a non-sentinel mesh created by the task currently executing on this worker. */
    public void bindMeshFromActiveTask(final Object mesh) {
        Objects.requireNonNull(mesh, "mesh");
        WorkToken<T> token = activeTask.get();
        if (token == null) {
            return;
        }
        synchronized (this) {
            if (!active()) {
                return;
            }
            WorkToken<T> previous = meshTokens.get(mesh);
            if (previous != null && previous != token) {
                failOpenLocked(FailOpenReason.MESH_REBOUND);
                return;
            }
            if (previous == null && meshTokens.size() >= config.maxTrackedMeshes()) {
                failOpenLocked(FailOpenReason.MESH_CAPACITY);
                return;
            }
            if (previous == null) {
                meshTokens.put(mesh, token);
                boundMeshes = saturatedIncrement(boundMeshes);
            }
        }
    }

    /**
     * Checks the only vanilla publication boundary. Direct empty/block-entity-only work uses the
     * active task token; callback-driven mesh publication uses the mesh token captured at staging.
     */
    public PublicationDecision publicationDecision(final long sectionId, final Object mesh) {
        synchronized (this) {
            if (!config.requested() || failOpen) {
                baselinePublicationsAfterFailOpen = saturatedIncrement(baselinePublicationsAfterFailOpen);
                return PublicationDecision.BASELINE_ALLOW;
            }

            WorkToken<T> token = mesh == null ? null : meshTokens.get(mesh);
            WorkToken<T> active = activeTask.get();
            if (token == null && active != null) {
                token = active;
            }
            if (token == null) {
                failOpenLocked(FailOpenReason.UNKNOWN_PUBLICATION);
                baselinePublicationsAfterFailOpen = saturatedIncrement(baselinePublicationsAfterFailOpen);
                return PublicationDecision.BASELINE_ALLOW;
            }

            boolean current = token.version().sectionId() == sectionId
                    && isCurrentLocked(token)
                    && !taskOps.isCancelled(token.task());
            consumeTokenLocked(token, mesh);
            if (!current) {
                rejectedStalePublications = saturatedIncrement(rejectedStalePublications);
                return PublicationDecision.REJECT_STALE;
            }
            allowedPublications = saturatedIncrement(allowedPublications);
            return PublicationDecision.ALLOW_CURRENT;
        }
    }

    /** Forget ownership after vanilla releases a candidate or retired mesh. */
    public synchronized void forgetMesh(final Object mesh) {
        if (mesh != null) {
            meshTokens.remove(mesh);
        }
    }

    public void markDirty(final long sectionId) {
        invalidateSection(sectionId, true);
    }

    /** Section lifecycle invalidation is as strong as a content change for publication safety. */
    public void invalidateSectionLifetime(final long sectionId) {
        invalidateSection(sectionId, true);
    }

    public void advanceMaterialGeneration() {
        final List<T> cancel;
        synchronized (this) {
            if (!active()) {
                return;
            }
            materialGeneration = incrementGeneration(materialGeneration);
            cancel = removeAllTrackedTasksLocked();
        }
        cancelTasks(cancel);
    }

    public void advanceWorldEpoch() {
        final List<T> cancel;
        synchronized (this) {
            if (!active()) {
                return;
            }
            worldEpoch = incrementGeneration(worldEpoch);
            sectionVersions.clear();
            cancel = removeAllTrackedTasksLocked();
        }
        cancelTasks(cancel);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                config.requested(),
                active(),
                failOpen,
                failOpenReason,
                worldEpoch,
                materialGeneration,
                sectionVersions.size(),
                taskTokens.size(),
                meshTokens.size(),
                registeredTasks,
                cancelledObsoleteTasks,
                boundMeshes,
                allowedPublications,
                rejectedStalePublications,
                baselinePublicationsAfterFailOpen,
                failOpenCount,
                sectionCapacityFailOpenCount,
                taskCapacityFailOpenCount,
                meshCapacityFailOpenCount,
                unknownTaskFailOpenCount,
                unknownPublicationFailOpenCount,
                meshReboundFailOpenCount
        );
    }

    private void invalidateSection(final long sectionId, final boolean bothRevisions) {
        final List<T> cancel = new ArrayList<>();
        synchronized (this) {
            if (!active()) {
                return;
            }
            SectionVersion version = sectionVersions.get(sectionId);
            if (version == null) {
                if (!ensureSectionSlotLocked(sectionId)) {
                    return;
                }
                version = newSectionVersionLocked();
                sectionVersions.put(sectionId, version);
            }
            version.geometryRevision = nextSectionRevisionLocked();
            if (bothRevisions) {
                version.lightingRevision = nextSectionRevisionLocked();
            }
            var iterator = taskTokens.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().version().sectionId() == sectionId) {
                    cancel.add(entry.getKey());
                    iterator.remove();
                }
            }
        }
        cancelTasks(cancel);
    }

    private ContentVersion captureCurrentVersionLocked(final long sectionId) {
        SectionVersion version = sectionVersions.get(sectionId);
        if (version == null) {
            if (!ensureSectionSlotLocked(sectionId)) {
                return null;
            }
            version = newSectionVersionLocked();
            sectionVersions.put(sectionId, version);
        }
        return new ContentVersion(
                worldEpoch,
                sectionId,
                version.geometryRevision,
                version.lightingRevision,
                materialGeneration
        );
    }

    private boolean isCurrentLocked(final WorkToken<T> token) {
        ContentVersion captured = token.version();
        SectionVersion current = sectionVersions.get(captured.sectionId());
        return current != null
                && captured.worldEpoch() == worldEpoch
                && captured.materialGeneration() == materialGeneration
                && captured.geometryRevision() == current.geometryRevision
                && captured.lightingRevision() == current.lightingRevision;
    }

    /**
     * Makes room for one previously unseen section without retaining an unbounded world history.
     * Only metadata with no task/mesh ownership may be evicted. A capacity full of live ownership
     * is not guessable, so mutation is disabled and vanilla resumes unchanged.
     */
    private boolean ensureSectionSlotLocked(final long sectionId) {
        if (sectionVersions.containsKey(sectionId) || sectionVersions.size() < config.maxTrackedSections()) {
            return true;
        }

        Set<Long> referencedSections = new HashSet<>();
        for (WorkToken<T> token : taskTokens.values()) {
            referencedSections.add(token.version().sectionId());
        }
        for (WorkToken<T> token : meshTokens.values()) {
            referencedSections.add(token.version().sectionId());
        }

        var iterator = sectionVersions.keySet().iterator();
        while (iterator.hasNext()) {
            long candidate = iterator.next();
            if (!referencedSections.contains(candidate)) {
                iterator.remove();
                return true;
            }
        }

        failOpenLocked(FailOpenReason.SECTION_CAPACITY);
        return false;
    }

    private SectionVersion newSectionVersionLocked() {
        return new SectionVersion(nextSectionRevisionLocked(), nextSectionRevisionLocked());
    }

    private long nextSectionRevisionLocked() {
        sectionRevisionSequence = incrementGeneration(sectionRevisionSequence);
        return sectionRevisionSequence;
    }

    private void consumeTokenLocked(final WorkToken<T> token, final Object mesh) {
        taskTokens.remove(token.task());
        if (mesh != null) {
            meshTokens.remove(mesh);
        } else {
            meshTokens.entrySet().removeIf(entry -> entry.getValue() == token);
        }
    }

    private List<T> removeAllTrackedTasksLocked() {
        List<T> tasks = new ArrayList<>(taskTokens.keySet());
        taskTokens.clear();
        return tasks;
    }

    private void cancelTasks(final List<T> tasks) {
        for (T task : tasks) {
            cancelTask(task);
        }
    }

    private void cancelTask(final T task) {
        if (!taskOps.isCancelled(task)) {
            taskOps.cancel(task);
            synchronized (this) {
                cancelledObsoleteTasks = saturatedIncrement(cancelledObsoleteTasks);
            }
        }
    }

    private void failOpenLocked(final FailOpenReason reason) {
        if (failOpen) {
            return;
        }
        if (reason == FailOpenReason.NONE) {
            throw new IllegalArgumentException("fail-open reason must be explicit");
        }
        failOpen = true;
        failOpenReason = reason;
        failOpenCount = saturatedIncrement(failOpenCount);
        switch (reason) {
            case SECTION_CAPACITY ->
                    sectionCapacityFailOpenCount = saturatedIncrement(sectionCapacityFailOpenCount);
            case TASK_CAPACITY -> taskCapacityFailOpenCount = saturatedIncrement(taskCapacityFailOpenCount);
            case MESH_CAPACITY -> meshCapacityFailOpenCount = saturatedIncrement(meshCapacityFailOpenCount);
            case UNKNOWN_TASK -> unknownTaskFailOpenCount = saturatedIncrement(unknownTaskFailOpenCount);
            case UNKNOWN_PUBLICATION ->
                    unknownPublicationFailOpenCount = saturatedIncrement(unknownPublicationFailOpenCount);
            case MESH_REBOUND -> meshReboundFailOpenCount = saturatedIncrement(meshReboundFailOpenCount);
            case NONE -> throw new IllegalArgumentException("fail-open reason must be explicit");
        }
        // Metadata is no longer authoritative after fail-open. Drop strong identity references so
        // the diagnostic safety layer cannot retain vanilla tasks/meshes for the rest of the game.
        sectionVersions.clear();
        taskTokens.clear();
        meshTokens.clear();
    }

    private static long incrementGeneration(final long value) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("terrain content generation exhausted");
        }
        return value + 1L;
    }

    private static long saturatedIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static final class SectionVersion {
        private long geometryRevision;
        private long lightingRevision;

        private SectionVersion(final long geometryRevision, final long lightingRevision) {
            this.geometryRevision = geometryRevision;
            this.lightingRevision = lightingRevision;
        }
    }

    private record WorkToken<T>(T task, ContentVersion version) {
    }
}
