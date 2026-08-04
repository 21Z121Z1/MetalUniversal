# Architecture and best-practice gap analysis

## Decision rule

Adopt a mechanism only when it closes a fixed OpenGL/Iris/Sodium semantic,
Metal lifetime rule, measured bottleneck, or required evidence gate. Reuse the
existing MetalUniversal semantic owners, frame graph, compiled pipeline, packet
bridge and native module. A planner, mirror, counter or default-off pilot is not
an implementation unless real work executes through it.

## Layer boundary

Target data flow:

```text
Minecraft / Blaze3D / Sodium / Iris compatibility calls
  -> validated observable semantics
  -> generation-aware resource identities and normalized operations
  -> compiled pipeline, binding and pass plans
  -> bounded off-heap ordered packets
  -> one versioned Java FFM ABI
  -> existing Swift native module
  -> Metal 3 encoders or Metal 4 command allocators/encoders
```

Java is the semantic owner. Swift validates transport/handle/type/lifetime
preconditions and executes Metal commands, but does not infer OpenGL behavior.
Encoder state shadows are execution caches scoped to an encoder; they are not a
second semantic state machine.

## Adopted practices and current gaps

### Canonical state and backend generations

- Source: MobileGL `MG_State/GLState/*`, `BackendObject_DirectVulkan.*`, latest
  commit `598c5497`; OpenGL 4.6 core object/deletion rules.
- Applies when an API-visible object is rebound, resized, orphaned, relinked,
  reattached or otherwise receives new backend storage.
- MobileGL design: API objects retain semantic identity and version state while
  backend allocations and renderer generations change independently.
- Starting MetalUniversal: textures have stable validation identities and
  ref-counted views; buffer orphaning swaps `MTLBuffer` handles without a
  backing generation. Some validation/resource keys therefore cannot
  distinguish old and new storage.
- Adoption: add monotonic backing generations, snapshot `(object,generation,
  native handle)` into ordered commands and compiled binding/ICB keys, retire
  old native handles by command-buffer completion.
- Not copied: MobileGL C++ ownership containers and Vulkan image layout state.
- Verification: unit replacement/deletion tests, same-command-buffer upload and
  draw readback, world rebuild/unload, Metal validation and no stale ICB handle.

### Frames in flight and deferred reclamation

- Source: MobileGL `FrameContext.*`, `BufferArena.*`; Apple “Synchronizing CPU
  and GPU Work”.
- Applies to mutable CPU-visible data and any resource reused while up to three
  command buffers are outstanding.
- MobileGL design: per-frame context owns transient slices and deferred backend
  destruction until its fence/serial completes.
- Starting MetalUniversal: three submit slots, transient arenas and a deferred
  destruction queue exist, but argument snapshots are Java mirrors and several
  pools require explicit generation proof.
- Adoption: one completion serial per submission slot; argument data, packet
  memory, transient slices and retired backings remain slot-owned until the
  completion handler advances it. Pool budgets remain bounded.
- Not copied: VMA allocation and Vulkan fences.
- Verification: delayed-completion unit test, repeated submit pressure, peak
  pool bytes and Metal validation.

### Compiled typed resource layout and argument data

- Source: MobileGL `ProgramFactory.*`, `UniformManager.*`; Apple argument-buffer
  and Metal 4 argument-table guidance; SPIRV-Cross reflection.
- Applies after a program is linked/translated and resource indices/stage
  visibility are fixed.
- MobileGL design: program reflection creates backend layouts once; runtime
  updates typed locations, not resource names.
- Starting MetalUniversal: `MetalCompiledBindingPlan` compiles resource slots,
  but `IrisMetalArgumentBindingRuntime` discovers private fields/methods by
  reflection and its own class comment confirms native setters remain the
  execution mechanism.
- Adoption: expose the compiled layout through a typed provider; build per-slot
  native-encoded argument snapshots and bind one table/buffer per relevant
  stage. Dirty deltas update table memory; dominant individual resource setters
  disappear from admitted draws. Writable resources are declared resident.
- Not copied: Vulkan descriptor sets/pools and MobileGL descriptor update code.
- Verification: ABI/layout tests, Metal 3/4 physical readback, per-slot safety,
  `nativeSetters` reduction, argument updates > 0, no reflection/string lookup
  in the draw sample.

### Atomic command packets and FFM

- Source: MobileGL ordered renderer/operation boundary; Java 25 FFM
  `MemorySegment` and `Arena` lifetime rules.
- Applies when multiple small state or command calls would otherwise cross FFM
  individually.
- MobileGL design: the backend consumes normalized operations rather than
  repeating GL entry semantics.
- Starting MetalUniversal: render/state/compute packets exist and native code
  has two-pass validation, but formal trials lacked exact global downcall count
  and some workloads did not activate compute/ICB.
- Adoption: versioned aligned packets with bounded capacity; exact handle-level
  FFM telemetry; reset only after warmup; packet rejects before operation zero;
  legacy replay only on proven zero execution.
- Not copied: C++ virtual dispatch or Vulkan command representation.
- Verification: malformed/truncated/unknown op tests, physical packet tests,
  packet calls/ops > 0, replay = 0, exact FFM calls per measured frame.

### Pipeline identity, archive and prewarm

- Source: MobileGL `PipelineFactory.*`, `VertexInputStateFactory.*`; Apple
  pipeline binary archive/prewarm and Metal 4 compiler guidance.
- Applies to every shader, vertex, attachment, sample, blend/mask,
  depth/stencil, raster and resource-layout variant.
- MobileGL design: normalized state forms cache identity and backend pipeline
  construction is isolated from draw submission.
- Starting MetalUniversal: the stable identity covers shader hashes, color
  targets, blend/masks, depth, raster, resources and vertex input, while
  attachment depth/stencil variants are asynchronously built. No trustworthy
  sample-window `runtimePipelineCompiles` gate exists.
- Adoption: instrument actual PSO creation with identity and phase, finish all
  observed variants before the performance window, persist compatible archive
  metadata, require zero sampled runtime compile and list late identities.
- Not copied: Vulkan pipeline-cache binary or render-pass compatibility key.
- Verification: key mutation tests, archive identity tests, reload/device
  generation invalidation, sample counter exactly zero.

### Hazard graph, encoder boundaries and load/store

- Source: MobileGL `DirectVulkanResourceState.h`, `VkRenderPassManager.*`,
  `VkClearManager.*`; Apple resource synchronization guidance.
- Applies to RAW/WAR/WAW, attachment reuse, transfers, compute/render edges,
  untracked resources and aliasing.
- MobileGL design: resources have explicit backend state and frame ordering;
  pending clears attach to concrete targets.
- Starting MetalUniversal: frame graph/hazard classes and pending clear maps
  exist, but some identities omit buffer backing generation; correctness must
  not rely on unified memory.
- Adoption: access/range/generation edges select encoder boundary, fence/event
  and resource usage; pending clear/load/store state is attachment-generation
  scoped; fusion only across proven-safe edges.
- Not copied: Vulkan layouts/stage masks/access flags verbatim.
- Verification: render->compute, compute->render, copy->render, clear->draw,
  ping-pong and aliasing readbacks with first divergent producer identity.

### Terrain ICB

- Source: Apple indirect command encoding/CPU ICB guidance; MobileGL bulk draw
  execution and per-frame ownership; Sodium fixed terrain source.
- Applies only to qualifying stable terrain command ranges whose pipeline,
  vertex/index buffers, transforms, material and sampled resources can be
  represented with complete lifetime/residency information.
- Starting MetalUniversal: real scope detection and telemetry exist, but the
  path requires an explicit direct-resource probe, creates an ICB per batch and
  has not admitted a real terrain draw. The sampled texture/sampler contract is
  incomplete.
- Adoption: cache bounded per-slot ICB capacity by pipeline/resource generation,
  feed sampled resources through the real argument layout, declare residency,
  encode all four terrain classes while preserving translucent order, and
  reject stale/incomplete ranges before any indirect execution.
- Not copied: Vulkan multi-draw/descriptor behavior.
- Verification: physical ICB readback, real accepted draw count > 0 for all
  applicable classes, fallback reason for inapplicable draws, rebuild/unload,
  no skipped draw, visual and performance guardrails.

### Presentation and timing

- Source: MobileGL `SwapchainObject.*`; Apple drawable lifecycle, presentation
  and Metal System Trace guidance.
- Applies to resize/fullscreen, drawable loss, display timing and normal exit.
- MobileGL design: swapchain generation and image-defined state are explicit.
- Starting MetalUniversal: native presentation/MetalFX code is extensive and
  recent device-session lifetime work is present; prior evidence did not close
  attended visual or formal timing gates.
- Adoption: acquire drawable as late as the existing dependency graph permits,
  bind presentation resources to device/drawable generation, record command
  buffer/encoder/GPU/present waits separately and never report unavailable
  timing as zero.
- Not copied: Vulkan swapchain recreation and MoltenVK surface workarounds.
- Verification: resize/fullscreen/shader reload/world reload/dimension change,
  display and exit status, Metal validation, 120-second structured trials.

## Metal 3 versus Metal 4

Metal 3 and Metal 4 share semantic operations, resource generations, binding
layout and packet ABI. Only the execution adapter differs:

- Metal 3 uses command buffers/encoders and an encoded argument buffer where
  supported.
- Metal 4 uses the repository's device/session/compiler/command allocator path
  and argument table where supported.
- Capability selection is explicit and recorded in each run manifest.
- A Metal 4 optimization cannot become the sole correctness path until the
  equivalent Metal 3 lane remains correct, or a documented OS/hardware
  capability makes the Metal 3 lane inapplicable.

## Official source set used

- Apple: `Synchronizing CPU and GPU Work`, `Resource synchronization`,
  `Using argument buffers with resource heaps`, `Indirect command encoding`,
  `Encoding indirect command buffers on the CPU`, WWDC 2025 Metal 4 sessions,
  and the 21 May 2026 Metal Feature Set Tables.
- Khronos: OpenGL 4.6 core specification and Vulkan synchronization semantics
  used only to interpret MobileGL behavior.
- MoltenVK: current runtime user guide and release notes, plus the exact local
  MoltenVK 1.4.2 binary identity above.
- Java: Java 25 `MemorySegment`/`Arena` API and final FFM rules from JEP 454.
- Fixed client source: Minecraft 26.2, Sodium 0.9.1 and Iris 1.11.2 resolved by
  this repository, not arbitrary current upstream branches.

The final report will cite exact URLs and run identities. Documentation claims
do not advance a matrix row to `PASS`; only tests and runtime evidence do.
