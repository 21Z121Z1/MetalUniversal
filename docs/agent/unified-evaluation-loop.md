# Unified render evaluation and autonomous optimization loop

This is the canonical correctness/performance workflow for renderer work. `system-model.md` defines the overall control plane; `system-registry.json` defines ownership, boundary and proof rules; `context.py` compiles those rules into a task-local view; `unified-evaluation-acceptance.json` defines acceptance thresholds.

The canonical continued-development base is `integration/iris-metal-next`. Historical task branches, prompts and handoffs are provenance/recipes unless current executable truth explicitly points to them.

## 1. Evaluation model

One evaluation platform serves three suites with deliberately different instrumentation cost:

```text
deterministic scenario + source/binary/environment identity
        |
        +-- conformance
        |     semantic pass/resource trace + targeted expectations
        |     -> first divergent semantic pass/resource/producer
        |
        +-- performance
        |     low-overhead counters + GPU/CPU timing
        |     -> interleaved paired baseline/candidate comparison
        |
        +-- diagnostic
              focused producer detail + targeted readback
              -> one already-localized divergence
```

`RenderTraceRecorder`, semantic pass IDs and generation-aware `ResourceIdentity` are canonical cross-backend identities. Broad readback/producer tracing is diagnostic/conformance instrumentation, not performance instrumentation.

## 2. Bootstrap: facts, inference and proof

Run:

```bash
python3 scripts/agent/context.py --task "<candidate or problem>"
```

Read the capsule in this order:

1. exact Git/source identity;
2. **changed-component ownership** when path-derived;
3. **planned route** when it is task-text inference only;
4. downstream impact and boundary contracts;
5. complete **proof obligations**;
6. **minimum execution schedule** after integrated gates are collapsed.

Do not call task-keyword routing a fact. Do not edit every impacted component merely because it appears in the impact closure.

For a multi-step task, create/update the ignored checkpoint:

```bash
python3 scripts/agent/checkpoint.py init \
  --task "<task>" \
  --hypothesis "<falsifiable hypothesis>" \
  --next-command "<next cheapest action>"
```

Every recorded PASS is bound to the source SHA at which it ran. After HEAD changes it is stale until the relevant proof is re-established.

## 3. Proof obligations vs minimum execution schedule

The registry's `depends_on` graph describes **logical evidence obligations**. It is not a command list.

Some executors deliberately produce lower proof artifacts internally. The registry records those relationships with `covers`. The context compiler therefore emits two views:

- `proof_obligations` — everything that must be established for the claim;
- `execution_plan` — the smallest command schedule that establishes those obligations without re-running integrated gates.

`repo.static` remains an explicit cheap preflight even if a later executor covers it, because early falsification saves expensive client/GPU work.

Typical renderer-performance example:

```text
logical obligations:
agent.control
repo.static
render.synthetic
render.gpu
minecraft.conformance
hosted.exact-head
minecraft.e2e
performance.paired

minimum execution schedule:
repo.static
performance.paired
hosted.exact-head
minecraft.e2e
```

The paired runner supplies its own local correctness prerequisites; exact-head CI and production-client E2E remain independent evidence and are not erased by that integration.

Run the generated execution schedule in increasing cost. Stop when an earlier gate falsifies the candidate.

## 4. Environment/capability truth

Hosted runners can prove Java/schema logic, Swift/Metal compilation and selected native/offscreen contracts. They cannot substitute for attended Apple Silicon or a real iOS runtime when the requested claim depends on visible presentation, device integration, hardware-specific behavior or stable GPU performance.

For comparable physical performance trials record at minimum:

```bash
export WORLD="<deterministic world>"
export METALLUM_EVAL_SHADER_PACK="<pack name and version>"
export METALLUM_EVAL_SHADER_PACK_PATH="<pack archive path>"
export METALLUM_EVAL_DISPLAY="<resolution, scale, refresh, HDR state>"
export METALLUM_EVAL_POWER_STATE="<AC/battery and thermal preparation>"
```

Source/native hashes, world, shader-pack/options, display mode and power state are part of trial identity.

## 5. Unified entry points

Correctness/conformance:

```bash
MODE=conformance WORLD="$WORLD" \
  CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Focused diagnosis:

```bash
MODE=diagnostic WORLD="$WORLD" \
  CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Correctness-gated paired performance:

```bash
MODE=full WORLD="$WORLD" BLOCKS=4 \
  CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

The runner emits a unique `build/agent-runs/<run>/` directory. `run-manifest.json` binds source/binary/environment/scenario identity; correctness/admission/trial artifacts contain source evidence; `decision.json` records the resulting decision. Reuse these artifacts rather than copying metrics into a parallel truth store.

### Vanilla 26.3 terrain observation

Use an isolated copy of the world with `-Pmetallum.noOptionalMods=true` for
vanilla-only client checks. `metallum.terrain.vanillaWorkEvents=true` enables
bounded work events; validate each `terrain-work-epoch-*.json` with
`scripts/agent/verify_terrain_work_events.py`. Supply a unique
`metallum.renderContract.runId` and output directory per trial. Native validation
defaults the run ID to the output directory name when no explicit ID is given.

The independent, default-off `metallum.terrain.vanillaUploadPressure=true` switch
adds `terrain-upload-pressure.json` beside the existing client reports. Its
fixed-space counters observe staging attempts (normal success, normal failure,
exception), requested bytes including retries, staging call duration, copy-lock
acquisition duration, and upload call duration. It does not change vanilla
allocation, retries, publication, scheduling or completion. Hooks require the
Metal backend and absence of Sodium/Iris; nonzero counters establish execution.

This diagnostic covers the process observation, including warmup. Requested
bytes can include partial allocations and repeated retries and are not uploaded
bytes. Lock duration includes uncontended acquisition overhead. CPU call
durations may overlap and are not GPU durations. GPU completion, in-flight bytes,
and time inside `Thread.onSpinWait` remain explicitly unavailable. The report is
not performance-eligible; do not add its totals to frame timing or use it for
paired acceptance. Measure instrumentation overhead separately before treating
an instrumented workload as a performance baseline.

### Vanilla terrain generation diagnostics

For opt-in vanilla T1 diagnostics, `metallum.terrain.vanillaAdmission=true`
enables the bounded queue and emits `terrain-admission.json`.
`metallum.terrain.vanillaGenerationGuard=true` emits `terrain-generation.json`;
its `mixinHooksObserved` field distinguishes executed hooks from a requested
flag. Add `metallum.terrain.vanillaGenerationEvents=true` to retain bounded
generation/publication decision events. `vanillaGenerationEventCapacity`
(under the same `metallum.terrain` prefix) defaults to 65,536 and accepts
1–262,144. Overflow records dropped events and makes the decision trace
incomplete; it never changes the guard's result.

Only invalidations of tracked section metadata produce `SECTION_INVALIDATED`
events. Unknown sections have no current token to invalidate and increment
`snapshot.untrackedInvalidations` without allocating metadata. New registrations
receive globally increasing revisions, so an old worker cannot match a section
recreated after eviction. The aggregate includes initialization notifications;
it is not a count of omitted decision events.

Run `python3 scripts/agent/verify_terrain_generation.py <report> --require-active`
to require observed guard activation, publication decisions, and complete
decision evidence. Without `--require-active`, an explicitly unobserved or
fail-open report may be a valid diagnostic; it is not proof of guarded execution.
These reports do not establish the full task/mesh ownership chain, atomicity
between decision and vanilla publication, lifecycle coverage, or performance
acceptance. Keep both optimizations default-off until their remaining gates pass.

The publication wrapper holds the guard monitor across the decision and vanilla's
synchronous mesh exchange. Invalidation uses the same monitor; a concurrent
invalidation cannot slip between admission and publication. Unit tests exercise
that ordering and exception propagation; real-client reports establish hook
execution only.

### Native fullscreen measurement windows

The native fullscreen report identifies its measurement window with an explicit
window ID, inclusive/exclusive frame bounds, and submit bounds. Each render CPU
sample closes at `renderFrame` return; its frame interval closes at the next
`renderFrame` entry. Warmup is excluded from both. The initial GPU drain and final
completion drain happen outside measured intervals; normal frames keep their
existing asynchronous submission behavior.

`gpuSubmissionSamples` preserves submission and frame identity captured when the
command buffer is created. GPU frame service time is the sum of command-buffer
durations for that frame, not end-to-end GPU latency or CPU time. Missing frames,
duplicate submissions, failed submissions, or recorder truncation invalidate the
GPU window. Positive-window samples have an independent 65,536-record buffer;
ordinary diagnostics keep their 16,384-record buffer and cannot trim a measured
window. Matching sample counts alone are insufficient; normalization checks
the explicit evidence. A CPU duration is render-loop wall time and can include
waits; it is not thread CPU utilization.

Native encoder timing records carry their captured window/frame IDs through GPU
completion. Their observed counts still do not prove complete coverage: unsupported
counter sampling, timestamp failures, bounded buffers, and Metal 4 encoder paths
can omit records. Encoder counts therefore use a separate `nativeEncoderLedger`,
bound to the command buffer or Metal 4 lease before any encoder is created.
Each row carries window/frame/submit identity, backend, attempted/created/ended
counts, physical render/blit/compute kinds, failures and unsupported encodes.
Metal 4 copies implemented with compute encoders count as compute. Counting an
encoder object does not count its draw, dispatch or copy operations.

The bounded ledger seals each submission before commit and is copied after the
measurement drain. Its native ABI uses eight signed 64-bit metadata words and
thirteen signed 64-bit words per row; `NativeEncoderCounts` documents the layout.
Collection starts only for an explicit measurement window and defaults off.
`reset(0)` disables and clears; capacities 1..65,536 enable and clear. Invalid
capacities fail without changing state. Submission identities are unique within
a window even if the physical command-buffer object is reused.
Capacity overflow invalidates evidence without changing rendering or submission.

The independent normalizer requires every measured GPU submission and frame to
match exactly, no drops/invalid/active entries, and attempted=created=ended=sum of
physical kinds. It recomputes per-frame counts and their nearest-rank median;
averages cannot substitute for that median. `nativeEncoderIdentityComplete` can
be true only after these lifecycle and identity checks. Timing samples may remain
partial even when count coverage passes.

Scope is `main-queue-native-encoders`, admitted only for the native MetalFX-off
lane with structured proof that auxiliary work is eliminated. Opaque MetalFX
internals, separate presentation queues, incomplete ledger rows, and old reports
without the ledger remain unavailable. This metric does not claim process-wide
GPU encoder coverage or resource-lifetime correctness. Run
`nativeEncoderCountsMetal3Test` and `nativeEncoderCountsMetal4Test` for independent
GPU lanes; ordinary JVM tests do not run these physical-device suites.

## 6. Candidate record

Before changing renderer behavior, record one falsifiable candidate:

```text
Observation:
Ownership fact / routing inference:
Impacted boundary/contract:
First suspect abstraction layer:
Hypothesis:
Target behavior/metric:
Semantic risk:
Activation proof:
Fastest falsification test:
Rollback condition:
```

One task should test one coherent hypothesis. Do not mix unrelated caching, fusion, allocation and submission ideas into one experiment.

## 7. Correctness and admission

Correctness is mandatory and optimization admission is fail-closed.

Hazard/liveness transforms must use stable semantic passes, generation-aware resources, explicit access modes, RAW/WAR/WAW/barrier edges and attachment/lifetime evidence. Safety cannot be inferred from pass labels or adjacency.

Resource pruning requires full-lifetime non-use. ABI/argument-table changes require exact layout, symbol, ownership and nullability compatibility. Unsupported transforms should remain disabled with a machine-readable rejection reason rather than approximate behavior.

If `validation.contract`, an analyzer or benchmark judge changes, first prove the changed judge through independent fixtures/self-tests before it can approve the same renderer candidate.

On semantic failure, diagnose the first divergent pass/resource/producer before broad screenshot or capture comparison.

## 8. Performance protocol

Use at least four interleaved paired blocks:

```text
block 1: baseline -> candidate
block 2: candidate -> baseline
block 3: baseline -> candidate
block 4: candidate -> baseline
```

Aggregate within each trial, compare baseline/candidate inside each block, then summarize paired deltas. Do not pool all frame samples as independent observations.

Structured JSON is acceptance authority. Log regex may reveal missing instrumentation but cannot approve a candidate. A missing structured metric is `unavailable`. FPS is mandatory whenever the performance client completed.

A performance candidate is accepted only when:

1. relevant correctness gates pass;
2. activation/admission is structured and explicit;
3. at least four paired blocks exist;
4. a declared target improves in at least 75% of pairs and its paired median improves;
5. GPU time, CPU time, memory and stutter guardrails remain within declared limits.

Positive but unstable results are `inconclusive-noise`. Zero delta is not improvement.

## 9. Evidence graph

A final decision must reconstruct as:

```text
source SHA
 + native/binary identity
 + environment/scenario identity
 + activation/admission
 + correctness
 + paired performance when claimed
 + exact artifact paths
 = decision
```

Compilation is not activation. Activation is not correctness. Correctness is not performance improvement. Screenshot similarity without semantic linkage is diagnostic evidence only. PASS from another SHA is stale evidence.

## 10. Autonomous loop

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

Operationally:

1. Compile task context and exact starting state.
2. Separate ownership facts from routing inference; inspect impact/boundaries.
3. Read only the routed source/tests/contracts.
4. Write one falsifiable candidate record.
5. Implement the narrowest complete change with fail-closed activation/lifecycle handling.
6. Execute the generated minimum schedule and bind each result to its exact SHA.
7. Localize the first semantic divergence before broadening instrumentation.
8. Run physical/device proof only when the claim requires it.
9. Run paired performance only after correctness/activation and only for performance claims.
10. Self-review the complete diff.
11. Distill reusable lessons into tests/contracts/ADR/registry + routing fixture/checker; leave transient state in generated evidence/checkpoint.

## 11. Final report

Distinguish validated, environment-blocked/unvalidated, rejected/reverted, inconclusive/noise and pre-existing policy drift.

Include exact start/end SHA, changed ownership/boundaries, actual executed proof profiles and exit status, latest exact-head CI, correctness/first divergence, activation, required physical limits, artifact paths, review readiness and residual risk.

For performance report before/after, raw delta, direction-normalized improvement and paired block count for every available relevant metric. Mark missing metrics `unavailable` with the exact absent structured source.

### Process resident-memory sampling

Optional raw native attachment facts are documented in
[`native-attachment-actions.md`](native-attachment-actions.md). Their opt-in
capture, independent integrity checker and conservative estimator can supply
logical load/store/resolve bytes for supported descriptors. These are not physical
bandwidth measurements; capture observer overhead remains a performance-admission
limit. Unsupported or incomplete facts reject the estimate, and absent opt-in
facts leave the metric unavailable.

The optional [module-owned allocation snapshot](resource-allocation-snapshot.md)
observes weak-live native resources after final GPU drain. Its allocated-size
total is deliberately separate from the still-unavailable complete renderer
residency/peak metric. It requires a process-start environment opt-in and an
independent row-sum/identity checker.

`processMemory` samples the current client process with public Mach
`task_info(TASK_VM_INFO)` at each measured frame's beginning and end, plus once
following the final GPU drain. The expected trace has exactly `2 * frames + 1`
rows, with explicit window/frame/phase/sequence identity and monotonic probe
intervals. Warmup observations cannot enter this trace. Capacity, failure, and
invalid-event counters fail closed; a failed probe does not interrupt rendering.
Collection only runs in the explicit performance validation timeline. One
`probeWarmup` query runs before frame timing is reanchored, so first-use bridge
initialization is excluded from steady frame measurements. Its duration and
values are reported separately and cannot enter the window maximum or probe
sum. A failed warmup query does not substitute for any required window sample.

`peak_resident_memory_bytes` is the **sampled maximum of current process RSS**
(`resident_size`) under `frame-boundaries-and-final-drain`; transient peaks between
samples may be missed. It includes the JVM and native allocations in the client
process and is neither Java heap usage nor GPU/Metal resource residency. The
process-lifetime `resident_size_peak` and `phys_footprint` are separate diagnostic
fields and cannot substitute for window RSS. No operating-system lifetime peak
is reset or represented as a measurement-window peak.

The independent normalizer recomputes maxima and probe-time totals from every
raw sample and validates the full frame sequence. `totalProbeNanos` and
`maxProbeNanos` describe the observed synchronous query duration, including FFM;
other recorder/allocation overhead is not isolated by these timings. Before
accepting performance improvements, use identical sampling for both lanes and
measure observer overhead separately. This instrumentation alone does not
establish a performance gain or close the other resource metrics.

Run `nativeProcessMemoryTest` for a current-process production-ABI query without
a Minecraft window. `ProcessMemoryMeasurementTest` covers window boundaries,
truncation, failures, malformed ABI records, and separation from lifetime peaks;
its generated fixture is independently checked by the Python normalizer.
