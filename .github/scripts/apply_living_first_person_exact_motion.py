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


# LivingEntityRenderState is not a rigid root-motion approximation. Minecraft 26.2 has already
# executed setupAnim before ModelFeatureRenderer stages the actual vertices, and the branch's
# previous-position history captures exactly those staged Position attributes. Admit it only as an
# exact-required candidate; the per-object manifest still fails closed on unsupported layers.
eligibility = "src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java"
replace_once(
    eligibility,
    '''     * <p>Living entities still remain fail-closed. Boat paddles also deform in setupAnim, but\n     * Boat model submits are now admitted only as exact staged-previous-position objects; their\n     * water-mask depth patch has a separately verified POSITION-only exact ABI.</p>\n''',
    '''     * <p>Living entities and Boat paddles deform through CPU-side model animation. They are\n     * admitted only as exact staged-previous-position candidates: every submitted draw must match\n     * the previous successfully submitted source frame before interpolation is allowed. Boat's\n     * optional water-mask depth patch has a separately verified POSITION-only exact ABI.</p>\n'''
)
replace_once(
    eligibility,
    '''        if (state instanceof LivingEntityRenderState) {\n            return NON_RIGID_ENTITY;\n        }\n        if (state instanceof BoatRenderState) {\n''',
    '''        if (state instanceof LivingEntityRenderState) {\n            // Candidate only. EntityRenderDispatcherMetalFxMixin marks the whole object\n            // exact-required, so setupAnim/model-layer deformation can never fall back to a\n            // rigid root approximation.\n            return 0;\n        }\n        if (state instanceof BoatRenderState) {\n'''
)

# Mark living entities exact-required at the point of actual submission. This is deliberately next
# to beginEntitySubmission so culled states never create an exact-motion obligation.
dispatcher = "src/main/java/com/metallum/mixin/render/EntityRenderDispatcherMetalFxMixin.java"
replace_once(
    dispatcher,
    '''import net.minecraft.client.renderer.entity.state.EntityRenderState;\n''',
    '''import net.minecraft.client.renderer.entity.state.EntityRenderState;\nimport net.minecraft.client.renderer.entity.state.LivingEntityRenderState;\n'''
)
replace_once(
    dispatcher,
    '''        MetalFxManager.observeFrameInterpolationEntity(state);\n        MetalEntityMotionCapture.beginEntitySubmission(state);\n''',
    '''        MetalFxManager.observeFrameInterpolationEntity(state);\n        if (state instanceof LivingEntityRenderState) {\n            MetalEntityMotionCapture.requireExactState(state);\n        }\n        MetalEntityMotionCapture.beginEntitySubmission(state);\n'''
)

# Synthetic first-person objects need the same source-frame transactional boundary as entity/root
# motion and previous-vertex history. A successful source frame without a given hand breaks that
# hand's continuity; a discarded frame never advances it.
store = "src/main/java/com/metallum/client/metal/render/MetalMotionStateStore.java"
replace_once(
    store,
    '''        MetalPreviousVertexHistory.beginFrame();\n        MetalSharedBatchMotion.beginFrame();\n''',
    '''        MetalPreviousVertexHistory.beginFrame();\n        MetalSharedBatchMotion.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n'''
)
replace_once(
    store,
    '''        MetalPreviousVertexHistory.commitSubmittedFrame();\n        MetalSharedBatchMotion.commitSubmittedFrame();\n        frameOpen = false;\n''',
    '''        MetalPreviousVertexHistory.commitSubmittedFrame();\n        MetalSharedBatchMotion.commitSubmittedFrame();\n        MetalSyntheticExactMotion.commitSubmittedFrame();\n        frameOpen = false;\n'''
)
replace_once(
    store,
    '''        MetalPreviousVertexHistory.discardFrame();\n        MetalSharedBatchMotion.discardFrame();\n        frameOpen = false;\n''',
    '''        MetalPreviousVertexHistory.discardFrame();\n        MetalSharedBatchMotion.discardFrame();\n        MetalSyntheticExactMotion.discardFrame();\n        frameOpen = false;\n'''
)
replace_once(
    store,
    '''        MetalPreviousVertexHistory.reset();\n        MetalSharedBatchMotion.reset();\n        frameOpen = wasOpen;\n''',
    '''        MetalPreviousVertexHistory.reset();\n        MetalSharedBatchMotion.reset();\n        MetalSyntheticExactMotion.reset();\n        frameOpen = wasOpen;\n'''
)

create_once(
    "src/main/java/com/metallum/client/metal/render/MetalSyntheticExactMotion.java",
    '''package com.metallum.client.metal.render;\n\nimport net.minecraft.world.InteractionHand;\nimport org.joml.Matrix4f;\nimport org.jspecify.annotations.Nullable;\n\n/**\n * Source-frame-transactional identities for exact staged geometry which has no world entity owner.\n *\n * <p>First-person main/off-hand rendering is generated after its current swing/equip/use PoseStack\n * transforms are known, but outside EntityRenderDispatcher. The staged vertex stream is therefore\n * the authoritative motion source. Stable synthetic keys let MetalPreviousVertexHistory compare\n * that stream to the previous successfully submitted source frame without pretending that the\n * current hand transform is a rigid object-motion approximation.</p>\n */\npublic final class MetalSyntheticExactMotion {\n    private static final long MAIN_HAND_OBJECT_ID = 0x4D46584D41494EL; // ASCII \"MFXMAIN\".\n    private static final long OFF_HAND_OBJECT_ID = 0x4D46584F464648L;  // ASCII \"MFXOFFH\".\n    // Real object lifetime generations are positive; fixed negative values are an explicit domain\n    // separator for synthetic staged-only objects.\n    private static final long MAIN_HAND_GENERATION = -0x4D41494EL;\n    private static final long OFF_HAND_GENERATION = -0x4F464648L;\n    private static final Object MAIN_HAND_STATE = new Object();\n    private static final Object OFF_HAND_STATE = new Object();\n    private static final ThreadLocal<Boolean> FIRST_PERSON_ACTIVE =\n            ThreadLocal.withInitial(() -> Boolean.FALSE);\n\n    private static boolean frameOpen;\n    private static boolean previousMainHand;\n    private static boolean previousOffHand;\n    private static boolean pendingMainHand;\n    private static boolean pendingOffHand;\n\n    private MetalSyntheticExactMotion() {\n    }\n\n    static void beginFrame() {\n        pendingMainHand = false;\n        pendingOffHand = false;\n        FIRST_PERSON_ACTIVE.remove();\n        frameOpen = true;\n    }\n\n    /**\n     * Begins one non-scoping first-person submission as an exact staged object.\n     *\n     * @return the sample used for the hand, or {@code null} when no safe transaction can be opened\n     */\n    public static @Nullable MetalEntityMotionCapture.Sample beginFirstPerson(final InteractionHand hand) {\n        if (!frameOpen || hand == null || Boolean.TRUE.equals(FIRST_PERSON_ACTIVE.get())) {\n            // Nested/unframed ownership would make capture attribution ambiguous. Keep the legacy\n            // whole-frame rejection for this impossible/changed-source-contract case.\n            MetalFxManager.observeFirstPersonMotion();\n            return null;\n        }\n\n        boolean main = hand == InteractionHand.MAIN_HAND;\n        Object state = main ? MAIN_HAND_STATE : OFF_HAND_STATE;\n        long objectId = main ? MAIN_HAND_OBJECT_ID : OFF_HAND_OBJECT_ID;\n        long generation = main ? MAIN_HAND_GENERATION : OFF_HAND_GENERATION;\n        boolean hasPrevious = main ? previousMainHand : previousOffHand;\n        if (main) {\n            pendingMainHand = true;\n        } else {\n            pendingOffHand = true;\n        }\n\n        Matrix4f identity = new Matrix4f();\n        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(\n                objectId,\n                generation,\n                identity,\n                hasPrevious ? identity : null\n        );\n        MetalEntityMotionCapture.attachState(state, sample);\n        MetalEntityMotionCapture.requireExactState(state);\n        MetalEntityMotionCapture.beginEntitySubmission(state);\n        FIRST_PERSON_ACTIVE.set(Boolean.TRUE);\n        return sample;\n    }\n\n    public static void endFirstPerson() {\n        if (!Boolean.TRUE.equals(FIRST_PERSON_ACTIVE.get())) {\n            return;\n        }\n        MetalEntityMotionCapture.endEntitySubmission();\n        FIRST_PERSON_ACTIVE.remove();\n    }\n\n    static void commitSubmittedFrame() {\n        if (!frameOpen) {\n            return;\n        }\n        previousMainHand = pendingMainHand;\n        previousOffHand = pendingOffHand;\n        pendingMainHand = false;\n        pendingOffHand = false;\n        FIRST_PERSON_ACTIVE.remove();\n        frameOpen = false;\n    }\n\n    static void discardFrame() {\n        pendingMainHand = false;\n        pendingOffHand = false;\n        FIRST_PERSON_ACTIVE.remove();\n        frameOpen = false;\n    }\n\n    static void reset() {\n        boolean wasOpen = frameOpen;\n        previousMainHand = false;\n        previousOffHand = false;\n        pendingMainHand = false;\n        pendingOffHand = false;\n        FIRST_PERSON_ACTIVE.remove();\n        frameOpen = wasOpen;\n    }\n}\n'''
)

# Replace the unconditional first-person rejection with a synthetic exact owner opened at the first
# pushPose after Minecraft's scoping early-return. RETURN injection closes it for every normal path.
hand_mixin = "src/main/java/com/metallum/mixin/render/ItemInHandRendererMetalFxMixin.java"
replace_once(
    hand_mixin,
    '''import com.metallum.client.metal.render.MetalFxManager;\n''',
    '''import com.metallum.client.metal.render.MetalSyntheticExactMotion;\n'''
)
replace_once(
    hand_mixin,
    ''' * Frame-interpolation admission for first-person geometry.\n *\n * Minecraft 26.2 computes swing, bob, equip/use transforms inside submitArmWithItem from current\n * interpolated player/item state. Until a previous local pose is carried through the staged\n * geometry path, zero object motion is only an approximation. Hook the first pushPose inside the\n * non-scoping branch so a scoped call that submits nothing does not reject the frame.\n''',
    ''' * Exact staged-motion ownership for first-person geometry.\n *\n * Minecraft 26.2 computes swing, bob, equip/use transforms inside submitArmWithItem from current\n * interpolated player/item state. Hook the first pushPose after the scoping early-return and bind\n * every resulting staged model/item draw to a transactional synthetic hand owner. Unsupported\n * pipelines or changed manifests still fail closed through MetalExactMotionCoverage.\n'''
)
replace_once(
    hand_mixin,
    '''            at = @At(\n                    value = "INVOKE",\n                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V"\n            )\n''',
    '''            at = @At(\n                    value = "INVOKE",\n                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",\n                    ordinal = 0\n            )\n'''
)
replace_once(
    hand_mixin,
    '''        MetalFxManager.observeFirstPersonMotion();\n    }\n}\n''',
    '''        MetalSyntheticExactMotion.beginFirstPerson(hand);\n    }\n\n    @Inject(method = "submitArmWithItem", at = @At("RETURN"))\n    private void metallum$endFirstPersonGeometry(\n            final AbstractClientPlayer player,\n            final float frameInterp,\n            final float xRot,\n            final InteractionHand hand,\n            final float attack,\n            final ItemStack itemStack,\n            final float inverseArmHeight,\n            final PoseStack poseStack,\n            final SubmitNodeCollector submitNodeCollector,\n            final int lightCoords,\n            final CallbackInfo ci\n    ) {\n        MetalSyntheticExactMotion.endFirstPerson();\n    }\n}\n'''
)

# Update admission tests and add a transactional synthetic-history contract.
eligibility_test = "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java"
replace_once(
    eligibility_test,
    '''    @Test\n    void nonRigidAndUnknownFamiliesFailClosed() {\n        assertEquals(\n                MetalFxMotionEligibility.NON_RIGID_ENTITY,\n                MetalFxMotionEligibility.incompleteEntityReason(new LivingEntityRenderState())\n        );\n        assertEquals(\n                MetalFxMotionEligibility.UNKNOWN_ENTITY,\n                MetalFxMotionEligibility.incompleteEntityReason(new EntityRenderState())\n        );\n    }\n''',
    '''    @Test\n    void livingEntityIsAnExactStagedGeometryCandidate() {\n        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new LivingEntityRenderState()));\n    }\n\n    @Test\n    void unknownEntityFamiliesStillFailClosed() {\n        assertEquals(\n                MetalFxMotionEligibility.UNKNOWN_ENTITY,\n                MetalFxMotionEligibility.incompleteEntityReason(new EntityRenderState())\n        );\n    }\n'''
)

create_once(
    "src/test/java/com/metallum/client/metal/render/MetalSyntheticExactMotionTest.java",
    '''package com.metallum.client.metal.render;\n\nimport net.minecraft.world.InteractionHand;\nimport org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.Test;\n\nimport static org.junit.jupiter.api.Assertions.assertFalse;\nimport static org.junit.jupiter.api.Assertions.assertNotNull;\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nfinal class MetalSyntheticExactMotionTest {\n    @AfterEach\n    void reset() {\n        MetalSyntheticExactMotion.reset();\n        MetalEntityMotionCapture.beginFrame();\n    }\n\n    @Test\n    void firstPersonHistoryAdvancesOnlyAfterSubmittedSourceFrame() {\n        MetalEntityMotionCapture.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n        MetalEntityMotionCapture.Sample first =\n                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND);\n        assertNotNull(first);\n        assertFalse(first.hasPrevious());\n        MetalSyntheticExactMotion.endFirstPerson();\n        MetalSyntheticExactMotion.commitSubmittedFrame();\n\n        MetalEntityMotionCapture.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n        MetalEntityMotionCapture.Sample second =\n                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND);\n        assertNotNull(second);\n        assertTrue(second.hasPrevious());\n        MetalSyntheticExactMotion.endFirstPerson();\n        MetalSyntheticExactMotion.discardFrame();\n\n        MetalEntityMotionCapture.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n        MetalEntityMotionCapture.Sample afterDiscard =\n                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND);\n        assertNotNull(afterDiscard);\n        assertTrue(afterDiscard.hasPrevious());\n        MetalSyntheticExactMotion.endFirstPerson();\n    }\n\n    @Test\n    void submittedFrameWithoutHandBreaksContinuity() {\n        MetalEntityMotionCapture.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n        assertNotNull(MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND));\n        MetalSyntheticExactMotion.endFirstPerson();\n        MetalSyntheticExactMotion.commitSubmittedFrame();\n\n        MetalEntityMotionCapture.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n        MetalSyntheticExactMotion.commitSubmittedFrame();\n\n        MetalEntityMotionCapture.beginFrame();\n        MetalSyntheticExactMotion.beginFrame();\n        MetalEntityMotionCapture.Sample returned =\n                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND);\n        assertNotNull(returned);\n        assertFalse(returned.hasPrevious());\n        MetalSyntheticExactMotion.endFirstPerson();\n    }\n}\n'''
)
