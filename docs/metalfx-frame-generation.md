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

Source admission is also latest-source-wins. If a newer Minecraft source reaches
the presenter while an older source is still waiting for a display-link update,
the older source is cancelled immediately. Texture ownership is reused only
after input/generated/real GPU work already submitted for that source completes;
the render thread does not wait for the 0.75-second display-starvation timeout.
Normal 120 Hz generated/real pairs are unchanged because their real command
buffer releases ownership before the next 60 Hz source arrives.
Late drawable callbacks carry history ownership tokens, so a failed callback
from a superseded source cannot reset history established by a newer source.

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

Nine scenarios pass: static, translation, rotation, occlusion/reveal,
alpha-test, scene cut, illegal motion, history reset, and steady first-person
hand fusion. The latest midpoint metrics include:

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

The task emits 225 current-run files under
`build/metal-validation/offscreen-current`, including all requested texture
planes, PNGs, raw readbacks and JSON.

## Resolution order and GPU budget

### Retina fullscreen ownership

Minecraft's ordinary macOS fullscreen path binds the GLFW window to a monitor
video mode. On the current M1 Pro test machine that changes the drawable to
1920x1200, so a nominal 50% render scale becomes 960x600 and no longer exercises
the native Retina target required by the QA goal.

The QA-only property below selects a borderless macOS fullscreen path instead:

```text
-Dmetallum.window.retinaFullscreen=true
```

The window remains a windowed Cocoa surface (`glfwGetWindowMonitor == 0`) and
covers the current monitor work area in logical coordinates. GLFW therefore
keeps the monitor's Retina backing scale: the framebuffer and CAMetalLayer
drawable use backing pixels, while the window dimensions remain logical points.
The work area is queried again on every fullscreen mode update, so the behavior
tracks display migration and is not tied to 1512x839 or any other fixed size.
Leaving fullscreen restores the original decorated window geometry. The
property defaults to false and does not change the persistent MetalFX mode,
render scale, reactive-mask or Frame Generation settings.

Runtime framebuffer proof is still required before adding this property to the
Launcher QA profile. The current macOS user session cannot complete that probe
because its WindowServer/launchd XPC state is returning error 141; Java and
mixin compilation alone are not treated as runtime acceptance.

Frame Generation uses a bounded scene-working resolution while keeping the
drawable and GUI at native backing resolution. The bounded path originally
coupled 3D resolution to the Frame Generation cap.
At a 1708x960 drawable, Temporal 67% and a 1280-pixel cap produced:

```text
Minecraft 3D 858x482
  -> MetalFX Temporal 1280x718
  -> MTLFXFrameInterpolator 1280x718
  -> linear scene scale to 1708x960 drawable
  -> premultiplied-alpha 1708x960 GUI overlay
```

The interpolator is linked to the active Temporal scaler through
`MTLFXFrameInterpolatorDescriptor.scaler`. Generated frames never enter the
Temporal history. Reversing the order would either pollute Temporal history
with synthetic frames or require running Temporal at the 120 Hz present rate.

The hybrid implementation no longer lets that cap reduce Minecraft's 3D input.
At a 3024x1734 fullscreen drawable with the required 50% mode, its graph is:

```text
Minecraft 3D 1512x867
  -> MetalFX Temporal 3024x1734 native real scene
  -> linear downsample 1280x734 FrameGen scene
  -> conservative depth + nearest motion downsample 640x367
  -> MTLFXFrameInterpolator 1280x734 generated scene
  -> generated scene scales to 3024x1734 only during fused present
  -> real scene presents directly at 3024x1734
  -> premultiplied-alpha 3024x1734 GUI overlay on both
```

`metallum.metalfx.frameGenerationOutputWidth` controls only the generated-frame
work cap and defaults to 1280 (bounded to 640...3840). FrameInterpolator gets
its own half-work-resolution depth/motion inputs, so this cap never changes the
exact-half Minecraft 3D render. The explicit values `native`, `display`, and `0`
remove the cap. The setting does not lock the persisted mode, exact 50%
Temporal ratio, reactive-mask or Frame Generation UI settings. This dual-scene
topology has compile and lifecycle coverage; fullscreen performance and visual
acceptance remain required before it replaces the last Launcher QA artifact.

The 2026-07-27 hybrid microbenchmark measured these linked FrameInterpolator
p95 values on the M1 Pro:

| FG input -> output | FrameInterpolator p95 |
| --- | ---: |
| 854x490 -> 1708x980 | 7.52 ms |
| 756x434 -> 1512x867 | 6.73 ms |
| 640x367 -> 1280x734 | 5.45 ms |

The 1708 path leaves no reliable 8.33 ms present slot after fullscreen
composition. The 1280 path fits the generated-frame slot, but native Temporal,
input preparation, both presents and Minecraft rendering still share the
16.67 ms source budget. It therefore remains an experimental candidate rather
than proof of stable 60-source/120-present operation or shader headroom.

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
headroom. This is a GPU budget, not proof of scanout cadence.

### Native Retina fullscreen ceiling on M1 Pro

The later Metal 4 comparison adds a true 50% fullscreen case and measures both
MTL3 and MTL4 effects against the same 1512x839 -> 3024x1678 textures. The MTL4
Temporal scaler is linked to the MTL4 FrameInterpolator and the result records
that link explicitly. On the Apple M1 Pro (30 measured iterations after five
warm-ups), the p95 values were:

| Path | Temporal | FrameInterpolator |
| --- | ---: | ---: |
| Metal 3 linked | 7.60 ms | 22.32 ms |
| Metal 4 linked | 7.76 ms | 22.53 ms |

The linked MTL4 path therefore does not reduce interpolation cost. Moving the
production Temporal encode from the established MTL3 command stream to a new
MTL4 queue was also 0.16 ms slower in this run while adding a cross-queue event
and another resize/shutdown lifetime. That migration is rejected by the measured
result rather than reported as a performance improvement.

For the real client gate, `-Dmetallum.validation.preserveFullscreen=true`
keeps the scripted readbacks, GUI open/close and 180-frame steady tail on the
current fullscreen drawable instead of switching to the 1708x960 golden-frame
window. The 2026-07-27 M1 Pro run used a 3024x1734 drawable, exact 1512x867 3D
input, native MTL4 Frame Generation output and direct native presentation. It
passed 16/16 attachment readbacks, but failed every performance budget:

| Metric | Measured p95 | Required |
| --- | ---: | ---: |
| Source interval | 30.50 ms | <= 18.50 ms |
| Presenter admission wait | 27.80 ms | diagnostic |
| Generated-frame GPU | 25.71 ms | <= 7.00 ms |
| Present interval | 25.00 ms | <= 8.50 ms |
| Total GPU | 63.91 ms | <= 13.67 ms |

Only 220/256 retained presentation records reported nonzero `presentedTime`
(0.859), and all 128 complete source pairs exceeded 16.67 ms. This is direct
Minecraft/WindowServer evidence that native 3024x1734 60-source/120-present is
not attainable with Apple's current FrameInterpolator on this M1 Pro. The
1280/1708 bounded paths remain the deployable 120 Hz modes; native fullscreen
must remain a measured fail-closed option unless the framework or hardware
cost changes.

The exact-half rule is intentional: 50% no longer clears the low bit after
rounding, so an even 1734-pixel drawable produces 867 input pixels rather than
866. Other quality ratios keep their existing even-size alignment.

The default was reduced from 1440 to 1280 after an automated real Minecraft
Quick Play comparison at a 1708x960 framebuffer. Both runs used Temporal 67%,
native-resolution GUI composition, a 180-source-frame readback-free steady tail,
and native GPU timestamps. The raw presenter records are written to
`build/metal-validation/minecraft-client-current/frame-generation-timeline.json`;
the Gradle gate writes its aggregate to `frame-generation-performance.json`.
Each record also carries the source enqueue timestamp and the time the render
thread waited to acquire the presenter slot. The aggregate therefore separates
source CPU cadence, presenter backpressure p95 and GPU execution time instead of
inferring all three from the displayed FPS counter.

| FG work width | Source interval p50 / p95 | Present interval p50 / p95 | Source GPU p95 | Generated GPU p95 | Total GPU p95 / 16.67 ms margin |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1440 | 16.68 / 24.73 ms | 8.33 / 16.67 ms | 6.17 ms | 7.32 ms | 12.44 / 4.22 ms |
| 1280 | 16.62 / 17.90 ms | 8.33 / 8.33 ms | 5.63 ms | 5.30 ms | 11.10 / 5.57 ms |

At 1440, the interpolation tail is close enough to one 8.33 ms display slot
that occasional frames defer the next real present. At 1280, no measured source
pair exceeded the 16.67 ms total GPU budget and the present-interval p95 stayed
at 8.33 ms. `minecraftMetalFxClientValidation` now fails if the 180-frame tail
does not contain at least 120 complete pairs, source interval p95 exceeds 18.5
ms, present interval p95 exceeds 8.5 ms, generated GPU p95 exceeds 7 ms, total
GPU p95 leaves less than 3 ms headroom, or any complete pair exceeds 16.67 ms.

The automated client is normally unfocused, so occasional `presentedTime == 0`
callbacks can still be WindowServer coalescing of an occluded window. A
foreground Launcher run remains required to close the zero-dropped-scanout gate;
the unattended result is not relabelled as that visual acceptance.

Both visible Gradle gates now fail before launch when `ioreg` reports a locked
macOS console. A locked session cannot produce nonzero WindowServer
`presentedTime` callbacks, so waiting for the full scripted run would only
measure GPU completion behind a display that is ineligible for scanout.

`minecraftMetalFxLockedBackpressureValidation` is a separate locked-console
stress gate. It deliberately does not evaluate presentation cadence and writes
`scanoutValidated: false`; instead, it records every bounded source admission in
`frame-generation-source-admission.json` and gates the time Minecraft's render
thread waited for the presenter slot. The ordinary foreground task retains its
strict unlocked-console preflight and scanout gates.

The Apple M1 Pro locked-console Quick Play run on 2026-07-27 completed all 16
GPU readbacks and queued 436 Frame Generation sources. The longest presenter
session admitted 345 sources, superseded 343 stalled sources, and measured
source-admission wait p50/p95/max of 0.0015/0.0022/2.1841 ms. This proves that
missing WindowServer callbacks no longer serialize the render thread onto the
0.75-second starvation timeout; it is not evidence of 120 Hz scanout.

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

### Metal 4 presentation

`metal4PresentValidation` runs the same visible-window pacing, resize and
shutdown harness with the MTL4 FrameInterpolator and present queue. The present
path owns two nonblocking command-buffer/allocator slots, matching the layer's
two-drawable pool. A slot is released only by MTL4 commit feedback; if both are
still in flight, that display update is recorded as
`dropped:metal4-in-flight-saturated` without waiting in the display-link
callback or resetting allocator memory that the GPU may still reference.

The headless `metal4PipelinePathTest` holds both submissions behind an
unsignaled shared event and verifies that a third begin fails immediately, both
slots return after GPU completion, and encoding can resume. It runs with Metal
API Validation. The visible MTL4FX test explicitly disables the Debug Layer on
macOS 26.5: MetalFX otherwise sends `globalTraceObjectID` to Apple's
`MTL4DebugComputeCommandEncoder` wrapper and aborts before the first frame. This
is isolated to the validation wrapper; the same automatic window run against
the release encoder passes and still provides GPU timestamps and drawable
presented-time evidence.

The post-fix M1 Pro run presented 57 real and 56 generated measured frames at
57.93 source / 114.87 present FPS, exercised resize, and shut down in 0.0017
seconds. Generated GPU p95 was 8.97 ms and no in-flight saturation drop occurred.
That establishes Metal 4 lifecycle stability, not the final performance goal:
the generated-frame tail still exceeds one 120 Hz slot by 0.63 ms and leaves no
shader headroom.

The same Metal 4 path also passed the automated real Minecraft client at a
1708x960 drawable with 50% 3D rendering, native-width Temporal output and
native-width Frame Generation. The 256-record steady tail presented every real
and generated frame, with 8.3334 ms present-interval p95, 7.3861 ms source GPU
p95, 6.6176 ms generated GPU p95 and 13.7399 ms combined GPU p95. That leaves
2.92675 ms to the 16.67 ms source budget, so the strict 3 ms shader-headroom
gate fails by about 0.073 ms even though no measured source pair exceeded the
budget. The client completed all 16 attachment captures, queued 436 source
frames, and kept Frame Generation enabled through shutdown.

An earlier run inherited `fullscreen:true` and `exclusiveFullscreen:true` from
`run/options.txt`, so it did not exercise the requested 1708x960 validation
window. It instead sustained a 3416x1678 drawable with approximately 2288x1124
3D input, native Temporal output and native Metal 4 Frame Generation. Source and
generated GPU p95 were approximately 34.54 and 32.99 ms respectively, proving
that the lifecycle remains bounded under the larger allocation but also that
full Retina is far outside the 60-to-120 budget. The validation client now exits
both fullscreen modes before pinning its framebuffer, preventing future runs
from silently measuring the wrong resolution.

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
