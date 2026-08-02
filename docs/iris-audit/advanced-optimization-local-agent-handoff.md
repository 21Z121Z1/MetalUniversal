# Advanced Iris Metal optimization: local-agent handoff

Branch: `feature/iris-metal-performance`

This document is the execution handoff for the advanced optimization lanes added after the conservative fast paths. No remote build or runtime validation has been performed.

## What is now connected

`IrisMetalOptimizationBootstrapMixin` calls `IrisMetalOptimizationBootstrap.onPostChainCreated` after each `IrisMetalPostChain.create` and clears the plan on close.

The bootstrap reflectively scans the immutable generation and exports:

- raster post passes and final pass;
- DRAWBUFFERS attachment writes;
- declared sampler reads and legacy colortex aliases;
- compute reflection resources, bindings and writable state;
- persistent history targets;
- known colortex and depthtex resources.

It then creates the generation-owned `IrisMetalOptimizationPlan` through `IrisMetalExperimentalOptimizer` and optionally dumps JSON with:

```text
-Dmetallum.iris.experimental.planDump=/absolute/path/iris-metal-plan.json
```

`IrisMetalDepthLivenessMixin` is an active execution hook. With:

```text
-Dmetallum.iris.experimental.resourcePruning=true
```

it cancels depthtex1 or depthtex2 capture copies only when the live plan reports no consumer. Allocation is intentionally retained until local validation proves the full terrain/core/shadow scan complete.

## Implemented execution infrastructure

### Hazard-checked grouping

`IrisMetalHazardGraph` derives RAW, WAR, WAW and explicit-barrier edges.

`IrisMetalScheduledWorkQueue` executes only precomputed merge groups. It never merges work dynamically and retains one encoder per logical item when the corresponding feature switch is disabled or a group is incomplete.

Integration targets:

- replace the loop in `IrisMetalPostChain.executeComputeGroup` with a compute `Work` list;
- export eligible raster fullscreen passes as render `Work` items only after their physical attachment keys are known;
- provide `ScopeFactory` implementations backed by `MetalComputePass` and a new merged-render-pass wrapper.

### Attachment load/store policy

`IrisMetalOptimizationPlan.AttachmentPolicy` carries independent load/store decisions per physical attachment:

```text
LoadAction: DONT_CARE, LOAD, CLEAR
StoreAction: DONT_CARE, STORE
```

The current native bridge only accepts clear booleans, so the local agent must add a V3 render-pass entry point rather than overloading the existing values.

Required Java signature shape:

```java
renderCommandEncoderV3(
    MetalGpuTextureView[] colors,
    MetalGpuTextureView depth,
    int[] colorLoadActions,
    int[] colorStoreActions,
    float[] clearColors,
    int depthLoadAction,
    int depthStoreAction,
    double clearDepth,
    ...
)
```

Required Swift mapping:

```text
0 -> .dontCare
1 -> .load / .store
2 -> .clear (load only)
```

Reject `DONT_CARE` whenever blending, partial viewport/scissor, validation capture or a later consumer requires preserved content.

### Final plus color-space fusion

`IrisMetalFinalColorFusion` implements a fail-closed source fusion step. It accepts only:

- one observable final color output;
- a pointwise color transform;
- no external color-stage resources;
- an explicit `/* METALLUM_FINAL_COLOR_OUTPUT */ expression;` marker.

It rejects all ambiguous source layouts and keeps the two-pass path. The local agent should insert the marker while generating the final GLSL, before SPIR-V compilation and binding relocation. Do not concatenate MSL.

### Argument-buffer and Metal 4 argument-table ABI

`IrisMetalOptimizationPlan.ArgumentLayout` freezes the logical ABI and stable hash.

`IrisMetalArgumentSnapshot` provides per-program, per-in-flight-slot state with dirty masks for buffers, textures and samplers. It deliberately prevents a mutable table from being rewritten after an earlier draw references it.

Local native work:

1. cache a Metal 3 `MTLArgumentEncoder` per ABI hash;
2. allocate one argument buffer per program per in-flight slot;
3. reuse the same snapshot model for the existing Metal 4 argument-table path;
4. add bulk FFM updates for compact dirty-index arrays;
5. advance the snapshot ring only after command-buffer submission.

Never share one mutable table across concurrently in-flight draws.

### ICB and GPU-driven submission

`IrisMetalIndirectCommandStream` and `IrisMetalOptimizationPlan.IndirectBatch` group commands by stable pipeline and attachment state.

First integration target: Sodium terrain only.

Sequence:

1. feed batches into the existing native multi-draw path;
2. compare command counts and framebuffer output;
3. encode the same CPU-produced list into `MTLIndirectCommandBuffer`;
4. only after equivalence, add compute culling/compaction that writes ICB commands.

Do not use ICB for isolated fullscreen passes.

## Feature switches

All execution-changing advanced switches default off:

```text
-Dmetallum.iris.experimental.passFusion=true
-Dmetallum.iris.experimental.loadStoreLiveness=true
-Dmetallum.iris.experimental.computeGrouping=true
-Dmetallum.iris.experimental.resourcePruning=true
-Dmetallum.iris.experimental.finalColorFusion=true
-Dmetallum.iris.experimental.argumentTables=true
-Dmetallum.iris.experimental.icb=true
```

Enable one at a time. Plan generation itself is diagnostic and does not require an execution flag.

## Immediate local-agent tasks

1. Compile first and repair Mixin descriptors or reflection names if the pinned Iris/Minecraft mappings differ.
2. Run with every execution flag off and inspect the JSON plan.
3. Confirm depthtex1/2 liveness includes terrain, core and shadow consumers; extend bootstrap scanning before enabling resource pruning.
4. Add render-pass V3 load/store ABI and wire only the policy model.
5. Replace independent compute encoder loops with `IrisMetalScheduledWorkQueue`; preserve explicit barriers.
6. Add a merged fullscreen render encoder wrapper and require identical physical attachment arrays.
7. Insert the final-output fusion marker at GLSL generation and compile a separate cached synthetic pipeline.
8. Implement Metal 3/Metal 4 argument snapshot encoders.
9. Route Sodium terrain batches through `IrisMetalIndirectCommandStream`, then implement ICB.

## Required diagnostics

Log once per generation:

```text
[metallum-iris-opt] generation=N nodes=X edges=Y renderGroups=R computeGroups=C dead=[...] argumentLayouts=A indirectBatches=B
```

For every rejected optimization candidate, emit one stable reason rather than a free-form per-frame warning:

```text
ATTACHMENT_SIGNATURE_MISMATCH
PHYSICAL_FLIP_SIDE_CHANGED
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

## Validation order

1. all flags off: planner-only parity;
2. depth capture skipping;
3. load/store actions;
4. compute grouping;
5. render-pass fusion;
6. final/color fusion;
7. argument tables;
8. CPU-generated ICB;
9. GPU-generated ICB.

For every stage test Potato and BSL, Metal 3 and Metal 4, resize/reload/dimension changes, fixed-camera framebuffer comparison, Metal API Validation, then MetalFX combinations.
