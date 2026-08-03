# MobileGL-inspired Java / FFM / Metal hot-path redesign

Branch: `feature/mobilegl-inspired-hotpath`

Base: `integration/iris-metal-next`

This branch independently reimplements performance patterns observed in
MobileGL's DirectVulkan renderer. It does not copy MobileGL source code and does
not add Vulkan or MoltenVK to MetalUniversal.

## Objective

The original submission path could suppress selected Iris-level updates, but a
logical draw still expanded into repeated Java collection work and many
fine-grained Java -> FFM -> Swift -> Metal calls:

```text
MetalRenderPass
  -> String-keyed resource lookup
  -> one native setter per changed buffer/texture/sampler/state
  -> one native call per draw
```

The staged replacement is:

```text
compatibility-facing String API
  -> process-stable MetalBindingToken
  -> pipeline-local dense MetalCompiledBindingPlan
  -> pass-local binding fingerprint arrays
  -> encoder-local state shadow
  -> suppress unchanged FFM calls / use offset-only buffer updates
  -> collapse compatible indexed multi-draws into one native call
  -> later: versioned frame-local command packets
```

The branch deliberately stops before the final native packet ABI until the
Java-side changes pass compilation, semantic validation and controlled A/B
performance testing.

## Implemented layers

### 1. Encoder-local render state shadow

`MetalRenderStateShadow` tracks sticky state for one native
`MTLRenderCommandEncoder`:

- render pipeline;
- depth-stencil state;
- depth bias;
- winding, cull mode and fill mode;
- scissor;
- buffers and offsets per binding and shader-stage bit;
- textures and samplers per binding and shader-stage bit.

`MTLRenderCommandEncoder` checks the shadow before crossing FFM. A combined
vertex+fragment bind seeds both stage shadows.

Buffer changes are classified as:

```text
SKIP         same backing and offset: no FFM call
OFFSET_ONLY  same backing, new offset: setBufferOffset
FULL_BIND    new backing or unknown slot: setBuffer
```

The shadow belongs to the encoder object, not a process-global cache. New
encoders start empty. Native clear helpers invalidate the shadow because they may
install temporary native pipeline and binding state.

### 2. Encoder-local compute state shadow

`MetalComputeStateShadow` suppresses repeated compute pipeline, buffer, texture
and sampler setters within one compute encoder. Dispatches and synchronization
operations are never suppressed.

### 3. Allocation-free no-trace draw lane

Render-contract capture needs per-draw parameter maps and decimal strings. In
production, `contractPassToken < 0` means the recorder discards those objects.

`MetalRenderPassNoTraceDrawMixin` performs the same Metal work without building
producer metadata for:

- direct indexed draws;
- direct non-indexed draws;
- pointer-array indexed multi-draws;
- indexed indirect draws;
- non-indexed indirect draws.

The original methods remain the automatic path whenever contract capture is
active or the optimization is disabled.

Pass timing avoids both `System.nanoTime()` calls when GPU pass timing is off.
`MetalGpuTimingRecorder` also checks disabled flags before entering class
monitors for completed-frame timing, pass timing and encoder-lookup telemetry.

### 4. Process-stable binding tokens

`MetalBindingTokenRegistry` compiles each distinct compatibility resource name
once into:

```text
MetalBindingToken
  id
  logical Iris SSBO binding, when encoded in the name
  semantic flags
```

`DynamicTransforms` and `Projection` carry an explicit
`INVALIDATES_GENERATED_IRIS_BLOCK` flag, so deduplication cannot suppress their
required Iris draw-block invalidation.

`MetalBindingTokenCache` is a small direct-mapped identity cache. Stable pipeline
String objects resolve with one identity probe; equal-but-distinct strings fall
through to the canonical concurrent registry.

When tokenization is disabled, `MetalRenderPassBindingCacheMixin` restores the
prior String-keyed binding-state map for direct A/B comparison.

### 5. Pipeline-local dense binding plan

`MetalCompiledBindingPlan` is built once when `MetalCompiledRenderPipeline`
construction completes. Reflection is restricted to this generation step and
extracts the package-private binding records into immutable dense arrays:

```text
slot -> token
slot -> resource kind
slot -> physical Metal binding index
slot -> shader stage mask
```

A token-to-slot primitive map is also built once. When a render pass selects a
pipeline, it installs a `BindingState[]` sized exactly to the plan. Stable
uniform setup therefore becomes:

```text
String identity probe
  -> MetalBindingToken
  -> dense slot
  -> BindingState[] comparison
```

No String hash or per-draw reflection is involved. Unknown/non-pipeline bindings
fail open to a primitive token-id map. Switching pipelines replaces the dense
state array so slots from different layouts cannot alias.

The compiled-plan lane can be disabled independently while keeping tokenization
enabled.

### 6. Native indexed multi-draw collapse

Mojang's interleaved indexed records are:

```text
[firstIndex, indexCount, baseVertex]
```

`MetalRenderPassMultiDrawBatchMixin`:

1. preserves triangle-fan and malformed-input fallbacks;
2. validates absolute `IntBuffer.get(index)` against `limit()`, not `capacity()`;
3. deinterleaves records into reusable thread-local native arrays;
4. converts first-index units to byte offsets;
5. binds state once;
6. invokes the existing native Metal multi-draw ABI once.

The default threshold is four emitted draws. GPU draw order and GPU draw count do
not change; Java/FFM draw crossings are collapsed.

When tracing is off but a group cannot be batched, the original per-draw loop is
executed without producer metadata. When tracing is on and batching is
ineligible, the mixin returns before encoder creation and lets the target method
produce exact validation evidence.

### 7. Byte-budgeted dynamic backing pool

Dynamic uniform orphaning previously bounded retained handles per size bucket,
but not total retained bytes or number of distinct size buckets. A workload with
many allocation sizes could therefore retain substantially more memory than the
per-bucket constant suggested.

`MetalCommandEncoderDynamicBackingBudgetMixin` runs after `submit()` has rotated
the deferred destruction queue. Every handle visible in the pool is therefore
already safe with respect to in-flight GPU work.

`MetalDynamicBackingPoolBudget` enforces:

- a total retained-byte limit;
- a distinct-bucket limit;
- largest-allocation-first eviction;
- whole-bucket eviction when the bucket count is over budget;
- release-before-remove behavior, preserving the handle if native release throws.

The policy does not change `orphanWrite`, partial-update copying, backing swaps or
GPU lifetime proof. Optional telemetry records peak observed bytes, released
bytes, released handles and removed buckets.

## Runtime switches

```text
# Encoder-local render/compute state shadows. Default: true
-Dmetallum.opt.encoderStateShadow=true

# Maximum shadowed binding count, clamped to 8..256. Default: 64
-Dmetallum.opt.maxShadowedBindings=64

# Process-stable token binding cache. false restores String-keyed cache.
-Dmetallum.opt.bindingTokens=true

# Pipeline-local dense binding-state arrays. Requires bindingTokens=true.
-Dmetallum.opt.compiledBindingPlan=true

# Allocation-free production draw lane.
-Dmetallum.opt.noTraceDrawFastPath=true

# Interleaved indexed multi-draw collapse.
-Dmetallum.opt.nativeMultiDrawBatch=true

# Minimum emitted draws for batching, minimum 2.
-Dmetallum.opt.nativeMultiDrawBatchThreshold=4

# Dynamic backing total-budget enforcement.
-Dmetallum.opt.dynamicBackingPoolBudget=true

# Retained dynamic backing bytes. Default: 32 MiB.
-Dmetallum.opt.dynamicBackingPoolBytes=33554432

# Maximum distinct dynamic backing buckets. Default: 64.
-Dmetallum.opt.dynamicBackingPoolBuckets=64

# Submit interval between byte scans. Default: 16.
-Dmetallum.opt.dynamicBackingPoolTrimInterval=16

# Optional counters.
-Dmetallum.hotpath.telemetry=true
```

## Correctness boundaries

The redesign does not:

- reorder draws;
- merge different pipeline or attachment signatures;
- suppress draw, dispatch, fence, barrier or store-action operations;
- cancel texture/storage-image calls at the pass layer where deferred clears and
  possible shader writes are handled;
- batch triangle fans;
- change producer metadata when validation is enabled;
- change Iris pass order, ping-pong sides or resource identities;
- enable Metal 4 argument tables alongside direct bindings;
- add ICB, GPU culling, heap aliasing or async compute;
- release a dynamic backing before the existing deferred queue proves GPU
  completion.

Unknown stage masks and out-of-range bindings fail open to the original native
setter. A closed encoder still throws before a duplicate can be suppressed.

## Added unit coverage

The branch adds tests for:

- render state-shadow invalidation and stage-specific bindings;
- compute state-shadow behavior;
- reusable native multi-draw scratch arrays;
- stable/equal binding-token resolution;
- identity-cache clearing and capacity validation;
- Iris SSBO logical-binding compilation;
- dense binding-plan slot and metadata generation;
- duplicate binding-plan rejection;
- dynamic backing byte and bucket budgets;
- pool-key size decoding;
- preservation of a pooled handle when native release fails.

These tests still need to be executed by Gradle/CI; their presence is not itself
build evidence.

## Validation matrix

From a clean checkout:

```bash
./gradlew clean test
./gradlew buildMacNative build
```

Use the same world, deterministic camera path, resolution, Retina scale, VSync,
Sodium/Iris versions, shader pack and render distance.

Recommended isolated lanes:

```text
A  encoderStateShadow=false
   bindingTokens=false
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   dynamicBackingPoolBudget=false

B  encoderStateShadow=true
   bindingTokens=false
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   dynamicBackingPoolBudget=false

C  encoderStateShadow=true
   bindingTokens=true
   compiledBindingPlan=false
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   dynamicBackingPoolBudget=false

D  encoderStateShadow=true
   bindingTokens=true
   compiledBindingPlan=true
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   dynamicBackingPoolBudget=false

E  D + noTraceDrawFastPath=true

F  E + nativeMultiDrawBatch=true

G  F + dynamicBackingPoolBudget=true
```

For each lane record:

- FFM state setters per frame;
- render/compute suppression ratios;
- offset-only buffer updates;
- Java allocation bytes per frame and per draw;
- Java/FFM draw crossings and native multi-draw batches;
- GPU draw count separately;
- dynamic backing retained/released bytes and bucket count;
- CPU encode p50/p95/p99;
- frame-time p50/p95/p99/p99.9;
- 1% and 0.1% lows;
- frames over 33.3/50/100 ms;
- GPU frame time;
- framebuffer hashes and fixed-camera image diffs;
- Metal API Validation output.

Acceptance requires framebuffer equivalence and no new Metal validation error.
A change is admitted only when at least one target metric improves without a
statistically meaningful regression in the others.

Hosted CI can compile native code and run headless tests. Physical-GPU readbacks,
CAMetalLayer presentation and fixed-camera image evidence remain local Apple
Silicon acceptance requirements.

## Remaining phases

### P1c: token-native private call surfaces

The compatibility API still passes names into `setUniform`/`bindTexture`.
MetalUniversal-private Iris and Sodium paths should carry `MetalBindingToken`
directly, using the compiled plan without any name resolution. Public Blaze3D
compatibility methods remain as adapters.

### P2: explicit three-slot frame context and upload arena

Unify command-buffer lifetime, upload cursor, retained-resource list and deferred
release queue into three explicit frame slots. Dynamic uniforms should append
aligned slices rather than copying an entire previous backing for partial
orphaning where semantics permit.

### P3: versioned native state/command packets

Replace the remaining changed setter stream with a negotiated ABI:

```text
Java compact dirty entries / draw packets
  -> one ordinary FFM apply/encode call per pass, then per frame
  -> native state shadow or argument-table patch
  -> Metal draws/dispatches
```

This must extend the existing versioned native interface. It must not load a
second dylib or mirror direct setters and argument tables simultaneously.

### P4: true Metal 4 argument-table replacement

Argument tables are admitted only when they replace corresponding direct
`setBuffer`/`setTexture`/`setSampler` calls. Unchanged content signatures skip
both table patching and table binding.

### P5: terrain ICB/GPU-driven submission

Only after Java/FFM submission cost is reduced should Sodium opaque/cutout
terrain move to ICB or GPU-driven visibility. Iris composite and dynamically
varying pack passes remain on the ordinary command stream unless profiling shows
a separate benefit.
