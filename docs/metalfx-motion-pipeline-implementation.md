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

## Automated renderer evidence

`minecraftMetalFxClientValidation` launches an integrated Minecraft client,
loads a fixed test world, places and moves controlled entities, advances a
deterministic sequence, captures GPU textures before present and exits without
manual input or system screenshots.

The latest clean-source run passed all eight captures:

| Capture | Object validity pixels | Depth pixels | Object-region disocclusion | Motion comparison |
| --- | ---: | ---: | ---: | --- |
| fixed camera + static entity | 6,249 | 6,249 | 175 | error 0.0000109 |
| fixed camera + moving entity | 6,214 | 6,214 | 234 | error 0.0024578 |
| moving camera + static entity | 6,209 | 6,209 | 188 | error 0.0000739 |
| camera and entity moving | 6,225 | 58,381 | 262 | error 0.0025196 |
| entity occluded | 0 | 379,611 | 0 | no false object validity |
| entity revealed | 6,201 | 113,311 | 6,201 | error 0 |
| GUI | 6,181 | 122,742 | 14 | error 0 |
| scene reset | 0 | 125,291 | 0 | history invalidated |

The expected object motion is calculated from the known current and previous
transforms and compared numerically. The artifact is
`build/metal-validation/minecraft-client-current/run-state.json`.

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
| Cutout foliage | depth-edge/reactive policy; no animation motion | partial |
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
motion, object motion, validity, merged motion, disocclusion, reactive,
Temporal output, interpolated output, directly rendered midpoint ground truth,
difference images and JSON metrics for eight scenarios.

## Fail-closed gate

The gate may not be opened until all required object categories, the complete
runtime matrix, API validation and presentation acceptance criteria pass.
Current source intentionally contains:

```java
OBJECT_MOTION_PRODUCER_CONNECTED = false;
```

Do not reinterpret a successful ordinary-entity slice, a zero vector, a
reactive fallback or an offscreen MetalFX encode as full producer coverage.
