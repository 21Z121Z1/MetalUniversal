# CUTOUT Shimmer Remediation — Reactive Policy Rework (2026-07-27)

Implementation specification. Written so that an implementing agent can apply
every edit without reading any other design discussion. All paths are relative
to the `MetalUniversal-master` repository root. Line numbers reference the
tree as of commit `ea2dfd4` plus the uncommitted 2026-07-26 working-tree state;
always locate edits by the quoted "current code" text, not by line number
alone.

---

## 0. TL;DR for the implementer

The mod marks every alpha-tested (CUTOUT) terrain pixel — all leaves and grass
— as **fully reactive (1.0)** in the MetalFX temporal scaler's reactive mask.
Reactive = 1.0 means "discard history, trust only the current frame". The
current frame's alpha-test coverage changes every frame by design (subpixel
Halton jitter), so full-canopy history suppression **guarantees** the shimmer
it was supposed to fix. The fix: reactive protection becomes a **narrow,
capped edge band**; interiors keep temporal accumulation; the depth-edge and
transparency producers get caps; the CUTOUT fragment shader's alpha signal is
made subpixel-continuous under minification; and the validation harness gains
an objective temporal-flicker metric so "fixed" is measured, not asserted.

Execution order (do not reorder):

1. Stage 1 — Swift kernels + tuning plumbing + Java config knobs (§6)
2. Stage 1 — validation assertions rework (§6.9)
3. Stage 2 — CUTOUT fragment shader stabilization (§7)
4. Stage 3 — flicker metric in manager + validation client (§8)
5. Tests (§9), build + A/B validation (§10), acceptance (§11), deploy (§12)

---

## 1. Problem statement

With `metallum.metalfx.mode=TEMPORAL` (67% scale, 18-phase Halton jitter),
alpha-cutout materials (leaves, tall grass, and every other Sodium
fragment-discard terrain pass) shimmer/strobe. The 2026-07-26 "fix" (CUTOUT
MRT coverage → dilate → reactive mask) did not resolve it; user confirmed
shimmer persists in real play.

## 2. Root cause (verified in code)

Three producers combine so that the entire canopy region has its temporal
history suppressed:

| # | Producer | File / symbol | Behavior today |
|---|----------|---------------|----------------|
| 1 | CUTOUT coverage MRT | `src/main/resources/assets/metallum/shaders/blocks/block_layer_cutout_reactive.fsh` (`metallumCutoutCoverage = vec4(1.0, ...)`) | Every fragment surviving the alpha test writes coverage 1.0 |
| 2 | Coverage dilation | `src/main/native/MetallumNative.swift`, `metallum_cutout_reactive_dilate` | `reactive = max(reactive, coverage)` over a 0–3 px window → **all covered pixels + a halo become reactive = 1.0** |
| 3 | Depth-edge heuristic | same file, `metallum_depth_edge_reactive` (v1) and `depthBoundary` (v2) | Any valid/invalid depth boundary (every leaf↔sky edge) returns **1.0** |

The scaler consumes this via `scaler.reactiveMaskTexture` (macOS 14.4+).
Apple's semantics: value 0 = default temporal treatment, value > 0 biases
toward the **current frame**. Under temporal upscaling, subpixel detail such
as leaf holes is reconstructed by accumulating differently-jittered frames;
per-frame binary alpha coverage is *supposed* to differ frame-to-frame and be
averaged by history. Reactive = 1.0 over the whole canopy displays the raw
per-frame jittered binary mask → shimmer is architecturally guaranteed.

The 2026-07-26 validation asserted mask *completeness*
(`coveredCutoutReactivePixels == cutoutCoveragePixels` in
`MetalFxManager.measureObjectMotion`) — it enforced the harmful policy and
never measured output stability.

Verified non-causes (do not "fix" these):

- Jitter sequence/phase count: `MetalFxConfig.phaseCount` implements
  `ceil(8·n²)` (18 at 1.5×) — matches FSR2 guidance exactly.
- Jitter sign conventions: opaque geometry is temporally stable in play;
  a sign error would make every edge crawl.
- GPTK LOD bias: `MetalCrossShaderCompiler.applySampleLodBias` intentionally
  skips `gradient2d(`/`level(` samples; Sodium terrain uses
  `textureGrad`/`textureLod` exclusively, so terrain alpha is not
  over-sharpened by the `log2(scale) − 1` bias.

## 3. External guidance this change follows

- AMD FSR2 README (integration guide): reactive mask is for **alpha-blended**
  content lacking depth/motion; write the blend alpha as the value; and:
  *"It is unlikely that a reactive value of close to 1 will ever produce good
  results. Therefore, we recommend clamping the maximum reactive value to
  around 0.9."* (https://github.com/GPUOpen-Effects/FidelityFX-FSR2)
- AMD FSR2 UE plugin foliage articles: foliage quality under temporal
  upscaling is fixed by **supplying correct motion vectors and letting
  accumulation work**, not by reactivity.
  (https://gpuopen.com/learn/fsr-2-1-unreal-engine-plugin-part1/ ,
  https://gpuopen.com/learn/fsr-2-1-unreal-engine-plugin-part2/)
- Apple `MTLFXTemporalScaler.reactiveMaskTexture` docs: 0 = default temporal
  treatment; > 0 biases to current frame; intended for fast-changing content
  such as particles.
  (https://developer.apple.com/documentation/metalfx/mtlfxtemporalscaler)
- Alpha-tested mip stability literature (context for Stage 2 and the knob
  ceiling; no atlas changes in this change):
  Castaño, "Computing Alpha Mipmaps"
  (http://the-witness.net/news/2010/09/computing-alpha-mipmaps/),
  lisyarus, "Exploring ways to mipmap alpha-tested textures"
  (https://lisyarus.github.io/blog/posts/exploring-ways-to-mipmap-alpha-tested-textures.html),
  Sawicki, "Improving the quality of the alpha test"
  (https://asawicki.info/articles/alpha_test.php5).

CUTOUT terrain has valid depth and correct camera motion (depth
reconstruction). By FSR2/Apple guidance it therefore belongs to the
**accumulated** class. Reactivity is retained only as a narrow anti-ghosting
band at coverage boundaries, well below 1.0.

## 4. Design overview

New policy, all values launch-time tunable (§5):

| Region | Old reactive | New reactive (default) |
|---|---|---|
| CUTOUT interior (window fully covered) | 1.0 | `cutoutReactiveInteriorWeight` = **0.0** |
| CUTOUT edge band (window mixed, both sides, width = dilation radius 1–3 px) | 1.0 | `cutoutReactiveEdgeWeight` = **0.35** |
| Depth valid↔invalid boundary (leaf↔sky), valid-side gradient edges | 1.0 / `min(1, 4·Δd)` | capped by `depthEdgeReactiveCap` = **0.5** |
| Transparency layers (translucent/itemEntity/particles/weather/clouds), any content | 1.0 binary | presence × `transparencyReactiveValue` = **0.9** (FSR2 max-reactive guidance) |
| Sky (invalid depth) and true disocclusion / motion-invalid pixels | 1.0 | **unchanged** 1.0 — these have no trustworthy motion; suppression is correct |

Plus Stage 2: in the CUTOUT fragment shader, blend the nearest-snapped sample
toward plain trilinear (`textureGrad`) as minification starts, so the
alpha-test signal moves subpixel-continuously under jitter instead of
flipping whole texels (the flip zone is the 1–2 texels-per-pixel range where
foliage usually sits on screen).

Everything else — jitter, motion reconstruction, disocclusion logic, scaler
configuration, frame generation gating — is untouched.

## 5. New tuning properties

All parsed in `MetalFxConfig`, clamped to [0.0, 1.0], threaded to the native
side once at `MetalFxManager` construction through a new
`metallum_metalfx_set_reactive_tuning` call. Invalid values fall back to the
default.

| JVM property | Default | Legacy value (pre-change behavior) | Consumed by |
|---|---|---|---|
| `metallum.metalfx.cutoutReactiveEdgeWeight` | 0.35 | 1.0 | dilation kernel |
| `metallum.metalfx.cutoutReactiveInteriorWeight` | 0.0 | 1.0 | dilation kernel |
| `metallum.metalfx.depthEdgeReactiveCap` | 0.5 | 1.0 | motion kernels (v1+v2) |
| `metallum.metalfx.transparencyReactiveValue` | 0.9 | 1.0 | transparency kernel |
| `metallum.metalfx.stableCutoutAlpha` (boolean) | true | false | CUTOUT fsh define (§7) |
| `metallum.validation.lenient` (boolean) | false | — | validation: record metrics but force `passed=true` (used for legacy-policy A/B runs) |

Legacy equivalence proof (needed for the A/B baseline): with edge = interior
= 1.0 the new dilation kernel emits 1.0 whenever any covered sample exists in
the window — identical to the old `max()` dilation for binary coverage at
radius ≥ 1. `MetalFxMath.cutoutReactiveRadius` yields radius 1 at the 0.67
validation scale, and the kernel now clamps radius to ≥ 1, so baseline runs
reproduce the old mask bit-for-bit. Caps at 1.0 are exact no-ops.

---

## 6. Stage 1 — exact edits

### 6.1 Swift: tuning state + setter

File: `src/main/native/MetallumNative.swift`

**(a)** Inside the `NativeState` enum/struct block (it currently ends with
`static var frameGenerationPresenter: MetalFrameGenerationPresenter?` then
`#endif`), add **before** the `#endif`:

```swift
    // Reactive-policy tuning, set once from Java before the first frame.
    // Order: (cutoutEdgeWeight, cutoutInteriorWeight, depthEdgeCap,
    // transparencyValue). Defaults mirror MetalFxConfig defaults so a missing
    // Java call keeps the shipped policy.
    static var reactiveTuning = SIMD4<Float>(0.35, 0.0, 0.5, 0.9)
```

**(b)** Next to the other `@_cdecl` functions (place it directly above
`@_cdecl("metallum_metalfx_supports_cutout_reactive")`), add:

```swift
@_cdecl("metallum_metalfx_set_reactive_tuning")
public func metallum_metalfx_set_reactive_tuning(
    _ cutoutEdgeWeight: Float,
    _ cutoutInteriorWeight: Float,
    _ depthEdgeCap: Float,
    _ transparencyValue: Float
) {
    #if os(macOS) && canImport(MetalFX)
    func clamped(_ value: Float, _ fallback: Float) -> Float {
        value.isFinite ? min(max(value, 0.0), 1.0) : fallback
    }
    NativeState.reactiveTuning = SIMD4<Float>(
        clamped(cutoutEdgeWeight, 0.35),
        clamped(cutoutInteriorWeight, 0.0),
        clamped(depthEdgeCap, 0.5),
        clamped(transparencyValue, 0.9)
    )
    NSLog(
        "[Metallum] MetalFX reactive tuning: cutoutEdge=%.3f cutoutInterior=%.3f depthEdgeCap=%.3f transparency=%.3f",
        NativeState.reactiveTuning.x,
        NativeState.reactiveTuning.y,
        NativeState.reactiveTuning.z,
        NativeState.reactiveTuning.w
    )
    #endif
}
```

### 6.2 Swift: CUTOUT dilation kernel → edge band

Same file, function `cutoutReactiveDilationMslSource()`. Replace the entire
MSL string with:

```swift
private func cutoutReactiveDilationMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct CutoutReactiveUniforms {
      uint4 dims;      // x = width, y = height, z = radius, w = unused
      float4 weights;  // x = edge-band weight, y = interior weight
    };

    kernel void metallum_cutout_reactive_dilate(
      texture2d<float, access::read> cutoutCoverage [[texture(0)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(1)]],
      constant CutoutReactiveUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.dims.x || pixel.y >= u.dims.y) return;

      // Radius floors at 1: the edge band needs at least one neighbor to
      // detect a coverage transition, and it must span the jitter/upscale
      // reconstruction footprint on both sides of the alpha-test boundary.
      int radius = int(clamp(u.dims.z, 1u, 3u));
      float coverageMin = 1.0;
      float coverageMax = 0.0;
      for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
          int2 samplePosition = int2(pixel) + int2(x, y);
          if (samplePosition.x < 0 || samplePosition.y < 0
              || samplePosition.x >= int(u.dims.x)
              || samplePosition.y >= int(u.dims.y)) {
            continue;
          }
          float coverage = clamp(cutoutCoverage.read(uint2(samplePosition)).r, 0.0, 1.0);
          coverageMin = min(coverageMin, coverage);
          coverageMax = max(coverageMax, coverage);
        }
      }

      // Interior (window fully covered): history stays valid, accumulation
      // is what resolves jittered subpixel coverage — keep reactivity low.
      // Edge band (window mixed): the alpha-test decision can flip with
      // jitter, and history can smear a leaf into the hole during motion —
      // bias to the current frame, but far below full suppression
      // (FSR2 guidance: reactive near 1.0 never produces good results).
      float contribution = 0.0;
      if (coverageMax >= 0.5) {
        contribution = coverageMin < 0.5 ? u.weights.x : u.weights.y;
      }
      float reactive = max(
        float(reactiveTexture.read(pixel).r),
        clamp(contribution, 0.0, 1.0)
      );
      reactiveTexture.write(
        half4(half(clamp(reactive, 0.0, 1.0)), half(0.0), half(0.0), half(0.0)),
        pixel
      );
    }
    """
}
```

### 6.3 Swift: dilation uniform fill

Same file, in `metallum_metalfx_apply_cutout_reactive`, replace:

```swift
            var uniforms = SIMD4<UInt32>(
                UInt32(inputWidth),
                UInt32(inputHeight),
                UInt32(radius),
                0
            )
            encoder.setComputePipelineState(pipeline)
            encoder.setBytes(
                &uniforms,
                length: MemoryLayout<SIMD4<UInt32>>.stride,
                index: 0
            )
```

with:

```swift
            struct CutoutReactiveUniforms {
                var dims: SIMD4<UInt32>
                var weights: SIMD4<Float>
            }
            var uniforms = CutoutReactiveUniforms(
                dims: SIMD4<UInt32>(
                    UInt32(inputWidth),
                    UInt32(inputHeight),
                    UInt32(radius),
                    0
                ),
                weights: SIMD4<Float>(
                    NativeState.reactiveTuning.x,
                    NativeState.reactiveTuning.y,
                    0.0,
                    0.0
                )
            )
            encoder.setComputePipelineState(pipeline)
            encoder.setBytes(
                &uniforms,
                length: MemoryLayout<CutoutReactiveUniforms>.stride,
                index: 0
            )
```

(The function's guard block — dimensions, `.r8Unorm`, `radius >= 0, radius <= 3`
— stays unchanged. The Java/FFM signature stays unchanged.)

### 6.4 Swift: depth-edge caps (v1 and v2 motion kernels)

Same file. Four MSL structs/functions and one Swift struct change. The Swift
`MotionUniforms` struct and both MSL `MotionUniforms` structs must stay
byte-identical in layout.

**(a)** Swift struct (`private struct MotionUniforms`), add a `params` member:

```swift
private struct MotionUniforms {
    var currentViewProjection: simd_float4x4
    var inverseCurrentViewProjection: simd_float4x4
    var previousViewProjection: simd_float4x4
    var viewport: SIMD4<Float>
    var flags: SIMD4<UInt32>
    var params: SIMD4<Float>
}
```

**(b)** In `motionReconstructionMslSource()` (v1) and
`motionCameraV2MslSource()` (v2), extend the MSL struct identically:

```metal
    struct MotionUniforms {
      float4x4 currentViewProjection;
      float4x4 inverseCurrentViewProjection;
      float4x4 previousViewProjection;
      float4 viewport;
      uint4 flags;
      float4 params;  // x = depth-edge reactive cap
    };
```

**(c)** v1 heuristic `metallum_depth_edge_reactive`: add a `cap` parameter and
cap both terms. Current tail:

```metal
      return validityBoundary ? 1.0 : clamp(gradient * 4.0, 0.0, 1.0);
```

New signature and tail:

```metal
    inline float metallum_depth_edge_reactive(
      texture2d<float, access::read> depthTexture,
      uint2 pixel,
      uint width,
      uint height,
      float depth,
      float cap
    ) {
      ...body unchanged...
      // Depth boundaries have valid depth and correct camera motion on the
      // covered side; they need a history bias against edge smear, not full
      // suppression. The cap keeps accumulation alive on foliage silhouettes.
      return validityBoundary ? cap : min(cap, clamp(gradient * 4.0, 0.0, 1.0));
    }
```

and its call site in `metallum_motion_reconstruction` becomes:

```metal
      reactive = max(reactive, metallum_depth_edge_reactive(depthTexture, pixel, width, height, depth, u.params.x));
```

**(d)** v2 heuristic `depthBoundary` in `motionCameraV2MslSource()`: same
change — add `float cap` as the last parameter, same new `return` line, and
its call site in `metallum_motion_camera_v2` becomes:

```metal
      reactive = max(reactive, depthBoundary(depthTexture, pixel, width, height, depth, u.params.x));
```

Do **not** touch the `reactive = 1.0` assignments for invalid depth,
reconstruction failure, motion overflow, or disocclusion — those pixels have
no trustworthy motion and full suppression is correct there.

**(e)** Both Swift fill sites (`var uniforms = MotionUniforms(` in the v1
encode path and `var motionUniforms = MotionUniforms(` in the v2 encode path)
get the new member, filled from the tuning state — append after the `flags:`
argument:

```swift
                        params: SIMD4<Float>(NativeState.reactiveTuning.z, 0.0, 0.0, 0.0)
```

### 6.5 Swift: transparency mask cap

Same file, `transparencyMaskMslSource()`:

Struct:

```metal
    struct TransparencyMaskUniforms {
      uint4 viewport;
      uint4 flags;
      float4 params;  // x = transparency reactive value
    };
```

Kernel body — the five `reactive = max(reactive, targetActivity(...));` lines
each gain the multiplier, e.g.:

```metal
      if ((flags & 1u) != 0u) reactive = max(reactive, targetActivity(translucentTexture, pixel) * u.params.x);
```

(same for the other four lines).

Fill site in `metallum_metalfx_mark_transparency`:

```swift
            var uniforms = TransparencyMaskUniforms(
                viewport: SIMD4<UInt32>(UInt32(inputWidth), UInt32(inputHeight), 0, 0),
                flags: SIMD4<UInt32>(flags, 0, 0, 0),
                params: SIMD4<Float>(NativeState.reactiveTuning.w, 0.0, 0.0, 0.0)
            )
```

The Swift-side `TransparencyMaskUniforms` struct definition (search
`struct TransparencyMaskUniforms` in the Swift file) gains the matching
`var params: SIMD4<Float>` member.

### 6.6 Java: config parsing

File: `src/main/java/com/metallum/client/metal/render/MetalFxConfig.java`

**(a)** New fields on the config class, after `final boolean frameGeneration;`:

```java
    final float cutoutReactiveEdgeWeight;
    final float cutoutReactiveInteriorWeight;
    final float depthEdgeReactiveCap;
    final float transparencyReactiveValue;
```

**(b)** Extend the private constructor with the four `float` parameters (same
order) and assign them.

**(c)** In `load()`, before the `return`, parse:

```java
        float cutoutReactiveEdgeWeight = parseUnitFloat(
                System.getProperty("metallum.metalfx.cutoutReactiveEdgeWeight"), 0.35F);
        float cutoutReactiveInteriorWeight = parseUnitFloat(
                System.getProperty("metallum.metalfx.cutoutReactiveInteriorWeight"), 0.0F);
        float depthEdgeReactiveCap = parseUnitFloat(
                System.getProperty("metallum.metalfx.depthEdgeReactiveCap"), 0.5F);
        float transparencyReactiveValue = parseUnitFloat(
                System.getProperty("metallum.metalfx.transparencyReactiveValue"), 0.9F);
```

and pass them to the constructor.

**(d)** New helper next to `parseScale`:

```java
    static float parseUnitFloat(final String value, final float fallback) {
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isFinite(parsed)) {
                return Math.clamp(parsed, 0.0F, 1.0F);
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }
```

These are launch-argument knobs only — do not add them to the persistent
Sodium settings file.

### 6.7 Java: FFM bridge for the setter

File: `src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java`

**(a)** Next to the other handle fields, add
`private static MethodHandle metalfxSetReactiveTuning;` (match surrounding
declarations).

**(b)** In the static downcall-resolution block, next to
`metalfxSupportsCutoutReactive`:

```java
            metalfxSetReactiveTuning = optionalDowncall(
                    lookup,
                    "metallum_metalfx_set_reactive_tuning",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT
                    )
            );
```

**(c)** Public wrapper, next to `metallum_metalfx_apply_cutout_reactive`:

```java
    public static void metallum_metalfx_set_reactive_tuning(
            final float cutoutEdgeWeight,
            final float cutoutInteriorWeight,
            final float depthEdgeCap,
            final float transparencyValue
    ) {
        if (metalfxSetReactiveTuning == null) {
            return;
        }
        try {
            metalfxSetReactiveTuning.invokeExact(
                    cutoutEdgeWeight,
                    cutoutInteriorWeight,
                    depthEdgeCap,
                    transparencyValue
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_set_reactive_tuning", throwable);
        }
    }
```

### 6.8 Java: push tuning at manager construction

File: `src/main/java/com/metallum/client/metal/render/MetalFxManager.java`

In the private constructor, immediately after
`this.config = MetalFxConfig.load();`:

```java
        MetalNativeBridge.metallum_metalfx_set_reactive_tuning(
                this.config.cutoutReactiveEdgeWeight,
                this.config.cutoutReactiveInteriorWeight,
                this.config.depthEdgeReactiveCap,
                this.config.transparencyReactiveValue
        );
```

And extend the existing "MetalFX configured:" info log with
`, reactiveTuning=(edge={}, interior={}, depthCap={}, transparency={})` and the
four values, so launch logs record the active policy.

### 6.9 Java: validation assertions rework

File: `src/main/java/com/metallum/client/metal/render/MetalFxManager.java`,
method `measureObjectMotion`, plus the `MotionMetrics` record and its
`toJson`, plus the info log in `finishValidationCapture`.

The old cutout invariant enforced the harmful policy and must be replaced:

**(a)** Replace the counter block:

```java
        int cutoutCoveragePixels = 0;
        int coveredCutoutReactivePixels = 0;
        int dilatedCutoutReactivePixels = 0;
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            boolean covered = Byte.toUnsignedInt(cutoutCoverage[pixel]) >= 128;
            boolean markedReactive = Byte.toUnsignedInt(reactive[pixel]) >= 128;
            ...
        }
```

with:

```java
        // New policy invariants (see docs/cutout-shimmer-remediation-2026-07-27.md):
        // interior CUTOUT pixels must KEEP temporal accumulation (low
        // reactive); the edge band must still carry a protective bias.
        // Interior = every in-bounds neighbor within the submitted dilation
        // radius is covered, mirroring the kernel's window classification.
        int cutoutCoveragePixels = 0;
        int cutoutInteriorPixels = 0;
        int cutoutInteriorViolations = 0;
        int cutoutEdgeBandReactivePixels = 0;
        int effectiveRadius = Math.clamp(cutoutRadius, 1, 3);
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            boolean covered = Byte.toUnsignedInt(cutoutCoverage[pixel]) >= 128;
            int reactiveValue = Byte.toUnsignedInt(reactive[pixel]);
            int x = pixel % renderWidth;
            int y = pixel / renderWidth;
            if (covered) {
                cutoutCoveragePixels++;
                if (allCutoutNeighborsCovered(cutoutCoverage, x, y, renderWidth, renderHeight, effectiveRadius)) {
                    cutoutInteriorPixels++;
                    // Disoccluded pixels are legitimately fully reactive for
                    // one frame (the capture frames sit a few frames after a
                    // scripted scene mutation); the invariant targets the
                    // standing policy, so those transients are excluded.
                    if (reactiveValue > INTERIOR_REACTIVE_MAX
                            && Byte.toUnsignedInt(disocclusion[pixel]) < 128) {
                        cutoutInteriorViolations++;
                    }
                } else if (reactiveValue >= EDGE_REACTIVE_MIN) {
                    cutoutEdgeBandReactivePixels++;
                }
            } else if (reactiveValue >= EDGE_REACTIVE_MIN && hasCutoutCoverageNeighbor(
                    cutoutCoverage, x, y, renderWidth, renderHeight, effectiveRadius)) {
                cutoutEdgeBandReactivePixels++;
            }
        }
```

**(b)** New constants next to the other private constants of the manager:

```java
    // Interior CUTOUT pixels may only carry residual reactivity (depth
    // gradients read ~0-0.06 there); 48/255 ≈ 0.19 leaves margin while
    // catching any interior flood. The edge band must reach at least
    // 72/255 ≈ 0.28 (< default edge weight 0.35 and < depth-edge cap 0.5).
    private static final int INTERIOR_REACTIVE_MAX = 48;
    private static final int EDGE_REACTIVE_MIN = 72;
```

**(c)** New helper next to `hasCutoutCoverageNeighbor` (same loop shape;
out-of-bounds neighbors are skipped, i.e. do not break interior-ness):

```java
    private static boolean allCutoutNeighborsCovered(
            final byte[] coverage,
            final int x,
            final int y,
            final int width,
            final int height,
            final int radius
    ) {
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int sampleY = y + offsetY;
            if (sampleY < 0 || sampleY >= height) {
                continue;
            }
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                int sampleX = x + offsetX;
                if (sampleX < 0 || sampleX >= width) {
                    continue;
                }
                if (Byte.toUnsignedInt(coverage[sampleY * width + sampleX]) < 128) {
                    return false;
                }
            }
        }
        return true;
    }
```

**(d)** Scenario switch: replace the `"cutout_leaves", "cutout_grass"` case
with:

```java
            case "cutout_leaves", "cutout_grass" -> depthContractPassed
                    && cutoutCoveragePixels > 32
                    && cutoutInteriorPixels > 0
                    && cutoutInteriorViolations == 0
                    && cutoutEdgeBandReactivePixels > 0;
```

**(e)** Lenient mode for A/B baselines — immediately before
`return new MotionMetrics(...)`:

```java
        if (Boolean.getBoolean("metallum.validation.lenient")) {
            passed = true;
        }
```

(change `boolean passed = switch ...` to a non-final local if needed).

**(f)** `MotionMetrics` record: replace the two fields
`coveredCutoutReactivePixels` / `dilatedCutoutReactivePixels` with
`cutoutInteriorPixels`, `cutoutInteriorViolations`,
`cutoutEdgeBandReactivePixels` (keep `cutoutCoveragePixels` and
`cutoutRadius`). Update the constructor call, `toJson` (replace the two old
JSON keys with `"cutoutInteriorPixels"`, `"cutoutInteriorViolations"`,
`"cutoutEdgeBandReactivePixels"`), and the
`finishValidationCapture` log line (replace
`coveredCutoutReactivePixels={} dilatedCutoutReactivePixels={}` with
`cutoutInteriorPixels={} cutoutInteriorViolations={} cutoutEdgeBandReactivePixels={}`
and pass the new values).

---

## 7. Stage 2 — CUTOUT alpha-test stabilization

File: `src/main/resources/assets/metallum/shaders/blocks/block_layer_cutout_reactive.fsh`

Only `main()` changes; the three sample helpers stay byte-identical. Replace
`main()` with:

```glsl
void main() {
    vec4 color = u_UseRGSS
        ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize)
        : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

#ifdef METALLUM_STABLE_ALPHA
    // Temporal-upscaling stabilization: nearest-path texel snapping makes the
    // sampled alpha flip by whole texels under subpixel camera jitter in the
    // 1-2 texels-per-pixel minification zone. Blending toward plain trilinear
    // as minification starts makes both the alpha-test signal and the
    // surviving color vary continuously with jitter, which temporal
    // accumulation can resolve. Magnified (close-up) texels keep the vanilla
    // nearest look; the smoothstep window matches sampleRGSS's transition.
    vec2 du = dFdx(v_TexCoord);
    vec2 dv = dFdy(v_TexCoord);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(u_TexelSize.x, u_TexelSize.y);
    float minified = smoothstep(minPixelSize, 2.0 * minPixelSize, maxTexelSize);
    if (minified > 0.0) {
        color = mix(color, textureGrad(u_BlockTex, v_TexCoord, du, dv), minified);
    }
#endif
    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    fragColor = _linearFog(
        color,
        v_FragDistance,
        u_FogColor,
        u_EnvironmentFog,
        u_RenderFog,
        fadeFactor
    );
    // This executes only for the exact samples that survived the scene-color
    // alpha test above. The reactive dilation pass classifies the coverage
    // into interior vs edge band; see the remediation doc.
    metallumCutoutCoverage = vec4(1.0, 0.0, 0.0, 0.0);
}
```

File: `src/main/java/com/metallum/client/metal/render/MetalCutoutReactivePipeline.java`

Add a field:

```java
    // Launch-arg escape hatch: -Dmetallum.metalfx.stableCutoutAlpha=false
    // restores the exact pre-change sampling for A/B comparisons.
    private static final boolean STABLE_ALPHA =
            !"false".equalsIgnoreCase(System.getProperty("metallum.metalfx.stableCutoutAlpha", "true"));
```

and in `build(...)` conditionally add the define. Change the single chained
builder expression into:

```java
        var builder = RenderPipeline.builder()
                ... existing chain unchanged through .withShaderDefine("ALPHA_CUTOUT", 0.5F);
        if (STABLE_ALPHA) {
            builder.withShaderDefine("METALLUM_STABLE_ALPHA");
        }
        return builder.build();
```

Notes for the implementer: this shader is only used while MetalFX temporal is
active (`usesCutoutReactiveTerrain`), so non-MetalFX rendering is untouched.
Expected visual delta: distant/mid-range cutout silhouettes get slightly
smoother; close-up look unchanged. The RGSS path already blends toward
smooth sampling in the same window; the extra mix is a mild, consistent
softening there.

---

## 8. Stage 3 — temporal flicker metric

Objective, machine-readable measurement so the fix is judged by output
stability, not mask composition. A static-camera hold phase is appended to
the automated validation timeline; consecutive upscaled output frames are
compared pixel-by-pixel.

### 8.1 Manager: flicker capture API

File: `src/main/java/com/metallum/client/metal/render/MetalFxManager.java`

**(a)** New fields (next to the other validation fields):

```java
    @Nullable
    private FlickerRequest flickerRequest;
    private boolean flickerCapturePending;
    private boolean flickerMetricCompleted;
    private int flickerFramesAccumulated;
    private int flickerDisplayWidth;
    private int flickerDisplayHeight;
    @Nullable
    private boolean[] flickerMask;
    private int flickerMaskPixels;
    @Nullable
    private byte[] flickerPreviousLuma;
    private final long[] flickerMaskedHistogram = new long[256];
    private final long[] flickerControlHistogram = new long[256];

    private record FlickerRequest(int frame, String scenario, boolean first, boolean last) {
    }
```

**(b)** Public statics (next to `setValidationFrame` /
`validationCapturesPending`):

```java
    public static void setFlickerCaptureFrame(
            final int frame, final String scenario, final boolean first, final boolean last) {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.flickerRequest = new FlickerRequest(frame, scenario, first, last);
        }
    }

    public static boolean flickerSeriesPending() {
        MetalFxManager manager = active;
        return manager != null && manager.flickerCapturePending;
    }

    public static boolean flickerMetricCompleted() {
        MetalFxManager manager = active;
        return manager != null && manager.flickerMetricCompleted;
    }
```

**(c)** Capture hook. In `beforeGuiInternal`, directly after the existing
`captureValidationFrameIfRequested(color, depth, output);` line (same guard:
`historyTransactionEncoded && depth != null`), add
`captureFlickerFrameIfRequested(output);`, and implement:

```java
    private void captureFlickerFrameIfRequested(final MetalGpuTexture temporalOutput) {
        FlickerRequest requested = this.flickerRequest;
        this.flickerRequest = null;
        if (requested == null || cutoutReactiveTexture == null || flickerMetricCompleted) {
            return;
        }
        this.flickerCapturePending = true;
        ValidationReadback outputReadback = validationReadback("flicker-output", temporalOutput);
        ValidationReadback coverageReadback = requested.first()
                ? validationReadback("flicker-coverage", cutoutReactiveTexture)
                : null;
        if (coverageReadback != null) {
            device.commandEncoder().copyTextureToBuffer(
                    coverageReadback.texture(), coverageReadback.buffer(), 0L, () -> { }, 0);
        }
        device.commandEncoder().copyTextureToBuffer(
                outputReadback.texture(),
                outputReadback.buffer(),
                0L,
                () -> finishFlickerCapture(requested, outputReadback, coverageReadback),
                0
        );
    }
```

**(d)** Processing. Add (readback byte extraction copies the pattern in
`finishValidationCapture`):

```java
    private void finishFlickerCapture(
            final FlickerRequest requested,
            final ValidationReadback outputReadback,
            @Nullable final ValidationReadback coverageReadback
    ) {
        try {
            byte[] output = readbackBytes(outputReadback);
            int width = outputReadback.texture().getWidth(0);
            int height = outputReadback.texture().getHeight(0);
            if (requested.first()) {
                byte[] coverage = readbackBytes(coverageReadback);
                beginFlickerSeries(width, height, coverage);
            }
            accumulateFlickerFrame(output, width, height);
            // Requests already in flight when the series closes must not
            // rewrite the metric: the JSON is final on the first close.
            if (requested.last() && !flickerMetricCompleted) {
                writeFlickerMetrics(requested.scenario());
                this.flickerMetricCompleted = true;
            }
        } catch (RuntimeException | IOException exception) {
            Metallum.LOGGER.error("MetalFX flicker capture failed for frame {}", requested.frame(), exception);
            this.flickerMetricCompleted = true; // fail open: the run reports, A/B compare will show the gap
        } finally {
            outputReadback.buffer().close();
            if (coverageReadback != null) {
                coverageReadback.buffer().close();
            }
            this.flickerCapturePending = false;
        }
    }

    private static byte[] readbackBytes(final ValidationReadback readback) {
        ByteBuffer source = readback.buffer().currentStorage()
                .limit(readback.byteCount())
                .slice()
                .order(ByteOrder.nativeOrder());
        byte[] bytes = new byte[readback.byteCount()];
        source.get(bytes);
        return bytes;
    }
```

**(e)** Metric math — exact semantics:

```java
    private void beginFlickerSeries(final int width, final int height, final byte[] coverage) {
        this.flickerDisplayWidth = width;
        this.flickerDisplayHeight = height;
        this.flickerFramesAccumulated = 0;
        this.flickerPreviousLuma = null;
        java.util.Arrays.fill(this.flickerMaskedHistogram, 0L);
        java.util.Arrays.fill(this.flickerControlHistogram, 0L);
        // Display pixel -> render pixel (integer scale), masked when any
        // CUTOUT coverage exists in the 3x3 render neighborhood: this covers
        // the upscale footprint plus the reactive edge band.
        boolean[] mask = new boolean[width * height];
        int maskPixels = 0;
        for (int y = 0; y < height; y++) {
            int renderY = Math.min(renderHeight - 1, y * renderHeight / height);
            for (int x = 0; x < width; x++) {
                int renderX = Math.min(renderWidth - 1, x * renderWidth / width);
                if (hasCutoutCoverageNeighbor(coverage, renderX, renderY, renderWidth, renderHeight, 1)) {
                    mask[y * width + x] = true;
                    maskPixels++;
                }
            }
        }
        this.flickerMask = mask;
        this.flickerMaskPixels = maskPixels;
    }

    private void accumulateFlickerFrame(final byte[] rgba, final int width, final int height) {
        if (flickerMask == null || width != flickerDisplayWidth || height != flickerDisplayHeight
                || rgba.length < width * height * 4) {
            throw new IllegalStateException("Flicker capture dimensions changed mid-series");
        }
        byte[] luma = new byte[width * height];
        for (int pixel = 0; pixel < width * height; pixel++) {
            int r = Byte.toUnsignedInt(rgba[pixel * 4]);
            int g = Byte.toUnsignedInt(rgba[pixel * 4 + 1]);
            int b = Byte.toUnsignedInt(rgba[pixel * 4 + 2]);
            // Integer Rec.709 luma; channel-order swaps would affect both A/B
            // runs identically and cancel out of the comparison.
            luma[pixel] = (byte) ((54 * r + 183 * g + 19 * b) >> 8);
        }
        if (flickerPreviousLuma != null) {
            for (int pixel = 0; pixel < width * height; pixel++) {
                int delta = Math.abs(
                        Byte.toUnsignedInt(luma[pixel]) - Byte.toUnsignedInt(flickerPreviousLuma[pixel]));
                if (flickerMask[pixel]) {
                    flickerMaskedHistogram[delta]++;
                } else {
                    flickerControlHistogram[delta]++;
                }
            }
        }
        this.flickerPreviousLuma = luma;
        this.flickerFramesAccumulated++;
    }

    private void writeFlickerMetrics(final String scenario) throws IOException {
        Path root = Path.of(System.getProperty(
                "metallum.validation.output",
                "build/metal-validation/minecraft-client-current"
        )).toAbsolutePath().normalize();
        Files.createDirectories(root);
        double maskedMean = histogramMean(flickerMaskedHistogram);
        int maskedP95 = histogramPercentile(flickerMaskedHistogram, 0.95);
        double controlMean = histogramMean(flickerControlHistogram);
        int controlP95 = histogramPercentile(flickerControlHistogram, 0.95);
        String json = String.format(
                java.util.Locale.ROOT,
                """
                {
                  "scenario": "%s",
                  "frames": %d,
                  "displayWidth": %d,
                  "displayHeight": %d,
                  "maskPixels": %d,
                  "maskedMeanDelta": %.6f,
                  "maskedP95Delta": %d,
                  "controlMeanDelta": %.6f,
                  "controlP95Delta": %d
                }
                """,
                scenario, flickerFramesAccumulated, flickerDisplayWidth, flickerDisplayHeight,
                flickerMaskPixels, maskedMean, maskedP95, controlMean, controlP95
        );
        Files.writeString(root.resolve("flicker-" + scenario + ".json"), json, StandardCharsets.UTF_8);
        Metallum.LOGGER.info(
                "MetalFX flicker metric: scenario={} frames={} maskPixels={} maskedMeanDelta={} maskedP95={} controlMeanDelta={} controlP95={}",
                scenario, flickerFramesAccumulated, flickerMaskPixels,
                String.format(java.util.Locale.ROOT, "%.4f", maskedMean), maskedP95,
                String.format(java.util.Locale.ROOT, "%.4f", controlMean), controlP95
        );
    }

    private static double histogramMean(final long[] histogram) {
        long total = 0L;
        long weighted = 0L;
        for (int value = 0; value < histogram.length; value++) {
            total += histogram[value];
            weighted += histogram[value] * value;
        }
        return total == 0L ? Double.NaN : (double) weighted / total;
    }

    private static int histogramPercentile(final long[] histogram, final double percentile) {
        long total = 0L;
        for (long count : histogram) total += count;
        if (total == 0L) return 0;
        long threshold = (long) Math.ceil(total * percentile);
        long cumulative = 0L;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative >= threshold) return value;
        }
        return histogram.length - 1;
    }
```

Memory note: only the previous frame's luma plane is retained (≤ ~2 MB);
frames are folded into the two 256-bin histograms incrementally.

### 8.2 Validation client timeline extension

File: `src/main/java/com/metallum/client/validation/MetalValidationClient.java`

**(a)** Constants next to `WARMUP_FRAMES`:

```java
    // Static-camera hold on the cutout grass scene: only the Halton jitter
    // varies between these frames, so any output delta is temporal
    // instability. 24 consecutive frames cover the full 18-phase cycle.
    private static final int FLICKER_START_FRAME = 92;
    private static final int FLICKER_END_FRAME = 115;
```

**(b)** `scenarioPoseFor` — replace the final `return`:

```java
        if (timelineFrame < 90) {
            return new ScenarioPose("cutout_grass", 0.80, 0.40);
        }
        return new ScenarioPose("cutout_grass_hold", 0.80, 0.40);
```

**(c)** `applyScenarioPose` — pitch condition covers the hold scenario:

```java
        float pitch = pose.scenario().startsWith("cutout_grass") ? 15.0F : cameraPitch;
```

**(d)** After the existing `MetalFxManager.setValidationFrame(...)` call, add:

```java
        if (frame >= FLICKER_START_FRAME
                && (frame <= FLICKER_END_FRAME
                || (!MetalFxManager.flickerMetricCompleted() && !MetalFxManager.flickerSeriesPending()))) {
            // Past the nominal end the series-closing request repeats until the
            // metric lands, so one dropped encode cannot hang the finish gate.
            MetalFxManager.setFlickerCaptureFrame(
                    frame,
                    "cutout_grass_hold",
                    frame == FLICKER_START_FRAME,
                    frame >= FLICKER_END_FRAME
            );
        }
```

**(e)** Finish gate — replace
`if (frame >= 90 && MetalFxManager.validationCapturesPending() == 0)` with:

```java
        if (frame >= FLICKER_END_FRAME + 3
                && MetalFxManager.validationCapturesPending() == 0
                && !MetalFxManager.flickerSeriesPending()
                && MetalFxManager.flickerMetricCompleted()) {
```

(The `completed != 10 || failures != 0` inner check stays as-is; the
`frame >= 220` timeout stays as-is. `appendFrameState`'s `frame < 90` guard
stays as-is so the golden frame-state JSON is unchanged.)

### 8.3 Metric interpretation

- `maskedMeanDelta` — mean per-pixel |ΔY| (0–255 scale) between consecutive
  frames within the CUTOUT-region mask. This is the shimmer number.
- `controlMeanDelta` — same outside the mask (ground/sky): regression guard.
- Absolute values are scene-dependent; judgments are made by comparing runs
  (§10), never against absolute thresholds.

---

## 9. Tests

File (new): `src/test/java/com/metallum/client/metal/render/MetalFxReactiveTuningTest.java`

```java
package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalFxReactiveTuningTest {
    @Test
    void parseUnitFloatClampsAndFallsBack() {
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat(null, 0.35F));
        assertEquals(0.5F, MetalFxConfig.parseUnitFloat("0.5", 0.35F));
        assertEquals(1.0F, MetalFxConfig.parseUnitFloat("7", 0.35F));
        assertEquals(0.0F, MetalFxConfig.parseUnitFloat("-3", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("NaN", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("leaves", 0.35F));
        assertEquals(0.35F, MetalFxConfig.parseUnitFloat("Infinity", 0.35F));
    }
}
```

Existing `MetalFxMathTest` and `MetalShaderLodBiasTest` are unaffected
(`cutoutReactiveRadius` and the LOD-bias patcher are untouched).

## 10. Build and validation protocol

Toolchain per `docs/metalfx-validation.md`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew clean test buildMacNative build --no-daemon
```

A/B flicker comparison (both runs write
`build/metal-validation/<dir>/flicker-cutout_grass_hold.json`):

Run A — legacy policy, lenient assertions:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew runClient --no-daemon \
  --args='--quickPlaySingleplayer "New World"' \
  -Dmetallum.metalfx.mode=TEMPORAL -Dmetallum.metalfx.scale=0.67 \
  -Dmetallum.metalfx.debug=true -Dmetallum.validation.enabled=true \
  -Dmetallum.validation.output=build/metal-validation/legacy-policy \
  -Dmetallum.validation.lenient=true \
  -Dmetallum.metalfx.cutoutReactiveEdgeWeight=1.0 \
  -Dmetallum.metalfx.cutoutReactiveInteriorWeight=1.0 \
  -Dmetallum.metalfx.depthEdgeReactiveCap=1.0 \
  -Dmetallum.metalfx.transparencyReactiveValue=1.0 \
  -Dmetallum.metalfx.stableCutoutAlpha=false
```

Run B — new defaults, strict assertions:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew runClient --no-daemon \
  --args='--quickPlaySingleplayer "New World"' \
  -Dmetallum.metalfx.mode=TEMPORAL -Dmetallum.metalfx.scale=0.67 \
  -Dmetallum.metalfx.debug=true -Dmetallum.validation.enabled=true \
  -Dmetallum.validation.output=build/metal-validation/new-policy
```

## 11. Acceptance criteria

1. `./gradlew test` passes; `buildMacNative` compiles the Swift (all four MSL
   kernels build — `supports_cutout_reactive` would return 0 on MSL compile
   failure and run B would log the CUTOUT fallback warning; treat that as a
   failure).
2. Run B passes all 10 capture scenarios with the new invariants
   (`cutoutInteriorViolations == 0`, `cutoutEdgeBandReactivePixels > 0`).
3. Flicker: run B `maskedMeanDelta` ≤ **50%** of run A's (target; record the
   actual ratio in the handoff notes). Run B `controlMeanDelta` within
   **±15%** of run A's (outside-mask behavior must not regress; small jitter
   in this number is expected because sky pixels remain fully reactive).
4. No new warnings of the form "CUTOUT reactive coverage failed closed" or
   "MetalFX encode failed" in either run's log.
5. In-game spot check on the deployed JAR (§12): foliage shimmer reduced;
   strafe past a tree at close range and confirm no leaf↔sky edge smear
   (ghosting) has been reintroduced. If smear appears, raise
   `cutoutReactiveEdgeWeight` toward 0.5 and/or `depthEdgeReactiveCap`
   toward 0.7 — do not go back to 1.0.

## 12. Deploy and rollback

Deploy (same JAR to both instances):

```sh
cp build/libs/metallum-1.0.1.jar \
  "$HOME/Library/Application Support/minecraft/instances/MinecraftMetal-Current-2026-07-26/mods/metallum-1.0.1.jar"
cp build/libs/metallum-1.0.1.jar \
  "$HOME/Library/Application Support/minecraft/instances/MetalUniversal-26.2/mods/metallum-1.0.1.jar"
```

(If `build/libs` holds a remapped variant, deploy the same artifact name that
is currently in the instance `mods/` directory: `metallum-1.0.1.jar`.)

Rollback without rebuilding — add to the instance `javaArgs`:

```
-Dmetallum.metalfx.cutoutReactiveEdgeWeight=1.0 -Dmetallum.metalfx.cutoutReactiveInteriorWeight=1.0 -Dmetallum.metalfx.depthEdgeReactiveCap=1.0 -Dmetallum.metalfx.transparencyReactiveValue=1.0 -Dmetallum.metalfx.stableCutoutAlpha=false
```

## 12a. Acceptance record (2026-07-27, M1 Pro 16GB, macOS 26.5.1)

Implemented as specified (plus two field fixes folded back into §6.9/§8:
interior-violation counting excludes disoccluded pixels, and the flicker
metric writes exactly once). `./gradlew test buildMacNative build` clean;
`MetalFxReactiveTuningTest` passes.

A/B flicker runs, 1708×960 output, 0.67 scale, 24-frame static hold,
maskPixels=292,961, both runs 10/10 captures, zero encode/fallback warnings,
byte-stable across repeat runs:

| Metric (cutout mask region) | Legacy policy (all knobs 1.0, stableAlpha off) | New defaults | Change |
|---|---|---|---|
| maskedMeanDelta | 1.3884 | 0.6368 | **−54.1%** |
| maskedP95Delta | 8 | 3 | **−62.5%** |
| controlMeanDelta (outside mask) | 0.1416 | 0.1282 | −9.5% (improved) |
| controlP95Delta | 1 | 1 | unchanged |

Strict invariants on the new policy: `cutout_leaves` 144,067 interior pixels,
0 violations, 42,411 edge-band pixels; `cutout_grass` 109,312 interior,
0 violations, 21,867 edge-band. Acceptance criteria §11(1)–(4) met; §11(5)
(in-game spot check on the deployed JAR) is the remaining human step.

Deployed `metallum-1.0.1.jar`
(SHA-256 `a28e651e4400d9e8df2eec674c0b42b7aa41744f33b74ed233bd8f6f0a883c91`,
embedded dylib `7d12dc763f90ecdd4c2be60fb7814b39bcb522585dddf76cc3d97030a1660ad4`)
to both instances' `mods/`.

## 14. Follow-up (2026-07-27b): sky far-plane motion + full reactive-writer audit

In-game testing after §12a confirmed leaves stopped shimmering but geometry
silhouetted **against the sky** (tree tops, vines) still strobed. Root cause:
sky pixels (cleared reversed-Z depth) were set `reactive = 1.0` **and**
`disocclusion = 1.0` every frame in the camera kernel, and the merge kernel's
reprojection test re-flagged the whole sky as disoccluded every frame
(`!validDepth(currentDepth)` → `disocclusion = 1.0` → `reactive = 1.0`).
Silhouette reconstruction needs history on both sides of an edge, so the
sky-side suppression kept the boundary band strobing regardless of the
cutout-side policy. Vines are fully inside Sodium's single CUTOUT pass
(verified: Sodium 0.9 defines exactly SOLID / CUTOUT(discard) / TRANSLUCENT),
so their coverage was never the problem — a 1-2 px wide strip is 100%
edge-band with sky behind it.

Fix (`metallum.metalfx.skyFarPlaneMotion`, default `true`, `false` = legacy):
cleared-far-plane pixels reconstruct camera motion at a substituted far depth
(`0.00002`) in both motion kernels — rotation produces correct flow,
translation is negligible at the far plane — and the merge reprojection
treats sky-onto-sky as valid history while geometry-onto-previous-sky and
sky-onto-previous-geometry still flag disocclusion. The tuning setter gained
a fifth argument (`skyFarPlaneMotion` 0/1) threaded through
`MetalNativeBridge`/`MetalFxConfig` like the others; `MotionUniforms.flags.y`
and a new `MergeUniforms.flags.x` carry it to the kernels.

Also from the audit: the transparency mask now writes the actual compositing
strength (`clamp(coverage,0,1) × transparencyReactiveValue`) instead of a
binary presence bit, per FSR2's "write alpha" guidance — faint rain streaks
and cloud wisps no longer take a full 0.9 suppression.

Complete reactive-writer audit (post-change status):

| # | Writer (kernel : condition) | Value | Standing or transient | Verdict |
|---|---|---|---|---|
| 1 | camera v1/v2 : cleared far plane (sky) | was 1.0 | was standing every frame | **fixed** — far-plane motion, accumulates |
| 2 | merge : sky reprojection (`!validDepth(current)`) | was 1.0 via disocclusion | was standing | **fixed** — sky-onto-sky valid |
| 3 | camera v1/v2 : depth-edge heuristic | ≤ depthEdgeCap (0.5) | standing on silhouette band | capped §6.4; knob |
| 4 | dilation : cutout edge band / interior | 0.35 / 0.0 | standing on band | by design §6.2; knobs |
| 5 | transparency : translucent/itemEntity/particles/weather/clouds | alpha × 0.9 | standing where drawn | refined to alpha-proportional; knob |
| 6 | hand overlay : first-person coverage | max(existing, 0.35) | standing on hand | intended (swing/bob lacks per-vertex motion) |
| 7 | camera v1/v2 : reconstruction failures (`w≈0`, non-finite) | 1.0 | transient, degenerate frames | correct guard, keep |
| 8 | camera v2 : previous position offscreen / motion overflow (>32) | 1.0 | transient at screen edges during fast rotation | correct (no history exists), keep |
| 9 | merge : reprojection depth mismatch (true disocclusion) | 1.0 | one-frame transients on reveals | correct, keep |
| 10 | merge : object-motion invalid/overflow | 1.0 | transient guard | correct, keep |

After #1/#2/#5, no standing full-suppression writer remains; every remaining
1.0 is a transient guard on pixels that genuinely have no usable history.

**Row 9 turned out to be wrong.** See §15 — "one-frame transients on reveals"
is true for a translating camera, but under sub-pixel jitter a *static*
silhouette re-triggers it on alternating frames, which made it a standing
writer in disguise. It was the dominant remaining cause.

## 15. Follow-up (2026-07-27c): the harness could not see the bug

### 15.1 Why §14 measured as a no-op

The §14 sky far-plane change validated as **bit-for-bit identical** to the
run before it (maskedMeanDelta 0.636798 → 0.636861). The cause was not the
change: the validation scene is a **sealed stone room**, built deliberately
in `installSceneClearing` so weather, distant terrain and drifting particles
cannot break byte-identical golden captures. Dumping `depth.bin` from the
`cutout_grass` capture gives a depth range of 0.0062–0.0355 and **zero**
cleared-far-plane pixels. The sky code path was never executed. A metric that
cannot reach the reported defect will report every candidate fix as a no-op.

### 15.2 Sky-visible scene and sky-edge statistic

`MetalValidationClient` gained a second hold. At frame 118
(`SKY_SCENE_FRAME`, a scene-mutation frame so the section builder drains
first) `installCutoutSkyScene` opens the room ceiling, clears anything above
it — a no-op where the world is already open air — and suspends a
half-filled checkerboard of persistent oak leaves with `VINE` blocks
(all four faces set, so the quads render free-standing) threaded through the
odd cells. The checkerboard maximises silhouette edge per block. The camera
pitches to `SKY_SCENE_PITCH = -50°`: with a 70° vertical FOV the view spans
-15°..-85°, keeping the horizon and any distant terrain out of frame, so only
sky backs the foliage. Random ticks, weather, daylight and clouds are already
pinned by `applyDeterministicWorldState`, so the hold is static. Frames
128–151 are the `cutout_sky_hold` flicker series.

`writeFlickerMetrics` gained three fields. Sky is classified from the same
cleared-far-plane test the motion kernels use (`≤ 0.00001`, reversed-Z), and
**sky-edge** is a *subset* of the existing mask — CUTOUT coverage *and* sky
in the same 3×3 render neighbourhood — so `maskedMeanDelta` stays comparable
with every earlier run:

```
"skyPixels": 174534,        // 0 here is the tell that the scene has no sky
"skyEdgePixels": 88676,
"skyEdgeMeanDelta": 10.710036,
"skyEdgeP95Delta": 39
```

`histogramMean` now returns 0 rather than `NaN` for an empty histogram; `NaN`
is not valid JSON and made the grass-hold file unparseable.

### 15.3 Jitter phase pinning

Before this, the Halton phase at the series start depended on how many frames
warm-up and terrain settling happened to render, which differs run to run and
moved both the coverage mask and the metric (`maskPixels` 292,961 vs 299,084
across builds). `setFlickerCaptureFrame` now sets `phase = 0` on the series'
first frame — it is called from the timeline tick on the render thread, before
that frame's encode. With this, two runs of the same build produce **identical**
`maskPixels` and `skyEdgePixels`, so A/B arms compare pixel for pixel.

### 15.4 Root cause of the sky-border strobe

With the sky scene in place the band measured **10.90 mean / p95 39**, against
0.20 for the same scene's non-cutout control — 53×. §14's sky motion fix was
already enabled and did not help, because it addressed the wrong writer.

`metallum_motion_merge_v2` ended with:

```metal
if (!isfinite(disocclusion) || disocclusion > 0.5) reactive = 1.0;
```

and probed the previous depth with a single nearest-neighbour sample,
`uint2(previousPixel)`. At a foliage/sky silhouette the sub-pixel jitter moves
the edge by up to ±0.5 px per frame, so that probe lands on the *other side*
of the edge on alternating frames. Leaf depth (~0.01) against sky (0.00002)
always clears the `max(0.0025, |d|·0.01)` threshold, so the pixel is flagged
disoccluded — and slammed to `reactive = 1.0`, the exact full-suppression
value this whole remediation exists to remove. The silhouette therefore threw
away its history every other frame. Same defect class as the original, one
layer further down the pipeline.

### 15.5 Fix

Two changes, both knobbed:

1. **Depth dilation** (`metallum.metalfx.mergeDepthDilation`, default `true`).
   The reprojection probes a 3×3 neighbourhood and keeps the sample whose
   depth is closest to the current pixel's, applying the sky substitution per
   probe. `radius = 0` reproduces the legacy single probe exactly. A genuine
   reveal — geometry over previous-frame sky with nothing closer nearby —
   still flags disocclusion, so §14's semantics survive.
2. **Disocclusion reactive cap** (`metallum.metalfx.disocclusionReactiveCap`,
   default `0.85`). `reactive = max(reactive, cap)` instead of `1.0`, matching
   the policy already applied to the CUTOUT edge band and the transparency
   mask. A disoccluded pixel still biases hard toward the current frame but
   leaves the accumulator a share.

The tuning setter went from five arguments to seven; `MergeUniforms` gained
`flags.y` (dilation) and `params.x` (cap).

### 15.6 Measured

Both arms are the same build, differing only in the two knobs, and produced
identical masks (`maskPixels` 299,624 / 271,748, `skyEdgePixels` 88,168):

| Metric | legacy (cap 1.0, no dilation) | fixed (cap 0.85 + dilation) | Δ |
|---|---|---|---|
| sky hold, **silhouette band mean** | 10.9038 | **6.1804** | **−43.3%** |
| sky hold, **silhouette band p95** | 39 | **21** | **−46.2%** |
| sky hold, whole cutout mask mean | 4.5725 | 2.8557 | −37.5% |
| sky hold, whole cutout mask p95 | 25 | 14 | −44.0% |
| sky hold, control (non-cutout) | 0.2005 | 0.1485 | −25.9% |
| grass hold, mask mean | 0.9684 | 0.6096 | −37.0% |
| grass hold, mask p95 | 5 | 3 | −40.0% |

Both arms: 10/10 GPU captures, including the `occluded_entity` and
`revealed_entity` disocclusion contracts. The control band improving too
confirms the 1.0 write was over-suppressing well beyond the cutout mask.

### 15.7 Offscreen suite assertion updated

`MetalFXOffscreenValidation.swift`'s `alpha_test` scenario asserted that
**every** CUTOUT coverage pixel carries `reactive > 127`. That invariant is
the pre-remediation full-suppression policy written down as a test: it only
held because every coverage pixel in the synthetic scene was disoccluded and
therefore 1.0. With dilation those pixels find valid history and correctly
drop to the edge (0.35) or interior (0.0) weight. Replaced with the two
assertions that express the current contract — the silhouette band still
carries reactivity (`≥ 72` on at least one coverage pixel), and no coverage
pixel reaches full suppression (`> 224/255`, above the 0.85 cap and below
1.0). This is a deliberate contract change, not a threshold relaxation.

### 15.8 Still open

- **Transparency `targetActivity` proportionality is unverified.** The sealed
  room and the sky scene contain no water, glass or particles, so no run has
  exercised it. The semantics follow FSR2's "write the compositing strength"
  guidance and solid content whose target alpha or colour reads near 1 still
  lands at ~0.9, but the claim is argued, not measured.
  `-Dmetallum.metalfx.transparencyReactiveValue=1.0` restores binary behaviour.
- **`skyFarPlaneMotion` has no isolated A/B.** It is on by default and was on
  in both §15.6 arms; its individual contribution was never separated because
  the first attempt to measure it was lost to a concurrent-build failure.
- ~~The residual 6.18 mean on the silhouette band is still ~30× the scene's
  control. Sky/foliage contrast is far higher than the sealed room's, so some
  of that is expected, but it has not been decomposed.~~ **Closed by §16**: the
  residual is the reactive edge band itself. Zeroing both band producers takes
  the same statistic to 0.4039 against a 0.0801 control (5×), so essentially all
  of it was reactivity, not scene contrast. Whether to spend it is a
  flicker-vs-ghosting trade the static-hold harness cannot judge — see §16.4.

## 16. Follow-up (2026-07-27d): the knob sweep, and what actually gates the run

### 16.1 The client could not start for the whole preceding window

Between `070cc40` and `c82bdaf` the client crashed in `GameRenderer.<init>` on
every launch. `MovingBlockFeatureRendererMetalFxMixin` (object-motion line) took
`@Redirect` on the `tesselateBlock` invoke inside `buildGroup`;
`fabric-renderer-api-v1` redirects that same invoke. `@Redirect` is exclusive, so
mixin applied metallum's, skipped fabric's, and fabric's redirector then failed
its own injection check (`0/1 succeeded`) — a fatal `InjectionError`. Fixed by
switching to MixinExtras `@WrapOperation`, which is built to compose; the
try/finally contract the redirect existed for is unchanged.

This matters for reading history: **any "L3 red" observed in that window is
uninformative — not a single frame rendered.** Five other `@Redirect` mixins
remain in the tree and carry the same latent failure mode if their targets ever
overlap a fabric/Sodium redirect.

### 16.2 Sweep

Eight arms, `cutout_sky_hold` series, all reporting `skyEdgePixels = 123904` and
therefore comparable pixel-for-pixel (§15.3's phase pinning holds). Arms
`probe3`–`eb010` were run by the preceding session; `eb020r` and `zero` are the
two combined arms it never landed.

| arm | `depthEdgeReactiveCap` | `cutoutReactiveEdgeWeight` | skyEdgeMean | P95 | maskMean | control |
|---|---|---|---|---|---|---|
| probe3 | 0.5 (default) | 0.35 (default) | 5.0099 | 17 | 2.5262 | 0.1888 |
| dc025 | 0.25 | 0.35 | 3.6094 | 12 | 1.9468 | 0.1448 |
| dc010 | 0.10 | 0.35 | 3.6062 | 12 | 1.9455 | 0.1151 |
| dc000 | 0.00 | 0.35 | 3.5962 | 12 | 1.9358 | 0.0900 |
| eb020 | 0.5 | 0.20 | 2.6998 | 9 | 1.4082 | 0.1414 |
| eb010 | 0.5 | 0.10 | 2.6744 | 9 | 1.2913 | 0.1398 |
| **eb020r** | **0.25** | **0.20** | **2.7020** | **9** | **1.4093** | **0.1415** |
| **zero** | **0.00** | **0.00** | **0.4039** | **1** | **0.2791** | **0.0801** |

### 16.3 What the sweep says

1. **The two knobs are not additive; they are nearly interchangeable, and both
   saturate immediately.** Holding edge at 0.35, dropping the cap 0.5→0.25 buys
   −28% and then nothing (0.25→0.10→0.00 moves the mean by 0.013). Holding the
   cap at 0.5, dropping edge 0.35→0.20 buys −46% and then nothing. `eb020r`
   (0.25 + 0.20) lands at 2.7020 against `eb020`'s (0.5 + 0.20) 2.6998 — a 0.08%
   difference, i.e. the cap contributes nothing once the edge weight is down.
   **Lowering either knob below its saturation point is wasted range.**
2. **Only the exact-zero corner releases the band.** Every arm with any nonzero
   reactivity on the silhouette sits at 2.7–5.0; `zero` drops to 0.4039 with P95
   1. The magnitude of a nonzero reactive value barely matters — its *presence*
   costs roughly 2.3 units of flicker. This is consistent with Apple's stated
   semantics (>0 biases toward the current frame) being sharply nonlinear near 0,
   and it is why the earlier single-knob arms all plateaued.
3. **§15.8's undecomposed residual is now decomposed.** The band's floor is
   0.4039 against a same-scene control of 0.0801 — 5×, not the ~30× recorded in
   §15.8. The residual was the reactive edge band itself, not an unexplained
   term.
4. **The response is not linear, so it must not be extrapolated.** A parallel
   analysis of the same arms proposed a linear law and predicted further material
   gains at (0.10, 0.10), with the zero point extrapolated rather than measured —
   three attempts to measure it were lost to the §16.1 startup crash. The
   measured points refute a linear reading: 0.20→0.10 on the edge weight moves
   the mean by 0.9% (2.6998→2.6744) and 0.5→0.25 on the cap moves it by 0.08%
   (2.6998→2.7020), while (0, 0) drops 85%. The shape is a plateau with a cliff
   at exactly zero, not a slope. Any predicted intermediate gain between 0.20 and
   0.00 is an artifact of fitting a line to a step.

### 16.4 Why this does not simply become the new default

`zero` is the best flicker number available and the worst ghosting posture
available: it removes the anti-ghosting band this remediation deliberately kept
(§4, §13). The harness measures a **static hold** and therefore cannot see
ghosting at all — it has no arm in which the trade is visible. Picking the
default is exactly acceptance criterion §11(5), the in-game strafe-past-a-tree
check, and it stays a human step. What the sweep does settle is the *shape* of
the choice:

- Anything in 0.10–0.35 for the edge weight is within 1% of the same flicker, so
  **prefer the high end of that range** — it is free protection.
- `depthEdgeReactiveCap` below 0.25 is inert; leave it at 0.25–0.5.
- The only decision with real flicker consequence is **band or no band**.

Recommendation, pending §11(5): keep the current defaults (0.5 / 0.35) or move to
(0.25 / 0.20) — the latter is −46% flicker for a band that is still 0.20 wide.
Do not ship `zero` without the in-game ghosting check.

### 16.5 The CUTOUT acceptance criteria were never the blocker

Both L3 runs pass `cutout_leaves` and `cutout_grass` at every knob setting
tested, including `zero`:

| scenario | coverage px | interior px | interior violations | edge-band reactive px |
|---|---|---|---|---|
| `cutout_leaves` | 87,581 | 51,712 | 0 | 93 |
| `cutout_grass` | 72,370 | 51,347 | 0 | 24 |

They pass at every setting, but for a reason that has to be stated plainly,
because it is a defect in the assertion rather than a property of the code. Per
arm, on `cutout_leaves`:

| arm | edgeWeight | cap | `cutoutEdgeBandReactivePixels` |
|---|---|---|---|
| probe3 | 0.35 | 0.5 | 62,802 |
| dc000 | 0.35 | 0.00 | 62,802 |
| eb020 | 0.20 | 0.5 | 93 |
| eb020r | 0.20 | 0.25 | 93 |
| zero | 0.00 | 0.00 | 93 |

`EDGE_REACTIVE_MIN` is 72, i.e. 0.282 in normalized terms. An edge weight of 0.35
writes 89/255 and clears it; 0.20 writes 51/255 and does not. So the count
collapses from 62,802 to 93 the moment the weight crosses that threshold — and
those 93 are then **identical at 0.20, at 0.10 and at 0.00**, because they are
supplied by the disocclusion cap (0.85 = 217/255, §15.5), not by the band.

Consequence: `cutoutEdgeBandReactivePixels > 0` is a real assertion at today's
0.35 default and a **vacuous** one at any weight below 0.282 — it would pass with
the band switched off entirely. `depthEdgeReactiveCap` never contributes to it at
all (dc000 at cap 0.0 still reports 62,802). **Any change of the default edge
weight below 0.282 must lower `EDGE_REACTIVE_MIN` in the same commit**, or the
invariant silently stops testing the thing it was written to test.

The interior-violation half of the contract is unaffected — it is a
`reactive > 48` test on interior pixels and stays meaningful across the range.

The gate's two red scenarios are `item_spin` and `minecart_rail`, both owned by
the object-motion line, and both failing only `OBJECT_MIN_SPIN_SPREAD_X = 0.008`:
`item_spin` measures X-spread 0.004677 (Y 0.0078125), `minecart_rail` measures
[0.001292, 0.007355]. Both are marginal misses against a floor introduced by
`5504828`. **The S9B/C and S10 enabled-state acceptance is therefore not blocked
on this line.**

## 13. Out of scope / known limitations

- Sky pixels stay fully reactive (no sky motion vectors); invisible on
  near-uniform sky, and out of scope here.
- First-person held items, block entities, and modded shader paths still lack
  object motion (`OBJECT_MOTION_PRODUCER_CONNECTED = false`) — unchanged.
- Atlas-level coverage-preserving alpha mips (Castaño) would further stabilize
  far-distance foliage density; deliberately not part of this change (touches
  vanilla/Sodium sprite mip generation). Revisit only if far-field density
  breathing remains objectionable after this lands.
- The reactive edge band slightly reduces temporal refinement exactly on
  cutout silhouettes; that is the intended trade against edge ghosting.
