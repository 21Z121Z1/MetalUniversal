package com.metallum.client.metal.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.AbstractList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-thread carrier for one ordinary-entity motion draw.
 *
 * <p>Minecraft 26.2 records model submits first and later batches their
 * geometry by render type. Object motion cannot therefore be represented by a
 * draw-global uniform unless each entity draw is deliberately split. This
 * carrier preserves the entity observation across those two phases and binds
 * it to the exact staged draw/execute-info pair that owns the vertices.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalEntityMotionCapture {
    private static volatile boolean enabled = true;
    public record Diagnostics(
            int statesAttached,
            int entitySubmissionsMatched,
            int modelSubmitsCaptured,
            int modelBuildsMatched,
            int splitChecksMatched,
            int drawsAttached,
            int executesTransferred,
            int executesConsumed,
            int motionDrawsEncoded,
            int itemMotionDrawsEncoded,
            int blockMotionDrawsEncoded,
            @Nullable String lastMotionDrawSkip,
            @Nullable String lastVertexShader
    ) {
    }

    public record Sample(
            long objectId,
            long generation,
            Matrix4f currentObject,
            @Nullable Matrix4f previousObject,
            FrameSynthesisContract.ProducerDomain domain
    ) {
        public Sample {
            currentObject = new Matrix4f(currentObject);
            previousObject = previousObject == null ? null : new Matrix4f(previousObject);
            if (domain == null) {
                throw new NullPointerException("domain");
            }
        }

        public Sample(
                final long objectId,
                final long generation,
                final Matrix4f currentObject,
                @Nullable final Matrix4f previousObject
        ) {
            this(
                    objectId,
                    generation,
                    currentObject,
                    previousObject,
                    FrameSynthesisContract.ProducerDomain.DYNAMIC_CONTENT
            );
        }

        @Override
        public Matrix4f currentObject() {
            return new Matrix4f(currentObject);
        }

        @Override
        public @Nullable Matrix4f previousObject() {
            return previousObject == null ? null : new Matrix4f(previousObject);
        }

        public boolean hasPrevious() {
            return previousObject != null;
        }
    }

    private record DrawCapture(
            Sample sample,
            MetalPreviousVertexHistory.DrawToken previousVertexToken
    ) {
    }

    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();
    private static final ThreadLocal<Object> ENTITY_SUBMISSION_STATE = new ThreadLocal<>();
    private static final ThreadLocal<Sample> MODEL_BUILD = new ThreadLocal<>();
    private static final Map<Object, Sample> STATES = new IdentityHashMap<>();
    private static final Map<Object, Sample> SUBMITS = new IdentityHashMap<>();
    private static final Map<Object, EntityRenderState> SHADOW_SUBMIT_STATES = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.Draw, DrawCapture> DRAWS = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.ExecuteInfo, Sample> EXECUTES = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.ExecuteInfo, MetalPreviousVertexHistory.DrawToken> EXECUTE_VERTEX_TOKENS =
            new IdentityHashMap<>();
    private static int statesAttached;
    private static int entitySubmissionsMatched;
    private static int modelSubmitsCaptured;
    private static int modelBuildsMatched;
    private static int splitChecksMatched;
    private static int drawsAttached;
    private static int executesTransferred;
    private static int executesConsumed;
    private static int motionDrawsEncoded;
    private static int itemMotionDrawsEncoded;
    private static int blockMotionDrawsEncoded;
    private static @Nullable String lastMotionDrawSkip;
    private static @Nullable String lastVertexShader;

    private MetalEntityMotionCapture() {
    }

    static void setEnabled(final boolean value) {
        enabled = value;
        if (!value) {
            clearFrameState();
            MetalExactMotionCoverage.reset();
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    public static void beginFrame() {
        if (!enabled) {
            return;
        }
        clearFrameState();
        MetalExactMotionCoverage.beginFrame();
    }

    private static void clearFrameState() {
        ENTITY_SUBMISSION.remove();
        ENTITY_SUBMISSION_STATE.remove();
        MODEL_BUILD.remove();
        STATES.clear();
        SUBMITS.clear();
        SHADOW_SUBMIT_STATES.clear();
        DRAWS.clear();
        EXECUTES.clear();
        EXECUTE_VERTEX_TOKENS.clear();
        statesAttached = 0;
        entitySubmissionsMatched = 0;
        modelSubmitsCaptured = 0;
        modelBuildsMatched = 0;
        splitChecksMatched = 0;
        drawsAttached = 0;
        executesTransferred = 0;
        executesConsumed = 0;
        motionDrawsEncoded = 0;
        itemMotionDrawsEncoded = 0;
        blockMotionDrawsEncoded = 0;
        lastMotionDrawSkip = null;
        lastVertexShader = null;
    }

    public static void attachState(final Object state, final Sample sample) {
        if (enabled && state != null && sample != null) {
            STATES.put(state, sample);
            statesAttached++;
        }
    }

    public static boolean hasPreviousState(final Object state) {
        Sample sample = enabled && state != null ? STATES.get(state) : null;
        return sample != null && sample.hasPrevious();
    }

    @Nullable
    public static Sample sampleForState(final Object state) {
        return enabled && state != null ? STATES.get(state) : null;
    }

    /** Marks the actual submitted entity object as requiring exact staged previous positions. */
    public static void requireExactState(final Object state) {
        Sample sample = enabled && state != null ? STATES.get(state) : null;
        if (sample != null) {
            MetalExactMotionCoverage.require(sample);
        }
    }

    /** Marks auxiliary geometry which cannot yet be isolated into an exact per-owner staged draw. */
    public static void rejectCurrentExactAuxiliary(final String reason) {
        if (enabled) {
            MetalExactMotionCoverage.fail(ENTITY_SUBMISSION.get(), reason);
        }
    }

    /** Explicit fail-closed hook for synthetic exact owners such as particle feature groups. */
    public static void failExactSample(final Sample sample, final String reason) {
        if (enabled && sample != null) {
            MetalExactMotionCoverage.fail(sample, reason);
        }
    }

    /** Final source-frame proof consumed by frame-interpolator admission. */
    public static boolean exactCoverageComplete() {
        return !enabled || MetalExactMotionCoverage.complete();
    }

    public static void beginEntitySubmission(final Object state) {
        if (!enabled) {
            return;
        }
        Sample sample = STATES.get(state);
        if (sample == null) {
            ENTITY_SUBMISSION.remove();
            ENTITY_SUBMISSION_STATE.remove();
        } else {
            ENTITY_SUBMISSION.set(sample);
            ENTITY_SUBMISSION_STATE.set(state);
            entitySubmissionsMatched++;
        }
    }

    public static void endEntitySubmission() {
        if (enabled) {
            ENTITY_SUBMISSION.remove();
            ENTITY_SUBMISSION_STATE.remove();
        }
    }

    public static void captureModelSubmit(final Object submit) {
        if (!enabled) {
            return;
        }
        Sample sample = ENTITY_SUBMISSION.get();
        if (submit != null && sample != null) {
            SUBMITS.put(submit, sample);
            modelSubmitsCaptured++;
        }
    }

    /** Captures one entity-shadow submit while its real dispatcher owner is still active. */
    public static void captureShadowSubmit(final ShadowFeatureRenderer.Submit submit) {
        if (!enabled || submit == null) {
            return;
        }
        Sample sample = ENTITY_SUBMISSION.get();
        Object state = ENTITY_SUBMISSION_STATE.get();
        if (sample != null && state instanceof EntityRenderState entityState) {
            SUBMITS.put(submit, sample);
            SHADOW_SUBMIT_STATES.put(submit, entityState);
            modelSubmitsCaptured++;
        }
    }

    /**
     * Activates one synthetic exact owner for Minecraft 26.2's complete shared shadow builder.
     * Every submit is paired with the real entity lifetime and the exact ordered terrain pieces
     * that generate its four-vertex quads.
     */
    public static void beginSharedShadowBuild(final List<ShadowFeatureRenderer.Submit> submits) {
        if (!enabled) {
            return;
        }
        MODEL_BUILD.remove();
        if (submits == null || submits.isEmpty()) {
            return;
        }

        java.util.ArrayList<MetalShadowBatchMotion.Member> members = new java.util.ArrayList<>(submits.size());
        for (ShadowFeatureRenderer.Submit submit : submits) {
            Sample owner = submit == null ? null : SUBMITS.remove(submit);
            EntityRenderState state = submit == null ? null : SHADOW_SUBMIT_STATES.remove(submit);
            MetalShadowBatchMotion.Member member =
                    owner == null || state == null || submit == null
                            ? null
                            : MetalShadowBatchMotion.member(owner, state, submit);
            if (member == null) {
                MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();
                return;
            }
            members.add(member);
        }

        Sample batch = MetalShadowBatchMotion.beginShadowBatch(members);
        if (batch == null) {
            MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();
            return;
        }
        MODEL_BUILD.set(batch);
        MetalExactMotionCoverage.require(batch);
        modelBuildsMatched++;
    }

    /**
     * Activates one synthetic exact owner for Minecraft 26.2's complete Flame shared builder.
     * Every Submit must resolve to the positive-lifetime owner captured at entity submission.
     * Per-member emitted vertex spans prevent equal-and-opposite topology changes from preserving
     * an unsafe aggregate ordinal mapping.
     */
    public static void beginSharedFlameBuild(final List<FlameFeatureRenderer.Submit> submits) {
        if (!enabled) {
            return;
        }
        MODEL_BUILD.remove();
        if (submits == null || submits.isEmpty()) {
            return;
        }

        java.util.ArrayList<MetalSharedBatchMotion.Member> members = new java.util.ArrayList<>(submits.size());
        for (FlameFeatureRenderer.Submit submit : submits) {
            Sample owner = submit == null ? null : SUBMITS.remove(submit);
            int vertexSpan = submit == null
                    ? -1
                    : MetalSharedBatchMotion.flameVertexSpan(
                            submit.entityRenderState().boundingBoxWidth,
                            submit.entityRenderState().boundingBoxHeight
                    );
            if (owner == null || owner.generation() <= 0L || vertexSpan <= 0) {
                MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();
                return;
            }
            members.add(new MetalSharedBatchMotion.Member(
                    owner.objectId(), owner.generation(), vertexSpan
            ));
        }

        Sample batch = MetalSharedBatchMotion.beginFlameBatch(members);
        if (batch == null) {
            MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();
            return;
        }
        MODEL_BUILD.set(batch);
        // First sight, membership/order changes and per-member span changes have no matching exact
        // history. Marking the synthetic owner exact-required guarantees root-motion fallback can
        // never make such a source frame eligible for MTLFXFrameInterpolator.
        MetalExactMotionCoverage.require(batch);
        modelBuildsMatched++;
    }

    public static void beginModelBuild(final Object submit) {
        beginBuild(submit, false);
    }

    /** Returns whether a custom submit still resolves to a real entity owner before its build. */
    public static boolean hasModelSubmitOwner(final Object submit) {
        return enabled && submit != null && SUBMITS.containsKey(submit);
    }

    public static void beginItemBuild(final Object submit) {
        beginBuild(submit, true);
    }

    public static void attachMovingBlockState(final Object renderState, final Sample sample) {
        if (enabled && renderState != null && sample != null) {
            SUBMITS.put(renderState, sample);
            modelSubmitsCaptured++;
        }
    }

    public static boolean hasMovingBlockOwner(final Object renderState) {
        return enabled && renderState != null && SUBMITS.containsKey(renderState);
    }

    @Nullable
    public static Sample sampleForSubmit(final Object submit) {
        return enabled && submit != null ? SUBMITS.get(submit) : null;
    }

    public static void beginMovingBlockBuild(final Object renderState) {
        beginBuild(renderState, true);
    }

    private static void beginBuild(final Object submit, final boolean retainOwner) {
        if (!enabled) {
            return;
        }
        Sample sample = retainOwner ? SUBMITS.get(submit) : SUBMITS.remove(submit);
        if (sample == null) {
            MODEL_BUILD.remove();
        } else {
            MODEL_BUILD.set(sample);
            modelBuildsMatched++;
        }
    }

    public static <T> List<T> activateBuildSampleOnAccess(final List<T> submits) {
        if (!enabled || submits == null || submits.isEmpty()) {
            return submits;
        }
        return new AbstractList<>() {
            @Override
            public T get(final int index) {
                T submit = submits.get(index);
                beginModelBuild(submit);
                return submit;
            }

            @Override
            public int size() {
                return submits.size();
            }
        };
    }

    public static void endModelBuild() {
        if (enabled) {
            MODEL_BUILD.remove();
        }
    }

    /** Opens an exact staged build which has no ordinary entity/model submit owner. */
    public static void beginParticleBatchBuild(final Sample sample) {
        if (!enabled) {
            return;
        }
        MODEL_BUILD.remove();
        if (sample == null) {
            return;
        }
        MODEL_BUILD.set(sample);
        MetalExactMotionCoverage.require(sample);
        modelBuildsMatched++;
    }

    public static boolean shouldSplitEntityDraw(final RenderPipeline pipeline) {
        if (!enabled) {
            return false;
        }
        Sample sample = MODEL_BUILD.get();
        if (sample == null || pipeline == null) {
            return false;
        }
        lastVertexShader = pipeline.getVertexShader().toString();
        boolean rootSupported = MetalEntityMotionPipeline.supports(pipeline);
        boolean exactSupported = MetalEntityMotionPipeline.supportsPreviousPositions(pipeline);
        boolean matched = MetalEntityMotionPipeline.isSplittableVertexShader(pipeline);
        if (exactSupported && !rootSupported) {
            // Exact-only auxiliary families (leash/world text) must prove a complete previous
            // staged manifest even for otherwise rigid entity classes. They have no safe root fallback.
            MetalExactMotionCoverage.require(sample);
        }
        if (MetalExactMotionCoverage.required(sample) && !exactSupported) {
            MetalExactMotionCoverage.fail(
                    sample,
                    "unsupported-exact-pipeline:" + pipeline.getVertexShader()
            );
        }
        if (matched) {
            splitChecksMatched++;
        }
        return matched;
    }

    public static void attachDraw(final StagedVertexBuffer.Draw draw, final RenderPipeline pipeline) {
        if (!enabled) {
            return;
        }
        Sample sample = MODEL_BUILD.get();
        if (draw != null && sample != null && pipeline != null) {
            DRAWS.put(draw, new DrawCapture(
                    sample,
                    MetalPreviousVertexHistory.reserveDraw(sample, pipeline)
            ));
            drawsAttached++;
        }
    }

    public static void captureVertexData(
            final StagedVertexBuffer.Draw draw,
            final VertexFormat format,
            final PrimitiveTopology topology,
            final List<ByteBufferBuilder.Result> slices,
            final int vertexCount,
            final int indexCount
    ) {
        if (!enabled || draw == null) {
            return;
        }
        DrawCapture capture = DRAWS.get(draw);
        if (capture != null) {
            MetalPreviousVertexHistory.capture(
                    capture.previousVertexToken(),
                    format,
                    topology,
                    slices,
                    vertexCount,
                    indexCount
            );
        }
    }

    public static void transferExecute(
            final StagedVertexBuffer.Draw draw,
            final StagedVertexBuffer.ExecuteInfo executeInfo
    ) {
        if (!enabled) {
            return;
        }
        DrawCapture capture = DRAWS.remove(draw);
        if (capture != null && executeInfo != null) {
            EXECUTES.put(executeInfo, capture.sample());
            if (capture.previousVertexToken() != null) {
                EXECUTE_VERTEX_TOKENS.put(executeInfo, capture.previousVertexToken());
            }
            executesTransferred++;
        }
    }

    @Nullable
    public static Sample takeExecute(final StagedVertexBuffer.ExecuteInfo executeInfo) {
        if (!enabled) {
            return null;
        }
        Sample sample = EXECUTES.remove(executeInfo);
        if (sample != null) {
            executesConsumed++;
        }
        return sample;
    }

    /** Consumes the staged previous-position identity paired with this exact ExecuteInfo. */
    static MetalPreviousVertexHistory.DrawToken takePreviousVertexToken(
            final StagedVertexBuffer.ExecuteInfo executeInfo
    ) {
        return enabled && executeInfo != null ? EXECUTE_VERTEX_TOKENS.remove(executeInfo) : null;
    }

    public static Diagnostics diagnostics() {
        return new Diagnostics(
                statesAttached,
                entitySubmissionsMatched,
                modelSubmitsCaptured,
                modelBuildsMatched,
                splitChecksMatched,
                drawsAttached,
                executesTransferred,
                executesConsumed,
                motionDrawsEncoded,
                itemMotionDrawsEncoded,
                blockMotionDrawsEncoded,
                lastMotionDrawSkip,
                lastVertexShader
        );
    }

    static void recordMotionDrawEncoded(final RenderPipeline source) {
        if (!enabled) {
            return;
        }
        motionDrawsEncoded++;
        MetalFxMotionTelemetry.recordMotionReplayDraw();
        if (source != null) {
            switch (source.getVertexShader().getPath()) {
                case "core/item" -> itemMotionDrawsEncoded++;
                case "core/block" -> blockMotionDrawsEncoded++;
                default -> {
                }
            }
        }
        lastMotionDrawSkip = null;
    }

    static void recordMotionDrawSkip(final String reason) {
        if (enabled) {
            lastMotionDrawSkip = reason;
        }
    }

    static void recordExactReplayEncoded(final MetalPreviousVertexHistory.DrawToken token) {
        if (enabled) {
            MetalExactMotionCoverage.recordExactEncoded(token);
        }
    }

    static Matrix4f objectCurrentToPrevious(final Sample sample) {
        Matrix4fc previous = sample.previousObject();
        Matrix4f inverseCurrent = sample.currentObject();
        if (previous == null || !inverseCurrent.invert().isFinite()) {
            return new Matrix4f();
        }
        return new Matrix4f(previous).mul(inverseCurrent);
    }
}
