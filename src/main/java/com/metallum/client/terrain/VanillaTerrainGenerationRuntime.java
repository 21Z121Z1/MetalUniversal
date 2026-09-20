package com.metallum.client.terrain;

import com.metallum.mixin.terrain.SectionTaskTerrainAdmissionAccessor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import java.util.function.Function;

/** Minecraft-facing adapter for the pure T1b generation guard. */
public final class VanillaTerrainGenerationRuntime {
    static final String ENABLE_PROPERTY = "metallum.terrain.vanillaGenerationGuard";
    static final String TRACKED_CAPACITY_PROPERTY = "metallum.terrain.vanillaGenerationTrackedCapacity";
    private static final int DEFAULT_TRACKED_CAPACITY = 32768;
    private static final int MAX_TRACKED_CAPACITY = 1 << 20;

    private static final TerrainPublicationGenerationGuard<SectionRenderDispatcher.RenderSection.SectionTask> GUARD =
            new TerrainPublicationGenerationGuard<>(
                    new TerrainPublicationGenerationGuard.Config(
                            Boolean.getBoolean(ENABLE_PROPERTY),
                            trackedCapacity(),
                            trackedCapacity()
                    ),
                    new TerrainPublicationGenerationGuard.TaskOps<>() {
                        @Override
                        public boolean isCancelled(final SectionRenderDispatcher.RenderSection.SectionTask task) {
                            return ((SectionTaskTerrainAdmissionAccessor)(Object)task)
                                    .metallum$isCancelled()
                                    .get();
                        }

                        @Override
                        public void cancel(final SectionRenderDispatcher.RenderSection.SectionTask task) {
                            task.cancel();
                        }
                    },
                    eventCapacity()
            );

    static {
        VanillaTerrainGenerationTelemetry.register(GUARD::snapshotEvidence);
    }

    private static final Object LEVEL_LOCK = new Object();
    private static Object observedLevel;

    private VanillaTerrainGenerationRuntime() {
    }

    public static void markDirty(final long sectionId) {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.markDirty(sectionId);
    }

    public static void onLevelChanged(final Object level) {
        VanillaTerrainGenerationTelemetry.observeHook();
        synchronized (LEVEL_LOCK) {
            if (observedLevel == level) {
                return;
            }
            observedLevel = level;
        }
        GUARD.advanceWorldEpoch();
    }

    public static void onFullGeometryInvalidation() {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.advanceMaterialGeneration();
    }

    public static void invalidateSectionLifetime(final long sectionId) {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.invalidateSectionLifetime(sectionId);
    }

    public static void registerTask(
            final SectionRenderDispatcher.RenderSection.SectionTask task,
            final long sectionId
    ) {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.registerTask(task, sectionId);
    }

    public static boolean enterTask(final SectionRenderDispatcher.RenderSection.SectionTask task) {
        VanillaTerrainGenerationTelemetry.observeHook();
        return GUARD.enterTask(task);
    }

    public static void exitTask(final SectionRenderDispatcher.RenderSection.SectionTask task) {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.exitTask(task);
    }

    public static void bindMeshFromActiveTask(final Object mesh) {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.bindMeshFromActiveTask(mesh);
    }

    public static <R> R withPublicationDecision(long sectionId, Object mesh,
            Function<TerrainPublicationGenerationGuard.PublicationDecision, R> publication) {
        VanillaTerrainGenerationTelemetry.observeHook();
        return GUARD.withPublicationDecision(sectionId, mesh, publication);
    }

    public static void forgetMesh(final Object mesh) {
        VanillaTerrainGenerationTelemetry.observeHook();
        GUARD.forgetMesh(mesh);
    }

    static TerrainPublicationGenerationGuard.Snapshot snapshot() {
        return GUARD.snapshot();
    }

    private static int eventCapacity() {
        if (!Boolean.getBoolean("metallum.terrain.vanillaGenerationEvents")) return 0;
        int capacity = Integer.getInteger("metallum.terrain.vanillaGenerationEventCapacity", 65_536);
        if (capacity < 1 || capacity > 262_144) {
            throw new IllegalArgumentException("vanillaGenerationEventCapacity must be in [1, 262144]");
        }
        return capacity;
    }

    private static int trackedCapacity() {
        int value = Integer.getInteger(TRACKED_CAPACITY_PROPERTY, DEFAULT_TRACKED_CAPACITY);
        if (value < 1 || value > MAX_TRACKED_CAPACITY) {
            return DEFAULT_TRACKED_CAPACITY;
        }
        return value;
    }
}
