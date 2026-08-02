# Render Contract Validation

Render-contract validation is the backend-neutral evidence layer for fixed
Minecraft, Sodium, Iris, and MetalUniversal builds. It validates logical render
semantics before relying on a final screenshot. The contract is opt-in and is
disabled in ordinary gameplay.

## Architecture

```text
deterministic Minecraft scenario
        |
        v
MetalValidationClient -> RenderContractRuntime
        |
        +-> RenderTraceRecorder -> pass-manifest.json
        +-> ValidationCaptureService -> GPU readback -> results.json
        +-> Expectation engine -> actual/expected/diff/metrics artifacts
        +-> PassManifestComparator -> first divergent pass/producer
```

The recorder is a logical trace. A native Metal encoder may be split, merged,
or replaced without changing the semantic pass ID. The trace is not an
OpenGL-call replay format.

## Current implementation mapping

The existing deterministic timeline, Sodium FlawlessFrames setup, MetalFX
texture-to-buffer readback, flicker metrics, and `run-state.json` gate remain
owned by their original components. The new layer adds:

- `MetalCommandEncoder.createRenderPass` and `MetalRenderPass` record render
  pass attachments, viewport/scissor, pipeline IDs, shaders, and draw
  producers.
- `MetalComputePass` records compute passes and direct or indirect dispatches.
- `MetalCommandEncoder` records copy, resolve, mipmap, clear-region, and
  present operations as logical transfer passes.
- `MetalFxManager` submits its existing attachment readbacks to the generic
  capture service at `AFTER_TEMPORAL_ENCODE`.
- `MetalValidationClient` owns frame boundaries and requests final drawable
  capture for the same deterministic validation frames.
- The old per-frame `.bin` and `metrics.json` output remains intact; the
  contract artifacts are written beside it under `render-contract/`.
- A shared `ValidationStorageBudget` accounts the complete validation root,
  including legacy raw attachments, PNGs, metrics, results, run state, and the
  pass manifest. A budget failure is a failed contract result, never a silent
  truncation.

## Capture points

`CapturePointKind` supports:

```text
BEFORE_PASS
AFTER_CLEAR
AFTER_PRODUCER
AFTER_PASS
AFTER_TEMPORAL_ENCODE
BEFORE_PRESENT
AFTER_UI_COMPOSE
FINAL_DRAWABLE
```

Normal Minecraft validation captures the ten existing MetalFX attachments at
`AFTER_TEMPORAL_ENCODE`:

```text
input-color, depth, camera-motion, object-motion, object-validity,
merged-motion, disocclusion, cutout-coverage, reactive, temporal-output
```

The service requires `requestCapture` before `completeCapture`. This makes a
missing or late readback a failed lifecycle event instead of an untracked
successful file write. Requests are bounded by maximum captures, pending
requests, capture payload bytes, artifact bytes, manifest bytes, and the
recorder's frame/pass/producer budgets.

The `FINAL_DRAWABLE` path reads the source texture immediately before present.
Its metadata says `PRE_PRESENT_DRAWABLE_CONTENT`; it is not a claim about
WindowServer scanout, VRR, or display timing. Real presentation timing remains
covered by the existing display-link validation.

## Pass and producer records

Each pass has a frame ID, per-frame sequence, semantic ID, pass type,
attachments, resource generation, viewport, scissor, pipeline ID, shader IDs,
producer list, and metadata. Producer types include clear, draw variants,
dispatch variants, blit, copy, resolve, mipmap generation, and present.

Every Java/native boundary carries the same `TraceIdentity`:

```text
runId, frameId, passSequence, semanticPassId,
producerIndex, commandBufferSubmissionId
```

The identity is serialized in the manifest and capture metadata and is also
emitted as a Metal debug group by the command encoder when the contract is
enabled. Log timestamps and encoder ordinals are diagnostic context only; they
are not used to join Java, FFM, and Swift events.

Stable pass names use semantic namespaces such as:

```text
minecraft/world/opaque
iris/gbuffers/terrain
iris/composite/0
iris/final
metallum/metalfx-temporal
metallum/present
```

An unknown render label is recorded as `unclassified/<stable-hash>` by the
recorder. It is never replaced by a different pass and can be rejected by a
strict fixture. Encoder ordinals, object addresses, and shader-pack names are
not semantic IDs.

Pipeline IDs are cached content-derived identifiers. They include the shader
stage material used by the compiled pipeline plus state inputs where the
existing pipeline exposes them. Metal 3/Metal 4 implementation labels belong
in metadata; they do not change semantic pass IDs.

## Resource identity

`ResourceIdentity` contains semantic name, runtime allocation ID, generation,
debug/native identity, format, dimensions, mip, sample count, and usage. The
stable key is for example:

```text
colortex0@41
colortex0@42
```

The generation changes when a semantic resource is reallocated with a new
runtime identity or shape. It is never based only on a Java object address,
Swift pointer, or semantic name.

## Expectations

The expectation package contains five distinct contracts:

- `ExactExpectation`: byte/texel equality with an optional byte mask.
- `NumericExpectation`: integer, FP16, FP32, depth, and HDR values with
  absolute/relative tolerance, optional ULP tolerance, bounds, NaN/Inf policy,
  mean, P95, P99, and maximum error.
- `InvariantExpectation`: executable rules such as finite motion, validity
  masks, coverage, dimensions, and declared depth conventions.
- `ImageExpectation`: LDR per-channel comparison with alpha handling,
  mismatch count, RMSE, PSNR, and SSIM metrics. New image contracts must
  declare channel order (`RGBA`/`BGRA`), origin (`TOP_LEFT`/`BOTTOM_LEFT`),
  and color space (`sRGB`/`linear`) when normalization is required. It is not
  used as the core contract for motion, depth, validity, or reactive resources.
- `TemporalExpectation`: ordered prefix comparison after warmup, with finite
  value checks and mean/P95/max frame deltas.

Failure artifacts retain raw bytes and structured metrics. Floating-point
attachments are not reduced to a PNG-only assertion.

## Fixture format

The registry lives at:

```text
validation/render-contract/cases.json
validation/render-contract/schemas/cases.schema.json
validation/render-contract/fixtures/<case>/
```

Every case has a schema version, scenario, backend modes, capture policy, and
expectation file. A real shader pack fixture records its identifier, version,
SHA-256, configuration hash, and acquisition note; the pack binary is not
committed without distribution permission.

A generated run uses this layout. The default agent/verification location is a
unique directory below the operating system temporary directory, not `build/`:

```text
${TMPDIR}/metallum-render-contract-*/
  pass-manifest.json
  results.json
  synthetic-validation.json
  frames/frame-.../<pass>/<producer>/<resource>/
    metadata.json
    actual.bin
    expected-<expectation>.bin
    actual.png                 # when the format is byte-image compatible
    expected-<expectation>.png
    diff-<expectation>.bin
    diff-<expectation>.png
    metrics.json
```

Successful Minecraft temporary runs are deleted after the Gradle completion
gate. Failed runs remain under the managed temporary prefix so they can be
inspected without copying a large capture into the repository. Stale managed
temporary runs can be removed explicitly:

```sh
./gradlew renderContractCleanup --no-daemon
```

To retain a run for analysis, opt in explicitly. This is the normal path that
places new render-contract output under `build/`:

```sh
./gradlew renderContractMinecraftValidation \
  -PrenderContractPersist=true --no-daemon

./gradlew renderContractCase \
  -PrenderContractCase=synthetic-mrt-basic \
  -PrenderContractPersist=true --no-daemon
```

`-Dmetallum.validation.output=/absolute/path` is also an explicit output
override. The storage controls are `metallum.renderContract.maxArtifactBytes`
and `metallum.renderContract.maxCaptureBytes`. A persistent output root uses a
2 GiB artifact default; a managed system-temporary root uses a 768 MiB artifact
default. The capture payload default follows the shared artifact budget, so a
capture scheduler cannot silently reserve a larger second budget. Explicit
properties still override these defaults, but the shared artifact budget always
remains authoritative. `metallum.renderContract.maxManifestBytes` defaults to
64 MiB. Limits are per run, not a rolling quota, and rewrites are charged by
final file size.

The cleanup task keeps at most two managed runs and 768 MiB in the system
temporary directory by default, removing runs older than twelve hours first.
Those limits can be changed with `-PrenderContractTempRetentionHours`,
`-PrenderContractTempMaxRuns`, and `-PrenderContractTempMaxBytes`. A failed
run is retained under `/tmp` until cleanup so its bounded evidence can be
inspected; a successful temporary run is removed after its completion gate.
Use `-PrenderContractPersist=true` or an explicit
`-Dmetallum.validation.output=/absolute/path` only when the artifacts need to
survive for analysis. Persistent output is never copied automatically.
The Minecraft validation task also runs the same bounded cleanup as a
post-run finalizer, including when the client exits with a failed expectation,
so older managed evidence is evicted without deleting the newest failure
report.

Normal Minecraft contract validation records producer counts and type counts,
but omits per-producer bindings. Enable the expensive diagnostic evidence only
for a focused rerun:

```sh
./gradlew renderContractMinecraftValidation \
  -Dmetallum.renderContract.captureProducers=true --no-daemon
```

The pass manifest marks this choice as `producerDetailsCaptured` and
`producerDetailsComplete`. When the first is `false`, an empty `producers`
array means "details were not captured", not "the pass had zero producers".
When the second is `false`, the records are a bounded diagnostic slice rather
than a complete producer trace. Pass-level producer counts remain comparable;
`compareProducers` fails closed with `producer comparison unavailable` until
both sides contain complete detailed records.

Use a focused diagnostic rerun with:

```text
-Dmetallum.renderContract.tracePass=<semanticPassId>
-Dmetallum.renderContract.captureProducers=true
-Dmetallum.renderContract.producerRange=<start:end>
-Dmetallum.renderContract.maxDetailedProducers=<n>
```

The current implementation records producer type, parameters, resource
binding summaries, written attachments, and the shared trace identity. It does
not yet automatically replay a Minecraft pass while scheduling GPU attachment
readbacks after every producer; producer-level localization is therefore a
bounded manifest/evidence capability, not a claim of complete automatic GPU
binary-search replay.

For a persistent Minecraft diagnostic run, use the dedicated task. It enables
producer details by default and writes the outer validation artifacts to
`build/render-contract/minecraft-diagnose-current/`; the contract evidence is
under that directory's `render-contract/` child:

```sh
./gradlew renderContractMinecraftDiagnose --no-daemon
```

Ordinary `renderContractMinecraftValidation` remains temporary unless
persistence is explicitly requested. This diagnostic task is the intentional
analysis escape hatch and is not enabled by ordinary gameplay or unit tests.

Offline diagnosis compares two already persisted contract roots. Pass the
inner roots containing `pass-manifest.json` and `results.json`, not the outer
Minecraft directory:

```sh
./gradlew renderContractDiagnose \
  -PrenderContractReference=/absolute/reference/render-contract \
  -PrenderContractActual=/absolute/actual/render-contract \
  -PrenderContractReport=/absolute/report.json --no-daemon
```

The task fails when either run is incomplete, even when all available raw bytes
happen to match. A failed capture parent is still mined for any raw
`actual.bin` that was successfully written, but the report labels the result
incomplete and cannot mark it passed. This separates useful forensic evidence
from a valid contract result.

Golden files are never updated by a failed test. The explicit update command
requires both flags and copies the selected generated case into the fixture:

```sh
./gradlew renderContractCase -PrenderContractCase=synthetic-mrt-basic \
  -PrenderContractPersist=true --no-daemon
./gradlew updateRenderContractGolden \
  -PrenderContractCase=synthetic-mrt-basic \
  -PconfirmGoldenUpdate=true -PrenderContractPersist=true --no-daemon
```

## First divergence workflow

`PassManifestComparator.compare` finds the first logical pass whose manifest
differs. `compareCaptures` then compares ordered attachment samples and reports
the first pass, producer, resource, mismatch count, maximum error, mean error,
and P95 error. `compareProducers` performs the same localization within a
known pass, but fails closed when either manifest omitted producer details.
The final manifest has `manifestComplete=true` only after all open passes are
closed and the manifest itself has been written within budget.

Capture comparison aligns by frame, semantic pass occurrence, producer index,
and stable resource key. Native sequence numbers are evidence, not the
long-term identity. Attachment contracts compare semantic resource name,
generation, format, dimensions, mip/sample state, usage, and load/store
actions; runtime object IDs and native pointers are deliberately not used for
cross-backend equality.

For a costly real run, capture only `AFTER_PASS` first. Re-run the reported pass
with producer capture enabled and narrow the producer range until the report
contains `lastMatchingProducer` and `firstDivergentProducer`. The evidence
should include the producer type, pipeline/shader IDs, bindings, viewport,
scissor, blend/depth state metadata, and the previous/current attachment
artifacts. A "likely stage" is an inference and must not be presented as a
confirmed root cause.

## Iris reference boundary

`IrisReferencePassRegistry` registers program, pass index, and stage against a
semantic ID. It deliberately has no shader-pack-name branch. Reference runs
are represented by `ReferenceRun`, `ReferenceFrame`, `ReferencePass`,
`ReferenceAttachment`, and `ReferenceProducer` records.

The capability result is one of:

```text
SUPPORTED
SUPPORTED_WITH_DECLARED_DIFFERENCE
REJECTED_BEFORE_EXECUTION
UNCLASSIFIED
```

The current repository provides the registration boundary and synthetic
contract path. It does not yet claim a complete fixed-version
Iris/OpenGL-to-Metal replay or cross-backend parity for every shader pack.
Missing reference artifacts and unclassified strict passes are evidence of an
incomplete run, not a pass.

## Metal 3 and Metal 4

`renderContractMetal3NativeTest` and `renderContractMetal4NativeTest` run the
same production Java -> FFM -> Swift native integration suites with separate
backend properties. `renderContractSyntheticValidation` additionally runs the
deterministic contract model for MRT, depth/occlusion, blend, viewport/scissor,
compute-to-render dependency, temporal prefix, and final composition, then
compares the Metal 3 and Metal 4 logical manifests.

Native smoke success alone is not an expectation result. The task must produce
test results and the synthetic run must produce passed manifests and capture
results.

## Minecraft completion gate

`renderContractMinecraftValidation` enables the recorder only for that task.
The client writes contract counters into `run-state.json` and the Gradle
`runClient` gate requires:

```text
timeline completed
expected GPU captures completed
no legacy metric failures
contract requested == completed captures
no pending/failed/dropped captures
no dropped trace events
logical pass count > 0
pass manifest finalized
```

An absent or malformed run-state is a failure, even when Gradle itself exits
successfully. WindowServer, MetalFX private-kernel, GPU validation, and display
scanout limitations must be reported as skipped or environment failures, not
silently converted to a green result.

## Commands

Use the signed Homebrew JDK 25 on this machine:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew renderContractUnitTest --no-daemon

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew renderContractNativeTest --no-daemon

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew renderContractSyntheticValidation --no-daemon

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew renderContractMinecraftValidation --no-daemon

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew renderContractValidation --no-daemon
```

`renderContractValidation` is the aggregate and includes ordinary tests,
native build, both Metal modes, synthetic validation, manifest validation, and
the windowed Minecraft validation. The Minecraft task requires a usable macOS
Metal/WindowServer environment; the unit, native, and synthetic tasks remain
the useful evidence on headless systems.
