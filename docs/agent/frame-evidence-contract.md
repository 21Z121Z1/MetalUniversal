# Frame Evidence Contract v1

P0 observation slice. This is a permanent opt-in observer, not a rendering optimization
or evidence that P0's full metric coverage is complete. It leaves scheduling, draw order,
native ABI descriptors, resource ownership and presentation unchanged.

## Authority and coverage

| Observation | Authority | Boundary |
|---|---|---|
| Source commit/tree/dirty state | Embedded `metallum-build-identity.json` from `processResources` | Caller-supplied SHA cannot replace build provenance; dirty builds reject exact-head verification |
| Native binary | SHA-256 of the file actually loaded by `MetalNativeBridge.extractAndLoad` | Preloaded iOS modules remain unavailable |
| Java binary | Runtime code-source JAR SHA-256 | Loom development classes explicitly report `unavailable-dev-classes`; packaged acceptance requires `--require-packaged` |
| CPU frame interval | `Minecraft.renderFrame` HEAD through RETURN, monotonic clock | Includes observer overhead, extraction and render work; not input latency or source FPS |
| ABI count and inclusive/exclusive duration | All four central `MetalNativeBridge` downcall factories | Render-thread calls inside this frame only; worker/startup calls excluded; duration includes native waits/compilation |
| GPU service time | Existing main command-buffer completion and GPU start/end timestamps | One row per submission; never renamed GPU frame critical-path time or present time |
| Producer entries | Vanilla completed layer submission, Sodium terrain setup, active Iris generation | Distinct facts; installed mods and producer entry do not prove an optimized draw executed |
| Terrain latency | Existing generation-keyed `terrain-work-epoch-*.json` and oracle | No proximity or ordinal join with this observer; first encoded/drawn is not first presented |

Frame IDs are observation-local joins. They do not replace semantic pass IDs,
`ResourceIdentity`, terrain generations or MetalFX source-frame IDs. Command-buffer
identity is captured at creation and carried through submission and completion.
Delayed/out-of-order completion cannot be assigned to the latest frame. Reused buffers
crossing frame boundaries invalidate evidence. Existing `Metallum frame <submitIndex>`
command labels allow inspection in GPU captures; they remain diagnostic labels.

Minecraft 26.3's `renderFrame` argument is `advanceGameTime`, not world visibility.
`renderLevel` in the evidence means a client world was actually loaded at frame entry.
Recursive loading-screen calls retain `parentFrameId` and restore their outer scope.
Their CPU duration is inclusive; do not sum nested frame durations as elapsed time.

Each frame retains drawable dimensions, render distance and the actual Metal main path.
The report lists missing native-internal draw/encoder counts, effective PSO/binding changes,
memory/copy metrics, worker costs and presentation evidence under `unavailable`.
Do not infer those metrics from similarly named ABI calls. The existing aggregate
performance report is unchanged; its samples are not silently upgraded to frame evidence.

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
