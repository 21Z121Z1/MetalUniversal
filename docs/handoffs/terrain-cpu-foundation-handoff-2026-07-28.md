# Terrain CPU scheduling foundation handoff

Date: 2026-07-28
Branch: `claude/framegen-comparison`
Scope: Phase 1 only; no terrain arena, mega-batcher, ICB, GPU culling, Frame
Generation, Temporal, dynamic resolution, or renderer migration work.

## Implementation

The implementation is an opt-in policy layer around Sodium 0.9's existing
queues and ownership boundaries. It does not replace `RenderSectionManager`,
`ChunkBuilder`, or `RenderRegionManager`.

- `TerrainSchedulingController` is the deterministic policy and state machine.
  It supplies a build-submission budget and upload-work budget in nanoseconds,
  keeps a bounded forward-turn boost, applies pressure hysteresis, and owns
  bounded stage counters/timings.
- `SodiumWorldRendererTerrainSchedulingMixin` samples the real camera
  position/forward vector around `setupTerrain`. `MinecraftTerrainSchedulingMixin`
  measures the real `Minecraft.renderFrame` CPU interval.
- `RenderSectionManagerTerrainSchedulingMixin` reads Sodium's actual
  `ChunkBuilder` scheduled/busy/total thread signals, redirects the existing
  `ChunkJobCollector(long, Consumer)` duration and
  `LimitedResourceBudget(long, long)` duration arguments, times build submission
  and result processing, and augments the return value of Sodium's
  distance-only `shouldPrioritizeTask` predicate.
- `RenderRegionManagerTerrainSchedulingMixin` counts results at Sodium's actual
  `uploadResults` owner.
- `MetalGpuTimingRecorder.latestGpuNanos()` supplies the latest completed Metal
  command-buffer GPU service time only when adaptive scheduling or telemetry is
  enabled. No new GPU estimate is invented.
- `metallum_system_thermal_state` is a narrow optional native export backed by
  Foundation `ProcessInfo.thermalState` (0 nominal through 3 critical).
  Memory pressure uses the JDK's real heap usage and, when available, the
  `com.sun.management.OperatingSystemMXBean` total/free memory counters.

## Defaults and switches

Adaptive scheduling is disabled by default and the first 30 observed frames
remain on Sodium's original arguments even when the switch is enabled:

```text
-Dmetallum.opt.terrainAdaptiveScheduling=true
```

The controller uses 10% of the measured CPU frame interval for build submission
(clamped to 1.5-8 ms) and 8% for upload work (clamped to 2-8 ms). Constrained
pressure scales those budgets to 75%; severe pressure scales them to 50%.
Pressure rises after two samples and recovers only after eight samples below
the lower thresholds. Inputs are:

- backlog: 48 jobs constrained, 128 severe;
- CPU or completed-GPU frame service: 1.2x target constrained, 1.6x severe;
- Foundation thermal state: serious constrained, critical severe;
- measured heap/system memory pressure: 0.80 constrained, 0.92 severe.

After a meaningful camera turn (forward-vector dot product below 0.94), the
current forward direction receives a 12-frame boost. A candidate must remain
inside the original Sodium priority radius plus 24 blocks, inside the configured
render-distance bound, and inside a 0.62 forward cone. The mixin changes only
the priority predicate; Sodium's existing visibility checks and render-distance
ownership remain in place.

Opt-in CSV output is bounded to 4096 rows and includes stable columns with
explicit nanosecond names:

```text
-Dmetallum.opt.terrainSchedulingTelemetry=true
-Dmetallum.opt.terrainSchedulingCsv=/absolute/path/terrain-scheduling.csv
```

The explicit CSV path enables telemetry by itself. Without a path, the boolean
telemetry switch writes `metallum-terrain-scheduling.csv` in the Fabric game
directory. Rows contain frame index, adaptive/pressure state, budgets, queue
signals, thermal/memory inputs, CPU/GPU service, terrain/build/upload timings,
submitted-task and upload-result counts, and forward-turn state.

## Atlas and lightmap boundary

No atlas or lightmap throttle was added. `LightmapRenderState.needsUpdate` is a
real producer-owned dirty bit and `Lightmap.render` already skips work when it
is false, so there is no safe additional rate limiter to add here. Atlas
animation dirtiness is private to `TextureAtlas` and
`SpriteContents.AnimationState`; this checkout exposes no correctness-preserving
dirty-state hook for delaying `TextureAtlas.tick()` or animation uploads.
Atlas throttling is explicitly deferred until that contract is available.

## Validation

The signed Homebrew JDK 25.0.2 was used because the requested `/tmp` JDK 25.0.3
tree cannot launch on this host due to a macOS `libjvm.dylib` rebase-opcode
failure.

Passed focused validation:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home PATH=... ./gradlew test --tests com.metallum.client.terrain.TerrainSchedulingControllerTest --tests com.metallum.client.terrain.TerrainNativeSignalTest --tests com.metallum.mixin.MetallumMixinRegistrationTest --no-daemon --console=plain
```

Passed required validation:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home PATH=... ./gradlew test buildMacNative metal4PipelineSmokeTest metal4PipelinePathTest --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL`, 11 actionable tasks. The full Java suite, native
build, Metal 4 PSO smoke, and shipping Metal 4 path test passed. The shipping
path regression reported exact binding-churn readback and exact asynchronous
private vertex/index rewrite readback across all three reusable slots; its
upload-barrier telemetry reported 6 barriers. The thermal export ABI smoke and
CSV/controller tests passed.

`git diff --check` passed before handoff creation. The only remaining dirty
paths outside this Phase 1 slice are the user-owned compressed logs. No logs
were modified, staged, or committed by this task.

## Boundaries and next contract

This phase has no real-client A/B performance data and no Minecraft launch was
performed. Real-client terrain visual correctness, frame pacing, smoothness,
and attended quality remain human-unverified. The strict Metal 3 kill switch
and `OBJECT_MOTION_PRODUCER_CONNECTED=false` were not changed.

The earlier accepted Metal 4 upload synchronization slice was checkpointed in
local commit `50698d0` (`fix(metal4): synchronize private buffer uploads`) before
these edits. It remains a separate history unit.

Before any future tight-packed terrain batching or range-scoped upload hazard
optimization, Sodium must expose or own an allocation contract containing the
destination buffer identity, byte range, producer submit/fence, consumer stage,
and completion-backed reuse lifetime. The current backend ABI cannot infer that
metadata. Atlas throttling likewise needs an explicit dirty-state/acknowledgment
contract before it can be optimized.
