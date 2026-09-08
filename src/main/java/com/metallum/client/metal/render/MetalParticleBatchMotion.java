package com.metallum.client.metal.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact source-frame identity for Minecraft 26.2 quad-particle staged draws.
 *
 * <p>QuadParticleRenderState intentionally stores only the final extracted quad pose, not the
 * particle object that produced it. QuadParticleFeatureRenderer then concatenates all particles of
 * the same Layer into one staged draw. This carrier restores only the missing identity edge: the
 * actual SingleQuadParticle object is tagged while its virtual extract call runs, every state.add
 * records that id in layer order, and the later feature group recreates Minecraft's layer batching
 * order before exact previous-position history is admitted.</p>
 *
 * <p>Membership, layer identity, particle order, or draw-plan changes allocate a fresh negative
 * generation. The first frame after any such change therefore remains real; no particle can inherit
 * another particle's four previous vertices merely because the aggregate count stayed constant.</p>
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

    record Signature(boolean translucent, List<LayerSignature> layers) {
        Signature {
            layers = List.copyOf(layers);
        }
    }

    private record GroupState(Signature signature, long generation) {
    }

    private record Extraction(QuadParticleRenderState state, long particleId) {
    }

    private record DrawPlan(boolean attach, RenderPipeline pipeline) {
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
                    pipeline.getVertexShader().toString(),
                    particleIds
            );
        }
    }

    private static final class ActiveGroup {
        final MetalEntityMotionCapture.Sample sample;
        final int pendingIndex;
        final ArrayDeque<DrawPlan> drawPlans;
        boolean invalid;

        ActiveGroup(
                final MetalEntityMotionCapture.Sample sample,
                final int pendingIndex,
                final ArrayDeque<DrawPlan> drawPlans
        ) {
            this.sample = sample;
            this.pendingIndex = pendingIndex;
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
    private static final ArrayList<GroupState> previousGroups = new ArrayList<>();
    private static final ArrayList<GroupState> pendingGroups = new ArrayList<>();

    private static long nextGeneration = -1L;
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
        pendingGroups.clear();
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

        final int pendingIndex = pendingGroups.size();
        final boolean translucent = submits.getFirst().translucent();
        IdentityHashMap<SingleQuadParticle.Layer, LayerAccumulator> byIdentity = new IdentityHashMap<>();
        ArrayList<LayerAccumulator> orderedLayers = new ArrayList<>();

        for (QuadParticleFeatureRenderer.Submit submit : submits) {
            if (submit == null || submit.translucent() != translucent || submit.particles() == null) {
                rejectGroup(pendingIndex, null, "particle-submit-shape-changed");
                return;
            }
            QuadParticleRenderState state = submit.particles();
            if (state.isEmpty()) {
                continue;
            }
            Integer submittedCount = SUBMITTED_COUNTS.get(state);
            if (INVALID_STATES.contains(state) || submittedCount == null
                    || submittedCount <= 0 || submittedCount != capturedCount(state)) {
                rejectGroup(pendingIndex, null, "particle-membership-unproven");
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

        ArrayList<LayerSignature> signatureLayers = new ArrayList<>();
        ArrayDeque<DrawPlan> plans = new ArrayDeque<>();
        for (LayerAccumulator accumulator : orderedLayers) {
            boolean attach = !accumulator.particleIds.isEmpty();
            RenderPipeline pipeline = accumulator.layer.pipeline();
            if (pipeline == null) {
                rejectGroup(pendingIndex, null, "particle-layer-pipeline-missing");
                return;
            }
            plans.addLast(new DrawPlan(attach, pipeline));
            if (attach) {
                if (!MetalEntityMotionPipeline.supportsPreviousPositions(pipeline)) {
                    rejectGroup(pendingIndex, null,
                            "particle-exact-pipeline-unsupported:" + pipeline.getVertexShader());
                    return;
                }
                signatureLayers.add(accumulator.signature());
            }
        }

        Signature signature = new Signature(translucent, signatureLayers);
        if (signature.layers().isEmpty()) {
            // Vanilla can retain empty Storage keys after clear(). Preserve the feature-group slot
            // without requiring a draw that emits no vertices.
            pendingGroups.add(new GroupState(signature, 0L));
            activeGroup = new ActiveGroup(null, pendingIndex, plans);
            return;
        }

        MetalEntityMotionCapture.Sample sample = beginSignatureBatch(signature);
        if (sample == null) {
            rejectGroup(pendingIndex, null, "particle-batch-transaction-invalid");
            return;
        }
        activeGroup = new ActiveGroup(sample, pendingIndex, plans);
        MetalEntityMotionCapture.beginParticleBatchBuild(sample);
    }

    /** Used by tests and by the real feature-group path after its exact signature is assembled. */
    static MetalEntityMotionCapture.Sample beginSignatureBatch(final Signature signature) {
        if (!frameOpen || signature == null || signature.layers().isEmpty()) {
            return null;
        }
        int ordinal = pendingGroups.size();
        GroupState previous = ordinal < previousGroups.size() ? previousGroups.get(ordinal) : null;
        boolean hasPrevious = previous != null
                && previous.signature() != null
                && previous.generation() < 0L
                && signature.equals(previous.signature());
        long generation = hasPrevious ? previous.generation() : allocateGeneration();
        if (generation >= 0L) {
            return null;
        }
        pendingGroups.add(new GroupState(signature, generation));
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(
                particleObjectId(ordinal),
                generation,
                identity,
                hasPrevious ? identity : null
        );
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
        if (plan.attach() && active.sample != null) {
            MetalEntityMotionCapture.attachDraw(draw, plan.pipeline());
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
        if (active.pendingIndex >= 0 && active.pendingIndex < pendingGroups.size()) {
            pendingGroups.set(active.pendingIndex, new GroupState(null, 0L));
        }
        if (active.sample != null) {
            MetalEntityMotionCapture.failExactSample(active.sample, reason);
        }
        MetalFxManager.observeParticleMotion();
    }

    private static void rejectGroup(
            final int pendingIndex,
            final MetalEntityMotionCapture.Sample sample,
            final String reason
    ) {
        while (pendingGroups.size() < pendingIndex) {
            pendingGroups.add(new GroupState(null, 0L));
        }
        if (pendingGroups.size() == pendingIndex) {
            pendingGroups.add(new GroupState(null, 0L));
        } else {
            pendingGroups.set(pendingIndex, new GroupState(null, 0L));
        }
        if (sample != null) {
            MetalEntityMotionCapture.failExactSample(sample, reason);
        }
        MetalFxManager.observeParticleMotion();
    }

    static void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        previousGroups.clear();
        previousGroups.addAll(pendingGroups);
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
        previousGroups.clear();
        pendingGroups.clear();
        MEMBERSHIP.clear();
        SUBMITTED_COUNTS.clear();
        INVALID_STATES.clear();
        EXTRACTION.remove();
        activeGroup = null;
        nextGeneration = -1L;
        frameOpen = wasOpen;
    }

    private static void clearTransient() {
        pendingGroups.clear();
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

    private static long particleObjectId(final int ordinal) {
        return PARTICLE_OBJECT_ID_BASE + (long) ordinal;
    }
}
