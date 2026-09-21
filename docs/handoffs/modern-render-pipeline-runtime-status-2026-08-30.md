# Modern render-pipeline runtime status — 2026-08-30

## Scope and identity

This receipt records the first real-Minecraft pass over the modern rendering
features already present on `integration/iris-metal-next`, plus the narrow
runtime-admission fix in `ccefd3150952ea97b09e187dbcc330aa11287fe4`.

- Base: `cf4e66250c8fd3bcf6fdf58e00e0cd841052cd58`
- Change commit: `ccefd3150952ea97b09e187dbcc330aa11287fe4`
- Tested JAR SHA-256:
  `08709e8756c6ba7cef83804cf8aaec71a26537caecba8032b5e267a5b285fb42`
- Host: MacBook Pro `MacBookPro18,3`, Apple M1 Pro, 10 cores, 16 GB
- OS: macOS 26.5.1 (25F80)
- Client: Minecraft 26.2, Fabric Loader 0.19.3, Sodium 0.9.1, Iris 1.11.2
- World: an isolated copy of `New World`; user instances and worlds were not
  modified.

This is source/build/native/real-client evidence. The client entered a world
and continued rendering; no controlled screenshot corpus or ABBA performance
acceptance was collected.

## Result matrix

| Path | Real-client result | Activation / measured evidence | Status |
|---|---|---|---|
| Safe Metal | World joined and remained live | 230–262 actual FPS after settling; main GPU p50 2.65–2.96 ms | Passed |
| Max-Stable Metal 4 | World joined and remained live | MTL4Compiler, three reusable command buffers, explicit residency, Metal 4 present and barrier active; operator observed 200–300 FPS | Passed |
| Fused terrain profile before admission fix | World joined but was CPU-bound | 17–21 actual FPS; frame-interval p50 40–47 ms; main GPU p50 only 4.6–5.4 ms | Failed performance gate |
| Fused terrain profile after admission fix | World joined and remained live | 246–304 actual FPS; source FPS p50 293–343; frame-interval p50 2.9–3.4 ms; main GPU p50 2.0–2.4 ms | Passed safe-fallback gate |
| Sodium terrain ICB on the real pack-independent terrain PSO | Driver rejected final PSO eligibility | `Fragment shader cannot be used with indirect command buffers` | Unsupported on this final M1 Pro PSO; fail-closed fallback passed |
| GPU visibility / stable compaction | Native Metal test passed; real-client diagnostic probe activated | Sparse source slots, compaction boundaries, cross-lease rejection and owner lifetime passed under Metal API Validation | Partial: diagnostic/native proof, not draw-authority proof |
| MetalFX Temporal | World joined and remained live | Requested/effective mode `TEMPORAL`; stable terrain sampler engaged | Passed runtime activation |
| MetalFX Frame Generation | Deliberately disabled | `complete object-motion producer is not connected` | Correctly blocked; implementation contract incomplete |
| Iris semantic + Potato | World joined and remained live | Generation 1 online; solid/cutout translated from `gbuffers_terrain`, translucent from `gbuffers_water`; 32 logical targets at 3024×1964; 94–100 FPS, GPU p50 10.1–10.5 ms | Passed runtime activation |
| Iris modern combined profile | World joined and remained live | Strict semantic pipeline stayed online; early samples 93–100 FPS, later short samples 70–75 FPS | Partial: runnable, but individual optimization admissions were not proven |

## Defect and fix

Metal may accept the descriptor request and still return a correct fallback
render PSO whose `supportIndirectCommandBuffers` property is false. The old
path discovered this only after ending the render encoder. It then retried the
same rejected GPU-ICB work every frame and continued producing terrain scene
snapshots, draw metadata and visibility candidates that could never become
draw authority. The result was a CPU/synchronization bottleneck even though
the GPU needed only about 5 ms.

The fix makes the final compiled PSO authoritative:

1. query `MTLRenderPipelineState.supportIndirectCommandBuffers` before ending
   the render encoder;
2. reject ICB execution immediately when the final PSO is ineligible;
3. stop the upstream ICB-only snapshot, metadata and candidate producers for
   the rest of that world;
4. preserve the ordinary indirect draw fallback and the explicit visibility
   oracle;
5. reset runtime admission on world reset so a new pipeline generation can be
   evaluated again.

This does not claim that terrain ICB became active. It makes an unsupported
ICB path cheap, deterministic and fail-closed.

## Validation performed

All commands completed successfully unless noted:

```text
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
./gradlew --no-daemon clean buildMacNative jar
./gradlew --no-daemon compileJava buildMacNative metal4PipelinePathTest
./gradlew --no-daemon test verifyIsolatedClientProfiles
./gradlew --no-daemon test --tests com.metallum.client.metal.render.TerrainIcbRuntimeAdmissionTest
bash -n scripts/experience/launch-minecraft-26.2.sh
```

`metal4PipelinePathTest` ran on the physical Apple M1 Pro with Metal API
Validation and covered MTL4Compiler parity, residency, reusable command
buffers, visibility compaction, sparse/all-visible ICB fixtures, barriers,
present, argument tables, private rewrites and the shipping Metal 4 Spatial
path. Those fixtures establish API/runtime capability; they do not override
the real Minecraft terrain PSO rejection above.

The Java test process emitted existing Objective-C duplicate-class warnings
because it loaded both a temporary native dylib and the resources dylib. The
test completed successfully, and the real Minecraft processes did not load
both copies.

## Reference implementations and implications

The next implementation should borrow contracts, not wholesale architecture:

1. **Apple GPU-authored ICB sample** — reset commands, perform visibility and
   command authoring in one compute pass, declare residency/use, optionally
   optimize away empty commands, then execute one authoritative range. Adopt
   this only for PSOs that report ICB eligibility; never build CPU sidecars
   after admission is known to be false.
   <https://developer.apple.com/documentation/metal/encoding-indirect-command-buffers-on-the-gpu>
2. **Apple MetalFX guidance** — temporal upscaling and frame interpolation
   require correct dejittered per-pixel motion, depth convention, reactive
   inputs, two real rendered frames, and presentation/UI ownership. This makes
   the missing object-motion producer the next correctness dependency, not a
   flag to force on.
   <https://developer.apple.com/videos/play/wwdc2025/211/>
3. **Iris pipeline ownership** — retain Iris program order, target flips,
   shadow targets, pack directives and ShaderKey routing as the semantic
   authority. Optimizations should consume the immutable Iris plan rather than
   infer behavior from encoder order.
   <https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/pipeline/CompositeRenderer.java>
4. **Filament FrameGraph** — use a compact declared pass/resource lifetime
   model to calculate last use and transient allocation. Do not import an
   engine-scale graph; extend the existing MetalUniversal immutable plan and
   render receipt instead.
   <https://google.github.io/filament/notes/framegraph.html>
5. **Apple command-buffer guidance** — prefer one or very few submissions per
   frame and keep CPU work ahead of the GPU. Avoid an optimization that adds
   encoder transitions, queue round trips or readbacks unless measured savings
   exceed their cost.
   <https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/MTLBestPracticesGuide/CommandBuffers.html>

## Recommended next implementation order

### 1. Promote individual Iris plan optimizations with activation receipts

Run the existing profiles separately: depth liveness, compute grouping, pass
fusion and argument tables. Each candidate needs one structured admission
counter, one render-contract pass, and a short same-world performance sample.
Do not use the combined profile to decide which optimization is effective.

This is the highest-value next step because Iris + Potato is already a real,
GPU-bound workload at about 10 ms. The plan/compiler surfaces already exist;
the missing work is proving and promoting them individually, not adding a new
graph system.

### 2. Complete one shared motion/reactive contract for MetalFX

Add generation-owned previous transforms for terrain, entities, held items and
deforming geometry; produce dejittered motion in a single convention; keep sky,
transparency and unreliable motion in the reactive mask; and retain the current
fail-closed frame-generation admission. Only then run the WindowServer
presentation/pacing gate and UI-separation acceptance.

### 3. Revisit terrain GPU-driven submission only behind PSO eligibility

First identify which Sodium fragment state makes the final M1 Pro terrain PSO
ineligible. Test the smallest shader/pipeline-state reduction against the
original ShaderKey and attachment semantics. If a real terrain PSO becomes ICB
eligible, fuse visibility and command generation as in Apple's sample and
delete the CPU metadata sidecar from the shipping path. If eligibility cannot
be obtained without semantic changes, keep the new cheap fallback and stop.

### 4. Defer broader architecture work

Do not introduce meshlets, a second render graph, bindless material systems,
virtual geometry or Iris+MetalFX combined ownership yet. They add substantial
semantic and validation surface while the current measurable gaps are narrower:
individual Iris admissions, complete motion, and one real terrain PSO.

