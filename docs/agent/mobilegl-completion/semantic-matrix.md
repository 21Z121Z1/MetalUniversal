# Observable semantic matrix

Status values are evidence states, not feature marketing:

- `PASS`: implemented and exercised by the stated current test.
- `PARTIAL`: a real implementation exists but a required semantic or runtime
  activation proof is missing.
- `GAP`: the current implementation is mirror-only, unsafe, missing, or known
  incorrect.
- `N/O`: not observed in the pinned Minecraft/Fabric/Sodium/Iris surface; no
  speculative implementation is planned unless runtime tracing observes it.

This matrix is updated as implementation and physical evidence land.

| Domain | Required observable behavior | Starting state at `e52d843` | Required closure/evidence |
| --- | --- | --- | --- |
| Compatibility entry | Blaze3D/Sodium/Iris call validated before native mutation | PARTIAL: validation exists but remains distributed across Mixins, render pass and native bridge | Consolidate command validation and add invalid enum/value/range/lifetime cases without partial execution. |
| Error behavior | compatibility error, unsupported semantic, ABI rejection and Metal failure distinguished | PARTIAL | Structured fail reason; no warning-and-skip for required work; negative tests. |
| Context/state owner | one semantic truth, encoder shadows reset at pass/encoder/frame boundaries | PARTIAL | Audit all state shadow invalidations and transfer helper pollution; physical render/compute transitions. |
| Object identity | API object separate from backend allocation | PARTIAL | Buffer/texture/sampler/program/pipeline identities and generations tested across replacement/reload/unload. |
| Buffer backing | orphan/replace preserves required ranges, increments generation, old backing retires after completion | GAP: dynamic backing swaps a native handle without generation | Add backing generation and propagate to binding/command/resource identities; same-submit replacement test. |
| Texture backing/views | ref-counted views and completion-based release; replacement invalidates views/generation | PARTIAL | Audit mip-chain/recreate ordering and add write-before-recreate readback test. |
| Sampler | immutable state, independent lifetime, texture-unit pairing | PARTIAL | Typed compiled slots and argument representation; unload/reload lifetime test. |
| Shader/program | validation, interface match, typed reflection, explicit translation failure | PARTIAL: broad Iris translation and fixtures exist | Add first divergent semantic identity and runtime BSL/Potato physical evidence; no guessed resource. |
| Resource layout | compiled once, typed slots/stages, no draw-time reflection or string lookup | PARTIAL: `MetalCompiledBindingPlan` exists; compatibility String lookup is cached | Remove reflection in `IrisMetalArgumentBindingRuntime`; bind by compiled plan/token in production. |
| Argument table/buffer | actual native table replaces dominant buffer/texture/sampler setters, in-flight safe | GAP: snapshot explicitly mirrors setters and only marks dirty | Implement native Metal 3 argument buffer / Metal 4 table path or one common encoded ABI; activation counters prove setter replacement. |
| Pipeline identity | all shader, attachment, blend/mask, depth/stencil, raster, vertex and mode inputs included | PARTIAL: stable hash covers most fields and runtime attachment signature | Add explicit compile telemetry/identity, sample count/format variants tests, and zero runtime compile gate. |
| Pipeline prewarm/archive | all observed Sodium/Iris variants ready before measured window | PARTIAL | Count actual PSO creations after warmup; require zero and list exact late identities on failure. |
| Draw arrays/elements | topology/index/base vertex/instance/base instance correct | PARTIAL | Expand parameterized semantic/readback coverage and real-client admission. |
| Multi-draw | ordered compact command representation, no per-command allocation | PASS for synthetic packet tests; runtime scope not yet accepted | Exact packet ops/calls and real Sodium terrain observation. |
| Indirect draw/count | normalized parameters and resource hazards | PARTIAL/N/O by function | Trace fixed client; physically test any observed command. |
| Compute | compiled dispatch plan and packet execution | PARTIAL: physical integration exists; BSL workload has no observed dispatch | Conformance compute pack must issue a real dispatch with packet ops > 0 and replay = 0. |
| Primitive restart/fan | OpenGL topology semantics | N/O pending runtime trace | Fail closed if observed; implement expansion only for an observed required mode. |
| Clear | per-attachment color/depth/stencil, scissor/integer behavior; no shadow leak | PARTIAL | Physical clear/readback cases plus next-draw state preservation. |
| Copy/blit/mipmap | format/filter/depth-stencil and backing ordering correct | PARTIAL | Physical same-frame producer/consumer matrix and mip recreation case. |
| Pixel pack/unpack/readback | row alignment and synchronization correct | PARTIAL | Exact stride/alignment fixtures and GPU completion-bound readback. |
| Hazard graph | RAW/WAR/WAW and attachment transitions generation-aware | PARTIAL | Replace object-only buffer identity with backing generation; render->compute and compute->render physical tests. |
| Fence/sync | completion serial controls reuse/release and client wait | PARTIAL | No reuse before completion; timeout/close tests; real validation clean. |
| Query | result availability/timing not fabricated | PARTIAL | Missing data stays unavailable; query pool/generation tests if fixed client observes query. |
| Presentation | drawable generation/lifecycle and normal exit | PARTIAL | resize/fullscreen/reload/client exit under Metal validation. |
| Frames in flight | bounded slots and frame-local arenas | PARTIAL | Prove each slot only reused after completion and counters are sample-window aligned. |
| Deferred destruction | backend handles survive all recorded command use but do not leak | PARTIAL | replacement/world unload/device close stress and validation. |
| Frame graph | compiled dependencies, load/store, transient liveness, safe fusion | PARTIAL | Generation-aware identities, attachment readbacks and first-divergence report. |
| Heap/aliasing | no alias across an unclosed lifetime | PARTIAL/disabled where unproven | Enable only for resources whose compiler proves disjoint lifetimes; physical hazard tests. |
| Terrain ICB | real SOLID/CUTOUT/CUTOUT_MIPPED/TRANSLUCENT admission with all resources resident | GAP: opt-in direct-resource probe; fresh ICB; no real accepted draw | Complete argument/resource contract, persistent bounded ICB storage and real-client accepted commands; otherwise optimized direct fallback with reason. |
| Terrain rebuild/replacement | stale handles rejected across rebuild/world unload | GAP/PARTIAL | Generation token in ICB cache key; rebuild/unload tests and fallback telemetry. |
| Translucent terrain | sorting/order remains observable | PARTIAL | Do not ICB-collapse ordering; moving-camera readback/visual acceptance. |
| FFM path | exact call count and compact atomic packets | GAP at start, implementation in progress | Instrument every bridge handle, reset after warmup, require packet replay zero and no partial execution. |
| Validation framework | flags/config/commit/exit code/metric provenance exact | GAP at start, implementation in progress | Preserve explicit MetalFX OFF, fail missing metrics, exact sample windows, profile admission. |
| Sodium-only client | complete fixed-world correctness | unverified for this exact SHA/worktree | 30 s warmup plus correctness lane and normal exit. |
| Iris + BSL | all listed visual semantics and lifecycle actions | prior limited client evidence only | Attended or deterministic camera/readback sequence, intermediate attachment identities and normal exit. |
| Iris + Potato | same | prior translation only | Same as BSL with pinned pack hash. |
| O/V/M differential | identical config and camera path | GAP | Build MobileGL against exact MoltenVK; record stock, V and M manifests and first semantic divergence. |
| Formal performance | four interleaved 30/120 s paired blocks, guardrails | GAP | B/C ABBA first, then V; at least 75% block improvement for one target plus paired median and no guardrail regression. |

## OpenGL object rules applied to the fixed surface

| Rule | MetalUniversal representation |
| --- | --- |
| Name `0` is default/unbound according to object type | Compatibility adapter maps it to an explicit default/null binding; it never becomes a live native handle. |
| Generated name is not necessarily an instantiated object | Do not allocate Metal resources until immutable storage/backing is required. |
| Deletion removes the name, not necessarily all extant references | Java semantic object closes to new calls while command/pass/attachment references retain backend generation until completion. |
| Buffer data/orphan replaces storage, not object identity | Stable object id plus monotonic backing generation. |
| FBO/VAO/program attachments retain referenced objects | Compiled plan owns typed generation references; deletion invalidates future lookup but not already ordered work. |
| Re-link/reload replaces executable generation | Pipeline/binding/pass keys include program generation; old native functions/PSOs retire after completion. |
| Default framebuffer changes across drawable/resize | Presentation resource identity includes drawable/device generation and dimensions/formats. |

## Command normalization contract

Every executed operation must have exactly one normalized description:

```text
kind + semantic pass id + ordered resource uses (id,generation,range,access)
+ pipeline/binding plan id + dynamic state + command parameters
+ required encoder transitions + fail-closed preconditions
```

Render/state/compute packets may change transport, but not this semantic
meaning. Native validation must complete for the whole packet before operation
zero executes. A legacy fallback is legal only after a native rejection proving
zero execution; once any operation executes, replay is forbidden.
