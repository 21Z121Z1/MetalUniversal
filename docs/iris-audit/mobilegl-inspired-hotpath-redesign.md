# MobileGL-inspired Java / FFM / Metal hot-path redesign

Branch: `feature/mobilegl-inspired-hotpath`

Base: `integration/iris-metal-next`

This branch independently reimplements performance patterns observed in
MobileGL's DirectVulkan renderer. It does not copy MobileGL source code and does
not add Vulkan or MoltenVK to MetalUniversal.

## Design objective

The original submission path could suppress some high-level Iris bindings, but
state still reached the native bridge as many fine-grained calls:

```text
MetalRenderPass
  -> String-keyed resource lookup
  -> MTLRenderCommandEncoder.setPipeline/setBuffer/setTexture/...
  -> one FFM downcall per changed setter
  -> one Metal encoder call per changed setter
  -> one FFM downcall per draw
```

The staged redesign is:

```text
compatibility-facing String API
  -> stable MetalBindingToken
  -> compact pass-local binding fingerprints
  -> encoder-local allocation-free state shadow
  -> skip unchanged FFM calls
  -> use setBufferOffset when only the offset changed
  -> collapse compatible indexed multi-draws into one native batch call
  -> later: versioned native state/command packets
```

The branch intentionally stops before the final packet phase until the lower
risk Java changes are correctness- and performance-validated.

## Implemented execution changes

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

`MTLRenderCommandEncoder` checks this shadow before crossing FFM. A combined
vertex+fragment bind seeds both stage shadows, so later stage-specific calls can
be suppressed safely.

Buffer updates have three outcomes:

```text
SKIP         same backing and offset: no FFM call
OFFSET_ONLY  same backing, new offset: use setBufferOffset
FULL_BIND    new backing or untracked slot: use setBuffer
```

The cache is owned by the encoder object. A new encoder starts empty, preventing
cross-generation state leakage. The native clear helper invalidates the shadow
conservatively because it may install temporary native state.

### 2. Encoder-local compute state shadow

`MetalComputeStateShadow` suppresses repeated compute pipeline, buffer, texture
and sampler setters within one compute encoder. Dispatch calls and fence calls
are never suppressed.

### 3. Stable binding tokens

Compatibility-facing Blaze3D calls still supply resource names as strings.
`MetalBindingTokenRegistry` compiles each distinct name once into a process-stable
integer identity:

```text
name
  -> token id
  -> logical Iris SSBO binding, when encoded in the descriptor name
  -> semantic flags
```

The two Mojang blocks that invalidate the generated Iris draw block,
`DynamicTransforms` and `Projection`, carry an explicit semantic flag. This
prevents ordinary binding deduplication from suppressing their required
invalidation behavior.

`MetalBindingTokenCache` adds a small direct-mapped identity cache in front of
the registry. Stable pipeline strings therefore resolve with one identity probe;
equal-but-distinct strings safely fall through to the canonical registry token.
The cache is reusable by the later command-stream writer and compute binding
path.

`MetalRenderPassBindingCacheMixin` now keys uniform fingerprints by primitive
token id instead of strings. A stable repeated uniform binding performs:

```text
String identity probe
  -> primitive token id
  -> Int2Object binding-state lookup
  -> buffer/backing-generation/offset/length comparison
```

No string hash or allocation is required on the identity-cache hit path.
Storage buffers already have integer logical bindings and retain their primitive
map.

Texture and storage-image calls are not cancelled at this layer because those
methods also materialize deferred clears or mark possible shader writes. Their
redundant native setters are suppressed by the encoder-local state shadow.

### 4. Allocation-free no-trace draw lane

Render-contract validation requires per-draw parameter maps and decimal strings.
In production, `contractPassToken < 0` means those objects are discarded
immediately by the recorder.

`MetalRenderPassNoTraceDrawMixin` preserves the exact Metal draw work but bypasses
producer-metadata construction when tracing is disabled for:

- direct indexed draws;
- direct non-indexed draws;
- pointer-array indexed multi-draws;
- indexed indirect draws;
- non-indexed indirect draws.

The original target methods remain the automatic fallback whenever contract
capture is active or the optimization switch is disabled.

Pass CPU timing also avoids both `System.nanoTime()` calls when GPU pass timing
is disabled.

### 5. Disabled telemetry does not enter monitors

`MetalGpuTimingRecorder` previously declared high-frequency recording methods as
`synchronized` and checked the feature flag only after monitor entry.

The disabled path now checks its static flag before synchronization for:

- completed frame timing;
- render-pass CPU timing;
- render-encoder lookup telemetry;
- latest GPU duration reads.

Enabled recording retains synchronized mutation and the existing public sample
ABI.

### 6. Native indexed multi-draw collapse

Mojang's first `multiDrawIndexed` overload supplies interleaved records:

```text
[firstIndex, indexCount, baseVertex]
```

The old Metal path issued one Java wrapper call and one FFM/native draw call for
every non-empty record. `MetalRenderPassMultiDrawBatchMixin` now:

1. keeps triangle-fan and malformed inputs on the conservative path;
2. validates absolute `IntBuffer.get(index)` accesses against `limit()`, not
   `capacity()`;
3. deinterleaves records into reusable thread-local native arrays;
4. converts first-index units to byte offsets using the active index type;
5. binds pipeline/resources once;
6. invokes the existing native Metal multi-draw ABI once.

The scratch arena grows geometrically and is reused by the render thread. The
batch path defaults to four or more emitted draws. GPU draw order and draw count
remain unchanged; only Java/FFM crossings are collapsed.

When tracing is disabled but a draw group is not eligible for batching, the
mixin executes the original per-draw loop without constructing producer metadata.
When tracing is enabled and batching is ineligible, it returns before creating an
encoder and lets the original method produce exact validation evidence.

## Runtime switches

```text
# Master switch for render/compute encoder state shadows. Default: true
-Dmetallum.opt.encoderStateShadow=true

# Maximum tracked binding index count. Clamped to 8..256. Default: 64
-Dmetallum.opt.maxShadowedBindings=64

# Allocation-free production draw lane. Default: true
-Dmetallum.opt.noTraceDrawFastPath=true

# Interleaved indexed multi-draw collapse. Default: true
-Dmetallum.opt.nativeMultiDrawBatch=true

# Minimum emitted draws for native batching. Minimum: 2. Default: 4
-Dmetallum.opt.nativeMultiDrawBatchThreshold=4

# Optional hot-path counters. Default: false
-Dmetallum.hotpath.telemetry=true
```

`MetalHotPathTelemetry.snapshot()` reports:

- render state calls forwarded across FFM;
- render state calls suppressed before FFM;
- full buffer binds replaced by offset-only binds;
- compute state calls forwarded/suppressed;
- native multi-draw batches and commands;
- removed Java/FFM draw crossings through `collapsedFfmDrawCalls()`.

## Correctness boundaries

The redesign does not:

- reorder draws;
- merge different pipelines or attachment signatures;
- suppress draw, dispatch, fence or store-action operations;
- suppress texture calls at the high-level pass where deferred-clear semantics
  are executed;
- batch triangle fans;
- alter validation producer metadata when tracing is enabled;
- alter Iris pass order, ping-pong sides or shader resources;
- enable Metal 4 argument tables in parallel with direct bindings;
- introduce ICB, GPU culling, heap aliasing or async compute.

Unknown stage masks and out-of-range bindings fail open to the original native
setter. A closed encoder still throws before a duplicate can be suppressed.

## Validation

From a clean checkout:

```bash
./gradlew clean test
./gradlew buildMacNative build
```

Then compare the branch against `integration/iris-metal-next` with the same
world, camera path, resolution, Retina scale, VSync state, Sodium/Iris versions,
shader pack and render distance.

Required functional lanes:

```text
A: encoderStateShadow=false, noTraceDrawFastPath=false, nativeMultiDrawBatch=false
B: encoderStateShadow=true,  noTraceDrawFastPath=false, nativeMultiDrawBatch=false
C: encoderStateShadow=true,  noTraceDrawFastPath=true,  nativeMultiDrawBatch=false
D: encoderStateShadow=true,  noTraceDrawFastPath=true,  nativeMultiDrawBatch=true
```

For each lane record:

- FFM state setters per frame;
- render and compute suppression ratios;
- Java allocation bytes per frame and per draw;
- Java/FFM draw crossings and native multi-draw batches;
- GPU draw count separately;
- CPU render-pass encode p50/p95/p99;
- frame-time p50/p95/p99/p99.9;
- 1% and 0.1% lows;
- frames over 33.3/50/100 ms;
- GPU time;
- framebuffer hashes and fixed-camera image diffs;
- Metal API Validation errors.

Acceptance requires framebuffer equivalence and no new Metal validation error.
A performance change is admitted only when at least one target metric improves
without a statistically meaningful regression in the others.

The repository's hosted workflow compiles native code and runs headless tests.
Physical-GPU readbacks, CAMetalLayer presentation and fixed-camera image evidence
remain local Apple Silicon acceptance requirements.

## Remaining phases

### P1b: pipeline-local compiled binding plan

The new process-stable token is the compatibility identity, not yet the final
physical layout. At pipeline creation, compile tokens into a dense immutable plan:

```text
MetalBindingToken
  -> resource kind
  -> stage mask
  -> physical Metal slot
  -> argument-table slot
  -> fallback policy
  -> residency usage
```

Private Iris/Sodium call sites can then carry the token directly and stop passing
names through the draw path.

### P2: frame-local upload arena

Unify command-buffer lifetime, upload cursor, retained-resource list and deferred
release queue into three explicit frame slots. Dynamic uniforms should append a
new aligned slice instead of orphaning/copying whole backing buffers.

### P3: versioned native state/command packet

Replace the remaining changed setter stream with one versioned ABI:

```text
Java compact dirty entries
  -> one ordinary FFM apply-state/encode call
  -> native state shadow or argument-table patch
  -> draw batch
```

This phase must extend the existing negotiated native interface and Swift module.
It must not load a second dylib or mirror old direct setters and argument tables
simultaneously.

### P4: true Metal 4 argument-table replacement

Argument tables are admitted only when they replace corresponding direct
`setBuffer`/`setTexture`/`setSampler` calls. Java emits changed slots, native
patches the frame-local table, and an unchanged content signature skips both the
patch and table bind.

### P5: terrain ICB/GPU-driven submission

Only after Java/FFM submission cost is reduced should Sodium opaque/cutout terrain
move to ICB or GPU-driven visibility. Iris composite and dynamically varying pack
passes remain on the ordinary command stream unless profiling demonstrates a
separate benefit.
