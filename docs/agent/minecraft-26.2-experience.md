# Minecraft 26.2 MetalUniversal experience profiles

This document is the release/acceptance boundary for the isolated Minecraft
26.2 client. It records what a profile requests, what the runtime is allowed
to claim, and which paths remain infrastructure or experiments. A JVM property
being present is not activation evidence: the runtime must publish a counter,
status line, or fail-closed fallback for the feature to count as effective.

## Profiles

The reproducible launcher is
`scripts/experience/launch-minecraft-26.2.sh`. It uses the installed official
Minecraft 26.2 and Fabric Loader 0.19.3 classpath, Java 25, the official
arm64 LWJGL natives, Sodium 0.9.1+mc26.2, Iris 1.11.2+mc26.2, and an explicit
instance directory.

| Profile | Requested lanes | Runtime policy | Acceptance status |
| --- | --- | --- | --- |
| `safe` | Metal backend plus stable cache/state paths; Metal 4 and terrain experiments off | ordinary Metal 3/fail-closed path remains available | world-load smoke passed |
| `metal4` (Max-Stable) | stable cache/state paths, Metal 4 compiler/main renderer/present/barrier and residency | terrain ICB is deliberately off because the M1 Pro rejects the terrain PSO's ICB capability; ordinary indirect terrain rendering remains authoritative | real world and long performance smoke passed; this is the deliverable profile |
| `visible` (All-Experimental-Visible) | Metal 4, terrain snapshot/metadata, GPU visibility probe, compaction, visible ICB | visibility/compaction may run as a decision probe, but draw authority falls back when ICB-capable PSOs are unavailable | world loaded; visibility/compaction counters ran; visible ICB was not effective and the run failed the stable-FPS gate |
| `fused` (All-Experimental-Fused) | Metal 4, terrain snapshot/metadata, fused visible ICB | no forced combination with the stable profile; any unsupported fused lane falls back | world loaded; fused lane produced no effective runtime counters and failed the stable-FPS gate |
| `framegen` | MetalFX temporal/frame-generation request | not part of Max-Stable; requires a separate MetalFX visual/pacing acceptance | not accepted in this client pass |

The `visible` and `fused` profiles are variants, not claims that every
requested lane is effective. `visible` and `fused` are mutually exclusive
authoring strategies and must not be silently combined.

## Feature matrix

“Classifier/contract” means that the code can classify, record, or validate a
future consumer, not that it has changed Minecraft's physical allocation or
draw authority. “Real client” refers to the current isolated Minecraft 26.2
run; “contract” refers to repository tests and native fixture coverage.

| Feature | Switch | Default | Prerequisites | Mutual exclusion | Fallback | Current validation layer |
| --- | --- | --- | --- | --- | --- | --- |
| Metal backend | `metallum.validation.forceMetal` | off unless requested by validation/profile | macOS Metal device and arm64 LWJGL | none | ordinary backend selection / fail closed | real client: Metal backend initialized |
| PSO archive | `metallum.opt.psoArchive` | true | Metal device | none | compile/cache miss path | contract + real client startup |
| Binding tokens / compiled binding plan | `metallum.opt.bindingTokens`, `metallum.opt.compiledBindingPlan` | true | render-pass cache hooks | none | ordinary binding path | contract + Max-Stable runtime |
| Deferred store/color store and blit batch | `metallum.opt.deferredStore`, `metallum.opt.deferredColorStore`, `metallum.opt.blitBatch` | `deferredStore`/`blitBatch`: true; `deferredColorStore`: false | Metal command encoder | none | ordinary encoder operations | contract + real client |
| Encoder state shadow / render-state packet | `metallum.opt.encoderStateShadow`, `metallum.opt.renderStatePacket` | true | Metal render/compute encoder | none | direct state calls | contract + real client |
| MSL disk cache | `metallum.opt.mslCache` | true | writable cache directory | none | compile from source | contract + real client |
| Metal 4 compiler | `metallum.opt.metal4`, `metallum.opt.metal4Compiler` | false | Metal 4 API/SDK and device capability | Metal 3 fallback is the alternative | Metal 3 pipeline compiler | real client: `available=true compiler=true` |
| Metal 4 main renderer | `metallum.opt.metal4MainRenderer` | false | Metal 4 compiler, main queue, lease/lifetime path | separate from main-queue pilot | Metal 3/main renderer path | real client: non-zero lease/submission counters and avoided factory calls in final Max smoke |
| Metal 4 presenter | `metallum.opt.metal4Present` | false | Metal 4 compiler and presenter path | frame generation has its own presenter constraints | existing present path | real client initialization; no framegen acceptance |
| Metal 4 barriers | `metallum.opt.metal4Barrier` | false | Metal 4 main renderer | none | Metal 3 synchronization | real client initialization + contract |
| Explicit residency sets | `metallum.opt.residencySet` | false | Metal 4 residency API and owned resources | none | normal resource binding | real client: residency path requested/effective on Max |
| Main-queue pilot | `metallum.opt.metal4MainQueuePilot` | false | experimental Metal 4 queue ownership | not required by Max-Stable | main renderer without pilot | not enabled |
| Renderer-owned buffer/texture generations | internal allocation identity/generation hooks | off for terrain capture; ordinary Metal ownership remains | owner identity and completion ordering | no draw-lane conflict | reject stale identity and use ordinary draw | source/contract; Max stability smoke |
| Terrain arena / reusable CPU terrain submission | internal Sodium terrain hooks; `metallum.opt.terrainIcb=false` in Max | normal terrain path on | Sodium 0.9.1 real storage | CPU draw authority is retained when GPU lanes are off | Sodium ordinary indirect submission | real client: chunks/world continuously render |
| Terrain scene snapshot | `metallum.opt.terrainSceneSnapshot` | false | Sodium section storage owner bridge | required by ICB/visibility lanes | no candidate snapshot | contract; enabled in experimental profile but not a draw claim |
| CPU-authored terrain ICB | `metallum.opt.terrainIcb` | false | Metal 4 ICB-capable PSOs and live draw metadata | conflicts with ordinary path only as an attempted optimization | ordinary indirect terrain draw | experimental run: `encoded=0 executed=0`, fail-closed |
| GPU-authored terrain ICB | `metallum.opt.terrainGpuEncode` | false | GPU encode pipeline and ICB-capable PSOs | alternative to CPU authoring | CPU/ordinary indirect path | not effective in client run |
| Terrain draw metadata | `metallum.opt.terrainDrawMetadata` | false | live Sodium storage and allocation generations | required for visible ICB | ordinary draw metadata path | contract + experimental producer hooks |
| GPU visibility decision probe | `metallum.opt.terrainGpuVisibilityProbe` | false | candidate snapshot, Metal compute visibility pipeline | can coexist with visible ICB, but does not itself authorize draws | CPU/ordinary draw list | real client Visible: non-zero candidates/attempts/dispatches/produced; false-negative oracle 0 |
| Hierarchical prefix scan / GPU compaction | internal visibility probe path | off unless visibility probe/visible ICB requests it | visibility bitset and compaction kernels | fused path uses a different producer seam | no compaction; ordinary draw list | real client Visible: non-zero compacted count/dispatches; mismatch oracle 0 |
| Visible GPU ICB | `metallum.opt.terrainVisibleGpuIcb` | false | visibility probe, draw metadata, ICB-capable terrain PSOs | alternative to fused visible ICB | ordinary indirect terrain draw | requested in Visible; effective false (`terrainIcb* = 0`) |
| Fused visible GPU ICB | `metallum.opt.terrainFusedVisibleIcb` | false; also requires visible ICB | fused candidate/visibility/authoring pipeline | mutually exclusive with the stable visible authoring variant | ordinary indirect terrain draw | requested in Fused; no effective counters |
| Terrain adaptive scheduling | `metallum.opt.terrainAdaptiveScheduling` | false | warmup, pacing telemetry, explicit world validation | not enabled in Max-Stable | fixed scheduling | contract only in this pass |
| Terrain scheduling telemetry | `metallum.opt.terrainSchedulingTelemetry` | false | telemetry output path | may be recorded without adaptive scheduling | no scheduling adaptation | source/contract; not Max runtime-enabled |
| Iris semantic bridge | `metallum.iris.semantic` | false in experience profiles | Iris 1.11.2 and shader activation | separate from vanilla/no-shader acceptance | Iris dormant / vanilla Metal path | Iris/Sodium loaded; no shader-pack world acceptance in this pass |
| Iris hazard graph | `metallum.iris.hazardGraph` | true | Iris semantic layer when consumed | none | conservative ordering | contract/source; not counted as shader visual proof |
| Iris pass fusion / compute grouping | `metallum.iris.passFusion`, `metallum.iris.computeGrouping` and experimental aliases | false | active Iris shader pack and semantic plan | independent lanes but not Max-Stable | unfused pass/compute path | contract/config only |
| Iris attachment/depth liveness and final-color fusion | `metallum.iris.attachmentLiveness`, `metallum.iris.depthLiveness`, `metallum.iris.finalColorFusion` | false | active shader pack, immutable plan, authoritative lifetimes | independent experimental lanes | conservative load/store and attachment path | classifier/contract only |
| Iris argument tables / indirect submission | `metallum.iris.argumentTables`, `metallum.iris.indirectSubmission` and aliases | false | active shader pack and compatible Metal argument/ICB consumer | independent experimental lanes | ordinary binding/draw path | contract/config only |
| Attachment lifetime compiler | internal Iris optimization-plan and receipt path | conservative by default | attachment receipt, pass graph, last-use publication | no physical heap claim | conservative allocation/lifetime | source/contract; no shader-pack visual closure |
| Authoritative attachment `lastUse` | internal publisher from the `a0010490` lineage | available as lifetime metadata | actual attachment consumer and completion ordering | none | conservative lifetime | source/contract; not physical heap placement evidence |
| Transient/memoryless classification | internal attachment classifier | conservative | render-graph usage classification | cannot override safety checks | backed attachment | source/contract only |
| Heap-alias recipe compiler/publisher | candidate commits `b8a7038` / `4790dda` only | not present in this experience branch | runtime physical heap placement, fences, alias ownership | not cherry-picked; no current production `IrisMetalHeapAlias*` path | no aliasing | explicitly not an enabled feature; no physical VRAM-saving claim |
| MetalFX spatial/temporal | `metallum.metalfx.mode` | `OFF` in experience profiles | MetalFX-capable device, correct input/output attachments | separate from frame generation | native-resolution Metal path | not accepted here |
| Reactive/transparency mask | MetalFX reactive mask settings | conservative/default policy | MetalFX temporal path and scene mask producers | follows MetalFX mode | no MetalFX | source/contract only |
| Motion reconstruction | internal motion capture/MetalFX motion path | capture exists, consumer is gated | valid motion vectors and temporal consumer | follows MetalFX mode | no reconstruction | source/contract only |
| Frame generation / pacing | `metallum.metalfx.frameGeneration` and `framegen` profile | false | MetalFX frame interpolation, presenter, motion, pacing | separate from Max-Stable | ordinary present | not accepted in this pass |

## Evidence interpretation

The Max-Stable run is allowed to claim Metal 4 main-renderer activation because
the real client emitted the Metal 4 capability line, loaded a real
`ServerLevel[New World]`, joined the player, and published non-zero main
renderer lease/submission counters over a long performance-only smoke. It is
not allowed to claim terrain ICB activation: those counters were zero, by
design, and ordinary indirect terrain drawing remained the effective path.

The Visible run is useful evidence that the candidate, visibility, bitset, and
compaction execution path can be reached by a real Sodium world. Its
`stable60=false` result and zero ICB encode/execute counters keep it out of
Max-Stable. The Fused run did not reach an effective fused producer and remains
an explicit experimental blocker.

The experience launcher writes `experience-launch.env` inside the selected
instance with the source SHA, artifact SHA-256, Minecraft JAR SHA-256, and
Fabric Loader JAR SHA-256. Those fields must be read together with the final
packaged JAR identity; a property-only log is not sufficient acceptance
evidence.
