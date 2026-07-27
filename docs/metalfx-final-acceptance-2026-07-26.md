# MinecraftMetal / MetalFX Final Acceptance Report — 2026-07-26

## Decision

The current source tree builds and the implemented MRT, ordinary-entity motion,
offscreen MetalFX and real display-link validation paths pass. The requested
full product acceptance is **not complete** because several Minecraft dynamic
content categories do not yet produce reliable object motion and the full
attended display matrix has not been run.

`OBJECT_MOTION_PRODUCER_CONNECTED` remains `false`. Production Frame Generation
must not be enabled from this report.

## Evidence boundary and repository state

The implementation root is:

```text
/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master
```

Git was initialized there to support worktree tracking. There is no HEAD commit
or imported baseline yet:

```text
## No commits yet on master
?? .gitattributes
?? .github/
?? .gitignore
?? LICENSE
?? README.md
?? build.gradle
?? dist/
?? docs/
?? gradle.properties
?? gradle/
?? gradlew
?? gradlew.bat
?? logs/
?? settings.gradle
?? src/
```

No reset, clean of user data, deletion of uncommitted source changes, commit,
push or release was performed. Gradle `clean` only removed the project build
directory. Validation tasks delete their exact `*-current` artifact directory
before execution so stale captures cannot satisfy a new run.

The latest rollout was used only to recover work and failures. Current source,
newly produced binaries and current-run artifacts are the acceptance facts.
The pre-repair native build failed first on Swift initialization/ownership and
then exposed optional Metal descriptor errors. The errors were fixed
iteratively until the same source produced a fresh dylib.

## Implemented changes

### Generic MRT backend

- Preserved all Java render-pass color attachment indices, including null
  middle slots and up to eight slots.
- Carried pipeline color formats, blend state and write masks per slot.
- Carried render-pass texture, load/store and clear state per slot.
- Added indexed V2 Java FFM / Swift ABI while retaining the legacy one-color
  entrypoint.
- Compared full render-pass/pipeline attachment signatures and marked copied
  destination textures dirty.
- Added real Java-to-GPU integration coverage and readback.

### Object motion and Temporal inputs

- Added a stable UUID plus live-identity generation state store for ordinary
  entities.
- Captured current/previous transforms and replayed staged entity geometry to
  object-motion `RG16_FLOAT` and validity `R8_UNORM` attachments.
- Kept the shared current-to-previous, top-left, unjittered motion convention.
- Distinguished static-valid zero motion from invalid/uncovered pixels.
- Committed previous object state only after successful GPU submission.
- Added camera/object merge, invalid-motion rejection, previous-depth
  disocclusion and reactive output.
- Preserved completed world depth before Minecraft clears main depth for the
  first-person hand stage.
- Made `Minecraft.renderFrame` the single whole-frame begin owner.

### Frame Generation presenter

- Fixed all stored-property and `NSObject` initialization ordering.
- Replaced the old pacing model with `CAMetalDisplayLink` update ownership.
- Retained both submission deadline and presentation timestamp.
- Used only ordinary `commandBuffer.present(drawable)` on the display-link
  path.
- Removed display-link-path `nextDrawable()`, targeted present, fixed 120 Hz
  and fractional fixed-delay behavior.
- Added explicit lifecycle states and exactly-once release.
- Bounded pending display updates to one and made stale/superseded callbacks
  explicit drops.
- Added bounded source starvation cancellation.
- Reordered shutdown to cancel unsubmitted work, release impossible presents,
  drain submitted GPU work, invalidate the link and stop.
- Removed unbounded per-frame `NSLog`.

### Three-layer automated validation

1. Offscreen native Metal validation renders only to `MTLTexture`, runs MRT,
   merge, disocclusion, reactive, Temporal and Frame Interpolator, and exports
   GPU readback without a layer, drawable, window or screenshot.
2. Automated Minecraft client validation loads a fixed integrated-client world,
   drives a controlled armor stand and camera, captures pre-present targets,
   compares expected motion numerically and exits automatically.
3. Real presentation validation creates an automated visible AppKit window,
   consumes real `CAMetalDisplayLink` drawables and records ownership/timing
   events without Computer Use, screenshots or manual operation.

## Key lifecycle invariants

- A source token has one owner and one terminal release.
- Generated then real ordering is preserved within and across source pairs.
- Unsubmitted work is cancellable; submitted work is retained until safe.
- A drawable belongs only to the display update that supplied it.
- A missed submission deadline is a drop, not a late present.
- `presentedTime == 0` is failure, never a successful display.
- Duplicate callbacks, error callbacks and release requests are idempotent.
- Shutdown does not wait for a callback made impossible by shutdown itself.
- Current object history advances only after a successful whole-frame GPU
  submission.
- History reset, failed/cancelled frames and scene change cannot promote pending
  object state.

## Commands and results

JDK used:

```text
/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home
```

Final clean matrix:

```sh
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
./gradlew clean test buildMacNative metalMrtBackendIntegrationTest \
metalFxOffscreenValidation metalFrameGenerationPresentationValidation \
build --no-daemon
```

Result:

```text
exit code: 0
BUILD SUCCESSFUL in 36s
19 tasks: 17 executed, 2 up-to-date
Java math tests: 29 passed, 0 failed, 0 errors, 0 skipped
MRT backend integration: 10 passed, 0 failed, 0 errors, 0 skipped
native lifecycle reducer: 9 passed
offscreen scenarios: 8 passed
real presentation: passed
full build: passed
```

The MRT negative tests intentionally request incompatible fragment
output/attachment signatures and produce five Metal validation errors. Those
errors are the expected rejection evidence for the mismatch cases; accepted
project pipelines produced no Metal API validation errors.

The Minecraft client was run after the clean matrix because its task needs the
new packaged mod:

```sh
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
./gradlew minecraftMetalFxClientValidation --no-daemon
```

Result:

```text
exit code: 0
BUILD SUCCESSFUL in 26s
controlled frames: 74
expected GPU captures: 8
completed GPU captures: 8
failed GPU captures: 0
dedicated server: false
system screenshot: false
Computer Use: false
status: passed
```

## MRT GPU acceptance

The integration test exercises:

- 1, 2, 3 and 8 color attachment descriptors/signatures;
- a middle unused slot;
- `RGBA8_UNORM`, `RG16_FLOAT`, `R8_UNORM`;
- per-slot clear/load/store;
- per-slot blend and color write mask;
- render-pass/pipeline signature mismatch;
- fragment location/format mismatch;
- legacy one-attachment ABI;
- indexed V2 ABI;
- GPU readback values.

Project-owned MRT shader/API validation was enabled. Result: 10/10 passed.

## Offscreen image acceptance

Artifact:

```text
build/metal-validation/offscreen-current/summary.json
```

The task emitted 217 files: 104 raw `.bin`, 104 `.png`, and 9 `.json`.
Each scenario exports input color, depth, camera motion, object motion,
validity, merged motion, disocclusion, reactive, Temporal output, interpolated
output, directly rendered midpoint ground truth and difference.

| Scenario | PSNR dB | MAE | Result |
| --- | ---: | ---: | --- |
| static | 120.000 | 0 | pass |
| translation | 20.275 | 0.01430 | pass |
| rotation | 22.544 | 0.01003 | pass |
| occlusion/reveal | 20.490 | 0.013997 | pass |
| alpha-test | 24.700 | 0.006877 | pass |
| scene cut | 120.000 | 0 | pass |
| illegal motion | 24.978 | 0.004799 | pass |
| history reset | 22.005 | 0.009561 | pass |

The executable records `uses_drawable=false`, `uses_layer=false`,
`uses_window=false`, and `uses_screenshot=false`.

Metal API validation remained enabled. Shader validation was disabled only for
the MetalFX private-kernel execution because the SDK's private kernel attempts
a 1,024-thread dispatch on this device when shader validation is injected,
while the device limit is 832. This is an Apple private-kernel validation
interaction, not a waiver for the repository-owned MRT pipeline.

## Automated Minecraft client acceptance

Artifact:

```text
build/metal-validation/minecraft-client-current/run-state.json
```

| Case | Validity pixels | Depth pixels | Disocclusion pixels | Object disocclusion | Motion error | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| fixed camera + static entity | 6,249 | 6,249 | 403,846 | 175 | 0.0000109 | pass |
| fixed camera + moving entity | 6,214 | 6,214 | 403,940 | 234 | 0.0024578 | pass |
| moving camera + static entity | 6,209 | 6,209 | 403,899 | 188 | 0.0000739 | pass |
| camera + entity moving | 6,225 | 58,381 | 359,432 | 262 | 0.0025196 | pass |
| occluded entity | 0 | 379,611 | 30,350 | 0 | n/a | pass |
| revealed entity | 6,201 | 113,311 | 406,793 | 6,201 | 0 | pass |
| GUI open | 6,181 | 122,742 | 287,921 | 14 | 0 | pass |
| scene reset | 0 | 125,291 | 284,629 | 0 | n/a | pass |

The revealed entity's entire valid region is marked disoccluded. Scene reset
has no object validity and no object-region disocclusion; diagnostics record a
history-reset skip. Expected motion comes from known current/previous
transforms, not visual judgment.

## Object-motion coverage

| Category | Status | Evidence |
| --- | --- | --- |
| Ordinary entity | completed | current client GPU capture and numeric comparison |
| Entity feature renderer | partially connected | source path; not exhaustive per feature |
| Static terrain/Sodium fallback | camera motion from depth | client camera-motion readback |
| Vehicles/dropped items | incomplete | no dedicated acceptance scenario |
| Block entities | not implemented | no reliable producer |
| First-person hand/item | not implemented | world depth preserved, no hand motion |
| CPU/vertex animation | not implemented | rejection/fallback only |
| Cutout foliage | partial | alpha/depth/reactive policy, no animation motion |
| Particles/weather/clouds | reactive only | source-target history rejection |
| Water/glass/translucency | reactive only | no universal true motion |

Missing producers are engineering gaps, not environment limitations.

## Display-link timeline acceptance

Artifact:

```text
build/metal-validation/presentation-current/timeline.json
```

Latest clean result:

```text
status: passed
warm-up source frames: 3
measured source frames: 10
real presented: 10
generated presented: 9
timeline records: 82
resize exercised: true
shutdown: 0.004846 s
real CAMetalLayer: true
CAMetalDisplayLink drawable: true
targeted present: false
Computer Use: false
system screenshot: false
```

Three consecutive runs before the final clean matrix also passed. Their
real/generated counts were 10/8, 9/7 and 10/9; shutdown remained between
0.0061 and 0.0088 seconds. The variation is recorded as display-update/drop
behavior, not forced into a fixed refresh-rate model.

Each drawable event can be correlated with source frame ID, generated/real
kind, display update ID, both target timestamps, commit, GPU completion,
presented time and terminal reason. Source inspection confirms the
display-link path calls ordinary `present(drawable)`. A separate ordinary
non-display-link present path still legitimately acquires a drawable.

## Binary and JAR receipt

| Artifact | Size | Modification time | SHA-256 |
| --- | ---: | --- | --- |
| `src/main/resources/natives/macos/libmetallum.dylib` | 353,008 | 2026-07-26 17:30:31 +0800 | `daec6d499e8d09d337d9f6b4c0acec58b727826f5f6b8f52fbfea960b92c5404` |
| `build/libs/metallum-1.0.1.jar` | 1,268,002 | 2026-07-26 17:30:53 +0800 | `4d32dcf16446752bb989a4ce212e8172d518d132c5bb64ab6711f8120be5bd69` |
| `build/libs/metallum-1.0.1-sources.jar` | 1,127,323 | current clean build | `83f0560290a810696d5ef35396c48db3730e46f930621ee7b0a6e9deaf282428` |

The dylib is newer than the latest native Swift source modification. Extracting
`natives/macos/libmetallum.dylib` from the JAR produces the same
`daec6d499e8d09d337d9f6b4c0acec58b727826f5f6b8f52fbfea960b92c5404`
hash.

`nm` confirms:

```text
_metallum_MTLCommandBuffer_completedSuccessfully
_metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2
_metallum_metalfx_encode_v2
_metallum_metalfx_stop_frame_generation
```

The JAR contains the current dylib, MetalFX manager/motion classes, entity
motion mixins and motion shaders.

## File-level change groups

- `build.gradle`: native/test executable tasks, macOS skips, clean current-run
  artifact handling and Minecraft validation mode.
- `src/main/native/MetallumNative.swift`,
  `MetalFrameGenerationLifecycle.swift`: indexed ABI, MetalFX encodes,
  display-link presenter and lifecycle.
- `src/main/java/com/metallum/client/metal/render/`: indexed MRT metadata,
  bridge, command submission callback, motion state/capture/pipeline, depth
  preservation, merge and MetalFX orchestration.
- `src/main/java/com/metallum/mixin/`: whole-frame ownership and real entity
  render-path capture.
- `src/main/resources/assets/metallum/shaders/`: entity motion/validity and
  merge-related shaders.
- `src/test/java/`: math and Java-to-Metal MRT integration tests.
- `src/test/native/`: MRT smoke, lifecycle, offscreen and real presentation
  validation executables.
- `docs/`: current implementation contracts, historical forensic corrections
  and this acceptance report.

## Completed but only static/unit evidence

- Lifecycle transition edge cases beyond those naturally produced by the
  visible-window run are proved by the 9-case reducer executable.
- Per-feature entity renderer compatibility is source-connected but not
  exhaustively exercised with every Minecraft feature type.
- Runtime near/far/FOV/depth assertions exist, but every third-party camera or
  projection mod combination is not exercised.

## Scaffold or partial behavior

- Reactive coverage exists for official transparency source targets, but it is
  not a complete material-derived strategy for all modded content.
- Vehicle/dropped-item rendering may traverse the ordinary entity path, but
  lacks dedicated expected-motion captures.
- The `descriptor.scaler` linked-scaler path is present; no controlled
  device-specific performance benefit is claimed.

## Not implemented

- Reliable block-entity motion.
- Reliable first-person hand/item motion.
- Universal CPU/vertex animation motion.
- Complete cutout, particle, weather, cloud, water and glass motion or a fully
  graded material policy.
- Full category acceptance sufficient to set the object producer gate true.

## Environment or attended-display limitations

The following were not represented as headless success:

- full 60 Hz and 120 Hz display matrix;
- VRR scanout and human-perceived smoothness;
- tearing observation;
- 30/40/60 FPS source pacing matrix;
- minimize/restore and fullscreen;
- multi-display migration;
- human visual inspection of all content classes.

These require an available display configuration or attended observation. They
do not invalidate the completed offscreen image, renderer integration or
timeline state-machine evidence.

iOS native compilation is not an iOS device/runtime acceptance result and is
outside this macOS validation decision.

## Known defects and final gate

- Dynamic-object coverage is incomplete.
- Material-derived reactive behavior is incomplete.
- The current ordinary entity slice does not establish every feature,
  translucent or procedural path.
- The complete refresh/source-rate/display-migration matrix is unrun.

Therefore:

```text
Frame Generation gate: CLOSED
OBJECT_MOTION_PRODUCER_CONNECTED: false
Overall status: PARTIAL ACCEPTANCE; DO NOT CLAIM FULL COMPLETION
```

## Addendum (2026-07-26, later): Sodium CUTOUT reactive repair accepted

The Temporal flicker repair for alpha-tested Sodium terrain (leaves and grass)
described in `docs/handoffs/metalfx-cutout-reactive-handoff-2026-07-26.md` was
completed and validated after this report's main body was written.

Offscreen evidence (synthetic, no window, no screenshot):

- `metalFxOffscreenValidation` passes all eight scenarios with Metal API
  Validation enabled. The `alpha_test` scenario feeds synthetic exact
  post-discard coverage through the radius-1 dilation, exports
  `cutout_coverage`, and asserts covered ⊆ reactive, dilation outside exact
  coverage, and `preserveReactiveMask=true`.

Real Minecraft renderer evidence (integrated client, fixed spectator world,
GPU readback before present, no screenshots or attended input):

- `minecraftMetalFxClientValidation` passes 10/10 captures
  (`expectedGpuCaptures=10`, `failedGpuCaptures=0`, `status=passed`) with
  Metal API Validation enabled and zero validation assertions.
- Both Sodium mixins inject; the run logs
  `MetalFX CUTOUT reactive coverage prepared from Sodium terrain MRT: radius=2`,
  which requires the redirected MRT render pass, the custom
  `block_layer_cutout_reactive` fragment shader and the native dilation merge
  to all be live.
- Frame 74 `cutout_leaves` (controlled persistent `OAK_LEAVES` wall):
  265,225 exact-coverage pixels, all 265,225 present in the final reactive
  mask, 29,948 dilated reactive pixels outside exact coverage at radius 2.
- Frame 82 `cutout_grass` (controlled `SHORT_GRASS` on `GRASS_BLOCK`, camera
  pitched down 15°): 274,954 exact-coverage pixels, all 274,954 present in the
  reactive mask, 35,370 dilated pixels at radius 2.
- The revealed-entity capture moved from frame 47 to the wall-removal frame 46
  and now shows a full one-frame reveal (4,336 valid pixels, 4,323
  object-region disocclusion pixels, error 0). Determinism changes for the
  client run are documented in `docs/metalfx-validation.md`.

Defects found and fixed during this acceptance:

- `MetalFX Reactive R8` was pre-cleared through a render-pass load action
  without `USAGE_RENDER_ATTACHMENT`; Metal API validation aborted the client.
  The texture is now created as a render target.
- The eight-capture client timeline raced asynchronous Sodium section
  rebuilds; the run configuration now uses `chunk_build_defer_mode=ZERO_FRAMES`
  with prioritized `important` rebuild requests after every controlled scene
  block change, plus 40 warm-up frames before the scripted timeline.

Deployment state (updated 2026-07-27):

- JAR `build/libs/metallum-1.0.2.jar` (SHA-256
  `83e2c8c6d048f40a01dbee8bb0171da42514a4f729ab6f9821fb137724014ad7`)
  is byte-identical to the copy in
  `MinecraftMetal-Current-2026-07-26/mods/`. The previous `1.0.1` JAR was
  moved to `.codex-backups/20260727-framegen-qa/` rather than deleted.
- The stable launcher profile `minecraftmetal-current-20260726` keeps
  `-Dmetallum.metalfx.frameGeneration=false`. Its shared persistent config is
  `TEMPORAL`, 67%, transparency reactive enabled and Frame Generation off.
- The separate launcher profile `metallum-fabric-26.2-framegen`, displayed as
  `FrameGen QA - MetalUniversal 26.2 (Gate Override)`, points to the existing
  `MetalUniversal-26.2` instance. That instance also carries the byte-identical
  `1.0.2` JAR, and the profile explicitly adds
  `-Dmetallum.metalfx.objectMotionProducer=true` plus
  `-Dmetallum.metalfx.frameGeneration=true`. Selecting it opens the production
  gate only for that launch and does not change the shipped
  `OBJECT_MOTION_PRODUCER_CONNECTED=false` default.

This addendum does not change the main gate:

```text
Frame Generation gate: CLOSED
OBJECT_MOTION_PRODUCER_CONNECTED: false
Overall status: PARTIAL ACCEPTANCE; CUTOUT reactive repair ACCEPTED
```

## Addendum (2026-07-27): Frame Generation recovery and CI gate

A gate-open real-client rerun disproved the prior assumption that 16/16 GPU
readbacks implied the presenter stayed live. Startup size churn produced one
scene-encode failure, permanently disabled Frame Generation, and then allowed
the attachment-only validation to finish green. The failure now suspends only
the affected frame, stops pending presenter work, and resumes on the next
stable frame with reset history. The structured client receipt now gates on a
positive native-enqueue count and an enabled completion state whenever Frame
Generation is explicitly requested.

The repaired run completed 16/16 readbacks, queued 255 source frames and ended
enabled. Its background drawable callbacks all had `presentedTime == 0`, so
this is connection/recovery evidence, not visual or scanout acceptance. The
independent foreground presentation harness passed 10 real and 9 generated
presents with 0.0068-second shutdown. The production constant therefore
remains `false` pending the attended refresh/source-rate/VRR matrix.

The first manually dispatched GitHub Actions run also found an infrastructure
error: the workflow used macOS 15 while `check` intentionally executes the
macOS 26-only Frame Interpolator offscreen gate. The workflow now targets the
available `macos-26` runner so CI can execute the assertion rather than skip or
fail solely on host version. A remote rerun still requires these local changes
to be committed and pushed.
