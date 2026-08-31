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
