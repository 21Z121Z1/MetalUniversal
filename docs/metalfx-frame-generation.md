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

Object motion is produced per draw by splitting an entity's geometry out of the
batched feature-renderer draw and replaying it through
`metallum:core/entity_motion` (`MetalEntityMotionCapture`,
`MetalEntityMotionPipeline`). Two Minecraft 26.2 pipeline families reach it:
`core/entity` (entity models) and `core/item` (dropped items, item frames, held
items). Both share `DefaultVertexFormat.ENTITY` and the same
`ProjMat * ModelViewMat * Position` clip transform, so one reduced shader
replays both.

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
and idempotent release. The current native test reports 9 passed.

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
GPU completion time
drawable presentedTime
drop/cancel/failure reason
```

After three warm-up sources, the latest clean run accepted 10 measured source
frames, presented 10 real frames and 9 generated frames, exercised resize and
shut down in 0.0048 seconds. Three consecutive pre-clean repetitions also
passed with bounded shutdown. Startup drawables whose presented timestamp was
zero remained classified as failures.

The current artifact is
`build/metal-validation/presentation-current/timeline.json`.

## GUI and scene policy

Opening a screen or overlay suspends frame generation and cancels work through
the lifecycle state machine. Closing it resets temporal/interpolator history.
Resize and world/history reset similarly invalidate source history. The GUI is
not independently interpolated; the presenter receives the pre-GUI scene and
the composed UI texture with the UI-composited contract.

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
- Falling blocks and block entities render through `core/block` and would need a
  second motion pipeline family; they currently reach the interpolator with
  translation-only or no object motion.
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
