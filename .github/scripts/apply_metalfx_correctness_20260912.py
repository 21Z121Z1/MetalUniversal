#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


native_path = Path("src/main/native/MetallumNative.swift")
native = native_path.read_text()

native = replace_once(
    native,
    '''private func buildMotionDepthResamplePipeline(
    device: MTLDevice,
    motionFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)
''',
    '''private func buildMotionDepthResamplePipeline(
    device: MTLDevice,
    motionFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        // This is texture-to-texture resampling, not drawable presentation.
        // Keep depth/motion in the renderer's native Metal orientation; the
        // final CAMetalLayer present is the only stage that applies the Y flip.
        let library = try device.makeLibrary(source: copyMslSource(), options: nil)
''',
    "depth/motion resample orientation",
)

native = replace_once(
    native,
    '''    NativeState.lastTemporalScalerForInterpolation = nil
    NativeState.metalFxHistoryLock.lock()
    NativeState.metalFxPreviousDepthTextures.removeAll()
    NativeState.metalFxValidationReactiveTextures.removeAll()
    NativeState.metalFxPreviousDepthValid.removeAll()
    NativeState.metalFxHistoryLock.unlock()
''',
    '''    NativeState.lastTemporalScalerForInterpolation = nil
    NativeState.metalFxHistoryLock.lock()
    // Callers drain submitted GPU work before cache teardown. Metal 4 history
    // and diagnostic textures are explicitly added to the residency set when
    // allocated, so remove them symmetrically before dropping the last strong
    // cache references. Untracked Metal 3 textures are harmless here because
    // residencyTrackReleased() is ledger-guarded and becomes a no-op.
    for texture in NativeState.metalFxPreviousDepthTextures.values {
        residencyTrackReleased(texture)
    }
    for texture in NativeState.metalFxValidationReactiveTextures.values {
        residencyTrackReleased(texture)
    }
    NativeState.metalFxPreviousDepthTextures.removeAll()
    NativeState.metalFxValidationReactiveTextures.removeAll()
    NativeState.metalFxPreviousDepthValid.removeAll()
    NativeState.metalFxHistoryLock.unlock()
''',
    "MetalFX residency release symmetry",
)

native_path.write_text(native)

color_path = Path("src/main/java/com/metallum/client/metal/render/FrameGenerationColorContract.java")
color = color_path.read_text()

color = replace_once(
    color,
    '''    enum TemporalEncoding {
        /** MetalFX Temporal's documented input/output semantic contract. */
        LINEAR,
        NOT_APPLICABLE,
        UNPROVEN
    }

''',
    '''    enum TemporalEncoding {
        /** MetalFX Temporal's documented input/output semantic contract. */
        LINEAR,
        NOT_APPLICABLE,
        UNPROVEN
    }

    /** Numeric meaning of the renderer's scene texture before MetalFX consumes it. */
    enum SceneEncoding {
        LINEAR,
        DISPLAY_REFERRED_SRGB,
        UNPROVEN
    }

''',
    "scene encoding enum",
)

color = replace_once(
    color,
    '''            GpuFormat sceneStorage,
            GpuFormat uiStorage,
            TemporalEncoding temporalEncoding,
''',
    '''            GpuFormat sceneStorage,
            GpuFormat uiStorage,
            SceneEncoding sceneEncoding,
            TemporalEncoding temporalEncoding,
''',
    "evidence scene encoding field",
)

color = replace_once(
    color,
    '''            Objects.requireNonNull(sceneStorage, "sceneStorage");
            Objects.requireNonNull(uiStorage, "uiStorage");
            Objects.requireNonNull(temporalEncoding, "temporalEncoding");
''',
    '''            Objects.requireNonNull(sceneStorage, "sceneStorage");
            Objects.requireNonNull(uiStorage, "uiStorage");
            Objects.requireNonNull(sceneEncoding, "sceneEncoding");
            Objects.requireNonNull(temporalEncoding, "temporalEncoding");
''',
    "evidence scene encoding validation",
)

color = replace_once(
    color,
    '''                case TEMPORAL_OUTPUT -> temporalEncoding == TemporalEncoding.LINEAR
                        && toneMapPlacement
                        == ToneMapPlacement.AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION;
                case NATIVE_DIRECT -> temporalEncoding == TemporalEncoding.NOT_APPLICABLE
                        && toneMapPlacement
                        == ToneMapPlacement.BEFORE_NATIVE_DIRECT_FRAME_INTERPOLATION;
''',
    '''                case TEMPORAL_OUTPUT -> sceneEncoding == SceneEncoding.LINEAR
                        && temporalEncoding == TemporalEncoding.LINEAR
                        && toneMapPlacement
                        == ToneMapPlacement.AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION;
                case NATIVE_DIRECT -> sceneEncoding == SceneEncoding.DISPLAY_REFERRED_SRGB
                        && temporalEncoding == TemporalEncoding.NOT_APPLICABLE
                        && toneMapPlacement
                        == ToneMapPlacement.BEFORE_NATIVE_DIRECT_FRAME_INTERPOLATION;
''',
    "production scene encoding gate",
)

color = replace_once(
    color,
    '''            if (sourcePath == SourcePath.TEMPORAL_OUTPUT) {
                if (temporalEncoding != TemporalEncoding.LINEAR) {
                    missing.add("temporal-linear");
                }
''',
    '''            if (sourcePath == SourcePath.TEMPORAL_OUTPUT) {
                if (sceneEncoding != SceneEncoding.LINEAR) {
                    missing.add("temporal-input-linearization");
                }
                if (temporalEncoding != TemporalEncoding.LINEAR) {
                    missing.add("temporal-linear");
                }
''',
    "temporal scene proof",
)

color = replace_once(
    color,
    '''            } else {
                if (temporalEncoding != TemporalEncoding.NOT_APPLICABLE) {
                    missing.add("native-direct-temporal-n/a");
                }
''',
    '''            } else {
                if (sceneEncoding != SceneEncoding.DISPLAY_REFERRED_SRGB) {
                    missing.add("native-direct-display-referred-scene");
                }
                if (temporalEncoding != TemporalEncoding.NOT_APPLICABLE) {
                    missing.add("native-direct-temporal-n/a");
                }
''',
    "native direct scene proof",
)

color = replace_once(
    color,
    '''     * <p>The Temporal path has an API-level linear semantic, but there is currently no explicit
     * post-Temporal tone-map/transfer pass before {@code sceneOutputTarget} is copied into the
     * Frame Interpolator ring. The native-direct path likewise has no machine-verifiable transfer
     * attestation. The UI target is RGBA8_UNORM, but the complete set of GUI/overlay blend modes
''',
    '''     * <p>The Temporal API has a documented linear semantic, but the current Minecraft scene
     * target is a plain non-sRGB UNORM texture carrying already tone-mapped/display-referred
     * values. There is no explicit display-referred -> linear conversion before Temporal, nor an
     * explicit post-Temporal tone-map/transfer pass before {@code sceneOutputTarget} is copied into
     * the Frame Interpolator ring. The native-direct path likewise has no machine-verifiable
     * transfer attestation. The UI target is RGBA8_UNORM, but the complete set of GUI/overlay blend modes
''',
    "current renderer color documentation",
)

color = replace_once(
    color,
    '''                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                sourcePath == SourcePath.TEMPORAL_OUTPUT
''',
    '''                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                SceneEncoding.DISPLAY_REFERRED_SRGB,
                sourcePath == SourcePath.TEMPORAL_OUTPUT
''',
    "current renderer scene encoding",
)

color_path.write_text(color)

test_path = Path("src/test/java/com/metallum/client/metal/render/FrameGenerationColorContractTest.java")
test = test_path.read_text()

test = replace_once(
    test,
    '''                "post-temporal-tone-map",
                "frame-interpolation-transfer",
''',
    '''                "temporal-input-linearization",
                "post-temporal-tone-map",
                "frame-interpolation-transfer",
''',
    "current temporal missing proof expectation",
)

test = replace_once(
    test,
    '''                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                FrameGenerationColorContract.TemporalEncoding.LINEAR,
                FrameGenerationColorContract.ToneMapPlacement.UNPROVEN,
''',
    '''                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                FrameGenerationColorContract.SceneEncoding.DISPLAY_REFERRED_SRGB,
                FrameGenerationColorContract.TemporalEncoding.LINEAR,
                FrameGenerationColorContract.ToneMapPlacement.UNPROVEN,
''',
    "rgba8 evidence scene encoding",
)

test = replace_once(
    test,
    '''                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                FrameGenerationColorContract.TemporalEncoding.LINEAR,
                FrameGenerationColorContract.ToneMapPlacement
                        .AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION,
''',
    '''                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                FrameGenerationColorContract.SceneEncoding.LINEAR,
                FrameGenerationColorContract.TemporalEncoding.LINEAR,
                FrameGenerationColorContract.ToneMapPlacement
                        .AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION,
''',
    "complete evidence scene encoding",
)

test_path.write_text(test)
