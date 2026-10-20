package com.metallum.client.metal.render;

import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Map;
import java.util.TreeMap;

/** Source execution evidence. Counts never stand in for per-pixel validity or device capability. */
public final class MetalMotionDrawCoverage {
    public record Snapshot(long sourceDraws, long ownedColorDraws, long unownedColorDraws,
                           long depthBoundsDraws, long transmittanceDraws, long accumulateDraws,
                           long duplicateColorDraws, long queuedReplays, long encodedReplays) {}
    private static long source, owned, unowned, depthBounds, transmittance, accumulate, duplicates, queued, encoded;
    private static final boolean VALIDATION = Boolean.getBoolean("metallum.metalfx.captureMotionValidation");
    private static boolean scene;
    private static final Map<String, long[]> PIPELINES = new TreeMap<>();

    public record PipelineCounts(long actualDraws, long hookedExecutions, long ownedColorExecutions) {}
    public record Census(boolean captureOnlyValidation, Map<String, PipelineCounts> pipelines, Snapshot lastFrame) {}

    private MetalMotionDrawCoverage() {}

    static void beginFrame() {
        scene = false;
        source = owned = unowned = depthBounds = transmittance = accumulate = duplicates = queued = encoded = 0;
    }

    static void beginScene() { scene = true; }
    static void endScene() { scene = false; }

    /** Independent denominator from the production backend, including direct and unowned draws. */
    static void actualDraw(final RenderPipeline pipeline, final int count) {
        if (VALIDATION && scene && pipeline != null && count > 0) {
            PIPELINES.computeIfAbsent(pipeline.getLocation().toString(), ignored -> new long[3])[0] += count;
        }
    }

    static void hookedDraw(final MetalPreviousVertexReplay.@Nullable SourceState state, final @Nullable OitStage stage,
                           final MetalEntityMotionCapture.@Nullable Sample sample) {
        if (VALIDATION && scene && state != null) {
            long[] counts = PIPELINES.computeIfAbsent(state.pipeline().getLocation().toString(), ignored -> new long[3]);
            counts[1]++;
            if ((stage == null || stage == OitStage.ACCUMULATE) && sample != null) counts[2]++;
        }
    }

    public static Census census() {
        Map<String, PipelineCounts> copy = new TreeMap<>();
        PIPELINES.forEach((name, counts) -> copy.put(name, new PipelineCounts(counts[0], counts[1], counts[2])));
        return new Census(VALIDATION, Map.copyOf(copy), snapshot());
    }

    static void sourceExecution(final @Nullable OitStage stage,
                                final MetalEntityMotionCapture.@Nullable Sample sample, final boolean duplicate) {
        source++;
        if (stage == OitStage.DEPTH_BOUNDS) depthBounds++;
        if (stage == OitStage.TRANSMITTANCE) transmittance++;
        if (stage == OitStage.ACCUMULATE) accumulate++;
        if (stage != null && stage != OitStage.ACCUMULATE) return;
        if (duplicate) duplicates++;
        else if (sample == null) unowned++;
        else owned++;
    }

    static void queued() { queued++; }
    static void encoded() { encoded++; }

    public static Snapshot snapshot() {
        return new Snapshot(source, owned, unowned, depthBounds, transmittance, accumulate, duplicates, queued, encoded);
    }
}
