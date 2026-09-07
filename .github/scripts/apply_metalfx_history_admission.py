from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}")
    p.write_text(text.replace(old, new, 1))


capture = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java"
replace_once(
    capture,
    "    public static void beginEntitySubmission(final Object state) {\n",
    "    /**\n"
    "     * True only when this rendered state is backed by an observation from the immediately\n"
    "     * preceding successfully submitted source frame. A state with no sample, or only a current\n"
    "     * sample, is valid input for Temporal's validity/disocclusion path but not for frame\n"
    "     * interpolation, which does not consume those confidence masks.\n"
    "     */\n"
    "    public static boolean hasPreviousState(final Object state) {\n"
    "        Sample sample = enabled && state != null ? STATES.get(state) : null;\n"
    "        return sample != null && sample.hasPrevious();\n"
    "    }\n\n"
    "    public static void beginEntitySubmission(final Object state) {\n",
)

manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    "    /** Marks a submitted entity whose complete previous geometry is not represented by the motion pass. */\n"
    "    public static void observeFrameInterpolationEntity(final EntityRenderState state) {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager != null && state != null) {\n"
    "            manager.motionEligibility.reject(MetalFxMotionEligibility.incompleteEntityReason(state));\n"
    "        }\n"
    "    }\n",
    "    /** Marks a submitted entity whose complete previous geometry is not represented by the motion pass. */\n"
    "    public static void observeFrameInterpolationEntity(final EntityRenderState state) {\n"
    "        MetalFxManager manager = active;\n"
    "        if (manager == null || state == null) {\n"
    "            return;\n"
    "        }\n"
    "        int incompleteReason = MetalFxMotionEligibility.incompleteEntityReason(state);\n"
    "        if (incompleteReason != 0) {\n"
    "            manager.motionEligibility.reject(incompleteReason);\n"
    "            return;\n"
    "        }\n"
    "        if (!MetalEntityMotionCapture.hasPreviousState(state)) {\n"
    "            // The current pose can seed history and remains useful to MetalFX Temporal, but\n"
    "            // MTLFXFrameInterpolator cannot safely infer object motion without a source-frame\n"
    "            // predecessor. Never reinterpret objectCurrentToPrevious's Temporal identity fallback\n"
    "            // as complete interpolation motion.\n"
    "            manager.motionEligibility.reject(MetalFxMotionEligibility.MISSING_HISTORY);\n"
    "        }\n"
    "    }\n",
)

eligibility = "src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java"
replace_once(
    eligibility,
    "    static final int DISPLAY_ENTITY = 1 << 5;\n",
    "    static final int DISPLAY_ENTITY = 1 << 5;\n"
    "    static final int MISSING_HISTORY = 1 << 6;\n",
)

unit = "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java"
replace_once(
    unit,
    "import org.junit.jupiter.api.Test;\n",
    "import org.joml.Matrix4f;\n"
    "import org.junit.jupiter.api.Test;\n",
)
replace_once(
    unit,
    "    @Test\n"
    "    void rejectionIsMonotonicWithinFrameAndResetsAtBoundary() {\n",
    "    @Test\n"
    "    void frameInterpolationRequiresARealPreviousState() {\n"
    "        Object state = new Object();\n"
    "        MetalEntityMotionCapture.beginFrame();\n"
    "        assertFalse(MetalEntityMotionCapture.hasPreviousState(state));\n\n"
    "        MetalEntityMotionCapture.attachState(\n"
    "                state,\n"
    "                new MetalEntityMotionCapture.Sample(1L, 1L, new Matrix4f(), null)\n"
    "        );\n"
    "        assertFalse(MetalEntityMotionCapture.hasPreviousState(state));\n\n"
    "        MetalEntityMotionCapture.attachState(\n"
    "                state,\n"
    "                new MetalEntityMotionCapture.Sample(1L, 1L, new Matrix4f(), new Matrix4f())\n"
    "        );\n"
    "        assertTrue(MetalEntityMotionCapture.hasPreviousState(state));\n\n"
    "        MetalEntityMotionCapture.beginFrame();\n"
    "        assertFalse(MetalEntityMotionCapture.hasPreviousState(state));\n"
    "    }\n\n"
    "    @Test\n"
    "    void rejectionIsMonotonicWithinFrameAndResetsAtBoundary() {\n",
)
