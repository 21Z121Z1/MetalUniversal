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
    private record OwnerKey(FrameSynthesisContract.ProducerDomain domain, long id, long generation) {}
    private static final Map<OwnerKey, String> FRAME_OWNER_CLASSES = new java.util.HashMap<>();
    private static final Map<String, long[]> OWNERS = new TreeMap<>();

    public record PipelineCounts(long actualDraws, long hookedExecutions, long ownedColorExecutions, long encodedMotionDraws) {}
    public record OwnerCounts(long capturedStates, long sourceColorDraws, long matchingGeometryDraws,
                              long matchingTransformDraws, long matchingSurfaceDraws, long encodedMotionDraws) {}
    public record Census(boolean rawMotionValidation, Map<String, PipelineCounts> pipelines,
                         Map<String, OwnerCounts> owners, Snapshot lastFrame, MetalEntityMotionCapture.Diagnostics capture) {}

    private MetalMotionDrawCoverage() {}

    static void beginFrame() {
        scene = false;
        FRAME_OWNER_CLASSES.clear();
        source = owned = unowned = depthBounds = transmittance = accumulate = duplicates = queued = encoded = 0;
    }

    static void attachOwner(final Object state, final MetalEntityMotionCapture.Sample sample) {
        if (!VALIDATION || state == null || sample == null) return;
        String name = state.getClass().getName();
        if (state.getClass() == net.minecraft.client.renderer.entity.state.EntityRenderState.class
                && ((net.minecraft.client.renderer.entity.state.EntityRenderState) state).entityType != null) {
            name += "[" + ((net.minecraft.client.renderer.entity.state.EntityRenderState) state)
                    .entityType.builtInRegistryHolder().key().identifier() + "]";
        }
        FRAME_OWNER_CLASSES.put(new OwnerKey(sample.domain(), sample.objectId(), sample.generation()), name);
        OWNERS.computeIfAbsent(name, ignored -> new long[6])[0]++;
    }

    private static long[] ownerCounts(final MetalEntityMotionCapture.Sample sample) {
        String name = FRAME_OWNER_CLASSES.getOrDefault(new OwnerKey(sample.domain(), sample.objectId(), sample.generation()),
                "domain:" + sample.domain());
        return OWNERS.computeIfAbsent(name, ignored -> new long[6]);
    }

    static void stagedHistory(final MetalEntityMotionCapture.Sample sample, final MetalPreviousVertexHistory.DrawToken token) {
        if (!VALIDATION) return;
        long[] counts = ownerCounts(sample);
        if (MetalPreviousVertexHistory.matchingManifestDrawCount(sample.objectId(), sample.generation()) > 0) counts[2]++;
        if (MetalPreviousVertexHistory.matchedPreviousDrawTransform(token) != null) counts[3]++;
        if (MetalPreviousVertexHistory.matchingSurfaceManifest(token)) counts[4]++;
    }

    static void beginScene() { scene = true; }
    static void endScene() { scene = false; }

    /** Independent denominator from the production backend, including direct and unowned draws. */
    static void actualDraw(final RenderPipeline pipeline, final int count) {
        if (VALIDATION && scene && pipeline != null && count > 0) {
            PIPELINES.computeIfAbsent(pipeline.getLocation().toString(), ignored -> new long[4])[0] += count;
        }
    }

    static void hookedDraw(final MetalPreviousVertexReplay.@Nullable SourceState state, final @Nullable OitStage stage,
                           final MetalEntityMotionCapture.@Nullable Sample sample) {
        if (VALIDATION && scene && state != null) {
            long[] counts = PIPELINES.computeIfAbsent(state.pipeline().getLocation().toString(), ignored -> new long[4]);
            counts[1]++;
            if ((stage == null || stage == OitStage.ACCUMULATE) && sample != null) {
                counts[2]++;
                ownerCounts(sample)[1]++;
            }
        }
    }

    public static Census census() {
        Map<String, PipelineCounts> copy = new TreeMap<>();
        PIPELINES.forEach((name, counts) -> copy.put(name, new PipelineCounts(counts[0], counts[1], counts[2], counts[3])));
        Map<String, OwnerCounts> owners = new TreeMap<>();
        OWNERS.forEach((name, counts) -> owners.put(name, new OwnerCounts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5])));
        return new Census(VALIDATION, Map.copyOf(copy), Map.copyOf(owners), snapshot(), MetalEntityMotionCapture.diagnostics());
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
    static void encoded(final MetalEntityMotionCapture.Sample sample, final RenderPipeline source) {
        encoded++;
        if (VALIDATION) {
            ownerCounts(sample)[5]++;
            PIPELINES.computeIfAbsent(source.getLocation().toString(), ignored -> new long[4])[3]++;
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(source, owned, unowned, depthBounds, transmittance, accumulate, duplicates, queued, encoded);
    }
}
