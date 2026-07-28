# Metal 4 Geometry Corruption Handoff - 2026-07-28

## Purpose

Fix a severe, user-visible geometry corruption regression in the Metal 4 main
renderer. Keep the investigation narrow until the failing M4 state transition
is identified. Do not treat this as a Temporal, transparency/reactive, or LOD
task unless a controlled isolation run proves that boundary.

## 14:00-14:40 live follow-up

The first null-binding/upload-barrier candidate did not fix the attended
Minecraft scene. The loaded candidate JAR was
`7f855553d604cb0efb90bf98e761236c14a26f36bcf15be5019986e8c1d7c4b2`.

The user then isolated the failure without restarting:

- corruption remained after switching MetalFX Scaling to `OFF`, at about
  120 FPS; this excludes Spatial and Temporal as the geometry producer;
- the stretched surfaces carried the nearby leaf texture and converged on a
  leaf block in the affected section;
- breaking that leaf block rebuilt the section and removed the corruption;
- the same M4 renderer continued drawing the rebuilt section correctly.

The paired screenshots are preserved under
`build/metal-validation/manual-evidence/`:

```text
m4-candidate-corruption-temporal-2026-07-28-1400.png
m4-candidate-corruption-leaves-2026-07-28-1402.png
m4-candidate-corruption-leaf-origin-2026-07-28-1403.png
m4-corruption-metalfx-off-2026-07-28-1403.png
m4-corruption-cleared-after-leaf-break-2026-07-28-1404.png
```

Minecraft 26.2 bytecode confirms that section draws bind a whole vertex
uber-buffer and pass `vertexBufferOffset / vertexStride` as `baseVertex`.
The native M4 regression now covers positive and negative `baseVertex`, a
nonzero index-buffer byte offset, alternating layouts and bindings, and exact
GPU readback across all three reusable slots. It passes under Metal API
Validation, so the generic base-vertex translation is not the current leading
cause.

The earlier candidate therefore added a bounded correctness drain before each
M4 submit that began a batch of non-dynamic/private buffer uploads. The current
implementation keeps that drain only as an explicit diagnostic fallback. The
normal path relies on the MTL4 queue barrier and leaves submissions
asynchronous. The fallback can be enabled with:

```text
-Dmetallum.opt.metal4PrivateUploadDrain=true
```

Candidate JAR SHA-256:

```text
4f408c65e7cbfa901388e950c49259e2485e6ad58608cb556cdacdfdb68254eb
```

Before the installation recorded below, this JAR had not replaced the running
instance. It still requires a cold Launcher run and attended reproduction in
fresh terrain. Java tests, JAR packaging, and the M4 path GPU readback pass.
The complete build's visible presentation task could not run while the macOS
console was reported locked; that is an environment gate, not a geometry-test
failure.

The user requested installation before leaving the machine. Minecraft was
terminated normally at 14:50, the previous installed JAR was backed up at:

```text
$HOME/Library/Application Support/minecraft/instances/MetalUniversal-26.2/.codex-backups/20260728-145053-metal4-private-upload-drain/metallum-1.0.2.jar
```

The candidate is now installed at `mods/metallum-1.0.2.jar` with the hash
above. ZIP integrity passed, no Minecraft process remains, and Launcher was
left running. No new game launch or visual claim has been made.

## Correctness-first follow-up

The implementation in the current dirty worktree is:

- MetalCommandEncoder.writeToBuffer invokes the fallback only for a
  non-dynamic buffer whose resource options report StorageModePrivate, only
  when the Metal 4 main renderer is enabled, and at most once per submit index.
  The property default is false, so normal Metal 4 uploads do not call
  waitForSubmittedGpuWork().
- With -Dmetallum.opt.metal4PrivateUploadDrain=true, the fallback performs one
  full CPU drain before the first private upload in a submit, emits one warning,
  and emits one bounded session summary with the total drain count. It is
  observable diagnostic evidence, not a performance optimization.
- metallum_MTLCommandBuffer_makeBlitCommandEncoder retains the existing MTL4
  consumer barrier with afterQueueStages=vertex|fragment|dispatch|blit,
  beforeStages=blit, and visibilityOptions=device. The render consumer barrier
  waits on blit|fragment|dispatch before vertex|fragment.
  metallum_metal4_upload_barrier_stats reports the bounded native
  upload/copy-barrier count and the first encoded barrier logs its stage and
  visibility contract.
- The native ABI has no Sodium allocation identity or byte-range metadata.
  Therefore this slice does not guess a range tracker or claim that the
  barrier is resource-selective. Completion-backed three-slot reuse and the
  four-deep Java destruction queue remain the lifetime mechanism.
- The native path regression preserves binding churn and adds three MTL4
  leases that rewrite the same private vertex and index destinations from
  per-slot staging buffers, commit all three before any completion wait, then
  verify exact 8x8 GPU readback from three separate targets.

Validation completed on the Apple M1 Pro with Metal API Validation:

~~~
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew test buildMacNative metal4PipelineSmokeTest metal4PipelinePathTest --no-daemon --console=plain
~~~

Result: BUILD SUCCESSFUL, 11 actionable tasks. The Java test suite, native
build, Metal 4 PSO smoke test, and shipping Metal 4 path test passed. The
shipping path test reported exact binding-churn readback, exact private rewrite
readback, and upload barriers=6.

Root verification repeated the complete command with `--rerun-tasks` so no
compile or test result depended on Gradle's up-to-date cache. The rebuild also
finished `BUILD SUCCESSFUL`; all 11 tasks executed and the same exact GPU
readbacks and barrier count passed under Metal API Validation.

The requested /tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home was also checked
but cannot launch: its lib/server/libjvm.dylib fails macOS dlopen with rebase
opcodes terminated early. The runnable Homebrew OpenJDK 25.0.2 was used for the
complete command above; no source change was made to either JDK tree.

Root packaging then rebuilt `build/libs/metallum-1.0.2.jar` from the accepted
dirty source with `./gradlew jar --rerun-tasks`. ZIP integrity passed and the
artifact contains both the Java drain gate and native upload-barrier telemetry.
Its SHA-256 is:

```text
7bf58cccdfde4071601f9c7d94a66dcb17ca2d6ff46bb3fab19cb31cce0bb313
```

This final-source artifact is not installed and has not been run in Minecraft.
The Launcher instance remains unchanged on the earlier `4f408c65...` candidate,
so evidence from that installed JAR cannot validate this final source state.

Unresolved boundaries:

- No deterministic pre-fix corruption was constructed by the native test; the
  test proves the encoded dependency and telemetry contract under asynchronous
  three-slot reuse, not the original Minecraft section failure.
- Real-client M4 visual correctness after this change remains human-unverified.
  The existing strict M3 kill switch and OBJECT_MOTION_PRODUCER_CONNECTED
  setting were not changed.
- A true range-scoped hazard tracker still requires Sodium allocator ownership
  and allocation-range metadata. Root decision: defer that contract to the
  terrain arena/Sodium batching phase. This synchronization slice must not be
  described as range-scoped and does not authorize a performance rollout.

## Published state

- Worktree: repository root of `claude/framegen-comparison`
- Implementation commit: `7dcf786baec0a3933fd4d01087fd5a13cb397a76`
- Remote branch: `21Z121Z1/MetalUniversal:claude/framegen-comparison`
- Draft PR: <https://github.com/21Z121Z1/MetalUniversal/pull/1>
- PR target: `master`
- Runtime JAR: `build/libs/metallum-1.0.2.jar`
- Runtime JAR SHA-256:
  `bb5953559953dab645d9da60074464aff390c33c726d021ca12f5ef0ee9ae8a9`
- The installed Launcher JAR has the same SHA-256.

After the implementation commit, the only pre-existing dirty paths were local
runtime logs under `logs/`. They are user-owned evidence. Do not reset, clean,
stage, or overwrite them.

## Confirmed facts

### Metal 4 failure

The user's real Minecraft M4 run showed large, multicolored triangles stretched
across the entire 3D view. Terrain that remained visible between the triangles
was otherwise recognizable. The hotbar, crosshair, Apple Metal HUD, and HUD text
were coherent.

The screenshot is preserved locally at:

```text
build/metal-validation/manual-evidence/m4-geometry-corruption-1920x1200-2026-07-28.png
```

SHA-256:

```text
dde37ccd972e2345cc8a90e6aa026b322ad558123199f572079e6722508e5dba
```

Visible screenshot state:

- Apple M1 Pro
- 1920x1200 window/output
- MetalFX Temporal 1920x1200 -> 1920x1200 (1:1)
- Frame Generation off
- 108.27 FPS
- 14.23 ms GPU
- 9.24 ms frame interval

The archived log for that run is:

```text
$HOME/Library/Application Support/minecraft/instances/MetalUniversal-26.2/logs/2026-07-28-2.log.gz
```

Its startup receipt is:

```text
[13:00:17] [Metallum] Metal 4: requested=true available=true compiler=true present=true mainQueuePilot=false mainRenderer=true barrier=true
[13:00:17] MetalFX configured: requested=TEMPORAL, effective=TEMPORAL, scale=1.0, ... frameGeneration=false, frameGenerationOutputWidth=render
```

The run exited normally at 13:03:02. It did not log `no free Metal 4 main
command-buffer slot`, `Failed to create MTLCommandBuffer`, or a Metal API
Validation failure.

### Metal 3 control is visually normal

The user explicitly completed attended validation of the Metal 3 fallback and
reported that it is completely normal. The M3 run used the same Launcher
instance and installed JAR. This is strong human evidence that the corruption
is specific to the M4 main-renderer path.

The current M3 log is:

```text
$HOME/Library/Application Support/minecraft/instances/MetalUniversal-26.2/logs/latest.log
```

Its strict kill-switch receipt is:

```text
[13:07:19] [Metallum] Metal 4: requested=false available=false compiler=false present=false mainQueuePilot=false mainRenderer=false barrier=false
```

The M3 client exited normally at 13:10:40. There is not yet a durable paired M3
screenshot or GPU readback, so retain the distinction: M3 visual correctness is
user-confirmed, while byte-identical M3/M4 parity is not yet proven.

Both the bad M4 run and normal M3 run logged a one-frame fullscreen-copy fallback
after live MetalFX setting changes. That warning is therefore not sufficient to
explain the geometry corruption.

## Launcher profile

Profile ID:

```text
metallum-fabric-26.2
```

Profile name and key argument:

```text
MetalUniversal 26.2 - Metal 3 Fallback (UI Unlocked)
-Dmetallum.opt.metal4=false
```

Game directory:

```text
$HOME/Library/Application Support/minecraft/instances/MetalUniversal-26.2
```

The modern Launcher removes the legacy top-level `selectedProfile` field. Do
not claim it is selected merely because that JSON field was written. The M3
classification above comes from the runtime log and user-attended image, not
from profile-file inference.

At handoff time there was no Minecraft client or Launcher process running.

## Highest-probability boundary

The coherent GUI/HUD over corrupt 3D output, strict-normal M3 control, and M4
main-renderer startup receipt put the first investigation boundary at M4 render
state and geometry binding, before MetalFX Temporal.

Two code facts deserve the first regression test:

1. `Metal4MainRenderEncoderBridge` owns one vertex and one fragment
   `MTL4ArgumentTable` for an entire render encoder and mutates their addresses
   as draw state changes (`MetallumNative.swift:872-949`).
2. `setBuffer(nil, ...)` rejects the null binding instead of zeroing the address,
   so an intentionally cleared slot can retain stale state
   (`MetallumNative.swift:894-911`).

`Metal4MainQueueContext.argumentTables(at:)` allocates a table pair per render
encoder, not per draw, and reuses those pairs when a command-buffer slot is
recycled after completion (`MetallumNative.swift:576-864`). This may be correct
only if Metal 4 snapshots the table contents at each draw. Prove that API
semantic with a minimal multi-draw readback before changing the production
allocator. Do not assume it.

Other plausible boundaries, in priority order:

1. stale vertex buffer GPU address or offset in the M4 argument table;
2. null bindings retaining a previous draw's address;
3. per-encoder argument table contents being observed after later mutations;
4. Java-to-Metal vertex slot remapping, stride, or base-vertex translation;
5. command-buffer slot, transient buffer, or resource lifetime recycling;
6. indirect or triangle-fan index buffer address/length handling.

The initial native/Java hotspots are:

- `src/main/native/MetallumNative.swift:482` - M4 lease completion ownership
- `src/main/native/MetallumNative.swift:576` - slot/table allocation and reuse
- `src/main/native/MetallumNative.swift:872` - M4 render encoder bridge
- `src/main/native/MetallumNative.swift:8693` - primitive draw exports
- `src/main/native/MetallumNative.swift:8722` - indexed draw exports
- `src/main/native/MetallumNative.swift:8761` - multi-draw indexed export
- `src/main/native/MetallumNative.swift:8811` - indirect indexed export
- `src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java:101`
  - vertex slot range and resource binding layout
- `src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:463`
  - Java vertex buffer push and slot remap
- `src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:541`
  - per-draw state binding

Do not begin by editing the transparency/reactive/LOD producer sections. Those
were being handled by another task and cannot explain a strict-normal M3 control
without further evidence.

## Minimum reproduction matrix

Run one change at a time and preserve a screenshot plus the startup receipt for
each row.

1. M3 strict kill switch, Temporal 1.0, Frame Generation off. Already visually
   green; archive a screenshot/readback if the user can reproduce the camera.
2. M4 main renderer on, MetalFX OFF, Frame Generation off. If triangles remain,
   MetalFX is excluded.
3. M4 available/compiler/present on but `metal4MainRenderer=false`, MetalFX OFF.
   If clean, the M4 main renderer is isolated from M4 compiler/present.
4. M4 main renderer on with a minimal native test that performs many draws in
   one render encoder while alternating vertex buffers, offsets, null bindings,
   indexed/non-indexed draws, and pipeline vertex layouts.
5. Repeat the fixed Minecraft scene for at least two cold launches and 600
   frames with Metal API Validation.

Useful M4 isolation properties are defined in `MetalDevice.java:78-112`:

```text
metallum.opt.metal4
metallum.opt.metal4Compiler
metallum.opt.metal4Present
metallum.opt.metal4MainQueuePilot
metallum.opt.metal4MainRenderer
metallum.opt.metal4Barrier
```

## Regression test to add first

Extend `src/test/native/Metal4PipelinePathTest.swift` with a single MTL4 render
encoder that:

- draws several non-overlapping shapes;
- changes vertex buffer GPU address and offset before every draw;
- clears and rebinds a buffer slot;
- alternates two pipeline vertex layouts;
- includes indexed and non-indexed draws;
- reads back exact expected colors/coverage;
- repeats across all three reusable command-buffer slots.

The existing path test proves isolated M4 draws, pipeline compilation, fallback,
residency, Spatial, barriers, and slot recycling. It does not reproduce the
high-frequency per-draw binding churn seen in Minecraft, so its current green
result does not close this bug.

## Frozen validation before handoff

The following completed successfully from commit `7dcf786` on the M1 Pro:

```bash
export JAVA_HOME='/path/to/a/JDK-25/Contents/Home'
./gradlew build \
  metalMrtSmokeTest \
  metal4PipelineSmokeTest \
  metal4PipelinePathTest \
  metalHudRuntimeTest \
  metalFrameGenerationLifecycleTest \
  metal4PresentValidation \
  metalFxOffscreenValidation \
  metalMrtBackendIntegrationTest \
  --no-daemon --console=plain
```

Final result: `BUILD SUCCESSFUL`, 29 actionable tasks, including macOS/iOS
native builds, Java tests, M4 pipeline/path, Spatial, HUD, lifecycle, offscreen,
MRT, and 60 real + 60 generated frames presented at 120 Hz with Metal API
Validation. Two isolated presentation-test launches initially failed to prime a
WindowServer-visible layer; the complete dependency run then passed. Record
that retry history rather than reporting an unconditional first-run pass.

These automated tests do not cover the real Minecraft binding churn that
produced the screenshot.

## Automated validation framework for other contributors

The framework source is already public on commit `7dcf786` and draft PR #1.
It includes the Gradle task wiring, Java tests, Swift/native test programs,
Metal API validation setup, GPU readbacks, visible-window presentation checks,
and JSON/image artifact writers. The important published entry points are:

```text
metalMrtSmokeTest
metal4PipelineSmokeTest
metal4PipelinePathTest
metalHudRuntimeTest
metalFrameGenerationLifecycleTest
metalFrameGenerationPresentationValidation
metal4PresentValidation
metalFxOffscreenValidation
metalFxPerformanceValidation
metalMrtBackendIntegrationTest
minecraftMetalFxClientValidation
```

It is ready for other MetalUniversal contributors to clone and run. It is not
yet a standalone package or generic Metal test SDK: the Java/native bridge,
Minecraft client validation, and several fixtures are coupled to this project.

### Requirements

- macOS with an Apple GPU;
- JDK 25 for the Minecraft 26.2 Java sources;
- Xcode command-line tools and the Metal/MetalFX frameworks;
- macOS 26 and Metal 4 support for the M4/FrameInterpolator cases;
- an unlocked, local WindowServer session for the two presentation tasks.

Unsupported Metal 4 or presentation hosts are capability-gated or skipped, but
a skipped task is not acceptance evidence. Linux/headless CI can review and
compile the Java surface only with additional project-specific setup; it cannot
execute the Metal readback and presentation gates.

### Clone and run the non-windowed suite

```bash
git clone https://github.com/21Z121Z1/MetalUniversal.git
cd MetalUniversal
git switch claude/framegen-comparison
export JAVA_HOME='/path/to/a/JDK-25/Contents/Home'
./gradlew \
  test \
  metalMrtSmokeTest \
  metal4PipelineSmokeTest \
  metal4PipelinePathTest \
  metalHudRuntimeTest \
  metalFrameGenerationLifecycleTest \
  metalFxOffscreenValidation \
  metalMrtBackendIntegrationTest \
  --no-daemon --console=plain
```

This is the useful default for remote contributors because it does not require
a scanout-visible layer. It still requires macOS/Metal for the native tasks.

### Run the visible presentation suite

Run this locally with the console unlocked and the test window visible:

```bash
./gradlew \
  metalFrameGenerationPresentationValidation \
  metal4PresentValidation \
  --no-daemon --console=plain
```

Do not run this through a locked desktop or a headless runner. A missing or
occluded WindowServer priming frame can fail before GPU work starts. The Metal 4
present task currently disables `MTL_DEBUG_LAYER` because macOS 26.5 MetalFX
calls `globalTraceObjectID` on Apple's MTL4 debug wrapper and aborts. API
Validation remains exercised by the non-windowed M4 path test; do not describe
the M4 present run itself as Debug Layer validated.

### Performance artifacts

```bash
./gradlew metalFxPerformanceValidation --no-daemon --console=plain
```

Primary outputs are written under:

```text
build/metal-validation/offscreen-current/
build/metal-validation/performance-current/
build/metal-validation/presentation-current/
build/metal-validation/presentation-metal4/
```

The performance task is a measurement harness, not a universal pass/fail
benchmark. Contributors should publish their Mac model, macOS version, input
and output sizes, warm-up/repetition count, and full artifact directory when
comparing results.

### Minecraft production validation boundary

`minecraftMetalFxClientValidation` adds deterministic scene scripting,
frame-exact GPU readbacks, a required `run-state.json`, and fail-closed checks
that reject a client which never reached the scripted timeline. It is useful
for this repository, but it expects the project's run directory, Minecraft
assets, Sodium configuration, and a prepared test world. It is not the first
command an external contributor should run.

Share draft PR #1 as the framework review link. Do not distribute the current
JAR as a stable M4 build while the geometry corruption in this handoff remains
open.

## Completion gate for this bug

Do not declare this geometry bug fixed until all are true:

- the minimal multi-draw M4 regression test fails before and passes after;
- the M3 kill switch remains fully functional;
- the same real Minecraft camera is clean on M3 and M4;
- M4 is clean with Temporal OFF and with Temporal 1.0;
- at least two cold M4 launches run 600 frames without stretched geometry,
  no-free-slot, timeout, command-buffer creation failure, or API validation
  error;
- the fix is measured for CPU/GPU cost and does not silently allocate an
  unbounded argument table per draw;
- the fix, evidence, commit, and updated limitation statement are pushed to
  draft PR #1.

The user explicitly stopped the broader migration work at this handoff. The
next task should only use this document to diagnose the M4 geometry corruption
or help contributors run the published validation framework. Do not resume the
remaining migration, performance, transparency, LOD, or Frame Generation work
without a new user request.
