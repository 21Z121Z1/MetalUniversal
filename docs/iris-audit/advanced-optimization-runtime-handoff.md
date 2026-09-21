# Iris Metal advanced optimization runtime handoff

Branch: `feature/iris-metal-performance`

This document is the local-agent handoff for the executable advanced optimization work layered on top of `experimental-performance-architecture.md`. It distinguishes code that now changes the real encoder/resource lifecycle from code that remains an ABI or source-generation staging layer.

No build, Metal API Validation run, framebuffer comparison, shader-pack launch, or GPU profile was performed remotely. Treat every runtime lane below as unvalidated until the local checklist passes.

## Effective runtime switches

Use one lane at a time.

```text
# Physical-resource-aware Iris render encoder reuse
-Dmetallum.iris.experimental.passFusion=true

# Hazard-independent non-concurrent compute encoder grouping
-Dmetallum.iris.computeGrouping=true
# legacy alias: -Dmetallum.iris.experimental.computeGrouping=true

# depthtex1/depthtex2 capture skipping and allocation pruning
-Dmetallum.iris.depthLiveness=true
# legacy alias: -Dmetallum.iris.experimental.resourcePruning=true

# Java ABI snapshot ownership over the existing Metal 3/Metal 4 bindings
-Dmetallum.iris.argumentTables=true
# legacy alias: -Dmetallum.iris.experimental.argumentTables=true

# diagnostics
-Dmetallum.iris.performanceCounters=true
-Dmetallum.iris.experimental.planDump=/absolute/path/iris-metal-plan.json
```

All listed stable `metallum.iris.*` switches are resolved through `IrisMetalAdvancedOptimizationConfig`. An explicitly supplied stable value wins over the legacy `metallum.iris.experimental.*` alias, including an explicit `false`; when neither value is supplied, the lane keeps its documented default.

## 1. Render-pass fusion: real execution path

Relevant files:

```text
IrisMetalRenderFusionRuntime.java
IrisMetalRenderFusionPolicyMixin.java
IrisMetalRenderFusionBoundaryMixin.java
MetalCommandEncoder.java
```

`MetalCommandEncoder` already reused an existing native render encoder when the color/depth attachment handles matched and the incoming logical pass did not clear. The new runtime makes Iris reuse fail closed rather than relying on handle equality alone.

For each real `IrisMetalPostChain.executePass` call it derives:

- the ordered physical write attachment signature;
- main/alt ping-pong side after the pass flip snapshot;
- physical colortex resources sampled by the current pass;
- physical colortex resources written by the previous pass.

The command encoder is forcibly ended unless all of these are true:

1. pass fusion is enabled;
2. an optimization plan exists;
3. both passes execute in the same `executeStage` call;
4. ordered physical attachment signatures are identical;
5. the current pass does not sample a resource written by the previous pass;
6. the current pass does not sample its own write attachment.

Compute, final, color-space, stage entry, and stage exit break the candidate chain.

The backend still performs the final attachment-handle and clear checks. The policy only authorizes reuse; it cannot force incompatible encoders to merge.

### Local checks

Set a breakpoint in:

```text
IrisMetalRenderFusionRuntime.beginPass
IrisMetalRenderFusionBoundaryMixin.metallum$applyFusionBoundary
MetalCommandEncoder.renderCommandEncoder
```

Record native encoder count with the flag off and on. A fused pair must retain separate logical contract pass tokens and debug groups even though it shares one native encoder.

Reject any pair where the second shader samples the first pass's physical write side.

## 2. Non-concurrent compute grouping: real execution path

Relevant files:

```text
IrisMetalComputeGroupingRuntime.java
IrisMetalPostChainComputeGroupingMixin.java
IrisMetalCommandEncoderComputeGroupingMixin.java
```

The existing non-concurrent path created and ended one native compute encoder per logical dispatch. The new path performs a complete reflection scan of the actual `PlannedCompute` group before encoding.

It groups only when:

- the pack did not opt into the existing concurrent-compute path;
- the group contains at least two dispatches;
- no later dispatch reads a resource written earlier in the group;
- no later dispatch writes a resource read earlier in the group;
- no two dispatches write the same normalized resource;
- reflection completed without an unknown failure.

`colorimgN` and legacy gbuffer aliases are normalized to the corresponding `colortexN` identity before comparison.

Execution behavior:

1. first logical pass creates the native compute encoder normally;
2. intermediate logical `MetalComputePass.close()` calls keep the native encoder open;
3. later logical passes bind their own pipeline/resources to the retained encoder;
4. final logical close performs the existing fence update and `endEncoding()`;
5. submit/render/new-compute boundaries clear any stale grouping state.

Each logical compute pass still owns an independent contract trace and Java pass object.

### Exception-path condition

`IrisMetalPostChain.executeComputeGroup` clears the grouping scope in a `finally` block, so a dispatch or resource-binding exception cannot leak a partial group into the next operation. The command-encoder submit/render/compute boundaries retain their fail-closed abort hooks as a second line of defense. Verify this under an intentionally failing compute binding before enabling the lane by default.

## 3. depthtex1/depthtex2 allocation pruning: real execution path

Relevant files:

```text
IrisMetalDepthLivenessMixin.java
IrisMetalDepthAllocationRuntime.java
IrisMetalDepthAllocationMixin.java
IrisMetalOptimizationBootstrapMixin.java
```

The previous implementation skipped the full-resolution copy when a depth history had no generation consumer. The new runtime also releases its texture and view after the complete post-chain plan becomes available.

Construction order is not assumed:

- targets register when constructed and whenever `createDepthTextures` runs after resize;
- plan construction notifies every live registered target;
- dead depth histories are closed after liveness becomes known.

Fail-closed behavior:

- accessor or capture requests inspect whether the pruned texture/view is closed;
- a missed consumer causes an immediate `D32_FLOAT` texture and view recreation at the current target extent;
- the request succeeds instead of receiving a null/closed resource;
- one warning identifies the liveness scan as incomplete for that shader pack.

The old closed objects remain valid for idempotent owner shutdown until replaced, avoiding null-sensitive close paths in `IrisMetalRenderTargets`.

### Local checks

For packs with no depthtex1/2 use:

- verify the copy hook is cancelled;
- verify both native allocations are released after plan creation;
- verify resize does not recreate and retain a dead history;
- verify dimension and shader-pack switches do not retain old weak registrations.

For a synthetic missed consumer:

- request the pruned accessor;
- confirm one recreation warning;
- confirm rendering continues with a new texture/view;
- treat the warning as a planner bug, not a successful optimization result.

## 4. Argument-buffer / Metal 4 argument-table ownership: connected logical lifecycle

Relevant files:

```text
IrisMetalArgumentSnapshot.java
IrisMetalArgumentBindingRuntime.java
IrisMetalArgumentSnapshotMixin.java
IrisMetalArgumentSnapshotSubmitMixin.java
```

The native Metal 4 renderer already writes bindings into per-in-flight argument tables through the existing `setBuffer`, `setTexture`, and `setTextureAndSampler` calls. The new Java layer does not load a second dylib or replace those native setters.

It now connects the shared logical ABI model to the real render-pass lifecycle:

1. `setPipeline` reflects `MetalCompiledRenderPipeline.resources()` and freezes a stable `ArgumentLayout`;
2. uniform, storage-buffer, sampled-image, sampler, storage-image, and texel-buffer slots are classified by binding index;
3. each render pass owns a three-slot `IrisMetalArgumentSnapshot.Ring`;
4. buffer backing handle/offset, texture handle, and sampler handle changes set per-class dirty masks;
5. `bindDrawState` marks the current snapshot encoded;
6. a real command-buffer submit rotates every ring;
7. a no-op submit does not rotate ownership.

This supplies one ABI and ownership model for:

- existing Metal 4 argument tables;
- a future Metal 3 `MTLArgumentEncoder` implementation;
- bulk dirty-entry FFM updates.

It is not yet a Metal 3 bulk argument-buffer implementation. The current Metal 3 path still executes the existing individual native setters.

Inspect counters with:

```java
IrisMetalArgumentBindingRuntime.stats()
```

The values are layout creations, logical entry mutations, and encoded snapshots.

## 5. Attachment load/store liveness

Implemented execution pieces:

- same-attachment render encoder reuse removes intermediate store/load cycles for approved fused passes;
- existing depth descriptors use deferred `.unknown` store action;
- existing `MetalCommandEncoder.endEncoder` resolves depth store to `dontCare` when an incoming clear or pending clear makes the contents dead;
- the optimization plan records per-attachment load/store policy candidates.

Not yet executable for color attachments:

- the current V2 FFM/native render-pass ABI carries clear flags but no independent color load/store arrays;
- encoding `dontCare` as a magic clear value would silently change existing native behavior and is prohibited;
- Metal 4 bridge objects are private to `MetallumNative.swift`, so loading a second dylib for new symbols would duplicate Swift bridge types and global state.

The local agent must extend the existing `MetalNativeBridge` lookup and the existing Swift module, not add a second native library instance.

Required V3 descriptor fields:

```text
colorTextures[]
colorLoadActions[]   # 0=dontCare, 1=load, 2=clear
colorStoreActions[]  # 0=dontCare, 1=store
colorClearValues[]
depthTexture
depthLoadAction
depthStoreAction     # may retain the existing deferred option
clearDepth
label
```

Keep V2 as the fallback when the V3 symbol is unavailable.

## 6. Final-pass/color-space fusion

`IrisMetalFinalColorFusion` provides a guarded pre-MSL source transformation. It requires a single final color output, a pointwise one-input transform, and an explicit output marker.

It is not connected to execution because the current public sequence runs `executeFinal` before the caller selects and invokes `executeColorSpace`. True fusion requires changing that API to select the color space before final-pipeline selection and precompiling one synthetic final pipeline per required color space.

Do not emulate fusion by suppressing `executeColorSpace` after a normal final pass. That would omit color conversion.

Required local integration:

1. change final execution input to include the selected `ColorSpace`;
2. build synthetic GLSL before SPIR-V/MSL lowering;
3. run normal binding relocation and reflection;
4. cache fused and unfused pipelines separately;
5. retain the two-pass fallback for screenshots, validation captures, MetalFX intermediate consumers, or compilation failure.

## 7. ICB / GPU-driven submission

Implemented staging:

```text
IrisMetalIndirectCommandStream
IrisMetalOptimizationPlan.IndirectBatch
IrisMetalIndirectCommandStreamTest
```

The stream freezes batch boundaries across pipeline, physical attachment signature, argument-layout hash, and indexed state. It can feed the existing indirect/multi-draw API without changing grouping semantics.

A true `MTLIndirectCommandBuffer` path is not connected. The current high-level `drawMultipleIndexed` path can vary vertex buffers and dynamic uniforms per draw, while the existing native indirect loop assumes state represented outside the indirect argument structure. Converting it blindly would bind the wrong per-draw state.

The first safe native target remains Sodium terrain after its per-draw vertex/material state is represented as buffer offsets or an argument-buffer record.

Required order:

1. serialize the current CPU terrain draw list into `IrisMetalIndirectCommandStream`;
2. verify batch boundaries and framebuffer equivalence;
3. feed the stream to the existing indirect draw API where state is already batch-invariant;
4. add an ICB native object owned per in-flight slot;
5. encode CPU-generated commands into ICB;
6. only then add GPU culling/compaction.

## Unit tests added

```text
IrisMetalHazardGraphTest
IrisMetalArgumentSnapshotTest
IrisMetalIndirectCommandStreamTest
```

They cover:

- independent adjacency;
- RAW and explicit-barrier rejection;
- no-op argument binding suppression;
- in-flight snapshot isolation;
- indirect batch coalescing and split keys.

They have been added but not run remotely.

## Local validation order

Run from a clean checkout of this branch:

```bash
./gradlew clean test
./gradlew build
```

Then use this order:

1. all advanced switches off; establish framebuffer and timing baseline;
2. plan dump only;
3. depth liveness only;
4. compute grouping only;
5. render-pass fusion only;
6. argument snapshot tracking only;
7. combinations after each lane passes independently.

For every lane test:

- Potato and BSL;
- Metal 3 and Metal 4;
- Metal API Validation;
- fixed camera and fixed time framebuffer captures;
- resize, reload, dimension switch, pack switch;
- MetalFX off, temporal scaler, then frame generation;
- median and p95 CPU/GPU timing;
- native render/compute encoder count;
- store/load, copy, mipmap, descriptor, and draw counts.

## Breakpoints and logging

Recommended breakpoints:

```text
IrisMetalOptimizationBootstrap.onPostChainCreated
IrisMetalRenderFusionRuntime.beginPass
IrisMetalComputeGroupingRuntime.begin
IrisMetalDepthAllocationRuntime.prune
IrisMetalDepthAllocationRuntime.ensure
IrisMetalArgumentBindingRuntime.attachPipeline
MetalCommandEncoder.renderCommandEncoder
MetalCommandEncoder.endEncoder
```

A generation plan failure must log once and preserve the conservative path. Do not add per-frame warning spam.

## Current implementation boundary

Now connected to real execution/resource ownership:

- physical-resource-aware render encoder fusion authorization;
- independent non-concurrent compute encoder grouping;
- depthtex1/2 copy skipping;
- depthtex1/2 allocation release and fail-closed lazy recreation;
- pipeline-derived argument ABI snapshots and in-flight rotation;
- existing deferred depth store optimization.

Implemented but awaiting native/API integration:

- independent color attachment load/store actions;
- Metal 3 bulk argument-buffer updates;
- final/color-space fused pipeline selection;
- true Metal ICB execution;
- GPU-generated command compaction.

This distinction must remain in any acceptance report.
