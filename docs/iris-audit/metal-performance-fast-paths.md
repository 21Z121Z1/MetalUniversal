# Iris-on-Metal performance fast paths

Branch: `feature/iris-metal-performance`

This change set implements conservative CPU and bandwidth optimizations without changing the fixed-Iris rendering contract, shader calculations, pass order, ping-pong transitions, or pack admission rules.

## Implemented

### Dynamic uniform dirty-range uploads

Metal uniform buffers use orphan-on-write backing swaps to preserve in-flight GPU safety. The upload path now compares the requested bytes with the current CPU-visible backing and computes the minimal changed half-open range.

- byte-identical uploads are cancelled completely;
- partially changed uploads are trimmed to the first and last changed byte;
- the existing orphan path copies the prior backing and patches only that range;
- first uploads and non-dynamic buffers retain the original full-write path.

A backing generation counter increments after every swap so descriptor caching never mistakes a new native `MTLBuffer` for the previous backing.

### Backing-aware render and compute binding caches

Within a render pass, repeated uniform and SSBO bindings are suppressed only when Java buffer identity, native-backing generation, offset, and range are unchanged.

Within one compute encoder, the backend also caches:

- compute pipeline identity;
- buffer identity, backing generation, and offset;
- texture or texture-view native handle;
- sampler native handle.

Texture binding still performs the deferred-clear safety check before a native bind can be skipped. `DynamicTransforms` and `Projection` remain excluded from render-pass uniform suppression because their calls also invalidate the generated Iris draw-uniform block.

### Validation-allocation gating

`MetalComputePass` no longer allocates producer metadata maps or numeric strings when no render-contract pass is active. Detailed bound-resource maps are allocated only when producer-detail capture is enabled.

### Content-versioned mipmap generation

Each physical Metal texture side receives an independent content version and mipmap version. Render attachments, copies, storage-image writes, uploads, MetalFX writes, and clear requests invalidate the version. `generateMipmaps` becomes a no-op when the texture has not changed since its last completed generation.

Tracking is per physical texture, so Iris main/alt ping-pong sides remain independent and flips preserve their original semantics.

### Exact full-surface copy suppression

A destination texture records the source object, source content version, mip level, width, and height of its last completed full-surface copy. A later copy is skipped only when every field still matches and neither texture has been written or cleared in between.

This mainly targets unchanged persistent history copies. Partial copies, format changes, different mip levels, and changed source content retain the original blit path.

### Persistent post-pass attachment views

`IrisMetalRenderTargets.createWriteDescriptor` now reuses the ping-pong target's generation-owned write views instead of creating and destroying one `MetalGpuTextureView` per MRT attachment per fullscreen pass. Only genuinely transient depth views remain owned by the descriptor wrapper.

Integer render-target sampler classification is also precomputed once per generation, and constant white/zero clear vectors are reused.

### Immutable Iris lookup caches

The branch caches generation-stable lookups for:

- `colortexN` and legacy render-target aliases;
- `colorimgN` storage-image aliases;
- sampler requirements and sampler types;
- uniform token to block object;
- uniform token to GPU slice;
- draw block size;
- DynamicTransforms and Projection requirements.

Negative block/slice results are not cached because core programs can be registered lazily.

### Optional counters

Set:

```text
-Dmetallum.iris.performanceCounters=true
```

Then query `IrisMetalPerformanceCounters.snapshot()` for:

- skipped descriptor/compute binding calls;
- fully skipped uniform uploads and bytes;
- partially trimmed uniform uploads and bytes;
- skipped mipmap generations;
- skipped full texture copies and bytes;
- resource-classification cache hits;
- uniform lookup cache hits.

Accounting is disabled by default; the optimization paths remain enabled.

## Deliberately not changed

The following remain outside this conservative pass because they require measured hazard or ABI work:

- shader precision or pack-visible calculations;
- render-pass fusion;
- automatic compute-dispatch reordering or broader concurrency;
- attachment load/store liveness changes;
- conditional depthtex1/depthtex2 allocation or capture removal across all gbuffer/core/post programs;
- final/color-space shader fusion;
- Metal argument-buffer or Metal 4 argument-table ABI migration;
- indirect command buffers or GPU-driven submission;
- MetalFX scheduling and presentation behavior.

## Validation status

No Gradle build, test suite, client launch, Metal API Validation run, or framebuffer comparison has been performed for this branch, following the requested implementation-only scope. The changes therefore remain implemented but unverified.
