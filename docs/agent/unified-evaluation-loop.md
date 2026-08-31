# Unified render evaluation and autonomous optimization loop

This is the canonical correctness/performance workflow for renderer work. It is one execution loop inside the broader control plane in `system-model.md`. Task/diff routing, impact closure and proof planning come from `scripts/agent/context.py`; acceptance thresholds come from `unified-evaluation-acceptance.json`; proof-profile ordering/requirements come from `system-registry.json`.

The canonical continued-development base is `integration/iris-metal-next`. Create one bounded task branch from that base. Historical `agent/*`, `codex/*`, prompts, dated handoffs and retired feature branches are evidence/provenance only unless current source or the registry explicitly routes to them.

## 1. Evaluation model

The repository has one evaluation platform and three suites with deliberately different instrumentation cost:

```text
deterministic scenario + source/binary/environment identity
        |
        +-- conformance
        |     logical pass trace + attachment/readback expectations
        |     -> first divergent semantic pass/resource/producer
        |
        +-- performance
        |     low-overhead counters + GPU/CPU timing
        |     -> interleaved paired baseline/candidate comparison
        |
        +-- diagnostic
              focused producer detail + targeted readback
              -> evidence for one already-localized divergence
```

`RenderTraceRecorder`, semantic pass IDs and generation-aware `ResourceIdentity` are canonical cross-backend identities. Iris construction-plan traces are oracle evidence. Timestamps, native pointers, encoder ordinals, object addresses, shader-pack names and log line numbers are never stable join keys.

Conformance and performance may share world/camera/profile, but not instrumentation cost. Broad readback/producer traces are disabled during performance trials. When conformance identifies a mismatch, rerun only the first divergent semantic pass in diagnostic mode.

## 2. Bootstrap, impact and proof planning

Before changing/evaluating renderer behavior:

```bash
python3 scripts/agent/context.py --task "<candidate or problem>"
```

Inspect **direct ownership**, **impact closure**, **boundary risk** and **minimum proof ladder** before reading broadly or editing. The diff may directly own one component while invalidating assumptions in several downstream components; downstream impact does not mean those components should all be edited.

For renderer/runtime work, then run the early applicable gates returned by the capsule. Common prerequisites are:

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

`doctor.sh` characterizes a physical/runtime-capable host; it is not required merely to edit control-plane prose or headless Java logic. Static verification proves the harness/control plane before expensive client work begins.

For multi-step work, create/update the ignored task checkpoint so an interrupted agent can recover hypothesis, exact-SHA proof state, blockers and next action:

```bash
python3 scripts/agent/checkpoint.py init \
  --task "<task>" \
  --hypothesis "<hypothesis>" \
  --next-command "<next cheapest proof/action>"
```

Do not start from an old branch/handoff copied from a historical plan. If current executable truth and historical prose disagree, executable truth wins and any canonical document must be corrected.

## 3. Environment and capability truth

Claims involving visible presentation, real-client shader-pack parity or stable GPU performance require an attended Apple Silicon Mac with the relevant macOS/Xcode/Metal SDK surfaces, Java 25, usable WindowServer, fixed display mode and stable power/thermal state.

Hosted runners can prove Java/schema logic, Swift/Metal compilation and selected offscreen/native capability contracts. They do not replace physical presentation/device evidence when the claim actually depends on it.

For comparable client trials record at minimum:

```bash
export WORLD="<deterministic world>"
export METALLUM_EVAL_SHADER_PACK="<pack name and version>"
export METALLUM_EVAL_SHADER_PACK_PATH="<pack archive path>"
export METALLUM_EVAL_DISPLAY="<resolution, scale, refresh, HDR state>"
export METALLUM_EVAL_POWER_STATE="<AC/battery and thermal preparation>"
```

A run is not comparable when source/native hashes, world, shader pack/options, resolution/render distance, display mode or power state differ without an explicit experimental reason.

## 4. Entry points and evidence

Correctness only:

```bash
MODE=conformance WORLD="$WORLD" \
  CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Targeted first-divergence diagnosis:

```bash
MODE=diagnostic WORLD="$WORLD" \
  CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Complete correctness-gated paired experiment:

```bash
MODE=full WORLD="$WORLD" BLOCKS=4 \
  CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

The runner creates a unique `build/agent-runs/<run>/` directory. `run-manifest.json` binds source/binary/environment/scenario identity; correctness/admission/trial artifacts record source evidence; `decision.json` records the resulting decision. Reuse/link these artifacts rather than copying metrics into another report database.

A checkpoint proof result is reusable only for the exact `source_sha` it records. After HEAD changes, old PASS records are stale until the relevant gate is rerun for the new SHA.

## 5. Candidate record

Before changing renderer behavior, record one falsifiable candidate:

```text
Observation:
Owning component(s):
Impacted boundary/contract:
First suspect abstraction layer:
Hypothesis:
Target behavior/metric:
Semantic risk:
Activation proof:
Fastest falsification test:
Rollback condition:
```

This prevents drifting across unrelated layers when one cheap test already identifies the wrong assumption.

## 6. Correctness and admission

A candidate must preserve every relevant observable semantic contract. Fail closed when planner/runtime cannot prove safety.

Hazard-based transforms must emit machine-readable admission/rejection reasons naming:

- semantic passes;
- generation-aware resources;
- access modes;
- RAW/WAR/WAW or explicit-barrier edges;
- attachment compatibility/liveness evidence where applicable.

Safety cannot be inferred from pass labels or adjacency. Resource pruning requires full-lifetime non-use across terrain, shadow, composite/final, copy/readback and temporal consumers. Argument-table/ABI migration requires exact slot/layout/symbol/ownership compatibility. A correctly disabled optimization is preferable to an approximate enabled path.

When `validation.contract` or an analyzer is itself modified, first prove the changed judge through independent fixtures/self-tests. Do not let candidate and oracle share one unverified failure mode.

On failure, diagnose the first divergent pass/resource/producer before broad screenshot comparison.

## 7. Canonical proof ladder

The machine authority is the proof DAG in `system-registry.json`; `context.py` computes the closure needed for the current direct components, boundaries and claim. The general escalation is:

```text
agent-control consistency
  -> repository/static contracts
    -> synthetic semantic oracle
      -> focused GPU/native contracts where capability exists
        -> independent exact-HEAD hosted CI
          -> Minecraft conformance / production E2E
            -> physical presentation/device proof when the claim requires it
              -> interleaved paired performance only when performance is claimed
```

Run in increasing cost. Stop when an earlier gate falsifies the candidate. Do not pay for physical/performance work for a control-plane-only diff, and do not use a later gate to erase an unmet semantic or ABI obligation.

The physical step is **claim-driven**, not mechanically mandatory before every Minecraft E2E: hosted/real-client E2E may establish startup/runtime contracts, while attended presentation/physical Metal behavior remains mandatory only for conclusions that those environments cannot prove.

## 8. Performance protocol

Use at least four interleaved paired blocks. Default order alternates direction across blocks:

```text
block 1: baseline -> candidate
block 2: candidate -> baseline
block 3: baseline -> candidate
block 4: candidate -> baseline
```

Aggregate samples inside each trial before comparing profiles. Compare baseline/candidate within the same block, then summarize paired deltas. Do not pool all frames as independent observations.

Structured JSON reports are authoritative. Log regex may discover missing instrumentation but is never acceptance evidence. An absent structured metric is `unavailable`; do not infer it from prose. FPS is mandatory whenever the client performance run completes.

A performance candidate is accepted only when:

1. complete relevant correctness gates pass;
2. candidate activation/admission is structured and explicit;
3. at least four paired blocks exist;
4. a declared target improves in at least 75% of pairs and its paired median improves;
5. GPU time, CPU time, peak memory and stutter guardrails remain within declared limits.

Positive but unstable results are `inconclusive-noise`. Zero delta is not improvement.

## 9. Evidence graph

A final decision must reconstruct from:

```text
source SHA
 + native/binary identity
 + environment identity
 + scenario identity
 + feature/admission activation
 + correctness result
 + paired performance result when claimed
 + exact artifact paths
 = decision
```

Missing required edges invalidate the claim. Compilation is not activation; activation is not correctness; correctness is not performance improvement; screenshot similarity without semantic linkage is diagnostic evidence only.

## 10. Autonomous agent loop

1. Generate context; create/check the checkpoint for multi-step work.
2. Establish direct ownership, impact closure, boundary contracts and exact starting SHA.
3. Read only scoped source/tests/canonical docs returned by the capsule.
4. Write the falsifiable candidate record.
5. Implement the smallest complete change with activation and fail-closed handling.
6. Execute the generated proof ladder in order, recording each result against its exact SHA.
7. If conformance fails, localize the first divergence and use diagnostic mode only there.
8. Run physical presentation/device evidence only for claims that require it.
9. Run paired performance only after correctness/activation and only when performance is claimed.
10. Keep/revert/extend evidence according to structured decision; do not call noise a win.
11. Self-review ABI symmetry, Metal 3/4 paths, resource recreate/close behavior, mixin application, stale flags and generated files.
12. Distill durable knowledge into tests/contracts/ADRs/registry/checkers; leave transient task/evidence state out of canonical docs.

## 11. Final report

Distinguish:

- implemented + validated;
- implemented + environment-blocked/unvalidated;
- rejected/reverted;
- inconclusive/noise;
- pre-existing policy drift.

Include exact start/end SHA, direct ownership/boundaries changed, commands/exit status, correctness/first divergence, activation, latest exact-head CI, required physical evidence and limits, artifact paths, review readiness and residual risk.

For performance report before/after, raw delta, direction-normalized improvement and paired block count for every available relevant metric. Mark missing metrics `unavailable` with the exact absent structured source.
