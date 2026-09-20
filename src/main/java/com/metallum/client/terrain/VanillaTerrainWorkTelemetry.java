package com.metallum.client.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Opt-in runtime facade for vanilla Minecraft 26.3 terrain telemetry.
 *
 * <p>The mixin config applies the corresponding hooks only when
 * {@code -Dmetallum.terrain.vanillaWorkEvents=true} and Sodium is absent. Every method remains
 * fail-closed so an unknown identity never becomes a fabricated work event.</p>
 */
public final class VanillaTerrainWorkTelemetry {
    public static final String ENABLE_PROPERTY = "metallum.terrain.vanillaWorkEvents";

    private static final VanillaTerrainWorkTracker TRACKER =
            new VanillaTerrainWorkTracker(new TerrainWorkEventRecorder());
    private static final Object LEVEL_LOCK = new Object();
    private static final Map<Object, DrawBatchContext> DRAW_BATCHES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static Object observedLevel;

    private VanillaTerrainWorkTelemetry() {
    }

    public static void markDirty(final long sectionId) {
        TRACKER.markDirty(sectionId, VanillaTerrainWorkTracker.DirtyKind.GEOMETRY_AND_LIGHTING);
    }

    public static void onLevelChanged(final Object level) {
        synchronized (LEVEL_LOCK) {
            if (observedLevel == level) {
                return;
            }
            observedLevel = level;
        }
        TRACKER.advanceWorldEpoch(System.nanoTime());
    }

    public static void onFullGeometryInvalidation() {
        TRACKER.advanceMaterialGeneration();
    }

    public static void beginWork(final long sectionId, final Object regionIdentity, final boolean queued) {
        TRACKER.beginWork(sectionId, regionIdentity, queued, System.nanoTime());
    }

    public static boolean beginBuild(final Object regionIdentity) {
        return TRACKER.beginBuild(regionIdentity, System.nanoTime());
    }

    public static void endBuild() {
        TRACKER.endBuild(System.nanoTime());
    }

    public static void bindConstructedMesh(final Object meshIdentity) {
        TRACKER.bindConstructedMesh(meshIdentity);
    }

    public static void uploadQueued(final Object meshIdentity, final long bytes, final String reason) {
        TRACKER.uploadQueued(meshIdentity, bytes, reason, System.nanoTime());
    }

    public static void gpuEncoded(final Object meshIdentity, final long bytes, final String reason) {
        TRACKER.gpuEncoded(meshIdentity, bytes, reason, System.nanoTime());
    }

    public static VanillaTerrainWorkTracker.DrawToken publish(
            final long sectionId,
            final Object meshIdentity,
            final String reason
    ) {
        return TRACKER.publish(sectionId, meshIdentity, System.nanoTime(), reason);
    }

    public static void retireMesh(final Object meshIdentity, final String reason) {
        TRACKER.retireMesh(meshIdentity, System.nanoTime(), reason);
    }

    public static VanillaTerrainWorkTracker.DrawToken publishEmpty(final long sectionId, final String reason) {
        return TRACKER.publishEmpty(sectionId, System.nanoTime(), reason);
    }

    public static void invalidateSection(final long sectionId, final String reason) {
        TRACKER.invalidateSection(sectionId, System.nanoTime(), reason);
    }

    public static VanillaTerrainWorkTracker.DrawToken drawTokenForMesh(final Object meshIdentity) {
        return TRACKER.drawTokenForMesh(meshIdentity);
    }

    public static long nextFrameIndex() {
        return TRACKER.nextFrameIndex();
    }

    public static void attachDrawBatch(
            final Object batchIdentity,
            final long frameIndex,
            final Map<?, ? extends List<VanillaTerrainWorkTracker.DrawToken>> candidates
    ) {
        if (batchIdentity == null || candidates.isEmpty()) {
            return;
        }
        Map<Object, List<VanillaTerrainWorkTracker.DrawToken>> copy = new IdentityHashMap<>();
        for (Map.Entry<?, ? extends List<VanillaTerrainWorkTracker.DrawToken>> entry : candidates.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        if (copy.isEmpty()) {
            return;
        }
        DRAW_BATCHES.put(batchIdentity, new DrawBatchContext(frameIndex, copy));
    }

    /**
     * Called only after the vanilla layer render method returns normally, so every indirect/separate
     * draw call belonging to that layer has actually been submitted.
     */
    public static void layerRendered(final Object batchIdentity, final Object layerIdentity) {
        DrawBatchContext batch = DRAW_BATCHES.get(batchIdentity);
        if (batch == null) {
            return;
        }
        List<VanillaTerrainWorkTracker.DrawToken> tokens = batch.candidates.get(layerIdentity);
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (VanillaTerrainWorkTracker.DrawToken token : tokens) {
            TRACKER.firstValidDraw(token, batch.frameIndex, now);
        }
    }

    public static TerrainWorkEventRecorder.Snapshot snapshot() {
        return TRACKER.snapshot();
    }

    static Map<Object, List<VanillaTerrainWorkTracker.DrawToken>> candidateMap() {
        return new IdentityHashMap<>();
    }

    static void addCandidate(
            final Map<Object, List<VanillaTerrainWorkTracker.DrawToken>> candidates,
            final Object layer,
            final VanillaTerrainWorkTracker.DrawToken token
    ) {
        if (token == null) {
            return;
        }
        candidates.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(token);
    }

    private record DrawBatchContext(
            long frameIndex,
            Map<Object, List<VanillaTerrainWorkTracker.DrawToken>> candidates
    ) {
    }
}
