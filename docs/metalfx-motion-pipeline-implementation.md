# MetalFX Motion Pipeline Implementation

Status: partially implemented and fail-closed, verified against the 2026-07-26
source tree. Frame Generation remains disabled because object-motion category
coverage is not complete.

## Current pipeline

```text
Java RenderPassDescriptor (up to 8 indexed color slots)
  -> MetalCompiledRenderPipeline attachment metadata
  -> Java FFM indexed V2 ABI
  -> Swift MTLRenderPassDescriptor / MTLRenderPipelineDescriptor
  -> Minecraft color + object motion + object validity MRT
  -> preserved world depth
  -> camera motion reconstruction
  -> object/camera merge + disocclusion + reactive
  -> MTLFXTemporalScaler
  -> pre-GUI display-resolution scene
  -> GUI composition
  -> ordinary present
```

The indexed backend retains null attachment slots rather than compacting the
array. It carries per-slot load/store/clear state and pipeline format, blend and
write-mask state. The legacy one-color ABI remains available for compatibility.

`metalMrtBackendIntegrationTest` exercises the real repository path from Java
descriptor construction through FFM and Swift Metal encoding to GPU readback.
It covers 1, 2, 3 and 8 slots, a middle null slot, `RGBA8_UNORM`,
`RG16_FLOAT`, `R8_UNORM`, per-slot clear/load/store/blend/write masks,
render-pass/pipeline mismatches, fragment-output mismatches and both ABI
versions. Its current result is 10 tests passed.

## Motion contract

All producers and consumers use:

```text
motion = previous unjittered top-left screen position
       - current unjittered top-left screen position
```

The motion texture stores NDC delta. MetalFX receives
`motionVectorScale = (inputWidth / 2, inputHeight / 2)`. Therefore:

```text
x = previousNdc.x - currentNdc.x
y = currentNdc.y - previousNdc.y
```

The Y sign converts Metal clip-space Y-up to top-left screen coordinates.
Rasterization uses the jittered projection; motion uses current and previous
unjittered projections. Jitter is excluded from object motion.

Validity is independent from the vector:

- a covered, static object writes `motion=(0,0), validity=valid`;
- a pixel without an object producer writes `validity=invalid`;
- invalid, non-finite or implausible object motion is rejected and does not
  override the camera fallback.

## Connected ordinary-entity producer

The first vertical slice is connected to the Minecraft 26.2 ordinary entity
draw path:

```text
entity UUID + live object identity generation
  -> current/previous entity render transform
  -> staged entity geometry replay
  -> object-motion RG16_FLOAT + validity R8_UNORM MRT
  -> camera/object merge
  -> disocclusion/reactive
  -> Temporal consumer and validation readback
  -> previous-state commit after successful GPU submission
```

The store does not associate history using a reusable integer entity ID alone.
Pending current state is promoted only by the command-buffer success callback.
Cancelled or failed frames, history resets and scene changes discard pending
state.

The motion replay preserves the entity vertex layout, including color at
attribute 1 and UV0 at attribute 2. This matters because shifting UV0 would make
alpha-test coverage diverge from the color pass. The motion shaders use the
same staged geometry coverage so discarded/cutout pixels do not become valid
motion pixels.

## Depth, merge, disocclusion and reactive inputs

Minecraft clears `mainRenderTarget.depth` before the first-person hand stage.
The manager therefore copies completed world depth into a persistent
`D32_FLOAT` scene-depth texture immediately before that clear. Temporal
upscaling, frame interpolation input and validation capture consume the
preserved world depth.

Camera motion is reconstructed from depth and current/previous unjittered
camera matrices. Valid finite object motion overrides it only where validity is
set. Previous-depth comparison produces disocclusion rejection. Non-finite or
out-of-contract motion, reset frames and uncovered dynamic content fall back
or reject history rather than manufacturing zero motion.

The reactive pass consumes Minecraft's separate translucent terrain, item
entity, particle, weather and cloud targets when available, plus depth and
motion rejection signals. This is a conservative reactive policy; it is not a
claim that every translucent or vertex-animated material has true motion.

Alpha-tested Sodium terrain (leaves, grass and every other material in a
non-translucent fragment-discard terrain pass) additionally writes exact
post-discard coverage to a separate `R8_UNORM` MRT attachment. The custom
fragment shader duplicates Sodium's atlas sampling and performs the same
`ALPHA_CUTOUT=0.5` discard before writing both outputs, so a discarded color
sample can never write coverage. A native compute pass then dilates that exact
coverage by `ceil(max(abs(jitter)) + max(0, 1/renderScale - 1))` clamped to
radius 3 and max-merges it into the final reactive mask. The coverage
attachment stays separate from the reactive mask, so the Sodium render pass
and the merge compute pass never share write ownership of one texture. This is
selected per terrain pass (`supportsFragmentDiscard() && !isTranslucent()`),
not by block or material name, and it fails closed to the depth-edge fallback.

## Automated renderer evidence

`minecraftMetalFxClientValidation` launches an integrated Minecraft client,
loads a fixed test world, places and moves controlled entities and scene
blocks, advances a deterministic sequence, captures GPU textures before
present and exits without manual input or system screenshots.

Determinism relies on three mechanisms: 40 warm-up frames (50 ms each) before
the scripted timeline so initial section meshes and the controlled entity's
render section settle; prioritized synchronous Sodium section rebuilds
(`scheduleRebuildForBlockArea(..., important=true)` with the run
configuration's `chunk_build_defer_mode=ZERO_FRAMES`) after every scene block
mutation so occlusion, reveal and CUTOUT scenes are meshed on the same frame
they change; and capture frames chosen on the exact frame of one-frame
transients — the revealed-entity capture is the wall-removal frame itself,
because its disocclusion signal only exists on the reveal frame.

The latest current-source run (2026-07-26, Apple M1 Pro, Metal API Validation
enabled, TEMPORAL at 0.5 scale, 854x480 -> 1708x960) passed all ten captures:

| Frame | Capture | Object validity pixels | Object-region disocclusion | Result |
| ---: | --- | ---: | ---: | --- |
| 6 | fixed camera + static entity | 5,027 | 31 | error 0 |
| 12 | fixed camera + moving entity | 4,576 | 77 | error 0.0047722 |
| 22 | moving camera + static entity | 4,162 | 182 | error 0.0000438 |
| 32 | camera and entity moving | 4,357 | 263 | error 0.0046820 |
| 42 | entity occluded | 11 | 0 | no false object validity |
| 46 | entity revealed | 4,336 | 4,323 | full one-frame reveal, error 0 |
| 54 | GUI | 4,332 | 87 | error 0 |
| 62 | scene reset | 0 | 0 | history invalidated |
| 74 | CUTOUT leaves | 948 | 88 | coverage acceptance below |
| 82 | CUTOUT grass | 465 | 33 | coverage acceptance below |

The CUTOUT captures validate the exact-coverage contract through the real
Sodium terrain draw path against controlled `OAK_LEAVES` and `SHORT_GRASS`
scenes (saved and restored around the run):

| Frame | Exact coverage pixels | Covered pixels also reactive | Dilated reactive outside coverage | Radius |
| ---: | ---: | ---: | ---: | ---: |
| 74 (leaves) | 265,225 | 265,225 | 29,948 | 2 |
| 82 (grass) | 274,954 | 274,954 | 35,370 | 2 |

Every exactly covered pixel is contained in the final reactive mask, and the
jitter/scale-derived dilation adds reactive pixels outside exact coverage.
The expected object motion is calculated from the known current and previous
transforms and compared numerically. The artifact is
`build/metal-validation/minecraft-client-current/run-state.json`
(`expectedGpuCaptures=10`, `completedGpuCaptures=10`, `failedGpuCaptures=0`,
`status=passed`).

## Coverage matrix

| Category | Current behavior | Evidence level |
| --- | --- | --- |
| Ordinary entities | real current/previous transform, motion + validity MRT | automated client GPU readback |
| Ordinary entity feature renderers | captured by the staged entity path where they use the connected buffers | source + integration coverage; not exhaustive per feature |
| Vehicles and dropped items | may traverse ordinary entity rendering, but no dedicated acceptance cases | incomplete |
| Block entities | camera fallback/reactive only | not implemented |
| First-person hand/item | world depth is preserved before hand; no reliable hand motion producer | not implemented |
| Vanilla/Sodium static terrain | camera-from-depth fallback | automated camera-motion readback |
| CPU/vertex-animated content | conservative rejection only | not implemented |
| Cutout foliage | exact post-discard MRT coverage, jitter/scale-bounded dilation, max-merged reactive mask; no animation motion | automated client GPU readback (frames 74/82) |
| Particles/weather/clouds | graded source-target reactive policy | reactive only |
| Water/glass/translucency | reactive/history rejection where source targets exist | reactive only |
| Mod/custom shader paths | fail closed unless they satisfy the indexed backend contract | compatibility only |

The missing rows are engineering gaps, not environment limitations. For this
reason `OBJECT_MOTION_PRODUCER_CONNECTED` remains `false`.

## Validation tasks

On macOS the repository exposes:

```sh
./gradlew test
./gradlew buildMacNative
./gradlew metalMrtBackendIntegrationTest
./gradlew metalFxOffscreenValidation
./gradlew minecraftMetalFxClientValidation
./gradlew metalFrameGenerationPresentationValidation
./gradlew build
```

`metalFxOffscreenValidation` uses no layer, drawable, window or screenshot. It
renders synthetic sequences to textures and exports input color, depth, camera
motion, object motion, validity, merged motion, disocclusion, exact CUTOUT
coverage, reactive, Temporal output, interpolated output, directly rendered
midpoint ground truth, difference images and JSON metrics for eight scenarios.
The `alpha_test` scenario feeds synthetic exact post-discard coverage through
the radius-1 dilation and asserts every covered pixel stays reactive, dilation
adds reactive pixels outside exact coverage, and Temporal receives
`preserveReactiveMask=true`.

## Fail-closed gate

The gate may not be opened until all required object categories, the complete
runtime matrix, API validation and presentation acceptance criteria pass.
Current source intentionally contains:

```java
OBJECT_MOTION_PRODUCER_CONNECTED = false;
```

Do not reinterpret a successful ordinary-entity slice, a zero vector, a
reactive fallback or an offscreen MetalFX encode as full producer coverage.
