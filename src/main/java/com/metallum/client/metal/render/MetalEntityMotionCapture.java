package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    private static final float OBJECT_MOTION_EPSILON = 1.0E-6F;
    private static volatile boolean enabled = true;

    public enum Source {
        ENTITY,
        DISPLAY,
        ITEM_FRAME_BASE,
        ITEM_FRAME_CONTENT,
        PAINTING,
        ARMOR_STAND,
        END_CRYSTAL,
        END_CRYSTAL_BEAM,
        BLOCK_ENTITY,
        PISTON_MOVED_BLOCK,
        PISTON_BASE
    }
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
            // Subset of motionDrawsEncoded that came from the core/item family.
            // Dropped items, item frames and held items are the only source, so a
            // scene with dropped items in view and a zero here means the item
            // motion path is not reaching the interpolator.
            int itemMotionDrawsEncoded,
            // Subset of motionDrawsEncoded that came from the core/block family.
            // Falling blocks and block entities are the only source, so a scene
            // with a falling block in view and a zero here means the block motion
            // path is not reaching the interpolator.
            int blockMotionDrawsEncoded,
            int blockEntityStatesAttached,
            int blockEntitySubmissionsMatched,
            int blockEntityModelSubmitsCaptured,
            int blockEntityMovingSubmitsCaptured,
            int blockEntityMotionDrawsEncoded,
            int pistonMotionDrawsEncoded,
            Map<Source, Integer> samplesAttachedBySource,
            Map<Source, Integer> drawsEncodedBySource,
            int poseOwnersCaptured,
            int poseOwnersMatched,
            Set<String> motionDrawFailures,
            @Nullable String lastMotionDrawSkip,
            @Nullable String lastVertexShader
    ) {
        public Diagnostics {
            samplesAttachedBySource = Map.copyOf(samplesAttachedBySource);
            drawsEncodedBySource = Map.copyOf(drawsEncodedBySource);
            motionDrawFailures = Set.copyOf(motionDrawFailures);
        }

        public int samplesAttached(final Source source) {
            return samplesAttachedBySource.getOrDefault(source, 0);
        }

        public int drawsEncoded(final Source source) {
            return drawsEncodedBySource.getOrDefault(source, 0);
        }
    }

    public record Sample(
            long objectId,
            long generation,
            Matrix4f currentObject,
            @Nullable Matrix4f previousObject,
            Source source
    ) {
        public Sample(
                final long objectId,
                final long generation,
                final Matrix4f currentObject,
                @Nullable final Matrix4f previousObject
        ) {
            this(objectId, generation, currentObject, previousObject, Source.ENTITY);
        }

        public Sample {
            currentObject = new Matrix4f(currentObject);
            previousObject = previousObject == null ? null : new Matrix4f(previousObject);
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

        /**
         * A previous pose alone is not enough to claim object motion. A static
         * object would otherwise replay camera-only/zero motion with validity,
         * overriding the camera producer in the merge pass.
         */
        public boolean hasObjectMotion() {
            return previousObject != null
                    && !currentObject.equals(previousObject, OBJECT_MOTION_EPSILON);
        }
    }

    private record BlockEntitySubmission(
            @Nullable Sample generic,
            Map<Object, Sample> movingBlocks
    ) {
    }

    private record EntitySubmission(
            @Nullable Sample generic,
            Map<MetalEntityObjectPose.EntityPart, Sample> parts
    ) {
    }

    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();
    private static final ThreadLocal<EntitySubmission> ENTITY_SUBMISSION_STATE = new ThreadLocal<>();
    private static final ThreadLocal<MetalEntityObjectPose.EntityPart> ENTITY_PART = new ThreadLocal<>();
    private static final ThreadLocal<BlockEntitySubmission> BLOCK_ENTITY_SUBMISSION = new ThreadLocal<>();
    private static final ThreadLocal<Sample> MODEL_BUILD = new ThreadLocal<>();
    private static final ThreadLocal<Sample> LAST_BUILDER_OWNER = new ThreadLocal<>();
    private static final Map<Object, EntitySubmission> STATES = new IdentityHashMap<>();
    private static final Map<Object, BlockEntitySubmission> BLOCK_ENTITY_STATES = new IdentityHashMap<>();
    private static final Map<Object, Sample> SUBMITS = new IdentityHashMap<>();
    private static final Map<PoseStack.Pose, Sample> POSE_SUBMITS = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.Draw, Sample> DRAWS = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.ExecuteInfo, Sample> EXECUTES = new IdentityHashMap<>();
    private static final EnumMap<Source, Integer> SAMPLES_ATTACHED_BY_SOURCE = new EnumMap<>(Source.class);
    private static final EnumMap<Source, Integer> DRAWS_ENCODED_BY_SOURCE = new EnumMap<>(Source.class);
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
    private static int blockEntityStatesAttached;
    private static int blockEntitySubmissionsMatched;
    private static int blockEntityModelSubmitsCaptured;
    private static int blockEntityMovingSubmitsCaptured;
    private static int blockEntityMotionDrawsEncoded;
    private static int pistonMotionDrawsEncoded;
    private static int poseOwnersCaptured;
    private static int poseOwnersMatched;
    private static final Set<String> MOTION_DRAW_FAILURES = new LinkedHashSet<>();
    private static @Nullable String lastMotionDrawSkip;
    private static @Nullable String lastVertexShader;

    private MetalEntityMotionCapture() {
    }

    static void setEnabled(final boolean value) {
        enabled = value;
        if (!value) {
            clearFrameState();
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
    }

    private static void clearFrameState() {
        ENTITY_SUBMISSION.remove();
        ENTITY_SUBMISSION_STATE.remove();
        ENTITY_PART.remove();
        BLOCK_ENTITY_SUBMISSION.remove();
        MODEL_BUILD.remove();
        LAST_BUILDER_OWNER.remove();
        STATES.clear();
        BLOCK_ENTITY_STATES.clear();
        SUBMITS.clear();
        POSE_SUBMITS.clear();
        DRAWS.clear();
        EXECUTES.clear();
        SAMPLES_ATTACHED_BY_SOURCE.clear();
        DRAWS_ENCODED_BY_SOURCE.clear();
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
        blockEntityStatesAttached = 0;
        blockEntitySubmissionsMatched = 0;
        blockEntityModelSubmitsCaptured = 0;
        blockEntityMovingSubmitsCaptured = 0;
        blockEntityMotionDrawsEncoded = 0;
        pistonMotionDrawsEncoded = 0;
        poseOwnersCaptured = 0;
        poseOwnersMatched = 0;
        MOTION_DRAW_FAILURES.clear();
        lastMotionDrawSkip = null;
        lastVertexShader = null;
    }

    public static void attachState(final Object state, final Sample sample) {
        if (enabled && state != null && sample != null) {
            STATES.put(state, new EntitySubmission(sample, Map.of()));
            statesAttached++;
            recordSampleAttached(sample);
        }
    }

    public static void attachEntityState(
            final Object state,
            @Nullable final Sample generic,
            final Map<MetalEntityObjectPose.EntityPart, Sample> parts
    ) {
        if (!enabled || state == null || (generic == null && parts.isEmpty())) {
            return;
        }
        STATES.put(state, new EntitySubmission(generic, Map.copyOf(parts)));
        statesAttached++;
        if (generic != null) {
            recordSampleAttached(generic);
        }
        for (Sample sample : parts.values()) {
            recordSampleAttached(sample);
        }
    }

    public static void attachBlockEntityState(final Object state, final Sample sample) {
        if (enabled && state != null && sample != null) {
            BLOCK_ENTITY_STATES.put(state, new BlockEntitySubmission(sample, new IdentityHashMap<>()));
            blockEntityStatesAttached++;
            recordSampleAttached(sample);
        }
    }

    public static void attachPistonState(
            final Object state,
            @Nullable final Object movedBlock,
            @Nullable final Sample movedSample,
            @Nullable final Object baseBlock,
            @Nullable final Sample baseSample
    ) {
        if (!enabled || state == null) {
            return;
        }
        IdentityHashMap<Object, Sample> movingBlocks = new IdentityHashMap<>();
        if (movedBlock != null && movedSample != null) {
            movingBlocks.put(movedBlock, movedSample);
            recordSampleAttached(movedSample);
        }
        if (baseBlock != null && baseSample != null) {
            movingBlocks.put(baseBlock, baseSample);
            recordSampleAttached(baseSample);
        }
        if (!movingBlocks.isEmpty()) {
            BLOCK_ENTITY_STATES.put(state, new BlockEntitySubmission(null, movingBlocks));
            blockEntityStatesAttached++;
        }
    }

    public static void beginEntitySubmission(final Object state) {
        if (!enabled) {
            return;
        }
        EntitySubmission submission = STATES.get(state);
        if (submission == null) {
            ENTITY_SUBMISSION.remove();
            ENTITY_SUBMISSION_STATE.remove();
        } else {
            ENTITY_SUBMISSION_STATE.set(submission);
            if (submission.generic() == null) {
                ENTITY_SUBMISSION.remove();
            } else {
                ENTITY_SUBMISSION.set(submission.generic());
            }
            entitySubmissionsMatched++;
        }
    }

    public static void endEntitySubmission() {
        if (enabled) {
            ENTITY_SUBMISSION.remove();
            ENTITY_SUBMISSION_STATE.remove();
            ENTITY_PART.remove();
        }
    }

    public static void beginEntityPart(final MetalEntityObjectPose.EntityPart part) {
        if (enabled) {
            ENTITY_PART.set(part);
        }
    }

    public static void endEntityPart() {
        if (enabled) {
            ENTITY_PART.remove();
        }
    }

    public static void beginBlockEntitySubmission(final Object state) {
        if (!enabled) {
            return;
        }
        BlockEntitySubmission submission = BLOCK_ENTITY_STATES.get(state);
        if (submission == null) {
            BLOCK_ENTITY_SUBMISSION.remove();
        } else {
            BLOCK_ENTITY_SUBMISSION.set(submission);
            blockEntitySubmissionsMatched++;
        }
    }

    public static void endBlockEntitySubmission() {
        if (enabled) {
            BLOCK_ENTITY_SUBMISSION.remove();
        }
    }

    public static void captureModelSubmit(final Object submit) {
        captureModelSubmit(submit, null);
    }

    public static void captureModelSubmit(
            final Object submit,
            final PoseStack.@Nullable Pose pose
    ) {
        if (!enabled) {
            return;
        }
        Sample sample = null;
        BlockEntitySubmission blockEntitySubmission = BLOCK_ENTITY_SUBMISSION.get();
        if (blockEntitySubmission != null) {
            sample = blockEntitySubmission.generic();
            if (sample != null) {
                blockEntityModelSubmitsCaptured++;
            }
        } else {
            EntitySubmission entitySubmission = ENTITY_SUBMISSION_STATE.get();
            MetalEntityObjectPose.EntityPart part = ENTITY_PART.get();
            sample = part == null || entitySubmission == null
                    ? ENTITY_SUBMISSION.get()
                    : entitySubmission.parts().get(part);
        }
        if (submit != null && sample != null) {
            SUBMITS.put(submit, sample);
            if (pose != null) {
                POSE_SUBMITS.put(pose, sample);
                poseOwnersCaptured++;
            }
            modelSubmitsCaptured++;
        }
    }

    /** Makes the pose-keyed owner current before a feature renderer asks for its vertex builder. */
    public static void beginModelBuildForPose(final PoseStack.@Nullable Pose pose) {
        if (!enabled) {
            return;
        }
        Sample sample = pose == null ? null : POSE_SUBMITS.get(pose);
        if (sample == null) {
            MODEL_BUILD.remove();
        } else {
            MODEL_BUILD.set(sample);
            modelBuildsMatched++;
            poseOwnersMatched++;
        }
    }

    /** Captures the owner of a {@code submitMovingBlock} record. */
    public static void captureMovingBlockSubmit(final Object submit) {
        if (!enabled) {
            return;
        }
        Sample sample = null;
        BlockEntitySubmission blockEntitySubmission = BLOCK_ENTITY_SUBMISSION.get();
        if (blockEntitySubmission != null) {
            sample = blockEntitySubmission.movingBlocks().get(submit);
            if (sample != null) {
                blockEntityMovingSubmitsCaptured++;
            }
        } else {
            sample = ENTITY_SUBMISSION.get();
        }
        if (submit != null && sample != null) {
            SUBMITS.put(submit, sample);
            modelSubmitsCaptured++;
        }
    }

    public static void beginModelBuild(final Object submit) {
        beginBuild(submit, false);
    }

    /**
     * {@code ItemFeatureRenderer.buildGroup} walks its submit list twice — main
     * geometry first, then the enchantment foil — so the owning entity has to
     * survive the first pass. {@link #beginFrame()} bounds the map instead.
     */
    public static void beginItemBuild(final Object submit) {
        beginBuild(submit, true);
    }

    /**
     * Moving blocks are keyed by their {@code MovingBlockRenderState} rather than
     * by the submit record.
     *
     * <p>{@code MovingBlockFeatureRenderer.buildGroup} inlines its per-submit work
     * in the loop body, so the submit itself is only a local there. The render
     * state is reachable at both ends — it is a constructor argument of the submit
     * and the level argument of the {@code tesselateBlock} call — and one falling
     * block owns one render state, so it identifies the same thing.</p>
     *
     * <p>The owner is retained rather than consumed, because a single block model
     * can tesselate into the solid, cutout and translucent render types and a
     * future caller may bracket each separately. {@link #beginFrame()} clears the
     * map every frame, so retaining cannot leak across frames.</p>
     */
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

    public static void endModelBuild() {
        if (enabled) {
            MODEL_BUILD.remove();
        }
    }

    public static boolean shouldSplitEntityDraw(final RenderPipeline pipeline) {
        if (!enabled) {
            return false;
        }
        Sample sample = MODEL_BUILD.get();
        if (pipeline == null || !MetalEntityMotionPipeline.isSplittableVertexShader(pipeline)) {
            return false;
        }
        lastVertexShader = pipeline.getVertexShader().toString();
        Sample lastOwner = LAST_BUILDER_OWNER.get();
        boolean ownerChanged = sample != lastOwner;
        LAST_BUILDER_OWNER.set(sample);
        // An owned submit always receives its own draw. When an owned submit is
        // followed by unowned geometry, the one-shot owner transition resets the
        // group's last draw so the unowned vertices cannot inherit its sample.
        boolean matched = sample != null || ownerChanged;
        if (matched) {
            splitChecksMatched++;
        }
        return matched;
    }

    public static void attachDraw(final StagedVertexBuffer.Draw draw) {
        if (!enabled) {
            return;
        }
        Sample sample = MODEL_BUILD.get();
        if (draw != null && sample != null) {
            DRAWS.put(draw, sample);
            drawsAttached++;
        }
    }

    public static void transferExecute(
            final StagedVertexBuffer.Draw draw,
            final StagedVertexBuffer.ExecuteInfo executeInfo
    ) {
        if (!enabled) {
            return;
        }
        Sample sample = DRAWS.remove(draw);
        if (sample != null && executeInfo != null) {
            EXECUTES.put(executeInfo, sample);
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
                blockEntityStatesAttached,
                blockEntitySubmissionsMatched,
                blockEntityModelSubmitsCaptured,
                blockEntityMovingSubmitsCaptured,
                blockEntityMotionDrawsEncoded,
                pistonMotionDrawsEncoded,
                SAMPLES_ATTACHED_BY_SOURCE,
                DRAWS_ENCODED_BY_SOURCE,
                poseOwnersCaptured,
                poseOwnersMatched,
                MOTION_DRAW_FAILURES,
                lastMotionDrawSkip,
                lastVertexShader
        );
    }

    static void recordMotionDrawEncoded(final RenderPipeline source, @Nullable final Sample sample) {
        if (!enabled) {
            return;
        }
        motionDrawsEncoded++;
        if (sample != null) {
            DRAWS_ENCODED_BY_SOURCE.merge(sample.source(), 1, Integer::sum);
        }
        if (source != null) {
            switch (source.getVertexShader().getPath()) {
                case "core/item" -> itemMotionDrawsEncoded++;
                case "core/block" -> blockMotionDrawsEncoded++;
                default -> {
                    // core/entity carries no subset counter of its own; it is
                    // motionDrawsEncoded minus the two subsets.
                }
            }
        }
        if (sample != null) {
            switch (sample.source()) {
                case BLOCK_ENTITY -> blockEntityMotionDrawsEncoded++;
                case PISTON_MOVED_BLOCK, PISTON_BASE -> pistonMotionDrawsEncoded++;
                default -> {
                }
            }
        }
    }

    static void recordMotionDrawSkip(final String reason) {
        if (enabled) {
            lastMotionDrawSkip = reason;
            if (motionCoverageFailure(reason)) {
                MOTION_DRAW_FAILURES.add(reason);
            }
        }
    }

    private static boolean motionCoverageFailure(final String reason) {
        return switch (reason) {
            case "motion-inputs-unprepared", "attachments-unavailable", "pipeline-unsupported",
                    "non-finite-transform", "flush-attachments-unavailable" -> true;
            default -> false;
        };
    }

    private static void recordSampleAttached(final Sample sample) {
        SAMPLES_ATTACHED_BY_SOURCE.merge(sample.source(), 1, Integer::sum);
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
