# Native attachment action facts

The optional `-Dmetallum.validation.attachmentActions=true` diagnostic records raw
render attachment descriptors and their final store actions in a fullscreen
baseline measurement window. It is disabled by default. It does not change a
load/store action, resource lifetime, render order, or submission policy.
Disabled factories check a flag before reading descriptor properties or
allocating rows. This is a small diagnostic gate, not a claim of zero overhead.

The independent estimator admits a bounded set of format/subresource semantics
after validating descriptor coverage. The canonical normalizer then admits
`render_pass_store_load_bytes_estimate_median` only for the same complete GPU and
encoder measurement window. This metric is logical action payload, not measured
GPU/DRAM traffic. Capture remains opt-in; observer overhead must be measured before
using this capture profile to accept a performance candidate.

## Capture boundaries

After warmup work drains, the validation client enables a bounded ledger. Each
tracked Metal 3 or Metal 4 render encoder factory captures the native descriptor.
Dynamic color and depth store setters update the recorded final action using
the actual native encoder identity; Metal 4 Java bridge wrappers are unwrapped.
The render encoder end hook seals the facts. After final GPU drain, the client
copies the records to `nativeAttachmentLedger` and disables capture.

The window/frame/submit/backend tuple comes from the existing encoder-count
binding, established before encoder construction. An encoder sequence is a
diagnostic sequence local to a reset, not a pointer or semantic pass identity.
Metal 4 physical command-buffer reuse retains each lease's bound tuple.

Every attempted descriptor has an `aspect=-1` marker followed by its present
color/depth/stencil attachments. A descriptor with no attachments still has a
marker. Its `slot` word is the expected attachment bitmask: color slots use bits
0–7, depth uses bit 8 and stencil uses bit 9. Consumers must match this mask to
the retained rows, so deleting an entire attachment row cannot appear complete.
Color holes have no attachment row; their slot numbers are preserved.
Rows contain value snapshots only; neither textures nor encoders are retained
by the ledger. Resolve fields are zero when no resolve texture is present.

Opaque encoders, private auxiliary queues and untracked pilot/auxiliary factories
remain outside this ledger's scope. The existing encoder ledger must pass before
using attachment rows; its unsupported-work diagnostics cannot be bypassed by
an apparently complete attachment snapshot. A missing identity, failed factory,
capacity overflow, active encoder, unresolved store or row error is incomplete
evidence. Consumers must check both envelope counters and per-row errors.

## Version 1 C ABI

All words are signed 64-bit integers, contiguous, with no struct padding.
Pointers are borrowed only during the call and are never retained.

`metallum_attachment_actions_reset(Int32 capacityRows) -> Int32` accepts 0 to
disable/reset and 1–65536 to enable/reset. It returns 1 for accepted capacity and
0 otherwise. The owner must drain work before resetting an active measurement.

`metallum_attachment_actions_copy(Int64 *rows, Int32 capacityRows,
Int64 *metadata) -> Int32` returns the row count. A null rows pointer queries the
count and metadata. A non-null rows pointer requires capacity for the entire
snapshot; insufficient capacity returns -1. The metadata buffer, when non-null,
must hold eight words. No GPU work may be recorded concurrently with the
Java two-call size/copy operation. Each row requires forty words.

| Metadata index | Meaning |
| --- | --- |
| 0 | Schema version, 1 |
| 1 | Enabled, 0 or 1 |
| 2 | Configured row capacity |
| 3 | Dropped rows |
| 4 | Invalid events |
| 5 | Active render encoders |
| 6 | Created render encoders represented by retained markers |
| 7 | Retained row count |

| Row indices | Fields, in order |
| --- | --- |
| 0–4 | windowId, frameId, submitIndex, backend (3/4), encoderSequence |
| 5–6 | aspect (-1 marker / 0 color / 1 depth / 2 stencil), slot (marker: expected attachment mask; color: index; depth/stencil: 0) |
| 7–14 | pixelFormat, width, height, depth, arrayLength, textureType, sampleCount, storageMode |
| 15–17 | level, slice, depthPlane |
| 18–20 | renderTargetWidth, renderTargetHeight, renderTargetArrayLength |
| 21–23 | loadAction, initialStoreAction, finalStoreAction |
| 24–31 | resolvePixelFormat, resolveWidth, resolveHeight, resolveDepth, resolveArrayLength, resolveTextureType, resolveSampleCount, resolveStorageMode |
| 32–35 | resolveLevel, resolveSlice, resolveDepthPlane, resolveFilter |
| 36–39 | storeActionOptions, ended (0/1), errorBits, reserved (0) |

Texture dimensions are native texture dimensions, before applying `level`.
Enum values are Metal raw values, not the Java V3 bridge's compact action codes:
for example, native `.unknown` store is 4, whereas the V3 bridge receives 2.
Marker rows preserve identity, expected attachment mask, render-target dimensions, ended and error fields;
their remaining fields are zero except `aspect=-1`.

Error bits are 1 for factory failure, 2 for unsupported work/action, and 4 for an
unresolved final store. Any nonzero value rejects a completed raw receipt.

No bytes-per-texel guess is embedded in the producer. A future consumer must
handle or reject compressed/packed formats, combined depth/stencil, memoryless
storage, MSAA, resolve modes, mips, array layers, depth planes, custom sample
depth stores and absent render-target dimensions explicitly. A valid ABI shape
alone does not prove any of those semantics.

## Validation commands

Run hardware lanes in separate JVMs so the Metal 4 context cannot contaminate
Metal 3:

```bash
./gradlew --no-daemon nativeAttachmentActionsMetal3Test nativeAttachmentActionsMetal4Test \
  -x buildIOSNative -x buildIOSSpvc
```

The GPU tests compare final color/depth actions, capture an encoder before and
after ending, cross Metal 4's lease ring, and exercise disabled, missing-identity
and capacity-overflow paths while checking real pixel readback. These cases are
bounded ABI/lifecycle evidence, not the complete renderer or descriptor matrix.
Java tests reject malformed envelopes without loading the native library.
Structural source checks supplement those tests; they do not prove runtime
coverage, byte estimates, visual equivalence or performance improvement.

For a real baseline report, run the independent raw-fact checker:

```bash
python3 scripts/agent/verify_native_attachment_facts.py \
  build/agent-runs/<run>/native-fullscreen-baseline.json \
  --output build/agent-runs/<run>/attachment-facts.json
```

This cross-checks attachment masks and encoder identities against the native
encoder ledger and GPU submission samples, including command buffers with no
render encoders. Exit 0 accepts diagnostic integrity only; exit 2 rejects the
receipt. Byte estimation is a separate check:

```bash
python3 scripts/agent/estimate_attachment_actions.py \
  build/agent-runs/<run>/native-fullscreen-baseline.json \
  --output build/agent-runs/<run>/attachment-bytes.json
python3 scripts/agent/normalize_unified_trial.py build/agent-runs/<run>
```

The trial directory must contain `exit-status.txt` with the actually observed
client command exit status. A source report alone does not establish successful
process completion. Missing metrics are separate performance-admission limits.

## Logical action byte model

The estimator sums load, final store, and resolve-destination payload per completed
frame, including explicit zero-render frames, then recomputes the median. The
historical metric name includes resolve bytes. Initial `unknown` resolved to
`dontCare` has a separate `deferredDiscardBytes` field; it is excluded from total
bytes and is not evidence of measured savings. Clear and dontCare do not load old
external contents. No physical allocation padding, compression, caches, tile
traffic, shader accesses, or auxiliary queues are inferred.

Supported shapes are level-zero 2D and 2D-array textures, including multisample
variants (1/2/4/8 samples), with valid selected slices or explicit layer counts.
[`renderTargetArrayLength`](https://developer.apple.com/documentation/metal/mtlrenderpassdescriptor/rendertargetarraylength)
equal to zero disables layered rendering: count the selected attachment slice,
not the entire texture array.
Explicit render dimensions must fit all attachments; implicit dimensions require
equal attachment extents. Mixed explicit/implicit dimensions, cube/3D textures,
nonzero mip levels, unknown formats, custom store options, and cross-aspect
resolve filters reject the entire estimate. No partial sum is published.

Color formats use their uncompressed logical sample width (1/2/4/8/16 bytes).
Depth16, Depth32 and Stencil8 use 2, 4 and 1 bytes. Depth32Float_Stencil8 uses
4 bytes for its depth aspect and 1 for stencil; this does not imply a 5-byte
allocation. Depth24Unorm_Stencil8 is not admitted. The exact enum allowlist is
`ASPECT_BYTES` in the estimator.

MSAA stores include every source sample; resolve stores include one destination
sample, and store-and-resolve includes both. Resolve destinations must be backed,
single-sample, format-matched and large enough. Memoryless source load/store is
rejected; clear/discard has zero external source payload, while a resolve still
counts its backed destination. This does not assert zero execution cost.

The model follows Apple's [load/store action semantics](https://developer.apple.com/documentation/metal/setting-load-and-store-actions)
and [Depth32Float_Stencil8 component and allocation distinction](https://developer.apple.com/documentation/metal/mtlpixelformat/depth32float_stencil8).
Synthetic tests establish arithmetic and rejection behavior. Real-client receipts
cover only the descriptor combinations actually present; they do not certify
every synthetic combination on hardware or full render-contract equivalence.
