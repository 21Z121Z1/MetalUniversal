# Iris Metal experimental performance architecture

Branch: `feature/iris-metal-performance`

This document describes the implementation and local-debug handoff for the remaining high-risk Iris-on-Metal optimizations. These paths are intentionally experimental and default-off. The conservative renderer remains the fallback until the local agent wires, compiles, profiles, and validates each transformation.

## Scope implemented in this branch

The branch now contains executable planning/runtime infrastructure for:

1. render-pass fusion;
2. attachment load/store liveness;
3. non-concurrent compute grouping;
4. depthtex1/depthtex2 and other resource liveness;
5. final-pass plus color-space fusion eligibility;
6. Metal 3 argument-buffer / Metal 4 argument-table ABI snapshots;
7. ICB or native multi-draw command grouping.

The infrastructure is not a documentation-only sketch. It constructs a generation-owned hazard graph, derives conservative merge groups, validates attachment signatures, calculates resource liveness, freezes argument layouts, creates per-in-flight descriptor snapshots, groups compatible indexed draws, and can dump the resulting plan to JSON.

Native execution remains default-off because the current Java/native interfaces do not yet expose all required load/store, fused-pass, argument-table, and ICB entry points.

## Runtime switches

Every transformation is independent and defaults to false:

```text
-Dmetallum.iris.experimental.passFusion=true
-Dmetallum.iris.experimental.loadStoreLiveness=true
-Dmetallum.iris.experimental.computeGrouping=true
-Dmetallum.iris.experimental.resourcePruning=true
-Dmetallum.iris.experimental.finalColorFusion=true
-Dmetallum.iris.experimental.argumentTables=true
-Dmetallum.iris.experimental.icb=true
```

Dump the generated plan with:

```text
-Dmetallum.iris.experimental.planDump=/absolute/path/iris-metal-plan.json
```

The local agent should never enable all flags at once. Enable and validate them in the order given below.

## 1. Hazard graph

Implementation:

```text
src/main/java/com/metallum/client/metal/render/IrisMetalHazardGraph.java
```

Each generation pass becomes a node with exact resource uses:

- sampled read;
- attachment read/write;
- storage read/write;
- buffer read/write;
- copy read/write;
- present read.

The builder derives:

- RAW dependencies: a pass reads a resource written by an earlier pass;
- WAR dependencies: a pass overwrites a resource still required by an earlier reader;
- WAW dependencies: two passes write the same resource;
- explicit barrier edges.

`mayMergeAdjacent(first, second)` returns true only when the nodes are adjacent, no explicit barrier exists, and no direct dependency crosses the boundary.

### Local integration point

Build descriptors from `IrisMetalPostChain.PlannedPass`, `PlannedCompute`, final copy/color-space operations, depth captures, and history copies. Use physical resource identities, not only logical colortex indices:

```text
colortex0/main
colortex0/alt
depthtex0
depthtex1
depthtex2
shadowcolor0/main
...
```

The flip snapshot determines whether one logical colortex reference resolves to main or alt.

## 2. Render-pass fusion

The planner derives adjacent render merge groups, but the local agent must apply additional Metal constraints before executing one encoder:

- identical physical color attachment array;
- identical depth/stencil attachment;
- identical sample count;
- no clear after the first logical pass;
- viewport and render area compatible;
- no sampled read from an attachment written earlier in the same encoder unless a supported raster-order or texture barrier mechanism is used;
- no pass expects a store/load round-trip as an observable boundary.

### Native work required

Add an API that opens one encoder for a merge group and changes pipeline/descriptors between logical passes without ending the encoder. Keep one logical contract trace per pass even when they share one native encoder.

Suggested Java API:

```java
MetalMergedRenderPass beginMergedRenderPass(MergedRenderPassDescriptor descriptor);
```

Suggested Swift behavior:

1. create one `MTLRenderPassDescriptor` using the first pass load actions and final pass store actions;
2. create one encoder;
3. emit a debug group for every logical pass;
4. change PSO and resources between fullscreen draws;
5. end after the last logical pass.

Do not merge passes whose physical attachment side changes after an Iris flip.

## 3. Attachment load/store liveness

The optimization plan supports per-node policies:

```text
LoadAction: DONT_CARE, LOAD, CLEAR
StoreAction: DONT_CARE, STORE
```

Rules for the local agent:

- `CLEAR` when the pass contract explicitly clears the attachment;
- `LOAD` when any pixel may need previous contents, including partial viewport/scissor or blending;
- `DONT_CARE` only when the pass provably overwrites every pixel/channel before any read;
- `STORE` when any later pass, copy, sampling operation, readback, or present consumes the result;
- `DONT_CARE` when no later consumer exists before the resource is overwritten or destroyed.

For MRT, calculate policy per physical attachment, not for the pass as a whole.

### Required native ABI

Extend render-pass creation to carry arrays of load/store actions. The current boolean-clear API cannot express `dontCare` safely.

Suggested bridge values:

```text
0 = dontCare
1 = load
2 = clear

0 = dontCare
1 = store
```

Keep the old bridge entry point as fallback.

## 4. Compute grouping

The hazard graph derives adjacent compute groups. Grouping is legal only when:

- the pack did not require an explicit barrier between dispatches;
- no resource written by dispatch A is read or written by dispatch B without a Metal barrier representable inside one compute encoder;
- indirect argument buffers are not rewritten between dispatches;
- storage textures/buffers use compatible hazard handling.

Two execution modes should be implemented:

1. same encoder, no barrier: independent dispatches;
2. same encoder, explicit `memoryBarrier`/resource barrier where supported.

If the required barrier cannot be represented precisely, retain separate encoders.

The existing `concurrentCompute` behavior remains authoritative. This optimization targets only cases the graph proves safe.

## 5. Resource liveness and depthtex pruning

`IrisMetalOptimizationPlan.ResourceLiveness` records:

- live resources;
- persistent resources;
- dead resources;
- whether depthtex1 is required;
- whether depthtex2 is required.

The local agent must gather uses from all of:

- Sodium terrain programs;
- Mojang/core gbuffer programs;
- shadow programs;
- setup/begin/prepare/deferred/composite/final raster programs;
- compute reflection;
- custom texture replacement rules;
- final history copies;
- validation/readback features.

Only after this complete generation scan may `IrisMetalRenderTargets` omit depthtex1/depthtex2 allocation or skip their capture hooks.

### Safe implementation sequence

1. calculate liveness and log it without changing allocation;
2. skip depth capture when the target has no consumer;
3. make the corresponding texture/view nullable;
4. skip allocation;
5. fail closed if a runtime resolver unexpectedly requests an omitted target.

Do not infer liveness from post-chain samplers alone. A terrain/core/shadow program can consume depth resources too.

## 6. Final and color-space fusion

Fusion is eligible only if:

- a final shader exists;
- color-space conversion is a pure one-input, one-output fullscreen transform;
- neither stage exposes its intermediate texture to another pass, copy, readback, screenshot, MetalFX input, or validation capture;
- blend, alpha, format and color-transfer behavior remain identical;
- the generated shader uses non-colliding bindings and varyings.

Recommended implementation:

1. preserve the final shader as stage A;
2. rename its fragment output to a local value;
3. inline a generated color-space function as stage B;
4. write one final fragment output;
5. compile and cache this as a separate synthetic pipeline;
6. retain the unfused two-pass path as fallback.

Do not perform textual concatenation after MSL generation. Fuse at GLSL/SPIR-V generation where reflection and binding relocation still run.

## 7. Argument-buffer / MTL4 argument-table ABI

Implementation:

```text
IrisMetalOptimizationPlan.ArgumentLayout
IrisMetalArgumentSnapshot
```

`ArgumentLayout` freezes the program ABI as ordered buffer, texture and sampler slots and generates a stable hash.

`IrisMetalArgumentSnapshot` provides:

- handle/offset arrays;
- per-class dirty masks;
- generation counter;
- no-op binding suppression;
- triple-buffered ring ownership.

The ring prevents the historical failure mode where one mutable argument table is rewritten after earlier draws already reference it.

### Metal 3 path

Create one `MTLArgumentEncoder` layout per stable ABI hash. Allocate one argument buffer per program per in-flight slot. Rewrite only dirty entries, then bind the argument buffer once per stage.

### Metal 4 path

Map the same logical layout to the existing MTL4 argument table implementation. Use one table snapshot per in-flight slot. Never let two concurrently encoded draws mutate the same table storage.

### Required bridge

The native bridge should expose bulk update functions rather than one FFM call per entry:

```text
metallum_argument_snapshot_update_buffers(...)
metallum_argument_snapshot_update_textures(...)
metallum_argument_snapshot_update_samplers(...)
```

Pass compact dirty-index arrays and handle arrays.

## 8. ICB and GPU-driven submission

Implementation:

```text
IrisMetalIndirectCommandStream
IrisMetalOptimizationPlan.IndirectBatch
```

Draws are grouped only when these values match:

- pipeline key;
- physical attachment signature;
- argument-layout hash;
- indexed/non-indexed mode.

The stream can first feed the existing native multi-draw path. After correctness is established, the same batches can populate an `MTLIndirectCommandBuffer`.

### First ICB target

Use Sodium terrain only. Do not begin with Iris fullscreen passes; they contain too few draws to justify ICB overhead.

Required per-command state:

- index buffer and type;
- vertex buffers and offsets;
- draw indexed arguments;
- per-draw transform/material offsets;
- stable argument snapshot or argument-buffer offset.

PSO and render attachments remain batch-level state.

### GPU-driven extension

GPU culling/compaction is a separate phase. First encode the CPU-generated batch into ICB and verify it matches native multi-draw. Only then add a compute producer for visible-command compaction.

## Planner runtime

Implementation:

```text
IrisMetalExperimentalOptimizer.java
```

It accepts immutable pass, program and draw descriptors and produces one `IrisMetalOptimizationPlan`.

It performs fail-fast validation for:

- duplicate argument names;
- duplicate indices within a resource class;
- merge groups crossing pass-kind boundaries;
- render merge groups with different attachment compatibility keys.

The plan can be inspected through:

```java
IrisMetalExperimentalOptimizer.active();
IrisMetalExperimentalOptimizer.toJson(plan);
```

### Stage A diagnostic plan receipt

The optimizer now emits an immutable logical pass receipt alongside the
existing hazard/liveness plan. The receipt contains the chain generation, and
each entry contains a deterministic
`iris/<stage>/<type>/<ordinal>/<normalized-name>` key, the
canonical semantic pass ID resolved by `SemanticPassIdResolver`, pass type,
logical resource uses/access modes, and attachment load/store candidates with
their compatibility key. The receipt is explicitly
`UNBOUND_DIAGNOSTIC_ONLY`: it does not construct `ResourceIdentity` values,
resolve native handles, or alter V2/V3 actions. Physical identity binding
remains owned by the render-contract recorder when a real backend allocation
exists. Repeated builds with the same descriptors produce byte-identical JSON;
same raw names in different stages or ordinals remain distinct.

## Required integration work for the local agent

### Phase A: planner population

Add a generation method in `IrisMetalPostChain` that exports `PassDescriptor` objects. Include setup, post, final, color-space, depth capture and history copy nodes.

Add program descriptors from:

- `MetalCompiledRenderPipeline.resources()`;
- `MetalComputePipeline` reflection;
- stage visibility and writable flags.

Add draw descriptors from Sodium terrain submission or the existing multi-draw batch.

Call `IrisMetalExperimentalOptimizer.build(...)` after all programs and resources have been prewarmed.

### Phase B: diagnostic-only comparison

With all execution flags false, dump the plan and compare:

- pass count/order;
- hazard edges;
- physical resource sides;
- dead resource list;
- merge candidates;
- argument ABI hashes;
- ICB batch boundaries.

### Phase C: enable one transform at a time

Recommended order:

1. resource capture skipping, without allocation pruning;
2. load/store actions;
3. independent compute grouping;
4. render-pass fusion;
5. final/color fusion;
6. argument snapshot backend;
7. ICB fed by CPU draw list;
8. GPU-generated ICB commands.

## Logging requirements

Add one log line per generation, not per frame:

```text
[metallum-iris-opt] generation=N nodes=X edges=Y renderGroups=R computeGroups=C dead=[...] argumentLayouts=A icbBatches=B
```

When a candidate is rejected, record one stable reason code:

```text
ATTACHMENT_SIGNATURE_MISMATCH
EXPLICIT_BARRIER
RAW_HAZARD
WAR_HAZARD
WAW_HAZARD
PARTIAL_RENDER_AREA
BLEND_REQUIRES_LOAD
VALIDATION_CAPTURE_CONSUMER
METALFX_CONSUMER
ARGUMENT_ABI_COLLISION
ICB_UNSUPPORTED_STATE
```

Avoid per-frame warning spam.

## Local validation matrix

Although this branch was requested without remote acceptance, the local agent should run:

1. Java compile and unit tests;
2. shader-pack admission for Potato and BSL;
3. Metal 3 and Metal 4 separately;
4. Metal API Validation;
5. fixed-camera framebuffer captures with every experimental flag individually;
6. reload, resize, dimension switch and shader-pack switch;
7. MetalFX disabled first, then temporal scaling, then frame generation;
8. median/p95 CPU and GPU pass timing;
9. encoder, load/store, copy, mipmap, descriptor and draw counts.

Every experimental path must have a runtime fallback to the conservative implementation when plan validation or native creation fails.

## Current status

The generation planner, hazard model, liveness model, load/store policy model, merge grouping, immutable argument ABI, per-in-flight argument snapshots, ICB-compatible command stream, validation and JSON plan dump are implemented.

The branch does not yet replace the native renderer with these plans by default. Native entry points and runtime population from the complete Iris generation remain the intended local-agent debugging and integration work. This boundary is deliberate: it keeps the repository bisectable and prevents unvalidated hazard or ABI changes from silently altering pack-visible output.
