# MobileGL-inspired Java / FFM / Metal hot-path redesign

Branch: `feature/mobilegl-inspired-hotpath`

Base: `integration/iris-metal-next`

This branch independently reimplements the performance patterns observed in
MobileGL's DirectVulkan renderer. It does not copy MobileGL source code and does
not add Vulkan or MoltenVK to MetalUniversal.

## Design objective

The previous submission path could suppress some high-level Iris bindings, but
state still reached the native bridge as many fine-grained calls:

```text
MetalRenderPass
  -> MTLRenderCommandEncoder.setPipeline/setBuffer/setTexture/...
  -> one FFM downcall per setter
  -> one Metal encoder call per setter
  -> one FFM downcall per draw
```

The new path moves deduplication to the lowest Java layer before FFM and
collapses compatible indexed multi-draws into the existing native batch ABI:

```text
MetalRenderPass compact binding fingerprint
  -> encoder-local allocation-free state shadow
  -> skip unchanged FFM calls
  -> use setBufferOffset when only the offset changed
  -> collapse interleaved indexed draws into one native multi-draw call
```

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

`MTLRenderCommandEncoder` now checks this shadow before crossing FFM. A combined
vertex+fragment bind seeds both stage shadows, so later stage-specific calls can
be suppressed safely.

Buffer updates have three outcomes:

```text
SKIP         same backing and offset: no FFM call
OFFSET_ONLY  same backing, new offset: use setBufferOffset
FULL_BIND    new backing or untracked slot: use setBuffer
```

The cache is owned by the encoder object, so a new encoder starts empty without
cross-generation state leakage. The native clear helper invalidates the shadow
conservatively because it may install temporary native state.

### 2. Encoder-local compute state shadow

`MetalComputeStateShadow` suppresses repeated compute pipeline, buffer, texture
and sampler setters within one compute encoder. Dispatch calls and fence calls
are never suppressed.

### 3. Compact high-level binding fingerprints

`MetalRenderPassBindingCacheMixin` previously split each uniform/SSBO
fingerprint across multiple `HashMap` instances. A repeated binding required
several hashes, boxed lookups and default-value checks.

It now uses one mutable state record per resource:

```text
buffer identity + native backing generation + offset + length
```

Uniforms use one `Object2ObjectOpenHashMap`; integer SSBO bindings use one
`Int2ObjectOpenHashMap`. Stable repeated calls perform one lookup and no
allocation.

### 4. Native indexed multi-draw collapse

Mojang's first `multiDrawIndexed` overload supplies interleaved records:

```text
[firstIndex, indexCount, baseVertex]
```

The old Metal path issued one Java wrapper call and one FFM/native draw call for
every non-empty record. `MetalRenderPassMultiDrawBatchMixin` now:

1. keeps triangle-fan and malformed inputs on the conservative path;
2. deinterleaves records into reusable thread-local native arrays;
3. converts first-index units to byte offsets using the active index type;
4. binds pipeline/resources once;
5. invokes the existing native Metal multi-draw ABI once.

The scratch arena grows geometrically and is reused by the render thread. The
batch path defaults to four or more emitted draws.

## Runtime switches

```text
# Master switch for render/compute encoder state shadows. Default: true
-Dmetallum.opt.encoderStateShadow=true

# Maximum tracked binding index count. Clamped to 8..256. Default: 64
-Dmetallum.opt.maxShadowedBindings=64

# Interleaved indexed multi-draw collapse. Default: true
-Dmetallum.opt.nativeMultiDrawBatch=true

# Minimum emitted draws for native batching. Minimum: 2. Default: 4
-Dmetallum.opt.nativeMultiDrawBatchThreshold=4

# Optional counters. Default: false
-Dmetallum.hotpath.telemetry=true
```

`MetalHotPathTelemetry.snapshot()` reports:

- render state calls forwarded across FFM;
- render state calls suppressed before FFM;
- full buffer binds replaced by offset-only binds;
- compute state calls forwarded/suppressed;
- native multi-draw batches and commands;
- estimated collapsed draw-call count.

## Correctness boundaries

The redesign does not:

- reorder draws;
- merge different pipelines or attachment signatures;
- suppress draw, dispatch, fence or store-action operations;
- suppress texture calls at the high-level pass where deferred-clear semantics
  are executed;
- batch triangle fans;
- change render-contract producer metadata;
- alter Iris pass order, ping-pong sides or shader resources.

Unknown stage masks and out-of-range bindings fail open to the original native
setter. A closed encoder still throws before a duplicate can be suppressed.

## Validation

Run from a clean checkout:

```bash
./gradlew clean test
./gradlew build
```

Then compare the branch against `integration/iris-metal-next` with the same
world, camera path, resolution, Retina scale, VSync state, Sodium/Iris versions,
shader pack and render distance.

Required A/B lanes:

```text
A: encoderStateShadow=false, nativeMultiDrawBatch=false
B: encoderStateShadow=true,  nativeMultiDrawBatch=false
C: encoderStateShadow=true,  nativeMultiDrawBatch=true
```

For each lane record:

- FFM state setters per frame;
- render and compute suppression ratios;
- draw calls and native multi-draw batches;
- CPU render-pass encode p50/p95/p99;
- frame-time p50/p95/p99/p99.9;
- 1% and 0.1% lows;
- GPU time;
- framebuffer hashes and fixed-camera image diffs;
- Metal API Validation errors.

Acceptance requires framebuffer equivalence and no new Metal validation error.
A performance change is admitted only when at least one target metric improves
without a statistically meaningful regression in the others.

## Remaining native packet phase

This branch removes redundant crossings and batches compatible draws using the
existing ABI. The next phase should replace the remaining changed setter stream
with one versioned native state packet:

```text
Java compact dirty entries
  -> one FFM apply-state call
  -> native state shadow / argument table update
  -> draw batch
```

That phase requires extending the existing `MetalNativeBridge` and the existing
Swift module in place. It must not load a second dylib or mirror old setters and
argument tables simultaneously.
