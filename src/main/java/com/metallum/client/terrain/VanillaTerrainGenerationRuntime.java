package com.metallum.client.terrain;

import com.metallum.mixin.terrain.SectionTaskTerrainAdmissionAccessor;
import java.util.function.Supplier;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

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
                    }
            );

    private static final Object LEVEL_LOCK = new Object();
    private static Object observedLevel;

    private VanillaTerrainGenerationRuntime() {
    }

    public static void markDirty(final long sectionId) {
        GUARD.markDirty(sectionId);
    }

    public static void onLevelChanged(final Object level) {
        synchronized (LEVEL_LOCK) {
            if (observedLevel == level) {
                return;
            }
            observedLevel = level;
        }
        GUARD.advanceWorldEpoch();
    }

    public static void onFullGeometryInvalidation() {
        GUARD.advanceMaterialGeneration();
    }

    public static void invalidateSectionLifetime(final long sectionId) {
        GUARD.invalidateSectionLifetime(sectionId);
    }

    public static void registerTask(
            final SectionRenderDispatcher.RenderSection.SectionTask task,
            final long sectionId
    ) {
        GUARD.registerTask(task, sectionId);
    }

    public static <R> R runTask(
            final SectionRenderDispatcher.RenderSection.SectionTask task,
            final R cancelledResult,
            final Supplier<R> original
    ) {
        return GUARD.runTask(task, cancelledResult, original);
    }

    public static void bindMeshFromActiveTask(final Object mesh) {
        GUARD.bindMeshFromActiveTask(mesh);
    }

    public static <M> M publish(final long sectionId, final M candidate, final Supplier<M> original) {
        return GUARD.publish(sectionId, candidate, original);
    }

    public static void forgetMesh(final Object mesh) {
        GUARD.forgetMesh(mesh);
    }

    public static TerrainPublicationGenerationGuard.Snapshot snapshot() {
        return GUARD.snapshot();
    }

    private static int trackedCapacity() {
        int value = Integer.getInteger(TRACKED_CAPACITY_PROPERTY, DEFAULT_TRACKED_CAPACITY);
        if (value < 1 || value > MAX_TRACKED_CAPACITY) {
            return DEFAULT_TRACKED_CAPACITY;
        }
        return value;
    }
}
