from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))


def create_once(path: str, content: str) -> None:
    p = Path(path)
    if p.exists():
        raise SystemExit(f"refusing to overwrite existing {path}")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


# Shadow is a second shared staged-geometry domain, independent of Flame. Keep its source-frame
# transaction alongside every other previous-vertex history producer.
store = "src/main/java/com/metallum/client/metal/render/MetalMotionStateStore.java"
replace_once(
    store,
    '''        MetalSharedBatchMotion.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n''',
    '''        MetalSharedBatchMotion.beginFrame();\n        MetalShadowBatchMotion.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n'''
)
replace_once(
    store,
    '''        MetalSharedBatchMotion.commitSubmittedFrame();\n        MetalSyntheticExactMotion.commitSubmittedFrame();\n''',
    '''        MetalSharedBatchMotion.commitSubmittedFrame();\n        MetalShadowBatchMotion.commitSubmittedFrame();\n        MetalSyntheticExactMotion.commitSubmittedFrame();\n'''
)
replace_once(
    store,
    '''        MetalSharedBatchMotion.discardFrame();\n        MetalSyntheticExactMotion.discardFrame();\n''',
    '''        MetalSharedBatchMotion.discardFrame();\n        MetalShadowBatchMotion.discardFrame();\n        MetalSyntheticExactMotion.discardFrame();\n'''
)
replace_once(
    store,
    '''        MetalSharedBatchMotion.reset();\n        MetalSyntheticExactMotion.reset();\n''',
    '''        MetalSharedBatchMotion.reset();\n        MetalShadowBatchMotion.reset();\n        MetalSyntheticExactMotion.reset();\n'''
)

create_once(
    "src/main/java/com/metallum/client/metal/render/MetalShadowBatchMotion.java",
    '''package com.metallum.client.metal.render;\n\nimport net.minecraft.client.renderer.entity.state.EntityRenderState;\nimport net.minecraft.client.renderer.feature.ShadowFeatureRenderer;\nimport net.minecraft.world.phys.AABB;\nimport org.joml.Matrix4f;\nimport org.jspecify.annotations.Nullable;\n\nimport java.util.ArrayList;\nimport java.util.List;\n\n/**\n * Exact source-frame identity for Minecraft 26.2's one shared entity-shadow staged draw.\n *\n * <p>ShadowFeatureRenderer concatenates every submitted entity and every terrain ShadowPiece into\n * one ENTITY/QUADS draw. Aggregate vertex count is not a sufficient correspondence proof: one\n * terrain block can disappear while another appears and preserve the same count. The signature\n * therefore records owner lifetime, shadow radius, ordered world block coordinates, and the exact\n * AABB bounds that determine each emitted quad. Previous staged positions remain the authoritative\n * per-vertex positions; this class only proves that vertex ordinal still names the same geometry.</p>\n */\nfinal class MetalShadowBatchMotion {\n    static final long SHADOW_OBJECT_ID = 0x4D465853484457L; // ASCII \"MFXSHDW\".\n    private static final double BLOCK_COORDINATE_EPSILON = 1.0e-3;\n\n    record PieceKey(\n            int blockX, int blockY, int blockZ,\n            long minXBits, long minYBits, long minZBits,\n            long maxXBits, long maxYBits, long maxZBits\n    ) {\n    }\n\n    record Member(long objectId, long generation, int radiusBits, List<PieceKey> pieces) {\n        Member {\n            pieces = List.copyOf(pieces);\n        }\n\n        int vertexSpan() {\n            return Math.multiplyExact(pieces.size(), 4);\n        }\n    }\n\n    private record Signature(List<Member> members) {\n        Signature {\n            members = List.copyOf(members);\n        }\n    }\n\n    private static @Nullable Signature previousSignature;\n    private static long previousGeneration;\n    private static @Nullable Signature pendingSignature;\n    private static long pendingGeneration;\n    private static long nextGeneration = -1L;\n    private static boolean frameOpen;\n    private static boolean batchOpened;\n\n    private MetalShadowBatchMotion() {\n    }\n\n    static void beginFrame() {\n        pendingSignature = null;\n        pendingGeneration = 0L;\n        batchOpened = false;\n        frameOpen = true;\n    }\n\n    /** Builds the exact ordinal identity for one entity-owned Shadow submit. */\n    static @Nullable Member member(\n            final MetalEntityMotionCapture.Sample owner,\n            final EntityRenderState state,\n            final ShadowFeatureRenderer.Submit submit\n    ) {\n        if (owner == null || owner.generation() <= 0L || state == null || submit == null\n                || !Float.isFinite(submit.radius()) || submit.radius() <= 0.0F\n                || submit.pieces() == null || submit.pieces().isEmpty()) {\n            return null;\n        }\n        if (!Double.isFinite(state.x) || !Double.isFinite(state.y) || !Double.isFinite(state.z)) {\n            return null;\n        }\n\n        ArrayList<PieceKey> pieces = new ArrayList<>(submit.pieces().size());\n        for (EntityRenderState.ShadowPiece piece : submit.pieces()) {\n            if (piece == null || piece.shapeBelow() == null\n                    || !Float.isFinite(piece.relativeX())\n                    || !Float.isFinite(piece.relativeY())\n                    || !Float.isFinite(piece.relativeZ())) {\n                return null;\n            }\n            Integer blockX = blockCoordinate(state.x, piece.relativeX());\n            Integer blockY = blockCoordinate(state.y, piece.relativeY());\n            Integer blockZ = blockCoordinate(state.z, piece.relativeZ());\n            if (blockX == null || blockY == null || blockZ == null) {\n                return null;\n            }\n\n            final AABB bounds;\n            try {\n                bounds = piece.shapeBelow().bounds();\n            } catch (RuntimeException invalidShape) {\n                return null;\n            }\n            if (bounds == null\n                    || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY) || !Double.isFinite(bounds.minZ)\n                    || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)\n                    || bounds.maxX < bounds.minX || bounds.maxY < bounds.minY || bounds.maxZ < bounds.minZ) {\n                return null;\n            }\n            pieces.add(new PieceKey(\n                    blockX, blockY, blockZ,\n                    Double.doubleToLongBits(bounds.minX),\n                    Double.doubleToLongBits(bounds.minY),\n                    Double.doubleToLongBits(bounds.minZ),\n                    Double.doubleToLongBits(bounds.maxX),\n                    Double.doubleToLongBits(bounds.maxY),\n                    Double.doubleToLongBits(bounds.maxZ)\n            ));\n        }\n        try {\n            Math.multiplyExact(pieces.size(), 4);\n        } catch (ArithmeticException overflow) {\n            return null;\n        }\n        return new Member(\n                owner.objectId(),\n                owner.generation(),\n                Float.floatToIntBits(submit.radius()),\n                pieces\n        );\n    }\n\n    /**\n     * Reconstructs the integer BlockPos used by EntityRenderer.extractShadow. That source stores\n     * relativeX/Y/Z as float(blockCoordinate - interpolatedEntityCoordinate), so adding the\n     * interpolated state coordinate back must land within float round-off of an integer.\n     */\n    static @Nullable Integer blockCoordinate(final double entityCoordinate, final float relativeCoordinate) {\n        if (!Double.isFinite(entityCoordinate) || !Float.isFinite(relativeCoordinate)) {\n            return null;\n        }\n        double absolute = entityCoordinate + (double) relativeCoordinate;\n        double rounded = Math.rint(absolute);\n        if (!Double.isFinite(absolute) || Math.abs(absolute - rounded) > BLOCK_COORDINATE_EPSILON\n                || rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {\n            return null;\n        }\n        return (int) rounded;\n    }\n\n    static MetalEntityMotionCapture.@Nullable Sample beginShadowBatch(final List<Member> members) {\n        if (!frameOpen || batchOpened || members == null || members.isEmpty()) {\n            return null;\n        }\n        for (Member member : members) {\n            if (member == null || member.generation() <= 0L || member.pieces().isEmpty()) {\n                return null;\n            }\n            try {\n                if (member.vertexSpan() <= 0) {\n                    return null;\n                }\n            } catch (ArithmeticException overflow) {\n                return null;\n            }\n        }\n\n        Signature signature = new Signature(members);\n        boolean hasPrevious = signature.equals(previousSignature);\n        long generation = hasPrevious ? previousGeneration : allocateGeneration();\n        if (generation >= 0L) {\n            return null;\n        }\n\n        batchOpened = true;\n        pendingSignature = signature;\n        pendingGeneration = generation;\n        Matrix4f identity = new Matrix4f();\n        return new MetalEntityMotionCapture.Sample(\n                SHADOW_OBJECT_ID,\n                generation,\n                identity,\n                hasPrevious ? identity : null\n        );\n    }\n\n    static void commitSubmittedFrame() {\n        if (!frameOpen) {\n            return;\n        }\n        previousSignature = pendingSignature;\n        previousGeneration = pendingSignature == null ? 0L : pendingGeneration;\n        pendingSignature = null;\n        pendingGeneration = 0L;\n        batchOpened = false;\n        frameOpen = false;\n    }\n\n    static void discardFrame() {\n        pendingSignature = null;\n        pendingGeneration = 0L;\n        batchOpened = false;\n        frameOpen = false;\n    }\n\n    static void reset() {\n        boolean wasOpen = frameOpen;\n        previousSignature = null;\n        previousGeneration = 0L;\n        pendingSignature = null;\n        pendingGeneration = 0L;\n        nextGeneration = -1L;\n        batchOpened = false;\n        frameOpen = wasOpen;\n    }\n\n    private static long allocateGeneration() {\n        long generation = nextGeneration;\n        nextGeneration = generation == Long.MIN_VALUE ? -1L : generation - 1L;\n        return generation;\n    }\n}\n'''
)

# Preserve the actual submitted EntityRenderState while the dispatcher owns the shadow constructor.
# This lets us recover stable BlockPos identities from the relative shadow-piece coordinates without
# guessing from camera-relative matrices.
capture = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java"
replace_once(
    capture,
    '''import net.minecraft.client.renderer.StagedVertexBuffer;\nimport net.minecraft.client.renderer.feature.FlameFeatureRenderer;\n''',
    '''import net.minecraft.client.renderer.StagedVertexBuffer;\nimport net.minecraft.client.renderer.entity.state.EntityRenderState;\nimport net.minecraft.client.renderer.feature.FlameFeatureRenderer;\nimport net.minecraft.client.renderer.feature.ShadowFeatureRenderer;\n'''
)
replace_once(
    capture,
    '''    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();\n    private static final ThreadLocal<Sample> MODEL_BUILD = new ThreadLocal<>();\n''',
    '''    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();\n    private static final ThreadLocal<Object> ENTITY_SUBMISSION_STATE = new ThreadLocal<>();\n    private static final ThreadLocal<Sample> MODEL_BUILD = new ThreadLocal<>();\n'''
)
replace_once(
    capture,
    '''    private static final Map<Object, Sample> STATES = new IdentityHashMap<>();\n    private static final Map<Object, Sample> SUBMITS = new IdentityHashMap<>();\n''',
    '''    private static final Map<Object, Sample> STATES = new IdentityHashMap<>();\n    private static final Map<Object, Sample> SUBMITS = new IdentityHashMap<>();\n    private static final Map<Object, EntityRenderState> SHADOW_SUBMIT_STATES = new IdentityHashMap<>();\n'''
)
replace_once(
    capture,
    '''        ENTITY_SUBMISSION.remove();\n        MODEL_BUILD.remove();\n        STATES.clear();\n        SUBMITS.clear();\n''',
    '''        ENTITY_SUBMISSION.remove();\n        ENTITY_SUBMISSION_STATE.remove();\n        MODEL_BUILD.remove();\n        STATES.clear();\n        SUBMITS.clear();\n        SHADOW_SUBMIT_STATES.clear();\n'''
)
replace_once(
    capture,
    '''        if (sample == null) {\n            ENTITY_SUBMISSION.remove();\n        } else {\n            ENTITY_SUBMISSION.set(sample);\n            entitySubmissionsMatched++;\n        }\n''',
    '''        if (sample == null) {\n            ENTITY_SUBMISSION.remove();\n            ENTITY_SUBMISSION_STATE.remove();\n        } else {\n            ENTITY_SUBMISSION.set(sample);\n            ENTITY_SUBMISSION_STATE.set(state);\n            entitySubmissionsMatched++;\n        }\n'''
)
replace_once(
    capture,
    '''    public static void endEntitySubmission() {\n        if (enabled) {\n            ENTITY_SUBMISSION.remove();\n        }\n    }\n''',
    '''    public static void endEntitySubmission() {\n        if (enabled) {\n            ENTITY_SUBMISSION.remove();\n            ENTITY_SUBMISSION_STATE.remove();\n        }\n    }\n'''
)
replace_once(
    capture,
    '''    public static void captureModelSubmit(final Object submit) {\n        if (!enabled) {\n            return;\n        }\n        Sample sample = ENTITY_SUBMISSION.get();\n        if (submit != null && sample != null) {\n            SUBMITS.put(submit, sample);\n            modelSubmitsCaptured++;\n        }\n    }\n\n    /**\n     * Activates one synthetic exact owner for Minecraft 26.2's complete Flame shared builder.\n''',
    '''    public static void captureModelSubmit(final Object submit) {\n        if (!enabled) {\n            return;\n        }\n        Sample sample = ENTITY_SUBMISSION.get();\n        if (submit != null && sample != null) {\n            SUBMITS.put(submit, sample);\n            modelSubmitsCaptured++;\n        }\n    }\n\n    /** Captures one entity-shadow submit while its real dispatcher owner is still active. */\n    public static void captureShadowSubmit(final ShadowFeatureRenderer.Submit submit) {\n        if (!enabled || submit == null) {\n            return;\n        }\n        Sample sample = ENTITY_SUBMISSION.get();\n        Object state = ENTITY_SUBMISSION_STATE.get();\n        if (sample != null && state instanceof EntityRenderState entityState) {\n            SUBMITS.put(submit, sample);\n            SHADOW_SUBMIT_STATES.put(submit, entityState);\n            modelSubmitsCaptured++;\n        }\n    }\n\n    /**\n     * Activates one synthetic exact owner for Minecraft 26.2's complete shared shadow builder.\n     * Every submit is paired with the real entity lifetime and the exact ordered terrain pieces\n     * that generate its four-vertex quads.\n     */\n    public static void beginSharedShadowBuild(final List<ShadowFeatureRenderer.Submit> submits) {\n        if (!enabled) {\n            return;\n        }\n        MODEL_BUILD.remove();\n        if (submits == null || submits.isEmpty()) {\n            return;\n        }\n\n        java.util.ArrayList<MetalShadowBatchMotion.Member> members = new java.util.ArrayList<>(submits.size());\n        for (ShadowFeatureRenderer.Submit submit : submits) {\n            Sample owner = submit == null ? null : SUBMITS.remove(submit);\n            EntityRenderState state = submit == null ? null : SHADOW_SUBMIT_STATES.remove(submit);\n            MetalShadowBatchMotion.Member member =\n                    owner == null || state == null || submit == null\n                            ? null\n                            : MetalShadowBatchMotion.member(owner, state, submit);\n            if (member == null) {\n                MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n                return;\n            }\n            members.add(member);\n        }\n\n        Sample batch = MetalShadowBatchMotion.beginShadowBatch(members);\n        if (batch == null) {\n            MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n            return;\n        }\n        MODEL_BUILD.set(batch);\n        MetalExactMotionCoverage.require(batch);\n        modelBuildsMatched++;\n    }\n\n    /**\n     * Activates one synthetic exact owner for Minecraft 26.2's complete Flame shared builder.\n'''
)

# Constructor time still has the real dispatcher owner. Do not reject the frame here: capture that
# ownership and let the shared builder prove exact continuity later.
shadow_submit = "src/main/java/com/metallum/mixin/render/ShadowFeatureSubmitMetalFxMixin.java"
replace_once(
    shadow_submit,
    '''import com.metallum.client.metal.render.MetalEntityMotionCapture;\nimport com.metallum.client.metal.render.MetalFxManager;\n''',
    '''import com.metallum.client.metal.render.MetalEntityMotionCapture;\n'''
)
replace_once(
    shadow_submit,
    '''        MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("shadow-shared-staged-draw");\n''',
    '''        MetalEntityMotionCapture.captureShadowSubmit((ShadowFeatureRenderer.Submit) (Object) this);\n'''
)

create_once(
    "src/main/java/com/metallum/mixin/render/ShadowFeatureRendererMetalFxMixin.java",
    '''package com.metallum.mixin.render;\n\nimport com.metallum.client.metal.render.MetalEntityMotionCapture;\nimport net.minecraft.client.renderer.feature.FeatureFrameContext;\nimport net.minecraft.client.renderer.feature.ShadowFeatureRenderer;\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.injection.At;\nimport org.spongepowered.asm.mixin.injection.Inject;\nimport org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n\nimport java.util.List;\n\n/** Owns Minecraft 26.2's single shared shadow builder as one exact staged-motion batch. */\n@Mixin(ShadowFeatureRenderer.class)\npublic abstract class ShadowFeatureRendererMetalFxMixin {\n    @Inject(method = "buildGroup", at = @At("HEAD"))\n    private void metallum$beginSharedShadowMotion(\n            final FeatureFrameContext context,\n            final List<ShadowFeatureRenderer.Submit> submits,\n            final CallbackInfo ci\n    ) {\n        MetalEntityMotionCapture.beginSharedShadowBuild(submits);\n    }\n\n    @Inject(method = "buildGroup", at = @At("RETURN"))\n    private void metallum$endSharedShadowMotion(\n            final FeatureFrameContext context,\n            final List<ShadowFeatureRenderer.Submit> submits,\n            final CallbackInfo ci\n    ) {\n        MetalEntityMotionCapture.endModelBuild();\n    }\n}\n'''
)

mixins = "src/main/resources/metallum.mixins.json"
replace_once(
    mixins,
    '''    "render.ShadowFeatureSubmitMetalFxMixin",\n    "render.LeashFeatureSubmitMetalFxMixin",\n''',
    '''    "render.ShadowFeatureSubmitMetalFxMixin",\n    "render.ShadowFeatureRendererMetalFxMixin",\n    "render.LeashFeatureSubmitMetalFxMixin",\n'''
)

# entity_shadow has ENTITY binding semantics but is translucent. Give it an exact-only family so it
# can never fall back to a rigid root replay. The fragment shader preserves the shadow texture's
# transparent footprint before writing the motion/validity MRTs.
pipeline = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionPipeline.java"
replace_once(
    pipeline,
    '''        ENTITY("core/entity_previous_motion", "core/entity_motion", "entity_previous_motion/"),\n        LEASH("core/leash_previous_motion", "core/leash_previous_motion", "leash_previous_motion/"),\n''',
    '''        ENTITY("core/entity_previous_motion", "core/entity_motion", "entity_previous_motion/"),\n        SHADOW("core/entity_previous_motion", "core/shadow_previous_motion", "shadow_previous_motion/"),\n        LEASH("core/leash_previous_motion", "core/leash_previous_motion", "leash_previous_motion/"),\n'''
)
replace_once(
    pipeline,
    '''        if (shader.equals("core/rendertype_leash")\n                && DefaultVertexFormat.POSITION_COLOR_LIGHTMAP.equals(format)) {\n''',
    '''        if (shader.equals("core/rendertype_entity_shadow")\n                && DefaultVertexFormat.ENTITY.equals(format)) {\n            return PreviousFamily.SHADOW;\n        }\n        if (shader.equals("core/rendertype_leash")\n                && DefaultVertexFormat.POSITION_COLOR_LIGHTMAP.equals(format)) {\n'''
)

create_once(
    "src/main/resources/assets/metallum/shaders/core/shadow_previous_motion.fsh",
    '''#version 330\n\nuniform sampler2D Sampler0;\n\nnoperspective in vec2 metallumObjectMotion;\nflat in float metallumObjectValidity;\nin vec2 metallumTexCoord;\nflat in float metallumVertexColorGuard;\n\nlayout(location = 0) out vec2 metallumMotionTarget;\nlayout(location = 1) out float metallumValidityTarget;\n\nvoid main() {\n    // Minecraft's entity shadow is a translucent textured quad. Pixels with exactly zero sampled\n    // coverage do not contribute to the source color, so they must not overwrite scene motion.\n    // Semitransparent texels do contribute and receive the same exact geometric motion.\n    float coverage = texture(Sampler0, metallumTexCoord).a * metallumVertexColorGuard;\n    if (!(coverage > 0.0)) {\n        discard;\n    }\n    metallumMotionTarget = metallumObjectMotion;\n    metallumValidityTarget = metallumObjectValidity;\n}\n'''
)

# Transaction and ABI tests: preserve identity only when ordered owner/piece topology is unchanged.
create_once(
    "src/test/java/com/metallum/client/metal/render/MetalShadowBatchMotionTest.java",
    '''package com.metallum.client.metal.render;\n\nimport org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.Test;\n\nimport java.util.List;\n\nimport static org.junit.jupiter.api.Assertions.*;\n\nfinal class MetalShadowBatchMotionTest {\n    private static final long ZERO = Double.doubleToLongBits(0.0);\n    private static final long ONE = Double.doubleToLongBits(1.0);\n    private static final MetalShadowBatchMotion.PieceKey A =\n            new MetalShadowBatchMotion.PieceKey(10, 63, 20, ZERO, ZERO, ZERO, ONE, ONE, ONE);\n    private static final MetalShadowBatchMotion.PieceKey B =\n            new MetalShadowBatchMotion.PieceKey(11, 63, 20, ZERO, ZERO, ZERO, ONE, ONE, ONE);\n    private static final MetalShadowBatchMotion.PieceKey B_HALF =\n            new MetalShadowBatchMotion.PieceKey(11, 63, 20, ZERO, ZERO, ZERO, ONE, Double.doubleToLongBits(0.5), ONE);\n    private static final MetalShadowBatchMotion.Member OWNER_AB =\n            new MetalShadowBatchMotion.Member(7L, 3L, Float.floatToIntBits(0.5F), List.of(A, B));\n    private static final MetalShadowBatchMotion.Member OWNER_BA =\n            new MetalShadowBatchMotion.Member(7L, 3L, Float.floatToIntBits(0.5F), List.of(B, A));\n\n    @AfterEach\n    void reset() {\n        MetalShadowBatchMotion.reset();\n    }\n\n    @Test\n    void stableWorldPieceMembershipReusesExactHistory() {\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertNotNull(first);\n        assertFalse(first.hasPrevious());\n        assertTrue(first.generation() < 0L);\n        MetalShadowBatchMotion.commitSubmittedFrame();\n\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample second = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertNotNull(second);\n        assertEquals(first.generation(), second.generation());\n        assertTrue(second.hasPrevious());\n    }\n\n    @Test\n    void sameVertexCountButDifferentPieceOrderOrBoundsBreaksContinuity() {\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertNotNull(first);\n        MetalShadowBatchMotion.commitSubmittedFrame();\n\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample reordered = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_BA));\n        assertNotNull(reordered);\n        assertNotEquals(first.generation(), reordered.generation());\n        assertFalse(reordered.hasPrevious());\n\n        MetalShadowBatchMotion.reset();\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample baseline = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertNotNull(baseline);\n        MetalShadowBatchMotion.commitSubmittedFrame();\n        MetalShadowBatchMotion.Member changedBounds = new MetalShadowBatchMotion.Member(\n                7L, 3L, Float.floatToIntBits(0.5F), List.of(A, B_HALF));\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample changed = MetalShadowBatchMotion.beginShadowBatch(List.of(changedBounds));\n        assertNotNull(changed);\n        assertNotEquals(baseline.generation(), changed.generation());\n        assertFalse(changed.hasPrevious());\n    }\n\n    @Test\n    void ownerLifetimeAndRadiusArePartOfIdentity() {\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertNotNull(first);\n        MetalShadowBatchMotion.commitSubmittedFrame();\n\n        MetalShadowBatchMotion.Member replacedOwner = new MetalShadowBatchMotion.Member(\n                7L, 4L, Float.floatToIntBits(0.5F), List.of(A, B));\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample replaced = MetalShadowBatchMotion.beginShadowBatch(List.of(replacedOwner));\n        assertNotNull(replaced);\n        assertFalse(replaced.hasPrevious());\n\n        MetalShadowBatchMotion.reset();\n        MetalShadowBatchMotion.beginFrame();\n        assertNotNull(MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB)));\n        MetalShadowBatchMotion.commitSubmittedFrame();\n        MetalShadowBatchMotion.Member radiusChanged = new MetalShadowBatchMotion.Member(\n                7L, 3L, Float.floatToIntBits(0.75F), List.of(A, B));\n        MetalShadowBatchMotion.beginFrame();\n        assertFalse(MetalShadowBatchMotion.beginShadowBatch(List.of(radiusChanged)).hasPrevious());\n    }\n\n    @Test\n    void discardedFrameDoesNotAdvanceAndSubmittedGapBreaksContinuity() {\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample first = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertNotNull(first);\n        MetalShadowBatchMotion.commitSubmittedFrame();\n\n        MetalShadowBatchMotion.beginFrame();\n        assertNotNull(MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_BA)));\n        MetalShadowBatchMotion.discardFrame();\n\n        MetalShadowBatchMotion.beginFrame();\n        MetalEntityMotionCapture.Sample recovered = MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB));\n        assertTrue(recovered.hasPrevious());\n        assertEquals(first.generation(), recovered.generation());\n        MetalShadowBatchMotion.commitSubmittedFrame();\n\n        MetalShadowBatchMotion.beginFrame();\n        MetalShadowBatchMotion.commitSubmittedFrame();\n        MetalShadowBatchMotion.beginFrame();\n        assertFalse(MetalShadowBatchMotion.beginShadowBatch(List.of(OWNER_AB)).hasPrevious());\n    }\n\n    @Test\n    void integerBlockCoordinateRecoveryAllowsOnlyFloatRoundoff() {\n        assertEquals(10, MetalShadowBatchMotion.blockCoordinate(10.25, -0.25F));\n        assertEquals(-30, MetalShadowBatchMotion.blockCoordinate(-29.875, -0.125F));\n        assertNull(MetalShadowBatchMotion.blockCoordinate(10.25, -0.20F));\n        assertNull(MetalShadowBatchMotion.blockCoordinate(Double.NaN, 0.0F));\n        assertNull(MetalShadowBatchMotion.blockCoordinate(0.0, Float.POSITIVE_INFINITY));\n    }\n}\n'''
)

pipeline_test = "src/test/java/com/metallum/client/metal/render/MetalEntityAuxiliaryMotionPipelineTest.java"
replace_once(
    pipeline_test,
    '''    @Test\n    void waterMaskUsesPositionOnlyExactPreviousAbi() {\n''',
    '''    @Test\n    void entityShadowUsesExactPreviousPositionsDespiteTranslucentSource() {\n        RenderPipeline shadow = pipeline("core/rendertype_entity_shadow", DefaultVertexFormat.ENTITY);\n        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(shadow));\n        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(shadow));\n        assertFalse(MetalEntityMotionPipeline.supports(shadow));\n        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(shadow);\n        assertEquals("core/entity_previous_motion", exact.getVertexShader().getPath());\n        assertEquals("core/shadow_previous_motion", exact.getFragmentShader().getPath());\n    }\n\n    @Test\n    void waterMaskUsesPositionOnlyExactPreviousAbi() {\n'''
)
replace_once(
    pipeline_test,
    '''        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n                pipeline("core/rendertype_water_mask", DefaultVertexFormat.ENTITY)));\n''',
    '''        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n                pipeline("core/rendertype_water_mask", DefaultVertexFormat.ENTITY)));\n        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n                pipeline("core/rendertype_entity_shadow", DefaultVertexFormat.BLOCK)));\n'''
)
