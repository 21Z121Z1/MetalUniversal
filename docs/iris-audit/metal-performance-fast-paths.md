# Iris-on-Metal performance fast paths

Branch: `feature/iris-metal-performance`

This change set implements conservative CPU and bandwidth optimizations without changing the fixed-Iris rendering contract, shader calculations, pass order, ping-pong transitions, or pack admission rules.

## Implemented conservative paths

### Dynamic uniform dirty-range uploads

Metal uniform buffers use orphan-on-write backing swaps to preserve in-flight GPU safety. The upload path compares requested bytes with the current CPU-visible backing and computes the minimal changed half-open range.

- byte-identical uploads are cancelled completely;
- partially changed uploads are trimmed to the first and last changed byte;
- the existing orphan path copies the prior backing and patches only that range;
- first uploads and non-dynamic buffers retain the original full-write path.

A backing generation counter increments after every swap so descriptor caching never mistakes a new native `MTLBuffer` for the previous backing.

### Backing-aware render and compute binding caches

Within a render pass, repeated uniform and SSBO bindings are suppressed only when Java buffer identity, native-backing generation, offset, and range are unchanged.

Within one compute encoder, the backend also caches compute pipeline identity, buffers, texture handles and sampler handles. Texture binding still performs the deferred-clear safety check before a native bind can be skipped.

`DynamicTransforms` and `Projection` remain excluded from render-pass uniform suppression because their calls also invalidate the generated Iris draw-uniform block.

### Validation-allocation gating

`MetalComputePass` no longer allocates producer metadata maps or numeric strings when no render-contract pass is active. Detailed bound-resource maps are allocated only when producer-detail capture is enabled.

### Content-versioned mipmap generation

Each physical Metal texture side receives an independent content version and mipmap version. Render attachments, copies, storage-image writes, uploads, MetalFX writes and clear requests invalidate the version. `generateMipmaps` becomes a no-op when the texture has not changed since its last completed generation.

### Exact full-surface copy suppression

A destination texture records the source object, source content version, mip level, width and height of its last completed full-surface copy. A later copy is skipped only when every field still matches and neither texture has been written or cleared in between.

### Persistent post-pass attachment views

`IrisMetalRenderTargets.createWriteDescriptor` reuses the ping-pong target's generation-owned write views instead of creating and destroying one `MetalGpuTextureView` per MRT attachment per fullscreen pass. Only genuinely transient depth views remain owned by the descriptor wrapper.

Integer render-target sampler classification is precomputed once per generation, and fixed white/zero clear vectors are reused.

### Immutable Iris lookup caches

The branch caches generation-stable lookups for render-target aliases, storage-image aliases, sampler requirements and types, uniform blocks and slices, draw block size, and dynamic transform/projection requirements.

Negative block/slice results are not cached because core programs can be registered lazily.

### Optional counters

Enable counters with:

```text
-Dmetallum.iris.performanceCounters=true
```

The snapshot reports skipped descriptor bindings, skipped or trimmed uniform uploads, skipped mipmap generation, skipped full texture copies and lookup-cache hits.

## Experimental high-risk layer

The remaining transformations now have an implemented default-off planning/runtime layer:

- hazard graph and dependency edges;
- render and compute merge candidates;
- attachment load/store policy model;
- generation resource liveness and depthtex1/depthtex2 requirements;
- final/color-space fusion eligibility inputs;
- stable argument-buffer / MTL4 argument-table layouts;
- per-in-flight argument snapshots;
- ICB-compatible indexed draw command grouping;
- fail-fast validation and JSON plan dump.

The full architecture and local-agent handoff are documented in:

```text
docs/iris-audit/experimental-performance-architecture.md
```

These transformations remain disabled until their native bridge entry points and complete generation descriptor population are connected locally.

## Validation status

No Gradle build, test suite, client launch, Metal API Validation run or framebuffer comparison has been performed for this branch, following the requested implementation-first scope. The changes remain unverified.
