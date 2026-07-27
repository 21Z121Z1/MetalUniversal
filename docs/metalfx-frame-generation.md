# MetalFX Frame Generation

Status: presenter, object-motion producer and validation infrastructure
implemented; production gate closed pending the attended 13.4 visual/pacing QA.

Frame interpolation is a macOS 26+ path based on
`MTLFXFrameInterpolator`. The code is present and automatically testable, but
Minecraft cannot enable it while
`OBJECT_MOTION_PRODUCER_CONNECTED == false`.

To run the gate open — this is what the attended QA does, and it does not change
what ships:

```
./gradlew minecraftMetalFxClientValidation \
    -Dmetallum.metalfx.objectMotionProducer=true \
    -Dmetallum.metalfx.frameGeneration=true
```

`metallum.metalfx.objectMotionProducer` opens the compile-time gate;
`metallum.metalfx.frameGeneration` remains the runtime kill switch and stays the
supported way to turn the feature off after the constant is eventually flipped.

## Object-motion coverage

Object motion is produced per draw by splitting an object's geometry out of the
batched feature-renderer draw and replaying it through a reduced motion shader
(`MetalEntityMotionCapture`, `MetalEntityMotionPipeline`). A family is a group of
Minecraft pipelines whose clip position one reduced shader can reproduce;
membership is decided by that transform and nothing else, because a shader that
reconstructs the wrong clip position yields motion vectors that look plausible
and are wrong.

| Family | Minecraft pipelines | Format | Clip position | Replayed by |
| --- | --- | --- | --- | --- |
| `ENTITY` | `core/entity`, `core/item` | `ENTITY` | `ProjMat * ModelViewMat * Position` | `metallum:core/entity_motion` |
| `BLOCK` | `core/block` | `BLOCK` | `ProjMat * ModelViewMat * (Position + ModelOffset)` | `metallum:core/block_motion` |

`core/entity` carries entity models and `core/item` dropped items, item frames and
held items; they share a format and a transform, so one shader replays both.
`core/block` needs its own because of the `ModelOffset` term. That uniform lives in
the shared `DynamicTransforms` block the source pipeline already binds, so the
reduced shader reads the value the color pass used.

Two renderers emit `core/block` geometry, via `submitMovingBlock`:
`FallingBlockRenderer`, whose submits are bracketed by
`MovingBlockFeatureRendererMetalFxMixin` and do produce object motion; and
`PistonHeadRenderer`, which is a block entity and has no motion producer yet — see
Known limits.

The root object-to-world transform is rebuilt by `MetalEntityObjectPose`, which
mirrors each renderer's transform order. Covered today:

| Category | Transform reproduced |
| --- | --- |
| Living entities | `T(pos) * R_y(180 - bodyRot)` |
| Dropped items | `T(pos) * T_y(bob) * R_y(spin)` |
| Minecarts (new behavior) | `T(renderPos) * R_y(yRot) * R_z(-xRot) * T_y(0.375)` + hurt shake |
| Minecarts (old behavior) | rail-sampled position and orientation, then `T_y(0.375) * R_y(180 - yaw) * R_z(-xRot)` + hurt shake |
| Boats | `T(pos) * T_y(0.375) * R_y(180 - yRot)` + hurt shake + bubble tilt |
| Arrows and tridents | `T(pos) * R_y(yRot - 90) * R_z(xRot)` |
| Everything else | translation only |

Constant factors are deliberately omitted because they cancel exactly in the
`previous * inverse(current)` delta: the `(-1, -1, 1)` model flip, per-entity
seed jitter, the item cluster's deterministic copy offsets, and the item's
`-boundingBox.minY + 1/16` lift. `MetalEntityObjectPoseTest` asserts the lift
cancellation rather than assuming it.

Limb, hand and other in-model animation is not covered by a root transform and
relies on disocclusion rejection, as before.

## Source-frame lifecycle

The presenter uses an explicit reducer-backed state machine:

```text
queued
  -> active
  -> GPU-submitted
  -> real-present-pending
  -> presented

queued/active -> cancelled
submitted states -> failed or drained
terminal state -> released
```

The central invariants are:

- each source ownership token is released exactly once;
- work not submitted to the GPU can be cancelled immediately;
- submitted work is retained until its completion path is safe;
- generated and real output cannot overtake another source pair;
- drawable skip, command-buffer error, resize, GUI suspension and shutdown all
  have terminal recovery paths;
- `presentedTime == 0` is a failed presentation, not success;
- duplicate callbacks and duplicate release requests are idempotent.

The pure lifecycle tests cover normal generated-to-real ordering, GUI suspend,
resize, shutdown after enqueue, shutdown after generated submit, shutdown after
real submit, command-buffer failure, stale display updates, duplicate callbacks
and idempotent release. The current native test reports 10 passed.

## CAMetalDisplayLink timing contract

The display-link callback supplies the only drawable used by that update. The
presenter never calls `nextDrawable()` on this path.

Each `DisplayUpdate` retains both:

- `targetTimestamp`: CPU/GPU submission deadline;
- `targetPresentationTimestamp`: source selection, animation time and
  presentation-error reference.

The path uses only:

```swift
commandBuffer.present(drawable)
```

It does not use targeted presentation, `presentAfterMinimumDuration`, a fixed
120 Hz model or a fractional hard-coded frame delay. A stale update is dropped
instead of being committed after a missed deadline.

At most one unconsumed display update is held. A new callback supersedes and
releases the prior unconsumed update so the drawable pool is not pinned. If a
source frame receives no usable update within the bounded starvation interval,
it is cancelled and its submitted GPU work is safely drained. Diagnostics are
bounded and opt-in rather than logged once per frame.

## Shutdown

Shutdown follows:

```text
stop accepting display-link callbacks
  -> atomically cancel pending/active unsubmitted work
  -> release source ownership that can no longer display
  -> wait only for submitted GPU work that must complete
  -> invalidate display link and exit worker
  -> stopped
```

It does not wait for a future presented callback after stopping has made that
callback impossible.

## Offscreen image validation

`metalFxOffscreenValidation` has no `CAMetalLayer`, `CAMetalDrawable`, window,
system screenshot or manual operation. It renders to `MTLTexture`, performs the
MRT motion pipeline, Temporal scaling and Frame Interpolation, and exports GPU
readback.

For interpolation it directly renders `t=0`, `t=0.5` and `t=1`. It feeds
`t=0` and `t=1` to MetalFX, treats the directly rendered `t=0.5` image as
ground truth, and exports the interpolated image and their difference.

Eight scenarios pass: static, translation, rotation, occlusion/reveal,
alpha-test, scene cut, illegal motion and history reset. The latest midpoint
metrics include:

| Scenario | PSNR dB | Mean absolute error |
| --- | ---: | ---: |
| static | 120.000 | 0 |
| translation | 20.275 | 0.01430 |
| rotation | 22.544 | 0.01003 |
| occlusion/reveal | 20.490 | 0.013997 |
| alpha-test | 24.700 | 0.006877 |
| scene cut | 120.000 | 0 |
| illegal motion | 24.978 | 0.004799 |
| history reset | 22.005 | 0.009561 |

The task emits 217 current-run files under
`build/metal-validation/offscreen-current`, including all requested texture
planes, PNGs, raw readbacks and JSON.

## Resolution order and GPU budget

Frame Generation uses a bounded scene-working resolution while keeping the
drawable and GUI at native backing resolution. At the 1708x960 QA size with
Temporal 67% and the default 1440-pixel Frame Generation output cap, the graph
is:

```text
Minecraft 3D 964x542
  -> MetalFX Temporal 1440x808
  -> MTLFXFrameInterpolator 1440x808
  -> linear scene scale to 1708x960 drawable
  -> premultiplied-alpha 1708x960 GUI overlay
```

The interpolator is linked to the active Temporal scaler through
`MTLFXFrameInterpolatorDescriptor.scaler`. Generated frames never enter the
Temporal history. Reversing the order would either pollute Temporal history
with synthetic frames or require running Temporal at the 120 Hz present rate.

`metallum.metalfx.frameGenerationOutputWidth` controls the cap and defaults to
1440 (bounded to 640...3840). It does not lock the persisted mode, Temporal
percentage, reactive-mask or Frame Generation UI settings. Texture LOD bias is
computed from the actual 3D/display ratio, so the extra work-resolution cap does
not silently select softer mips.

`metalFxPerformanceValidation` measures real GPU timestamps without a layer,
drawable, window or Computer Use. Apple M1 Pro results (30 measured iterations
after five warm-ups) are:

| Input -> Temporal/FG output | Temporal avg / p95 | FrameInterpolator avg / p95 |
| --- | ---: | ---: |
| 858x482 -> 1280x720 | 0.89 / 1.56 ms | 2.83 / 2.84 ms |
| 964x542 -> 1440x808 | 0.86 / 1.05 ms | 3.49 / 3.52 ms |
| 1144x643 -> 1708x960 | 1.24 / 1.30 ms | 4.81 / 4.86 ms |
| 2026x1119 -> 3024x1670 | 3.80 / 3.80 ms | 14.29 / 14.31 ms |

The 3024-wide interpolator alone consumes about 86% of a 16.67 ms source-frame
budget and cannot support 60 source -> 120 present with render or shader
headroom. At 1440, measured average Temporal plus interpolation is 4.35 ms; the
real scene-scale plus native-UI composition command buffer is about 0.24 ms,
leaving about 12.08 ms before the 60 Hz source deadline for Minecraft rendering
and shaders. This is a GPU budget, not proof of scanout cadence.

## Real presentation validation

`metalFrameGenerationPresentationValidation` creates an automated visible
AppKit window backed by a real `CAMetalLayer`. It uses
`CAMetalDisplayLink` updates and system-provided drawables; it does not use
Computer Use, screenshot APIs or manual input.

The timeline records:

```text
sourceFrameID
generated/real
displayUpdateID
targetTimestamp
targetPresentationTimestamp
CPU commit time
GPU start/end time and command-buffer duration
GPU completion time
drawable presentedTime
drop/cancel/failure reason
```

After three warm-up sources, the latest clean run accepted 10 measured source
frames, presented 10 real frames and 9 generated frames, exercised resize and
shut down in 0.0048 seconds. Three consecutive pre-clean repetitions also
passed with bounded shutdown. Startup drawables whose presented timestamp was
zero remained classified as failures.

Every run first writes `timeline-raw.json`, even when a cadence or presentation
gate fails. A passing run then writes `timeline.json`. The 120 Hz gate uses the
screen's nominal maximum refresh rather than the average of only the callbacks
the presenter happened to claim, so dropping every other update can no longer
misclassify the display as 60 Hz and skip the 55 source / 110 present floors.

## Production-gate follow-up (2026-07-27)

The gate-open Minecraft command exposed a recovery bug that the 16 attachment
readbacks did not cover. The first world resize could make the scene encode
fail after the GUI target had changed size; that one dropped source frame
permanently disabled Frame Generation, while the remaining Temporal readbacks
still reported 16/16 and made the task look green.

Scene-encode failure is now a recoverable suspension. Pending presenter work is
stopped, the frame uses the ordinary fullscreen-copy fallback, and the next
stable frame resumes Frame Generation with reset history. The client receipt
also records `frameGenerationFramesQueued` and
`frameGenerationEnabledAtCompletion`; when the Gradle command explicitly
requests Frame Generation it fails unless at least one source frame reached the
native presenter and the feature remained enabled through completion.

On the Apple M1 Pro the repaired gate-open run recovered from both startup and
GUI-transition size churn, completed 16/16 GPU readbacks, queued 255 source
frames, and ended with Frame Generation enabled. This is connectivity and
recovery evidence only. Because the automated Minecraft window ran in the
background, its presentation diagnostics reported `presentedTime == 0` and
classified those drawable presents as `not-presented`; it supplies no scanout,
smoothness, tearing or VRR evidence. The independent foreground AppKit
presentation harness still passed 10 real / 9 generated presents with a
0.0068-second shutdown in the same checkout.

## GUI and scene policy

Opening a screen or overlay suspends frame generation and cancels work through
the lifecycle state machine. Closing it resets temporal/interpolator history.
Resize and world/history reset similarly invalidate source history. During
normal gameplay the presenter receives a Temporal-upscaled scene and a separate
native-resolution transparent GUI texture. The GUI is not given to the
interpolator; the presenter composites the same sharp overlay after both the
generated and real scene paths, avoiding alternating sharp/soft text.

## Present-mode policy

`CAMetalDisplayLink` only schedules updates on the display's refresh boundary, so
the presenter is a vsync-on loop by construction and every pacing measurement was
taken that way. Minecraft can switch the surface to `MAILBOX` at any time from the
video settings, which drops `displaySyncEnabled`.

Frame generation therefore suspends — through the same mechanism as an open GUI —
whenever `MetalSurface.configure` reports the immediate present mode, and resumes
when VSync comes back. As a backstop the presenter also owns
`displaySyncEnabled` and `allowsNextDrawableTimeout` for its whole lifetime:
`metallum_configure_layer` defers both to the presenter instead of writing them
from the render thread, the presenter restates them from inside the display-link
callback after a present (the apply-after-present rule), and `shutdown()` hands
the layer back in the mode the game asked for. Before this, a resize silently
reset `allowsNextDrawableTimeout` to false, which is exactly the setting that
keeps a hidden or minimized window from blocking shutdown forever.

## Known limits

- Production Frame Generation remains disabled pending the attended visual and
  pacing QA in the audit's 13.4 matrix, not for lack of an object-motion
  producer.
- The gate-open Minecraft integration receipt proves enqueue and recovery, not
  display scanout. Its background run had no nonzero drawable presented time;
  the attended foreground matrix remains mandatory.
- Piston-moved blocks reach the interpolator with no object motion.
  `PistonHeadRenderer` submits two moving blocks through the same `core/block`
  family that now carries falling blocks, so the shader side is in place, but the
  sample never gets attached: block entities are dispatched by
  `BlockEntityRenderDispatcher`, not `EntityRenderDispatcher`, so no entity
  submission window is open when their submits are constructed.

  What remains is only the wiring. The root transform and the identity it is keyed
  under are in `MetalBlockEntityObjectPose`: the moved block carries the
  progress-interpolated offset and the base does not, and the id comes from the block
  position because `BlockEntityRenderDispatcher` builds a fresh render state every
  frame. Producing the sample needs a block-entity entry point alongside
  `MetalFxManager.captureEntityMotion`, because the current/previous pair has to come
  from the manager's own `MetalMotionStateStore`: that store commits only once a
  frame's output has been encoded, and a second store kept elsewhere would commit on
  frames the manager discarded and hand out a previous transform that was never
  presented. A `BlockEntityRenderDispatcher` mixin then brackets the submission
  window the way `EntityRenderDispatcherMetalFxMixin` does.
- No block entity gets object motion, whichever family its geometry belongs to.
  `beginEntitySubmission` is called only from `EntityRenderDispatcher.submit`, so a
  chest or a sign rendering entity-format models through `ModelFeatureRenderer` is
  in a family that can replay it and still has no sample to replay it with. The
  block-entity entry point above is the single missing piece for all of them; the
  piston is only the case that additionally needed the `BLOCK` family.
- Display entities, item frames, paintings, armour stands and end crystals get
  translation only; their non-translation motion is left to disocclusion
  rejection.
- First-person hand/item uses the zero-motion + validity kernel rather than an
  object transform.
- The generated/real pair is spaced by exactly one display refresh, because each
  `needsUpdate` claims at most one step. When the source frame rate falls below
  half the refresh rate the pair arrives as a burst followed by a gap; the
  present diagnostics ring (`METALLUM_METALFX_PRESENT_DIAGNOSTICS=1`) is the way
  to quantify that before trusting the gate at low source frame rates.
- The automated presentation test validates the display-link submission and
  ownership timeline on the current display. It does not establish human
  smoothness, scanout tearing, VRR behavior or display migration.
- A complete 60/120 Hz and 30/40/60 FPS source matrix, minimize/restore,
  fullscreen and multi-display migration remains attended display validation.
- Shader validation is enabled for the project MRT pipeline. It is disabled
  only for the offscreen MetalFX private-kernel run because Apple's private
  MetalFX kernel requests an invalid 1,024-thread dispatch on this 832-thread
  device when shader validation is injected; API validation remains enabled.

These are reported separately: missing producers are engineering work, while
VRR perception and display migration are environment/attended validation.
