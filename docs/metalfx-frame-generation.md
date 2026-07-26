# MetalFX Frame Generation

Status: presenter and validation infrastructure implemented; production gate
closed.

Frame interpolation is a macOS 26+ path based on
`MTLFXFrameInterpolator`. The code is present and automatically testable, but
Minecraft cannot enable it while
`OBJECT_MOTION_PRODUCER_CONNECTED == false`.

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

## Known limits

- Production Frame Generation remains disabled because object-motion coverage
  is incomplete.
- Block entities, first-person hand/item, procedural/vertex animation and
  several translucent categories do not yet have reliable object motion.
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
