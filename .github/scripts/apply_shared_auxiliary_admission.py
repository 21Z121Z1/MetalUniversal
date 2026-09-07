from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


eligibility = "src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java"
replace_once(
    eligibility,
    '''    static final int MISSING_HISTORY = 1 << 6;\n''',
    '''    static final int MISSING_HISTORY = 1 << 6;\n    static final int SHARED_AUXILIARY = 1 << 7;\n'''
)

manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    '''    /** Moving blocks without an entity-owned staged replay (notably pistons) have no previous pose sidecar. */\n    public static void observeUnownedMovingBlockMotion() {\n        MetalFxManager manager = active;\n        if (manager != null) manager.motionEligibility.reject(MetalFxMotionEligibility.MOVING_BLOCK);\n    }\n\n''',
    '''    /** Moving blocks without an entity-owned staged replay (notably pistons) have no previous pose sidecar. */\n    public static void observeUnownedMovingBlockMotion() {\n        MetalFxManager manager = active;\n        if (manager != null) manager.motionEligibility.reject(MetalFxMotionEligibility.MOVING_BLOCK);\n    }\n\n    /**\n     * Shared feature batches (currently shadow/flame) allocate one staged builder before iterating\n     * entities. Until the entire batch has a transactional exact previous-position identity, any\n     * submitted batch is globally incomplete for frame interpolation, even when the parent entity\n     * itself is a rigid family. MetalFX Temporal remains unaffected.\n     */\n    public static void observeUnresolvedSharedAuxiliaryMotion() {\n        MetalFxManager manager = active;\n        if (manager != null) manager.motionEligibility.reject(MetalFxMotionEligibility.SHARED_AUXILIARY);\n    }\n\n'''
)

for path in (
    "src/main/java/com/metallum/mixin/render/FlameFeatureSubmitMetalFxMixin.java",
    "src/main/java/com/metallum/mixin/render/ShadowFeatureSubmitMetalFxMixin.java",
):
    replace_once(
        path,
        '''import com.metallum.client.metal.render.MetalEntityMotionCapture;\n''',
        '''import com.metallum.client.metal.render.MetalEntityMotionCapture;\nimport com.metallum.client.metal.render.MetalFxManager;\n'''
    )
    replace_once(
        path,
        '''        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("''',
        '''        MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("'''
    )

test = "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java"
replace_once(
    test,
    '''        eligibility.beginFrame();\n        assertTrue(eligibility.eligible());\n        assertEquals(0, eligibility.rejectedReasons());\n    }\n}\n''',
    '''        eligibility.beginFrame();\n        assertTrue(eligibility.eligible());\n        assertEquals(0, eligibility.rejectedReasons());\n    }\n\n    @Test\n    void unresolvedSharedAuxiliaryIsAWholeFrameRejectionReason() {\n        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();\n        eligibility.beginFrame();\n        eligibility.reject(MetalFxMotionEligibility.SHARED_AUXILIARY);\n        assertFalse(eligibility.eligible());\n        assertEquals(MetalFxMotionEligibility.SHARED_AUXILIARY, eligibility.rejectedReasons());\n    }\n}\n'''
)

print("shared auxiliary admission patch prepared")
