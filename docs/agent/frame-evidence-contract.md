# Frame Evidence Contract v1

P0 observation slice. This is a permanent opt-in observer, not a rendering optimization
or evidence that P0's full metric coverage is complete. It leaves scheduling, draw order,
existing ABI descriptors, resource ownership and presentation unchanged.

## Authority and coverage

| Observation | Authority | Boundary |
|---|---|---|
| Source commit/tree/dirty state | Embedded `metallum-build-identity.json` from `processResources` | Caller-supplied SHA cannot replace build provenance; dirty builds reject exact-head verification |
| Native binary | SHA-256 of the file actually loaded by `MetalNativeBridge.extractAndLoad` | Preloaded iOS modules remain unavailable |
| Java binary | Runtime code-source JAR SHA-256 | Loom development classes explicitly report `unavailable-dev-classes`; packaged acceptance requires `--require-packaged` |
| CPU frame interval | `Minecraft.renderFrame` HEAD through RETURN, monotonic clock | Includes observer overhead, extraction and render work; not input latency or source FPS |
| ABI count and inclusive/exclusive duration | All four central `MetalNativeBridge` downcall factories | Render-thread calls inside this frame only; worker/startup calls excluded; duration includes native waits/compilation |
| Native encoding counts | Actual successful encoder creation and emitted direct/ordinary-indirect draw commands | Partial: main encoder bridges, clear helpers and ordinary presentation; excludes MetalFX and GPU-scene/ICB internal work |
| GPU service time | Existing main command-buffer completion and GPU start/end timestamps | One row per submission; never renamed GPU frame critical-path time or present time |
| Native presentation ticket | Ordinary-present encode return or the same owning command buffer at completion | Metal 4 assigns its ticket at native commit; never joined by timestamp proximity |
| Presented timestamp | Existing `CAMetalDrawable.addPresentedHandler`, keyed by that native ticket | Source frames only; `presentedTimeSeconds` is not GPU completion, input latency or generated FPS |
| Drawable acquisition wait | Existing timer around the ordinary present path's `nextDrawable`, carried by the same command buffer | `drawableWaitNs` is CPU wall time inside the present ABI; do not add it to ABI time or equate it with GPU work |
| Producer entries | Vanilla completed layer submission, Sodium terrain setup, active Iris generation | Distinct facts; installed mods and producer entry do not prove an optimized draw executed |
| Terrain latency | Existing generation-keyed `terrain-work-epoch-*.json` and oracle | `terrainBatchIndices` explicitly joins the existing terrain `frameIndex`; first encoded/drawn is not first presented |

Frame IDs are observation-local joins. They do not replace semantic pass IDs,
`ResourceIdentity`, terrain generations or MetalFX source-frame IDs. Command-buffer
identity is captured at creation and carried through submission and completion.
When both observers are enabled, `terrainBatchIndices` records the existing Vanilla
draw-batch index after a nonempty layer returns. That index is the terrain report's
`frameIndex`, not this observer's `frameId`. Repeated layers coalesce within the source
frame; more than 64 distinct batches invalidates the observation. This joins generation
events to source-frame evidence without timestamps, new mesh identities or GPU waits.
It does not attribute individual meshes to command buffers or prove pixel visibility.
`presentationRequested` distinguishes an offscreen submission from an ordinary
present attempt. `nativePresentationId` is the existing native ticket when returned,
otherwise null with a reason. A failed command buffer retains its scheduled ticket;
neither the ticket nor GPU completion is an assertion that the image was displayed.
`presentedTimeSeconds` is filled only by an actual finite, positive drawable callback.
The native observer retains the first 65,536 presentation tickets when evidence is enabled,
without retaining GPU objects. Cancelled, invalid, pending and unretained observations have
separate absence reasons. Export queries callbacks already received after the existing GPU
drain; it never waits for presentation. A late callback remains unavailable in that snapshot.
Delayed/out-of-order completion cannot be assigned to the latest frame. Reused buffers
crossing frame boundaries invalidate evidence. Existing `Metallum frame <submitIndex>`
command labels allow inspection in GPU captures; they remain diagnostic labels.

Minecraft 26.3's `renderFrame` argument is `advanceGameTime`, not world visibility.
`renderLevel` in the evidence means a client world was actually loaded at frame entry.
Recursive loading-screen calls retain `parentFrameId` and restore their outer scope.
Their CPU duration is inclusive; do not sum nested frame durations as elapsed time.

Each frame retains drawable dimensions, render distance and the actual Metal main path.
The report lists uncovered native-internal draw/encoder paths, effective PSO/binding changes,
memory/copy metrics, worker costs and generated-frame evidence under `unavailable`.
Do not infer those metrics from similarly named ABI calls. The existing aggregate
performance report is unchanged; its samples are not silently upgraded to frame evidence.

`commandBuffers[].nativeEncoding` reports render/compute/blit encoder counts and direct/
ordinary-indirect draw counts, or null when unavailable. Counts belong to the existing
command buffer (a per-submission lease on Metal 4) and are copied after its existing
completion check, before release. No GPU wait is added. The opt-in native v1 ABI copies
five signed 64-bit fields into caller-owned memory and retains no output pointer.
Metal 4 upload encoders count as compute encoders. These are encoded commands, not
visible draws or whole-frame totals. Disabled observation allocates no counter object
and performs no counter readback call.

`commandBuffers[].drawableWaitNs` reuses the ordinary present path's existing timer,
including failed drawable acquisition. A separate optional v1 export accepts the borrowed
command-buffer pointer and returns signed 64-bit nanoseconds after completion, before
release. The five-field encoding ABI stays unchanged. Offscreen/unobserved work and older
native modules return `-1`, exported as null with a reason; measured zero remains zero.

## Capture and verification

Use a disposable **copy** of a test world. The existing validation driver changes its
test scene. All output remains under ignored `build/` paths. For example:

The current implementation and validation scope is **Minecraft 26.3 RenderPearl
Vanilla without Sodium or Iris**. Iris adaptation is outside this work. Existing
adapter sources do not establish current compatibility or require further adapter work.

The `15ea1ef5` baseline failed before world rendering: common frame/depth helpers
linked absent Iris classes, then contradictory indirect capability flags rejected
Vanilla terrain draws. Common adapter entry points now check mod presence, and the
device advertises the ordinary indirect draws already implemented by both native
Metal paths. Missing bindings still fail with their own resource error.

The isolated Java check deliberately removes both optional mods and enables a stale
Iris opt-in flag. The GPU regression goes through the real RenderPearl frontend and
reads back indexed/non-indexed batches with nonzero argument offsets, first index,
negative vertex offset and nonzero first instance on Metal 3 and Metal 4:

```bash
./gradlew --no-daemon -Pmetallum.noOptionalMods=true vanillaRenderPearlBoundaryTest \
  -x buildMacNative -x buildIOSNative -x buildIOSSpvc
./gradlew --no-daemon renderContractMetal3NativeTest renderContractMetal4NativeTest \
  --tests '*renderPearlIndirectDrawsPreserveOffsetsAndFirstInstance' \
  -x buildIOSNative -x buildIOSSpvc
```

The migration CI also runs the isolated Java check. It is not GPU or game acceptance.
Require the real-client report's mod list to omit Sodium/Iris, its main readback to
pass, and its terrain lifecycle report to pass the independent oracle. A nonzero
readback proves image production, not full Minecraft pixel parity or performance.

The existing production Client GameTest also has a Vanilla lane. Build from a clean
commit, then run the actual JAR with Fabric's test driver. It creates disposable worlds
under the test project's `build/` directory and exercises readback controls, world
rendering, ordinary presentation and resource reload:

The main world scene explicitly uses the normal Overworld preset, seed `1`, with
structures enabled. Its eight captures follow a roughly 750-block route beyond
spawn so ordinary terrain generation and chunk uploads are exercised. The runtime
report records the actual generator, seed, save path and capture waypoints. Small
readback controls retain their isolated fixtures; they are not terrain coverage.

```bash
./gradlew --no-daemon jar -x buildIOSNative -x buildIOSSpvc
./gradlew --no-daemon -p .github/ci/minecraft-e2e \
  -PmetallumJar="$PWD/build/libs/metallum-1.0.3.jar" \
  -Pmetallum.noOptionalMods=true -PmetallumSourceSha="$(git rev-parse HEAD)" \
  runProductionClientGameTest
```

`artifact-identity.json` records the JAR that actually defined `MetalDevice`, its
embedded clean source identity, bundled native hash and the loaded mod set. Its
bundled native hash is an artifact check, not a separate observation of the native
load path. CI checks the loaded JAR against its independently built artifact. The
resource-reload/presentation reports prove completion and recovery; they do not yet
prove terrain generation safety across teleport and dimension changes.

```bash
./gradlew --no-daemon minecraftNativeFullscreenBaseline \
  -Pworld="frame-evidence-test-copy" -Pmetallum.noOptionalMods=true \
  -Dmetallum.frameEvidence.enabled=true \
  -Dmetallum.frameEvidence.trialId=p0-vanilla-01 \
  -Dmetallum.validation.output="$PWD/build/agent-runs/p0-vanilla-01" \
  -Dmetallum.terrain.vanillaWorkEvents=true
python3 scripts/agent/verify_frame_evidence.py \
  build/agent-runs/p0-vanilla-01/frame-evidence.json --expected-head "$(git rev-parse HEAD)"
```

The report is written after the existing device shutdown drain. It introduces no GPU
wait or per-frame file I/O. A crash may leave no report; missing reports are not passes.
`metallum.frameEvidence.capacity` defaults to 16,384 frames; overflow is reported and
rejected, never silently trimmed into a successful sample. Start a new process for each
trial instead of resetting counters across in-flight submissions.

The verifier checks exact source identity, clean build, native identity, validation
completion, monotonic unique frame IDs, ABI timing invariants, loss, submission lifecycle
and measurement availability. Its P50/P95/P99 summaries include **all observed world
frames**, including warmup. They are diagnostic and cannot approve a performance PR.
Passing returns `valid-observation-no-performance-decision`. Use the existing unified
correctness and paired-trial protocol for performance acceptance.

Disabled instrumentation retains original downcall handles. Enabled overhead is not
yet budgeted or accepted; do not use this diagnostic lane as low-overhead performance
instrumentation until matched off/on correctness and overhead measurements pass.

## Xcode 27 / macOS 27 tools

Apple's [GPTK4 workflow](https://developer.apple.com/videos/play/wwdc2026/357/)
provides command-line capture/debugging. Probe the installed toolchain first:

```bash
xcodebuild -version
xcrun xctrace list templates
xcrun gpucapture --help
xcrun gpudebug --help
```

For a separate diagnostic run, launch the client with `MTL_CAPTURE_ENABLED=1` in its
environment. Use the observed **client PID**, not the Gradle daemon PID:

```bash
xcrun gpucapture list
xcrun gpucapture boundaries --pid "$CLIENT_PID"
xcrun gpucapture start --pid "$CLIENT_PID" --boundary "$BOUNDARY_ID" \
  --count 1 --output "$RUN_DIR/frame.gputrace"
xcrun xctrace record --template 'Game Performance' --attach "$CLIENT_PID" \
  --time-limit 10s --output "$RUN_DIR/game-performance.trace"
xcrun xctrace export --input "$RUN_DIR/game-performance.trace" --toc \
  --output "$RUN_DIR/game-performance-toc.xml"
```

Select a reported CAMetalLayer boundary for presentation-frame capture; queue/device
boundaries count command buffers, not frames. `Game Memory` and `Metal System Trace`
are separate diagnostic templates. Keep PID, tool versions, commands, exit status,
capture hashes and source/binary/scenario identity together with the run manifest.
Capture and Instruments perturb execution; their timing is not an ABBA trial sample.
Use `gpudebug --gputrace <capture> --json` to inspect a capture, consulting its live
command help. Never equate capture creation with successful replay or visual parity.
Treat profiling capability rejection separately from capture success. For example,
the Xcode 27 tool on M1 Pro rejects `profile run` with "GPU profiling requires Apple
M3 / A17 Pro or later" while capture and command inspection work.

## Regression gate and next slice

`verify_unified_eval.sh` runs the recorder tests and independent verifier self-tests.
The first physical gate is telemetry off/on parity for the same copied world and
adapter, followed by overhead measurement. Then connect native-internal counters and
present IDs at their real authority boundaries. Keep the existing terrain lifecycle
oracle; extend it only when first-presented generation identity can actually be proven.
P1 transport batching remains conditional on the resulting ABI evidence.

## Submission transport

The existing separate indexed multi-draw now flushes pending render state before encoding.

ICB eligibility is cached with the final PSO for each attachment signature. Ineligible
terrain avoids ICB-only snapshot copying and encoder transitions; explicitly requested
diagnostic snapshots remain available. This does not introduce a Vanilla GPU scene or
change the existing ordinary indirect terrain producer. Pipeline close is idempotent,
invalidates cached variants, and prevents background prewarm from repopulating them.

`-Dmetallum.opt.asyncPrecompile=true` also uses RenderPearl's existing loading
executor for backend translation and native PSO preparation. `finishCompile` hands
ownership to the frontend after checking the device/cache generation. Abandoned
prepared pipelines are released on cache clear or device close; the default keeps
the previous deferred compilation path. This does not make demand compilation
asynchronous when the caller supplies an inline executor. Attachment variants
resolve functions from their source key under the existing compile lock, so a
function-cache clear cannot leave a borrowed handle in a still-live variant owner.

Ordinary presentation samples the submitted `GpuTextureView`, including its base
mip, rather than the underlying texture's level zero. Pre-present capture uses the
same mip and dimensions. A subview does not inherit a full-texture MetalFX synthesis
receipt and continues through ordinary source-frame presentation.

## Normal-world gameplay profiling

The existing production Client GameTest has an opt-in `-Pgameplay=true` route.
It uses [Fabric 26.3 TestInput](https://github.com/FabricMC/fabric/blob/26.3/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/TestInput.java)
for creative flight across new chunks, walking/jumping, inventory, block placement
and breaking, and camera turns in rain. World generation remains normal, seed 1;
initial positioning and landing are explicit setup teleports. Simulation, entities,
weather and texture animation continue normally. This is a bounded gameplay workload,
not a claim to cover every survival action or every biome.

Build the committed production JAR, then record on a physical Mac with Xcode 27:

```bash
python3 scripts/agent/record_vanilla_gameplay.py \
  --jar build/libs/metallum-1.0.3.jar \
  --output build/agent-runs/gameplay-unique-run
```

Performance recording uses the Metal 4 main renderer and proves native submissions
at the workload boundaries. Metal 3 remains a correctness-tested compatibility path.
The launcher attaches Instruments' `Game Performance` template to the exact client
PID. A recording-start notification releases the input route, so attachment startup
does not consume the workload. `gameplay.trace` contains CPU/Metal/system activity;
`gameplay.jfr` supplies Java method stacks. `gameplay.json` records actual movement,
interaction results and phase timestamps. High-frequency hot-path counters and broad
readbacks stay off during this route. The output directory must be new; saves and
previous recordings are preserved.

The native-max route uses the main display's physical pixel dimensions (not its
HiDPI logical desktop dimensions), fullscreen, Vanilla's Fabulous preset and the
26.3 maximum render distance of 32. Vsync is off and the FPS option is Unlimited.
The report records every quality option and rejects changed settings, reduced
framebuffer/render-target dimensions, effective view distance below 32, throttling
or timestamp overflow. A test-only hook counts source `GpuSurface.present` calls
and frame intervals. These are source submissions, not monitor refresh or generated
frames. Phase boundaries include source counts for a streaming-only breakdown.
Fabric's virtual framebuffer is resized through `TestInput.resizeWindow` too.
The launcher scopes SDL's documented `SDL_VIDEO_MAC_FULLSCREEN_SPACES=0` hint to
the client process, using desktop fullscreen so a macOS fullscreen Space does not
subtract the camera/menu strip from the drawable. Native SDL pixel size, source
texture size and present configuration must all equal the physical display.

`--reuse-encoder-state` enables the candidate CPU state-shadow and packet-scratch
reuse. Each live encoder exclusively owns its state; both ordinary and retained
native-handle closure invalidate/release it. GPU objects and resources are not
pooled by this change. The production default remains off pending paired evidence.
Pipeline binding indices use an immutable lookup built at pipeline creation.

The initial JFR flight profile identified state-shadow arrays and indexed-binding
list iterators as allocation hot spots. Reusing stable objects follows Apple's
[persistent objects guidance](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/MTLBestPracticesGuide/PersistentObjects.html).
JFR allocation sample weights are estimates; compare measured GC/frame tails as
well as allocations. The 16-chunk development recordings are not performance
baselines for this 32-chunk, native-resolution route. Xcode export can take several
minutes after capture stops; the launcher allows it to finish saving.
Use `--metrics-only` for timing trials after hotspot capture: it runs the same
world, inputs and quality settings without Instruments or JFR overhead, retaining
the bounded source-frame counter. Do not compare these FPS values directly to a
profiled run or treat one trial as an accepted improvement.

For pass attribution, use `--capture-seconds 10 --render-labels`. This records an
Instruments clip at the beginning of the route; JFR and the gameplay report still
cover the full route. Inspect exported timestamp coverage: a long trace can retain
only its trailing GPU events even when its overall recording duration is complete.
Labels use Vanilla's existing `--renderDebugLabels` option and are rejected with
`--metrics-only` so diagnostic label work does not enter timing trials.

`--presentation-metrics` optionally samples the existing native drawable-wait
getter once after each ordinary source present. The report and phase boundaries
include cumulative `drawableWaitNanos` and `drawableWaitSamples`. This measures
the synchronous ordinary-present path only, not GPU service time or displayed
frame cadence. It adds one FFM call per frame; keep it off for timing trials and
compare diagnostic runs only with the same instrumentation. With it disabled,
the source-frame observer still performs no per-frame FFM call.

Use the trace to choose a concrete optimization, then compare that candidate under
the same workload. A completed route proves only its reported actions; it does not
replace image correctness, paired performance trials or GPU validation. Xcode's
M3-only offline GPU-counter profiler is separate from the Instruments timeline and
must not be reported as available on an M1 Pro.

The native-max JFR capture also found compatibility binding-cache allocations on
the Vanilla path. Those two mixins now load only with Sodium or Iris; their optional
producer behavior is unchanged. Depth/stencil admission compares the same allowed
format pairs without temporary records, and descriptor traversal avoids an iterator.
The bounded render-graph event list stops constructing events at capacity while
continuing all cumulative counters; reset reopens event capture. These are candidate
CPU/allocation reductions, not a measured FPS improvement.

Fence polling follows the 26.3 producer contract: an unsubmitted fence queried
with a zero timeout stays pending and does not force a native submission or rotate
transient allocations. Explicit blocking waits retain the staging-ring flush path.
When no native command buffer exists yet, a blocking wait also materializes any
pending whole-texture clears before selecting the preceding submission as its
completion witness. Zero-time polls leave those clears pending.
The RenderPearl negative timeout sentinel maps to an infinite native wait; completed
fences cache their completion. The native readback regression covers poll, explicit
submit, completion and transient-slice lifetime together.
When a ring rotates after submission and no deferred work remains, its fence
captures the preceding actual submission. It does not acquire a dependency on
the next frame merely because encoding later resumes on the same command encoder.
