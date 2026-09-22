# Frame Evidence Contract v1

P0 observation slice. This is a permanent opt-in observer, not a rendering optimization
or evidence that P0's full metric coverage is complete. It leaves scheduling, draw order,
existing ABI descriptors, resource ownership and presentation unchanged.

## Authority and coverage

| Observation | Authority | Boundary |
|---|---|---|
| Source commit/tree/dirty state | Embedded `metallum-build-identity.json` from `processResources` | Caller-supplied SHA cannot replace build provenance; dirty builds reject exact-head verification |
| Native binary | SHA-256 of the file actually loaded by `MetalNativeBridge.extractAndLoad` | Preloaded iOS modules remain unavailable |
| Native build provenance | Packaged `natives/macos/libmetallum-build-identity.json`, emitted after successful shipping Swift compilation | Its binary hash must match the loaded file, and its source/tree/dirty/input hashes must equal the Java build identity; absent/stale manifests cannot approve packaged windows |
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
The native observer retains the latest 65,536 presentation tickets in insertion order when evidence is enabled,
without retaining GPU objects. Cancelled, invalid, pending and unretained observations have
separate absence reasons. Every 64 source scopes the observer copies already-received callbacks
for unresolved captured tickets; successful copies survive native eviction. Export also queries
after the existing GPU drain; neither operation waits for presentation. A late callback remains
unavailable in that final snapshot. Eviction cannot resurrect or reassign a ticket.
A callback with `presentedTime == 0` is `drawable-not-presented`: the SDK permits zero
when a drawable was not presented or was skipped. Negative and non-finite callback times
are `invalid-presented-timestamp`. Both close the ticket without creating a display event;
GPU completion cannot replace either receipt. This classification does not resolve the
physical cause of PR #72's missing presentation timestamps.
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
`metallum.frameEvidence.capacity` defaults to 16,384 frames, with at most 256 submissions
per retained scope; overflow is reported and rejected, never silently trimmed into a
successful sample. Start a new process for each trial instead of resetting counters
across in-flight submissions.

The verifier checks exact source identity, clean build, native identity, validation
completion, monotonic unique frame IDs, ABI timing invariants, loss, submission lifecycle
and measurement availability. Legacy unwindowed P50/P95/P99 summaries include **all
observed world frames**, including warmup, and remain diagnostic only.
Passing returns `valid-observation` when integrity holds but comparison prerequisites
are incomplete; a complete packaged replay may return `comparison-ready`, with the
previous status retained in `legacyStatus`. `comparison-ready` is still only a
prerequisite result: use the existing unified correctness and paired-trial protocol
for performance acceptance.

Disabled instrumentation retains original downcall handles. `timing` mode also retains
original ABI handles and omits Java per-submission encoding/wait diagnostic reads; it
still records source context, submission identities, completion and native presentation
receipts. `diagnostic` adds ABI timings and native counters. Neither mode has an accepted
overhead budget until matched physical off/on measurements pass.

## Explicit ordinary window and replay

The existing production gameplay launcher supports `--frame-evidence off|timing|diagnostic`.
The fixed `vanilla-normal-stationary-v1` profile uses the existing normal-world route,
native output/internal dimensions, FABULOUS/render distance 32, requested 260 fps with
vsync disabled, and MetalFX off. Optional mods are absent. It declares a stationary
warmup and sample window **before running**, defaulting to 5 seconds and 10 seconds.
The stationary route accepts `--frame-evidence-warmup-seconds` and
`--frame-evidence-sample-seconds`; each is bounded to 1–300 seconds and their sum
must not exceed 300 seconds. Off and timing runs use the same configured window.
Streaming keeps its existing route contract and accepts only the default 5-second
warmup and 10-second sample. These are engineering observation windows, not the
thermal/performance acceptance protocol. Full existing flight, input, block
placement/break and quality assertions must subsequently finish successfully.
`--frame-evidence-phase streaming` arms the same format at the existing input-driven
flight phase with the declared default durations; it does not add another driver or
change the route.

Before warmup the test driver flushes and copies a disposable world's initial content,
with a sorted path/file-hash manifest. `--initial-world` replays a verified snapshot into
only a newly created disposable test world. Fresh seed alone never proves equal input.
Use the same saved snapshot for off/on runs; preserve the snapshot and manifest together.

```bash
./gradlew --no-daemon jar -x buildIOSNative -x buildIOSSpvc
# First run preserves a snapshot; all runs must use a clean committed JAR.
python3 scripts/agent/record_vanilla_gameplay.py --jar build/libs/metallum-1.0.3.jar \
  --output build/frame-seed --trial-id seed --metrics-only --frame-evidence off
python3 scripts/agent/record_vanilla_gameplay.py --jar build/libs/metallum-1.0.3.jar \
  --output build/frame-off --trial-id off-A --metrics-only --frame-evidence off \
  --initial-world build/frame-seed/initial-world
python3 scripts/agent/record_vanilla_gameplay.py --jar build/libs/metallum-1.0.3.jar \
  --output build/frame-on --trial-id on-A --metrics-only --frame-evidence timing \
  --initial-world build/frame-seed/initial-world
python3 scripts/agent/verify_frame_evidence.py build/frame-on/frame-evidence.json \
  --expected-head "$(git rev-parse HEAD)" --require-packaged --require-comparable
```

For the production gameplay launcher, non-`off` runs pass the bounded segmented
archive setting and record the evidence mode, phase, segmented setting and
explicit trial identity in `recording.json`. The launcher checks the supplied
snapshot manifest before launch and requires the runtime replay hash to match it;
use one immutable snapshot and distinct `--trial-id` values for each A/A or off/on
run. It waits for normal client exit, including the existing device shutdown
drain, before requiring a complete archive and invoking the verifier. Mode,
phase and trial identity are checked against the exported archive. The verifier
may return `valid-observation`, `invalid-evidence`, or `comparison-ready` when
packaged replay prerequisites are complete; the latter still does not establish
physical performance acceptance.
`off` runs do not require a frame-evidence archive, but their `recording.json`
still records the trial and replay identity for pairing.

For a bounded multi-segment stationary observation, keep the same command and add
for example `--frame-evidence-warmup-seconds 5 --frame-evidence-sample-seconds 40`.
The runner receipt, gameplay profile and archive window must all carry that same
declaration; the fixed source timestamp storage remains bounded and reports overflow.

For a controlled fixed-view physical baseline, add `--stationary-baseline` to the
existing launcher with `--metrics-only --initial-world <snapshot>` and `off` or `timing`.
`vanilla-stationary-60-v1` requests 60 FPS and VSync through the existing Minecraft
options; it does not alter production scheduling or claim a system deadline. The
native-max profile above remains a headroom workload. The stationary route ends after
its configured warmup and sample (5 seconds and 10 seconds by default), retaining pose, native dimensions, quality,
Metal 4 activation and sample-completion checks; it does not run the movement route.
After measurement, it requests normal integrated-server halt on the server thread and
drives test ticks until shutdown before closing the world, avoiding Fabric's client/server
phase-barrier deadlock in 26.3 `IntegratedServer.halt`. The ordinary client/device shutdown
and its existing GPU drain still own final evidence export.

Before warmup, the fixed camera must have an empty section-task queue and all existing
section-task buffer packs free, plus an empty occlusion expected-chunk set, no uncompiled
visible section, and uploaded/admissible layer draws. Readiness records the queue size,
buffer capacity/free count, and `scheduledSectionWorkComplete`; this covers already
scheduled section work, not hidden dirty sections that have not been scheduled. A canonical
hash of section nodes and layer draw metadata must remain stable for at least 40 checks
and 2 seconds. The harness then requests Vanilla's existing full occlusion rebuild
(to remove loading-order-dependent conservative accumulation), waits for the graph task
and frustum update to finish without blocking, and repeats convergence. It is recorded
again after sampling; section identity and draw admission must still match. Normal
world ticks can change layer draw counts (for example kelp growth or grass eating).
Their raw rows, initial/final draw hashes and `stationaryGeometryChanged` remain
reported; do not freeze gameplay or claim identical per-frame geometry. Visible-section count,
pose and quality remain guarded each source frame. This proves a bounded stationary
rendering workload condition, not JVM-object equality or pixel identity. Pairwise analysis
must additionally match the section-identity hash, snapshot, quality, target and physical
display conditions. Report natural geometry evolution when interpreting overhead; these
readiness checks do not establish identical GPU work or a renderer optimization win.

`stationarySourceFrames` is present in both off/timing runs. It counts source returns in
its declared half-open Java-clock window, excluding warmup; intervals require both endpoints
inside that window. It is distinct from recorder source-entry and native presentation metrics.
Use off → timing → timing → off and report raw common metrics and both adjacent paired
deltas. A capped source rate can bound observable frame-delivery overhead; it cannot prove
zero CPU cost. Retain all trials and classify noisy results as inconclusive. Timing-only
presentation fields are never the off/on overhead oracle.

The observer admits root source scopes whose Java monotonic start lies in `[startNs,endNs)`.
Nested scopes inherit parent membership; warmup and long session tails allocate no retained
frame history. A level-object change advances the epoch, invalidating a cross-world window.
The source count rate uses root starts divided by the declared Java window duration.
Presentation analysis uses the complete callback cohort belonging to these captured source
scopes, including callbacks after the source window ends. It sorts actual drawable timestamps
independently of callback arrival order. The actual-present event-span rate is `(N-1)/(last-first)`
on that drawable clock; it is explicitly **not** a count divided by the Java window duration.
Uncalibrated Java/native clocks are never subtracted. System DisplayLink deadline stays unavailable.
The analyzer preserves the chronological interval sequence and ticket/source IDs, the ten
worst intervals, fixed-target long-frame events, and clusters of adjacent long intervals.
The long-frame threshold is twice the predeclared application target interval, not twice
the observed median and not a system deadline. Unlimited cadence supplies no threshold.
Without a native half-open presentation window, `presentedFps` remains unavailable; the
legacy callback-cohort event-span rate is not renamed to formal presented FPS.
Distinct native tickets can receive coincident presented timestamps. Keep every callback and
the zero interval; do not deduplicate by time. Such captures are valid observations, but distinct
display-event count/rate and P99.9 are unavailable and comparison eligibility fails closed.

Missing/cancelled/evicted callbacks, native identity gaps, unfinished windows, epoch or quality
changes, route failure and capacity loss cannot silently approve comparison. Observed partial
distributions retain their coverage label. P99.9 requires at least 10,000 intervals (ten nominal observations in the 0.1% tail, not a confidence guarantee); otherwise
its value is unavailable with the sample-count reason. Nested presentations remain visible
but need independent comparison proof. File export uses an atomic final rename; failed export
logs the error and leaves no new successful final report (a `.partial` file is not evidence).

`valid-observation` remains an integrity result and `invalid-evidence` rejects malformed
or incomplete evidence. A `comparison-ready` status means only that the verifier's
replay, artifact, window and coverage prerequisites passed. `productPromotable` is
always false in this verifier; no status asserts physical parity, accepted observer
overhead, pacing, power, or an optimization win. Legacy median and per-trial
`2 × median` stutter diagnostics cannot promote this result. Native pending-ID accounting is separately bounded to the latest 65,536 scheduled IDs,
even with the evidence observer off. Expiring an unresolved ID makes aggregate frames-in-flight
permanently unavailable (`-1`); later schedules/completions cannot manufacture a recovered count.
Its receipt becomes unretained, and a late callback cannot resurrect it. Recent authoritative
callbacks remain observable. Consumers already treat negative occupancy as unavailable; this
changes no scheduling, synchronization, drawable lifetime or display-wait behavior.

Review base for this continuation is `1fe0df7098218477c1006cb557b0b640f3540460` on
`codex/frame-evidence-contract-v1-20260920`. At branch creation the declared canonical
`integration/metaluniversal` was absent remotely; `master` and `integration/iris-metal-next`
were 26.2. This branch does not resolve or change that shared-mainline decision.

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

The controlled stationary pair makes this opt-in explicit: the baseline uses
`--optimization-profile baseline-v1` with reuse off, while the candidate uses
`--optimization-profile reuse-encoder-state-v1 --reuse-encoder-state`. Both keep
the same `vanilla-stationary-60-v1` route, native quality, cadence, immutable
initial-world snapshot and bounded window; only the encoder-state reuse feature
changes. `gameplay.json`, `recording.json` and the frame profile record that
pair identity. The gameplay route enables only the narrow reuse activation
counters for both pair members and the runner requires packet and state-shadow
reuse hits from the candidate. These counters are cumulative from client startup
through gameplay completion, not a sample-window cost. This proves route
activation, not comparable performance or physical acceptance.

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

The gameplay driver records paired `System.nanoTime`/`Instant` anchors around
`FrameEvidenceRuntime.armWindow` and at the observed source-window end. These
anchors calibrate Java monotonic time to host wall time for diagnostics only;
`frame-evidence.json.window.startNs/endNs` remains the recorder authority, and
native `presentedTime` is never mixed into that calibration. After a profiled run,
the runner exports the Game Performance `ca-client-present-request` and
`ca-client-presented-handler` tables for the attached client PID. Its
`recording.json.traceCoverage` result is `unavailable` when the archive, clock,
PID or table data cannot be mapped and `partial` when present events overlap the
archive window or are otherwise only event samples. Event rows do not prove continuous coverage, so this report is
kept separate from archive validity and performance conclusions. In `off` mode
the source-frame declaration remains the workload authority and Xcode coverage is
unavailable because there is no recorder window to join.

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

A shared ordinary command buffer may be allocated by tick uploads before a source
scope. Its first use inside a retained source scope records the existing native
submit index; reuse never replaces an already recorded owner. GPU service time
therefore describes the whole buffer, including any preceding uploads, rather than
a source-only critical path. Presentation coverage requires this carry-in ownership:
a complete callback subset is not proof that all ordinary requests were recorded.
