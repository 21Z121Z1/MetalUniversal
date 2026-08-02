# Iris-on-Metal performance fast paths

Branch: `feature/iris-metal-performance`

This change set implements conservative CPU and bandwidth optimizations without changing the fixed-Iris rendering contract or shader-pack admission rules.

## Implemented

### Byte-identical dynamic uniform upload suppression

Metal uniform buffers use orphan-on-write backing swaps to preserve in-flight GPU safety. Before an upload, the new fast path compares the requested range with the currently owned shared backing. An upload is skipped only when:

- the buffer is a dynamic Metal buffer;
- the offset and length match the previous successful upload; and
- the current bytes are exactly identical.

Changed uploads retain the existing orphan/write path. A backing generation counter increments after every swap so descriptor caching never mistakes a new native `MTLBuffer` for the previous backing.

### Backing-aware uniform and SSBO descriptor suppression

Within one `MetalRenderPass`, repeated buffer bindings are suppressed only when all of the following match:

- Java buffer identity;
- native backing generation;
- byte offset; and
- byte range.

`DynamicTransforms` and `Projection` are intentionally excluded because their calls also invalidate the generated Iris draw-uniform block. Texture and storage-image binding calls are also intentionally excluded because they flush deferred clears or mark potential shader writes.

### Content-versioned mipmap generation

Each Metal texture receives an independent content version and mipmap version. Render attachments, copies, storage-image writes, uploads, MetalFX writes, and queued clears already pass through `markContentsDirty` or the clear hooks. `generateMipmaps` now becomes a no-op when the physical texture side has not changed since its last completed mipmap generation.

Tracking is per physical texture, so Iris main/alt ping-pong sides remain independent and flips preserve their original semantics.

### Cached Iris resource-name classification

The fixed Iris aliases parsed by `IrisMetalPostChain.renderTargetIndex` and `colorImageIndex` are process-stable. Positive and negative classification results are cached, removing repeated regular-expression and legacy-alias scans from fullscreen raster and compute binding loops.

### Optional counters

Set:

```text
-Dmetallum.iris.performanceCounters=true
```

Then query `IrisMetalPerformanceCounters.snapshot()` for:

- skipped descriptor binding calls;
- skipped uniform uploads and bytes;
- skipped mipmap generations; and
- resource-classification cache hits.

Accounting is disabled by default; the optimization paths remain enabled.

## Deliberately not changed

- shader precision or pack-visible calculations;
- pass ordering, ping-pong transitions, explicit flips, or barriers;
- texture/storage-image binding side effects;
- attachment load/store policy;
- argument-buffer / Metal 4 argument-table ABI;
- render-pass fusion or concurrent-compute scheduling;
- MetalFX integration.

Those require measured pass-graph and hazard analysis rather than unconditional fast paths.

## Validation status

No build, test, client launch, Metal API Validation run, or framebuffer comparison was performed for this branch, per the requested scope.
