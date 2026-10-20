package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact source-frame identity for Minecraft 26.3 quad-particle staged draws.
 *
 * <p>QuadParticleRenderState intentionally stores only the final extracted quad pose, not the
 * particle object that produced it. QuadParticleFeatureRenderer then concatenates all particles of
 * the same Layer into one staged draw. This carrier restores only the missing identity edge: the
 * actual SingleQuadParticle object is tagged while its virtual extract call runs, every state.add
 * records that id in layer order, and the later feature group recreates Minecraft's layer batching
 * order before exact previous-position history is admitted.</p>
 *
 * <p>Each actual Layer identity has an independent source transaction. Unrelated layers and group
 * ordering cannot reset it. Membership changes remap each quad by persistent particle ID.
 * New or ambiguous emissions have invalid previous vertices, never another particle's history.</p>
 */
public final class MetalParticleBatchMotion {
    private static final long PARTICLE_OBJECT_ID_BASE = 0x4D465850415254L; // ASCII "MFXPART".
    private static final AtomicLong NEXT_PARTICLE_ID = new AtomicLong(1L);

    record LayerSignature(
            boolean translucent,
            String textureAtlas,
            String pipelineLocation,
            String vertexShader,
            List<Long> particleIds
    ) {
        LayerSignature {
            particleIds = List.copyOf(particleIds);
        }
    }

    private record LayerState(LayerSignature definition, long objectId, long generation) {
    }

    private record Extraction(QuadParticleRenderState state, long particleId) {
    }

    private record DrawPlan(RenderPipeline pipeline, List<Long> particleIds, MetalEntityMotionCapture.@Nullable Sample sample) {
    }

    private static final class LayerAccumulator {
        final SingleQuadParticle.Layer layer;
        final ArrayList<Long> particleIds = new ArrayList<>();

        LayerAccumulator(final SingleQuadParticle.Layer layer) {
            this.layer = layer;
        }

        LayerSignature signature() {
            RenderPipeline pipeline = layer.pipeline();
            return new LayerSignature(
                    layer.translucent(),
                    layer.textureAtlasLocation().toString(),
                    pipeline.getLocation().toString(),
                    pipeline.getShaders().get(ShaderType.VERTEX).toString(),
                    particleIds
            );
        }
    }

    private static final class ActiveGroup {
        final List<Object> layers;
        final List<MetalEntityMotionCapture.Sample> samples;
        final ArrayDeque<DrawPlan> drawPlans;
        boolean invalid;

        ActiveGroup(final List<Object> layers, final List<MetalEntityMotionCapture.Sample> samples,
                    final ArrayDeque<DrawPlan> drawPlans) {
            this.layers = List.copyOf(layers);
            this.samples = List.copyOf(samples);
            this.drawPlans = drawPlans;
        }
    }

    private static final ThreadLocal<Extraction> EXTRACTION = new ThreadLocal<>();
    private static final IdentityHashMap<
            QuadParticleRenderState,
            IdentityHashMap<SingleQuadParticle.Layer, List<Long>>
            > MEMBERSHIP = new IdentityHashMap<>();
    private static final IdentityHashMap<QuadParticleRenderState, Integer> SUBMITTED_COUNTS =
            new IdentityHashMap<>();
    private static final Set<QuadParticleRenderState> INVALID_STATES =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final IdentityHashMap<Object, LayerState> previousLayers = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, LayerState> pendingLayers = new IdentityHashMap<>();
    private static final Set<Object> invalidLayers = Collections.newSetFromMap(new IdentityHashMap<>());

    private static long nextGeneration = -1L;
    private static long nextLayerObjectId = PARTICLE_OBJECT_ID_BASE;
    private static boolean frameOpen;
    private static ActiveGroup activeGroup;

    private MetalParticleBatchMotion() {
    }

    /** Called by the per-particle mixin field initializer; identities never reset while objects live. */
    public static long allocateParticleIdentity() {
        long id = NEXT_PARTICLE_ID.getAndIncrement();
        if (id <= 0L) {
            throw new IllegalStateException("MetalFX particle identity space exhausted");
        }
        return id;
    }

    static void beginFrame() {
        MEMBERSHIP.clear();
        SUBMITTED_COUNTS.clear();
        INVALID_STATES.clear();
        pendingLayers.clear();
        invalidLayers.clear();
        EXTRACTION.remove();
        activeGroup = null;
        frameOpen = true;
    }

    /** Opens identity attribution around the actual virtual particle.extract call. */
    public static boolean beginParticleExtract(
            final SingleQuadParticle particle,
            final QuadParticleRenderState state
    ) {
        if (!frameOpen) {
            return false;
        }
        if (particle == null || state == null || EXTRACTION.get() != null) {
            if (state != null) {
                INVALID_STATES.add(state);
            }
            return false;
        }
        if (!(particle instanceof MetalParticleIdentityAccess identity)) {
            INVALID_STATES.add(state);
            return false;
        }
        long particleId = identity.metallum$motionIdentity();
        if (particleId <= 0L) {
            INVALID_STATES.add(state);
            return false;
        }
        EXTRACTION.set(new Extraction(state, particleId));
        return true;
    }

    public static void endParticleExtract(final boolean opened) {
        if (opened) {
            EXTRACTION.remove();
        }
    }

    /** Records exactly one QuadParticleRenderState.add in the same order Minecraft stores it. */
    public static void recordParticleAdd(
            final QuadParticleRenderState state,
            final SingleQuadParticle.Layer layer
    ) {
        if (!frameOpen) {
            return;
        }
        Extraction extraction = EXTRACTION.get();
        if (state == null || layer == null || extraction == null || extraction.state() != state) {
            if (state != null) {
                INVALID_STATES.add(state);
            }
            return;
        }
        IdentityHashMap<SingleQuadParticle.Layer, List<Long>> layers =
                MEMBERSHIP.computeIfAbsent(state, ignored -> new IdentityHashMap<>());
        layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(extraction.particleId());
    }

    /**
     * Actual-submit guard. particleCount is the authoritative number of Storage.add calls in the
     * vanilla state; it must equal the number of identities captured above before any feature group
     * is allowed to replace the legacy whole-frame rejection.
     */
    public static void observeSubmittedState(final QuadParticleRenderState state, final int particleCount) {
        if (!frameOpen || state == null || particleCount <= 0) {
            return;
        }
        MetalFxManager.observeParticlesWeather(particleCount);
        int captured = capturedCount(state);
        if (INVALID_STATES.contains(state) || captured != particleCount) {
            INVALID_STATES.add(state);
            MetalFxManager.observeParticleMotion();
            return;
        }
        SUBMITTED_COUNTS.put(state, particleCount);
    }

    private static int capturedCount(final QuadParticleRenderState state) {
        IdentityHashMap<SingleQuadParticle.Layer, List<Long>> layers = MEMBERSHIP.get(state);
        if (layers == null) {
            return 0;
        }
        int total = 0;
        for (List<Long> ids : layers.values()) {
            if (ids == null) {
                return -1;
            }
            try {
                total = Math.addExact(total, ids.size());
            } catch (ArithmeticException overflow) {
                return -1;
            }
        }
        return total;
    }

    /** Replays QuadParticleFeatureRenderer.prepareGroup's layer-consolidation order before build. */
    public static void beginFeatureGroup(final List<QuadParticleFeatureRenderer.Submit> submits) {
        MetalEntityMotionCapture.endModelBuild();
        activeGroup = null;
        if (!frameOpen || submits == null || submits.isEmpty()) {
            return;
        }

        if (submits.getFirst() == null) { rejectGroup(submits,"particle-submit-shape-changed"); return; }
        final boolean translucent = submits.getFirst().translucent();
        IdentityHashMap<SingleQuadParticle.Layer, LayerAccumulator> byIdentity = new IdentityHashMap<>();
        ArrayList<LayerAccumulator> orderedLayers = new ArrayList<>();

        for (QuadParticleFeatureRenderer.Submit submit : submits) {
            if (submit == null || submit.translucent() != translucent || submit.particles() == null) {
                rejectGroup(submits, "particle-submit-shape-changed");
                return;
            }
            QuadParticleRenderState state = submit.particles();
            if (state.isEmpty()) {
                continue;
            }
            Integer submittedCount = SUBMITTED_COUNTS.get(state);
            if (INVALID_STATES.contains(state) || submittedCount == null
                    || submittedCount <= 0 || submittedCount != capturedCount(state)) {
                rejectGroup(submits, "particle-membership-unproven");
                return;
            }
            IdentityHashMap<SingleQuadParticle.Layer, List<Long>> stateLayers = MEMBERSHIP.get(state);
            for (SingleQuadParticle.Layer layer : state.layers()) {
                if (layer == null || layer.translucent() != submit.translucent()) {
                    continue;
                }
                LayerAccumulator accumulator = byIdentity.get(layer);
                if (accumulator == null) {
                    accumulator = new LayerAccumulator(layer);
                    byIdentity.put(layer, accumulator);
                    orderedLayers.add(accumulator);
                }
                List<Long> ids = stateLayers == null ? null : stateLayers.get(layer);
                if (ids != null) {
                    accumulator.particleIds.addAll(ids);
                }
            }
        }

        // Validate the complete group before allocating any independent layer transaction.
        for (LayerAccumulator accumulator : orderedLayers) {
            RenderPipeline pipeline = accumulator.layer.pipeline();
            if (pipeline == null || (!accumulator.particleIds.isEmpty()
                    && !MetalEntityMotionPipeline.supportsPreviousPositions(pipeline))) {
                rejectGroup(submits,"particle-exact-pipeline-unsupported");
                return;
            }
        }
        ArrayList<Object> layerOwners = new ArrayList<>();
        ArrayList<MetalEntityMotionCapture.Sample> samples = new ArrayList<>();
        ArrayDeque<DrawPlan> plans = new ArrayDeque<>();
        for (LayerAccumulator accumulator : orderedLayers) {
            RenderPipeline pipeline = accumulator.layer.pipeline();
            MetalEntityMotionCapture.Sample sample = null;
            if (!accumulator.particleIds.isEmpty()) {
                sample = beginLayerBatch(accumulator.layer, accumulator.signature());
                if (sample == null) {
                    rejectGroup(submits,"particle-layer-transaction-invalid");
                    return;
                }
                layerOwners.add(accumulator.layer);
                samples.add(sample);
                if (sample.hasPrevious()) MetalFxManager.markExactParticleProducerCandidate(sample);
            }
            // Empty retained Storage keys still append a Draw, but never own geometry/history.
            plans.addLast(new DrawPlan(pipeline,List.copyOf(accumulator.particleIds),sample));
        }
        activeGroup = new ActiveGroup(layerOwners,samples,plans);
    }

    /** Layer identity is exactly the IdentityHashMap key used by Minecraft's real batching. */
    static MetalEntityMotionCapture.@Nullable Sample beginLayerBatch(final Object owner, final LayerSignature signature) {
        if (!frameOpen || owner == null || signature == null || signature.particleIds().isEmpty()
                || invalidLayers.contains(owner)) return null;
        LayerSignature definition = new LayerSignature(signature.translucent(),signature.textureAtlas(),
                signature.pipelineLocation(),signature.vertexShader(),List.of());
        LayerState previous = previousLayers.get(owner), pending = pendingLayers.get(owner);
        if (pending != null && !pending.definition().equals(definition)) {
            invalidateLayer(owner,"particle-layer-definition-changed-within-frame");
            return null;
        }
        boolean hasPrevious = previous != null && previous.definition().equals(definition);
        if (pending == null) {
            pending = new LayerState(definition,previous == null ? allocateLayerObjectId() : previous.objectId(),
                    hasPrevious ? previous.generation() : allocateGeneration());
            pendingLayers.put(owner,pending);
        }
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(pending.objectId(),pending.generation(),identity,
                hasPrevious ? identity : null,FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
    }

    /** Called from the StagedVertexBuffer append hook while this exact feature group is active. */
    public static void attachDrawIfActive(
            final StagedVertexBuffer.Draw draw,
            final VertexFormat format,
            final PrimitiveTopology topology
    ) {
        ActiveGroup active = activeGroup;
        if (!frameOpen || active == null || active.invalid) {
            return;
        }
        DrawPlan plan = active.drawPlans.pollFirst();
        if (plan == null) {
            invalidateActive("particle-extra-staged-draw");
            return;
        }
        if (!DefaultVertexFormat.PARTICLE.equals(format) || topology != PrimitiveTopology.QUADS) {
            invalidateActive("particle-staged-abi-changed");
            return;
        }
        MetalEntityMotionCapture.endModelBuild();
        if (plan.sample() != null) {
            MetalEntityMotionCapture.beginParticleBatchBuild(plan.sample());
            try { MetalEntityMotionCapture.attachDraw(draw, plan.pipeline(), plan.particleIds()); }
            finally { MetalEntityMotionCapture.endModelBuild(); }
        }
    }

    public static void endFeatureGroup() {
        ActiveGroup active = activeGroup;
        if (active != null && !active.invalid && !active.drawPlans.isEmpty()) {
            invalidateActive("particle-missing-staged-draw");
        }
        MetalEntityMotionCapture.endModelBuild();
        activeGroup = null;
    }

    private static void invalidateActive(final String reason) {
        ActiveGroup active = activeGroup;
        if (active == null || active.invalid) {
            return;
        }
        active.invalid = true;
        active.layers.forEach(layer -> invalidateLayer(layer,reason));
        active.samples.forEach(sample -> MetalEntityMotionCapture.failExactSample(sample,reason));
        MetalFxManager.observeParticleMotion();
    }

    static void invalidateLayer(final Object owner,final String reason) {
        invalidLayers.add(owner);
        LayerState removed = pendingLayers.remove(owner);
        if (removed != null) {
            Matrix4f identity = new Matrix4f();
            MetalEntityMotionCapture.failExactSample(new MetalEntityMotionCapture.Sample(removed.objectId(),
                    removed.generation(),identity,null,FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER),reason);
        }
    }

    private static void rejectGroup(final List<QuadParticleFeatureRenderer.Submit> submits, final String reason) {
        for (var submit : submits) if (submit != null && submit.particles() != null) {
            for (var layer : submit.particles().layers()) if (layer != null && layer.translucent() == submit.translucent()) {
                invalidateLayer(layer,reason);
            }
        }
        MetalFxManager.observeParticleMotion();
    }

    static void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        previousLayers.clear();
        previousLayers.putAll(pendingLayers);
        clearTransient();
        frameOpen = false;
    }

    static void discardFrame() {
        if (!frameOpen) {
            return;
        }
        clearTransient();
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        previousLayers.clear();
        pendingLayers.clear();
        invalidLayers.clear();
        MEMBERSHIP.clear();
        SUBMITTED_COUNTS.clear();
        INVALID_STATES.clear();
        EXTRACTION.remove();
        activeGroup = null;
        nextGeneration = -1L;
        frameOpen = wasOpen;
    }

    private static void clearTransient() {
        pendingLayers.clear();
        invalidLayers.clear();
        MEMBERSHIP.clear();
        SUBMITTED_COUNTS.clear();
        INVALID_STATES.clear();
        EXTRACTION.remove();
        activeGroup = null;
    }

    private static long allocateGeneration() {
        long generation = nextGeneration;
        nextGeneration = generation == Long.MIN_VALUE ? -1L : generation - 1L;
        return generation;
    }

    private static long allocateLayerObjectId() {
        long id = nextLayerObjectId;
        nextLayerObjectId = Math.addExact(nextLayerObjectId,1L);
        return id;
    }
}
