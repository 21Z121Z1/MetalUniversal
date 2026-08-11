package com.metallum.client.terrain;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;

/**
 * Render-thread/worker-thread contract for Sodium terrain meshes.
 *
 * <p>Iris changes the chunk vertex ABI and the material-map inputs used while
 * building that ABI.  A native pipeline generation therefore cannot silently
 * consume a mesh produced by another generation.  The mixins use the small
 * interfaces below instead of retaining a strong dependency on Sodium's
 * private fields.</p>
 */
public final class TerrainMeshGeneration {
    /** A build result that was not stamped by the meshing-task hook. */
    public static final long UNSTAMPED = Long.MIN_VALUE;

    private TerrainMeshGeneration() {
    }

    /** Returns the generation currently allowed to render Sodium terrain. */
    public static long current() {
        return IrisMetalPipelineOverrides.activeTerrainMeshGeneration();
    }

    /** Returns whether the active generation has completed its first material-map rebuild. */
    public static boolean renderReady() {
        return IrisMetalPipelineOverrides.terrainMeshRenderReady();
    }

    /** Vanilla/Sodium owns the mesh ABI when no Iris generation is active. */
    public static boolean contractActive() {
        return IrisMetalPipelineOverrides.activeGenerationForDiagnostics() >= 0;
    }

    public static boolean isCurrent(final long generation) {
        return !contractActive() || (generation != UNSTAMPED && generation == current());
    }

    /**
     * A region is safe only when every built section in it belongs to the
     * current generation.  Returning false hides the whole region until its
     * last old section has been rebuilt; mixed-format draws are never allowed.
     */
    public static boolean isRegionCurrent(final RenderRegion region) {
        if (!contractActive()) {
            return true;
        }
        if (!renderReady() || !(region instanceof RegionAccess access)) {
            return false;
        }
        return access.metallum$isTerrainGenerationCurrent(current());
    }

    public interface OutputAccess {
        long metallum$terrainGeneration();

        void metallum$setTerrainGeneration(long generation);
    }

    public interface SectionAccess {
        long metallum$terrainGeneration();

        void metallum$setTerrainGeneration(long generation);
    }

    public interface RegionAccess {
        boolean metallum$isTerrainGenerationCurrent(long generation);
    }

    /** Stable composition keeps pipeline, format/material epoch in one token. */
    public static long token(final int pipelineGeneration, final int materialMapGeneration) {
        return ((long) pipelineGeneration << 32) | (materialMapGeneration & 0xffff_ffffL);
    }

    public static boolean isSectionCurrent(final RenderSection section, final long generation) {
        return section instanceof SectionAccess access
                && access.metallum$terrainGeneration() == generation;
    }
}
