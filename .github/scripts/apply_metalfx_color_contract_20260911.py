from pathlib import Path

path = Path("src/main/java/com/metallum/client/metal/render/MetalFxManager.java")
text = path.read_text()

replacements = [
    (
        """    // True when this source frame submitted first-person geometry. The current\n    // hand path has no trusted previous local vertices for swing/bob/equip, so\n    // this observation is a hard Frame Generation admission veto. Temporal can\n    // still consume its reactive/history inputs.\n    private boolean firstPersonMotionObserved;\n""",
        """    // True only when this source frame observed first-person geometry without a\n    // trustworthy exact previous-vertex replay (history break, renderer contract\n    // failure, or unsupported submission). Continuous hand/equip/bob/swing motion\n    // with committed staged history uses the dedicated first-person validity plane\n    // and does not set this fallback veto.\n    private boolean firstPersonMotionObserved;\n""",
    ),
    (
        """    /**\n     * Records first-person geometry for this source frame. Its swing/bob/equip\n     * pose has no trusted previous local-vertex stream yet, so this is a\n     * deliberate Frame Generation veto; Temporal remains enabled.\n     */\n    public static void observeFirstPersonMotion() {\n""",
        """    /**\n     * Records a first-person fallback/history break for this source frame. Exact\n     * staged first-person replay does not call this method once it has committed\n     * previous geometry; only an unproven hand submission vetoes Frame Generation.\n     * Temporal remains enabled so its reactive/history safeguards can still run.\n     */\n    public static void observeFirstPersonMotion() {\n""",
    ),
    (
        """                    COMBINED_DIAGNOSTIC_COLOR_ASSUMPTION\n                            ? FrameSynthesisContract.ColorEncodingEvidence.DIAGNOSTIC_UNPROVEN_RGBA8_UNORM_SRGB_VIEW\n                            : FrameSynthesisContract.ColorEncodingEvidence.UNPROVEN_RGBA8_UNORM_SRGB_VIEW\n""",
        """                    FrameGenerationColorContract.currentRenderer(\n                            usesNativeDirectFrameGeneration()\n                                    ? FrameGenerationColorContract.SourcePath.NATIVE_DIRECT\n                                    : FrameGenerationColorContract.SourcePath.TEMPORAL_OUTPUT\n                    ).admissionEvidence(COMBINED_DIAGNOSTIC_COLOR_ASSUMPTION)\n""",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one bounded anchor, found {count}: {old[:80]!r}")
    text = text.replace(old, new, 1)

if "private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;" not in text:
    raise SystemExit("global motion gate unexpectedly changed")
if "FrameGenerationColorContract.currentRenderer(" not in text:
    raise SystemExit("color contract was not wired")
if "UNPROVEN_RGBA8_UNORM_SRGB_VIEW" in text[text.find("new FrameSynthesisContract.FrameGenerationAdmission"):text.find("new FrameSynthesisContract.FrameGenerationAdmission") + 2000]:
    raise SystemExit("admission still bypasses the structured color contract")

path.write_text(text)
