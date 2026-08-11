package com.metallum.client.terrain;

/**
 * Cross-thread ABI identity for Sodium terrain meshes.
 *
 * <p>Iris changes both Sodium's chunk vertex format and the material mappings
 * consulted while a worker builds that format. A build result may therefore
 * be published only when it still carries the exact immutable stamp that is
 * current on the render thread. The monotonically increasing epoch prevents
 * an A-to-B-to-A pipeline transition from accepting a worker that ran across
 * the intervening ABI.</p>
 *
 * <p>This class deliberately has no direct Iris or Sodium type dependency.
 * Sodium-only installations retain their original shaders-off behavior, and
 * the optional integration is expressed through the small access interfaces
 * implemented by mixins.</p>
 */
public final class TerrainMeshGeneration {
    public static final int NO_PIPELINE_GENERATION = -1;

    /** A result or section that was not stamped by the generation mixins. */
    public static final Stamp UNSTAMPED = new Stamp(
            Long.MIN_VALUE,
            Integer.MIN_VALUE,
            Integer.MIN_VALUE,
            false,
            true
    );

    private static final Timeline TIMELINE = new Timeline();

    private TerrainMeshGeneration() {
    }

    /** Returns one immutable snapshot suitable for capture by a worker. */
    public static Stamp current() {
        return TIMELINE.current();
    }

    /** Publishes a selected Iris pipeline and its current material-map epoch. */
    public static Stamp publishIris(
            final int pipelineGeneration,
            final int materialMapGeneration
    ) {
        if (pipelineGeneration < 0) {
            throw new IllegalArgumentException("Iris terrain pipeline generation must be non-negative");
        }
        if (materialMapGeneration < 0) {
            throw new IllegalArgumentException("Terrain material-map generation must be non-negative");
        }
        return TIMELINE.publish(
                pipelineGeneration,
                materialMapGeneration,
                materialMapGeneration > 0,
                true
        );
    }

    /**
     * Begins the gap between retiring Iris and constructing the replacement
     * vanilla pipeline. Builds remain rejected until that constructor has
     * published Sodium's compact vertex format.
     */
    public static Stamp beginShadersOffTransition() {
        return TIMELINE.publish(NO_PIPELINE_GENERATION, 0, false, true);
    }

    /**
     * Completes a pending shaders-off transition after the compact ABI exists.
     *
     * <p>A process that never selected Iris remains on the initial bypass stamp
     * and pays no region-scan cost.</p>
     *
     * @return true when a new ready stamp was published
     */
    public static boolean completeShadersOffTransition() {
        return TIMELINE.completeShadersOffTransition();
    }

    /** True only for a build produced entirely under the current render ABI. */
    public static boolean acceptsBuild(final Stamp stamp) {
        Stamp expected = current();
        return expected.renderReady()
                && stamp != null
                && stamp != UNSTAMPED
                && stamp.epoch() == expected.epoch();
    }

    /**
     * Fail-closed region check used at both of Sodium's storage lookups.
     * Stable regions answer from the mixin's epoch/revision cache.
     */
    public static boolean isRegionCurrent(final Object region) {
        Stamp expected = current();
        if (!expected.validationRequired()) {
            return true;
        }
        return expected.renderReady()
                && region instanceof RegionAccess access
                && access.metallum$isTerrainGenerationCurrent(expected);
    }

    /** Immutable worker/render-thread handoff. */
    public record Stamp(
            long epoch,
            int pipelineGeneration,
            int materialMapGeneration,
            boolean renderReady,
            boolean validationRequired
    ) {
    }

    /**
     * Small independently-testable publication primitive. Calls are rare and
     * render-thread-owned in production; synchronization gives tests and
     * defensive callers a total publication order.
     */
    static final class Timeline {
        private long nextEpoch;
        private volatile Stamp current = new Stamp(
                0L,
                NO_PIPELINE_GENERATION,
                0,
                true,
                false
        );

        Stamp current() {
            return this.current;
        }

        synchronized Stamp publish(
                final int pipelineGeneration,
                final int materialMapGeneration,
                final boolean renderReady,
                final boolean validationRequired
        ) {
            if (this.nextEpoch == Long.MAX_VALUE) {
                throw new IllegalStateException("Terrain mesh ABI epoch exhausted");
            }
            Stamp next = new Stamp(
                    ++this.nextEpoch,
                    pipelineGeneration,
                    materialMapGeneration,
                    renderReady,
                    validationRequired
            );
            this.current = next;
            return next;
        }

        synchronized boolean completeShadersOffTransition() {
            Stamp observed = this.current;
            if (observed.pipelineGeneration() != NO_PIPELINE_GENERATION
                    || observed.renderReady()) {
                return false;
            }
            publish(NO_PIPELINE_GENERATION, 0, true, true);
            return true;
        }
    }

    public interface OutputAccess {
        Stamp metallum$terrainGeneration();

        void metallum$setTerrainGeneration(Stamp stamp);
    }

    public interface SectionAccess {
        Stamp metallum$terrainGeneration();

        void metallum$setTerrainGeneration(Stamp stamp);
    }

    public interface RegionAccess {
        boolean metallum$isTerrainGenerationCurrent(Stamp expected);
    }
}
