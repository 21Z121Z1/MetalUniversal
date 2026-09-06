from pathlib import Path
import json


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}")
    p.write_text(text.replace(old, new, 1))


manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    "    private final MetalMotionStateStore motionStateStore = new MetalMotionStateStore();\n",
    "    private final MetalMotionStateStore motionStateStore = new MetalMotionStateStore();\n"
    "    private final MetalFxMotionEligibility motionEligibility = new MetalFxMotionEligibility();\n",
)
replace_once(
    manager,
    "        MetalFxConfig cfg = this.config;\n        if (this.effectiveMode == MetalFxConfig.Mode.OFF || runtimeDisabled.get()) {\n",
    "        MetalFxConfig cfg = this.config;\n"
    "        this.motionEligibility.beginFrame();\n"
    "        if (this.effectiveMode == MetalFxConfig.Mode.OFF || runtimeDisabled.get()) {\n",
)
replace_once(
    manager,
    "    private void captureEntityMotionInternal(final Entity entity, final EntityRenderState state) {\n",
    "    public static void observeEntitySubmission(final EntityRenderState state) {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager == null || state == null) {\n"
    "            return;\n"
    "        }\n"
    "        manager.motionEligibility.reject(MetalFxMotionEligibility.incompleteEntityReason(state));\n"
    "    }\n\n"
    "    public static void observeFirstPersonMotion() {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null) {\n"
    "            manager.motionEligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);\n"
    "        }\n"
    "    }\n\n"
    "    public static void observeParticleMotion() {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null) {\n"
    "            manager.motionEligibility.reject(MetalFxMotionEligibility.PARTICLE);\n"
    "        }\n"
    "    }\n\n"
    "    public static void observeMovingBlockMotion() {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null) {\n"
    "            manager.motionEligibility.reject(MetalFxMotionEligibility.MOVING_BLOCK);\n"
    "        }\n"
    "    }\n\n"
    "    private void captureEntityMotionInternal(final Entity entity, final EntityRenderState state) {\n",
)
replace_once(
    manager,
    "            this.frameGenerationRealFrameIndex > this.frameGenerationLastEncodedRealFrameIndex,\n            true,\n            true,\n            this.nativeFrameInterpolationCanRun,\n",
    "            this.frameGenerationRealFrameIndex > this.frameGenerationLastEncodedRealFrameIndex,\n"
    "            true,\n"
    "            this.motionEligibility.eligible(),\n"
    "            this.nativeFrameInterpolationCanRun,\n",
)

Path("src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java").write_text('''package com.metallum.client.metal.render;\n\nimport net.minecraft.client.renderer.entity.state.ArrowRenderState;\nimport net.minecraft.client.renderer.entity.state.BoatRenderState;\nimport net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;\nimport net.minecraft.client.renderer.entity.state.EntityRenderState;\nimport net.minecraft.client.renderer.entity.state.ItemEntityRenderState;\nimport net.minecraft.client.renderer.entity.state.LivingEntityRenderState;\nimport net.minecraft.client.renderer.entity.state.MinecartRenderState;\n\n/**\n * Per-real-frame receipt for dynamic geometry that does not yet have an exact\n * previous-to-current motion producer. MetalFX temporal scaling can still use\n * reactive/history rejection for these domains, but MTLFXFrameInterpolator has\n * no equivalent mask input, so frame generation must fail closed whenever one\n * of these domains was actually submitted in the source frame.\n */\nfinal class MetalFxMotionEligibility {\n    static final int NON_RIGID_ENTITY = 1;\n    static final int UNKNOWN_ENTITY = 1 << 1;\n    static final int FIRST_PERSON = 1 << 2;\n    static final int PARTICLE = 1 << 3;\n    static final int MOVING_BLOCK = 1 << 4;\n    static final int DISPLAY_ENTITY = 1 << 5;\n\n    private int rejectedReasons;\n\n    void beginFrame() {\n        this.rejectedReasons = 0;\n    }\n\n    void reject(final int reason) {\n        if (reason != 0) {\n            this.rejectedReasons |= reason;\n        }\n    }\n\n    boolean eligible() {\n        return this.rejectedReasons == 0;\n    }\n\n    int rejectedReasons() {\n        return this.rejectedReasons;\n    }\n\n    static int incompleteEntityReason(final EntityRenderState state) {\n        if (state instanceof LivingEntityRenderState) {\n            // Current staged vertices already contain the current skeletal pose;\n            // a root-object delta cannot reconstruct the previous per-part pose.\n            return NON_RIGID_ENTITY;\n        }\n        if (state instanceof DisplayEntityRenderState) {\n            // Block/item display roots are source-derived, but text displays emit\n            // custom geometry after the display transformation. Until that custom\n            // geometry has the same previous-pose ownership as staged model/item\n            // draws, the display family is not frame-interpolation complete.\n            return DISPLAY_ENTITY;\n        }\n        if (state instanceof ItemEntityRenderState\n            || state instanceof MinecartRenderState\n            || state instanceof BoatRenderState\n            || state instanceof ArrowRenderState) {\n            return 0;\n        }\n        return UNKNOWN_ENTITY;\n    }\n}\n''')

entity_mixin = "src/main/java/com/metallum/mixin/render/EntityRenderDispatcherMetalFxMixin.java"
replace_once(
    entity_mixin,
    "    ) {\n        MetalEntityMotionCapture.beginEntitySubmission(state);\n",
    "    ) {\n"
    "        MetalFxManager.observeEntitySubmission(state);\n"
    "        MetalEntityMotionCapture.beginEntitySubmission(state);\n",
)

moving_mixin = "src/main/java/com/metallum/mixin/render/MovingBlockFeatureRendererMetalFxMixin.java"
replace_once(
    moving_mixin,
    "import com.metallum.client.metal.render.MetalEntityMotionCapture;\n",
    "import com.metallum.client.metal.render.MetalEntityMotionCapture;\n"
    "import com.metallum.client.metal.render.MetalFxManager;\n",
)
replace_once(
    moving_mixin,
    "        MetalEntityMotionCapture.beginMovingBlockBuild(level);\n",
    "        MetalFxManager.observeMovingBlockMotion();\n"
    "        MetalEntityMotionCapture.beginMovingBlockBuild(level);\n",
)

Path("src/main/java/com/metallum/mixin/render/ItemInHandRendererMetalFxMixin.java").write_text('''package com.metallum.mixin.render;\n\nimport com.metallum.client.metal.render.MetalFxManager;\nimport com.mojang.blaze3d.vertex.PoseStack;\nimport net.minecraft.client.player.AbstractClientPlayer;\nimport net.minecraft.client.renderer.ItemInHandRenderer;\nimport net.minecraft.client.renderer.SubmitNodeCollector;\nimport net.minecraft.world.InteractionHand;\nimport net.minecraft.world.item.ItemStack;\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.injection.At;\nimport org.spongepowered.asm.mixin.injection.Inject;\nimport org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n\n/**\n * First-person swing/equip/use transforms are independent of the third-person\n * entity root. Until exact previous hand/item poses are replayed, any submitted\n * first-person arm/item makes the real frame ineligible for frame generation.\n */\n@Mixin(ItemInHandRenderer.class)\npublic abstract class ItemInHandRendererMetalFxMixin {\n    @Inject(method = "submitArmWithItem", at = @At("HEAD"))\n    private void metallum$observeFirstPersonMotion(\n        final AbstractClientPlayer player,\n        final float frameInterp,\n        final float xRot,\n        final InteractionHand hand,\n        final float attack,\n        final ItemStack itemStack,\n        final float inverseArmHeight,\n        final PoseStack poseStack,\n        final SubmitNodeCollector submitNodeCollector,\n        final int lightCoords,\n        final CallbackInfo ci\n    ) {\n        MetalFxManager.observeFirstPersonMotion();\n    }\n}\n''')

Path("src/main/java/com/metallum/mixin/render/ParticleEngineMetalFxMixin.java").write_text('''package com.metallum.mixin.render;\n\nimport com.metallum.client.metal.render.MetalFxManager;\nimport net.minecraft.client.Camera;\nimport net.minecraft.client.particle.ParticleEngine;\nimport net.minecraft.client.renderer.culling.Frustum;\nimport net.minecraft.client.renderer.state.level.ParticlesRenderState;\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.injection.At;\nimport org.spongepowered.asm.mixin.injection.Inject;\nimport org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n\n/**\n * Minecraft 26.2 only calls ParticlesRenderState.add for non-empty rendered\n * particle groups. Particle vertices have their own xOld/yOld/zOld history and\n * are not represented by entity-root motion, so observe that exact emission\n * boundary and fail frame interpolation closed until particle motion is replayed.\n */\n@Mixin(ParticleEngine.class)\npublic abstract class ParticleEngineMetalFxMixin {\n    @Inject(\n        method = "extract",\n        at = @At(\n            value = "INVOKE",\n            target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;add(Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;)V"\n        )\n    )\n    private void metallum$observeParticleMotion(\n        final ParticlesRenderState particlesRenderState,\n        final Frustum frustum,\n        final Camera camera,\n        final float partialTickTime,\n        final CallbackInfo ci\n    ) {\n        MetalFxManager.observeParticleMotion();\n    }\n}\n''')

mixins_path = Path("src/main/resources/metallum.mixins.json")
mixins = json.loads(mixins_path.read_text())
client = mixins.get("client")
if not isinstance(client, list):
    raise SystemExit("metallum.mixins.json: expected client mixin list")
for name in ["render.ItemInHandRendererMetalFxMixin", "render.ParticleEngineMetalFxMixin"]:
    if name not in client:
        client.append(name)
mixins_path.write_text(json.dumps(mixins, indent=2) + "\n")

Path("src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java").write_text('''package com.metallum.client.metal.render;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nimport static org.junit.jupiter.api.Assertions.assertFalse;\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nimport org.junit.jupiter.api.Test;\n\nfinal class MetalFxMotionEligibilityTest {\n    @Test\n    void frameStartsEligibleAndReasonsAccumulateMonotonically() {\n        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();\n        eligibility.beginFrame();\n        assertTrue(eligibility.eligible());\n\n        eligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);\n        eligibility.reject(MetalFxMotionEligibility.PARTICLE);\n        assertFalse(eligibility.eligible());\n        assertEquals(\n            MetalFxMotionEligibility.FIRST_PERSON | MetalFxMotionEligibility.PARTICLE,\n            eligibility.rejectedReasons()\n        );\n    }\n\n    @Test\n    void beginFrameClearsPriorFrameRejections() {\n        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();\n        eligibility.reject(MetalFxMotionEligibility.MOVING_BLOCK);\n        assertFalse(eligibility.eligible());\n\n        eligibility.beginFrame();\n        assertTrue(eligibility.eligible());\n        assertEquals(0, eligibility.rejectedReasons());\n    }\n\n    @Test\n    void zeroReasonDoesNotRejectFrame() {\n        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();\n        eligibility.beginFrame();\n        eligibility.reject(0);\n        assertTrue(eligibility.eligible());\n    }\n}\n''')
