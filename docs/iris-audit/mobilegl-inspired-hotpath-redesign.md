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

The implemented path is now:

```text
compatibility-facing String API
  -> process-stable MetalBindingToken
  -> pipeline-local dense MetalCompiledBindingPlan
  -> pass-local binding fingerprint arrays
  -> encoder-local state shadow
  -> frame-local transient wrapper/retirement reuse
  -> versioned off-heap render-state packet
  -> one ordinary FFM packet call before a draw
  -> shared Swift Metal 3 / Metal 4 setter dispatch
  -> explicit draw / multi-draw
```

The packet phase currently batches admitted render-state changes. Draws,
dispatches, fences, barriers and encoder lifetime operations remain explicit
boundaries. This is an intentionally smaller and more reversible step than a
single monolithic per-frame command stream.

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

`MTLRenderCommandEncoder` checks the shadow before a state change can enter the
FFM packet or legacy setter path. A combined vertex+fragment bind seeds both
stage shadows.

Buffer changes are classified as:

```text
SKIP         same backing and offset: emit nothing
OFFSET_ONLY  same backing, new offset: emit setBufferOffset
FULL_BIND    new backing or unknown slot: emit setBuffer
```

The shadow belongs to the encoder object, not a process-global cache. New
encoders start empty. Native clear helpers invalidate the shadow because they may
install temporary pipeline and binding state.

### 2. Encoder-local compute state shadow

`MetalComputeStateShadow` suppresses repeated compute pipeline, buffer, texture
and sampler setters within one compute encoder. Dispatches and synchronization
operations are never suppressed.

Compute state does not yet use a native packet. It remains a separate A/B target
after the render packet has build and physical-GPU evidence.

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

A token-to-slot primitive map is built once. When a render pass selects a
pipeline, it installs a `BindingState[]` sized exactly to the plan. Stable
uniform setup becomes:

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
6. flushes any pending state packet;
7. invokes the existing native Metal multi-draw ABI once.

The default threshold is four emitted draws. GPU draw order and GPU draw count do
not change; Java/FFM draw crossings are collapsed.

Pointer-array multi-draws in the original validation/legacy method also flush
after `bindDrawState`, because that method calls the native batch ABI directly
rather than an encoder draw wrapper.

### 7. Byte-budgeted dynamic backing pool

Dynamic uniform orphaning previously bounded retained handles per size bucket,
but not total retained bytes or number of distinct size buckets.

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

### 8. Frame-local transient arena reuse

`MetalTransientMemory` now follows the frame-arena pattern more closely:

- one `TransientGpuBuffer` facade per backing/usage pair per submit cycle;
- an identity-keyed, variant-indexed frame cache;
- cache reset on frame rotation without closing values retained by old slices;
- wrapper validity derived from owner shutdown or submit-index rotation;
- wrapper `close()` is non-owning, so closing one slice cannot invalidate every
  sibling slice sharing the same frame facade;
- one reusable primitive `int[]` work area for `multiUpload` ordering;
- no `IntStream`, boxed comparator or temporary `IntArrayList` in packing.

The underlying `TransientBlockAllocator`, block sizes and deferred GPU release
remain unchanged.

`MetalTransientArenaTelemetry` reports wrapper hits/misses, wrapper reuse ratio,
`multiUpload` calls and uploaded item counts.

### 9. Allocation-free deferred retirement rotation

`MetalDestructionQueue` no longer replaces a slot with a new `ArrayList` every
rotation. Each slot owns two reusable lists:

```text
pending  <- callbacks queued for this frame slot
draining <- callbacks proved safe and currently executing
```

Rotation swaps the two lists, executes `draining`, then clears it for future
reuse. A callback may enqueue another retirement without modifying the list
being iterated; the new callback waits until the slot rotates back again.

The queue depth remains `MAX_SUBMITS_IN_FLIGHT + 1`, preserving the existing
semaphore-completion proof before destruction.

### 10. Versioned render-state packet

`MetalRenderStatePacket` owns one confined, reusable off-heap segment per render
encoder. Only state changes admitted by `MetalRenderStateShadow` are appended.

Packet header:

```text
UInt32 magic      = 'MRSP'
UInt32 version    = 1
UInt32 byteCount
UInt32 entryCount
```

Each 48-byte entry contains:

```text
UInt32 opcode
UInt32 stageMask
UInt64 index
UInt64 a
UInt64 b
UInt64 c
UInt64 d
```

Supported opcodes:

- pipeline;
- depth-stencil;
- depth bias;
- winding;
- cull mode;
- fill mode;
- buffer;
- buffer offset;
- texture;
- texture + sampler;
- scissor.

State is flushed before:

- direct and indexed draws;
- indirect draws;
- triangle-fan draws;
- native multi-draw calls;
- clear helpers;
- fence waits/updates;
- deferred depth-store resolution;
- encoder end.

A single entry defaults to the legacy setter because a packet call would not
reduce crossing count. Two or more entries use one ordinary, non-critical FFM
call.

### 11. Native interface negotiation and atomic fallback

The packet function is published as feature id 3, ABI version 1 through the
existing append-only `metallum_get_interface` table. The interface includes its
own size, version, feature id, entry count and capability bits.

On macOS, the main native bridge loads a random temporary dylib through a private
FFM lookup. `DarwinLoadedSymbolLookup` therefore:

1. walks dyld's already-loaded image list;
2. accepts only `libmetallum*.dylib` or `metallum-native-*.dylib`;
3. rejects adjacent images such as `libspvc_metallum.dylib`;
4. opens the existing image with `RTLD_NOLOAD`;
5. verifies the requested symbol with `dlsym`;
6. keeps that handle for process lifetime.

It never extracts or loads a second Metallum dylib.

The Swift decoder validates the complete packet before applying any entry. It
then calls the shipping Swift setter functions rather than directly calling
Metal. Those setters already route the raw encoder handle to either:

- the Metal 3 `MTLRenderCommandEncoder`; or
- `Metal4MainRenderEncoderBridge`, including its Metal 4 argument tables.

If negotiation is unavailable, packet validation fails, or the returned applied
count differs, Java replays the complete packet through the legacy bridge. State
setters are idempotent, so replay restores the exact final encoder state. The
packet path is then disabled for that encoder.

`MetalRenderStatePacketTelemetry` reports:

- packet calls;
- packet entries;
- average entries per packet;
- collapsed setter downcalls;
- legacy replays and replayed entries;
- single-entry bypasses;
- capacity flushes.

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

# Render-state packet. Negotiation failure automatically uses legacy setters.
-Dmetallum.opt.renderStatePacket=true

# Maximum packet entries, clamped to 16..2048. Default: 256.
-Dmetallum.opt.renderStatePacketEntries=256

# Minimum entries required for a native packet call, clamped to 1..16.
-Dmetallum.opt.renderStatePacketMinEntries=2

# Dynamic backing total-budget enforcement.
-Dmetallum.opt.dynamicBackingPoolBudget=true

# Retained dynamic backing bytes. Default: 32 MiB.
-Dmetallum.opt.dynamicBackingPoolBytes=33554432

# Maximum distinct dynamic backing buckets. Default: 64.
-Dmetallum.opt.dynamicBackingPoolBuckets=64

# Submit interval between byte scans. Default: 16.
-Dmetallum.opt.dynamicBackingPoolTrimInterval=16

# Optional counters for shadows, packets, arena and backing pool.
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
- create a second native dylib instance;
- introduce a second Metal 4 binding implementation inside the packet decoder;
- add ICB, GPU culling, heap aliasing or async compute;
- release a dynamic backing before the existing deferred queue proves GPU
  completion.

Unknown stage masks and out-of-range bindings fail open to the existing Swift
setter behavior. A closed encoder still throws before a duplicate can be
suppressed.

## Added unit coverage

The branch adds or expands tests for:

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
- preservation of a pooled handle when native release fails;
- frame-local identity/variant cache semantics;
- deferred-retirement rotation and reentrant enqueue behavior;
- Darwin Metallum image-name filtering;
- append-only native interface header/entry parsing;
- render-state packet fixed-width field layout and float-bit encoding;
- packet close behavior.

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
   renderStatePacket=false
   dynamicBackingPoolBudget=false

B  encoderStateShadow=true
   bindingTokens=false
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   renderStatePacket=false
   dynamicBackingPoolBudget=false

C  encoderStateShadow=true
   bindingTokens=true
   compiledBindingPlan=false
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   renderStatePacket=false
   dynamicBackingPoolBudget=false

D  encoderStateShadow=true
   bindingTokens=true
   compiledBindingPlan=true
   noTraceDrawFastPath=false
   nativeMultiDrawBatch=false
   renderStatePacket=false
   dynamicBackingPoolBudget=false

E  D + noTraceDrawFastPath=true

F  E + nativeMultiDrawBatch=true

G  F + dynamicBackingPoolBudget=true

H  G + renderStatePacket=true
```

Also run the reverse packet isolation:

```text
P0 encoderStateShadow=false, renderStatePacket=false
P1 encoderStateShadow=false, renderStatePacket=true
P2 encoderStateShadow=true,  renderStatePacket=false
P3 encoderStateShadow=true,  renderStatePacket=true
```

For each lane record:

- logical state changes admitted by the Java shadow;
- direct setter FFM calls;
- packet calls and entries;
- collapsed setter downcalls;
- packet legacy replay count;
- render/compute suppression ratios;
- offset-only buffer updates;
- Java allocation bytes per frame and per draw;
- transient wrapper hits/misses and reuse ratio;
- `multiUpload` calls/items;
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
directly, using the compiled plan without name resolution. Public Blaze3D
compatibility methods remain adapters.

### P2b: explicit frame submission slots

Transient wrapper and retirement reuse are now frame-scoped, but command buffer,
submit callbacks, upload cursor, retained-resource list and deferred releases are
not yet represented by one explicit `MetalFrameContext[3]` object. That
consolidation should preserve the existing semaphore proof and avoid changing
presentation or MetalFX ownership in the same patch.

### P3b: draw/dispatch command packets

The render-state packet establishes negotiation, off-heap storage, failure
replay and Metal 3/4 shared decoding. The next packet stage may add draw records
only after packet state shows positive evidence. Readback, queries, fences,
encoder transitions and present remain forced flush points.

### P3c: compute state packet

Compute setters can use the same negotiated pattern after render packet evidence.
Do not combine this with compute-grouping or async-compute experiments.

### P4: true Metal 4 argument-table replacement

The existing Metal 4 setter path already writes its argument tables. Further
argument-table work is admitted only when it removes corresponding Java/native
setter work rather than mirroring it. Unchanged content signatures must skip both
patching and table binding.

### P5: terrain ICB/GPU-driven submission

Only after Java/FFM submission cost is reduced should Sodium opaque/cutout
terrain move to ICB or GPU-driven visibility. Iris composite and dynamically
varying pack passes remain on the ordinary command stream unless profiling shows
a separate benefit.
