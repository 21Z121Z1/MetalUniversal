# Native attachment action facts

The optional `-Dmetallum.validation.attachmentActions=true` diagnostic records raw
render attachment descriptors and their final store actions in a fullscreen
baseline measurement window. It is disabled by default. It does not change a
load/store action, resource lifetime, render order, or submission policy.
Disabled factories check a flag before reading descriptor properties or
allocating rows. This is a small diagnostic gate, not a claim of zero overhead.

These records are not yet an accepted attachment byte metric. The independent
estimator must establish descriptor coverage and supported format/subresource
semantics before admitting them. The canonical load/store byte metric remains
unavailable until that work passes. Logical attachment action bytes will also
remain distinct from physical GPU/DRAM traffic.

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
receipt. It does not turn on the canonical attachment byte metric.
