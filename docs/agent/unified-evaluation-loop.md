# Unified render evaluation and autonomous optimization loop

This is the canonical correctness/performance workflow for the renderer. It is one execution loop inside the broader control plane described by `system-model.md`; task routing and current Git context come from `scripts/agent/context.py`, while acceptance thresholds come from `unified-evaluation-acceptance.json`.

The canonical continued-development base is `integration/iris-metal-next`. Create one bounded task branch from that base. Historical `agent/*`, `codex/*`, dated handoffs and retired feature branches are evidence/provenance only unless current source or the system registry explicitly routes to them.

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
        |     -> ABBA/interleaved paired baseline/candidate comparison
        |
        +-- diagnostic
              focused producer detail + targeted readback
              -> evidence for one already-localized divergence
```

`RenderTraceRecorder`, semantic pass IDs and generation-aware `ResourceIdentity` are the canonical cross-backend identities. Iris construction-plan traces are oracle evidence. Timestamps, native pointers, encoder ordinals, object addresses, shader-pack names and log line numbers are never stable join keys.

Conformance and performance may use the same world/camera/profile, but they must not use the same instrumentation cost. Broad readback and producer traces are disabled during performance trials. When conformance identifies a mismatch, rerun only the first divergent semantic pass in diagnostic mode.

## 2. Bootstrap and routing

Before evaluating a candidate:

```bash
python3 scripts/agent/context.py --task "<candidate or problem>"
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

The context capsule establishes Git orientation and the source/docs/tests that own the task. `doctor.sh` establishes physical-host capability and writes an environment artifact. Static verification proves the harness/control plane itself before expensive client work begins.

Do not start with old handoffs or a branch name copied from a historical plan. If current source and historical prose disagree, source/evidence wins and the canonical document must be fixed.

## 3. Required physical environment

Claims involving visible presentation, real-client shader-pack parity or stable GPU performance require an attended Apple Silicon Mac with the relevant macOS/Xcode/Metal SDK surfaces, Java 25, a usable WindowServer session, fixed display mode and stable power/thermal state.

Hosted runners may prove Java/schema logic, Swift/Metal compilation and selected offscreen/native capability contracts. They do not replace physical presentation or stable-performance evidence.

For comparable client trials record at minimum:

```bash
export WORLD="<deterministic world>"
export METALLUM_EVAL_SHADER_PACK="<pack name and version>"
export METALLUM_EVAL_SHADER_PACK_PATH="<pack archive path>"
export METALLUM_EVAL_DISPLAY="<resolution, scale, refresh, HDR state>"
export METALLUM_EVAL_POWER_STATE="<AC/battery and thermal preparation>"
```

A run is not comparable when source/native hashes, world, shader pack/options, resolution/render distance, display mode or power state differ without an explicit experimental reason.

## 4. Entry points

Correctness only:

```bash
MODE=conformance WORLD="$WORLD" \
  CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Complete correctness-gated paired experiment:

```bash
MODE=full WORLD="$WORLD" BLOCKS=4 \
  CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Targeted first-divergence diagnosis:

```bash
MODE=diagnostic WORLD="$WORLD" \
  CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

The runner creates a unique directory below `build/agent-runs/`. It records source/binary identity, commands, exit status, correctness evidence, trial order, structured metrics, paired comparisons and decision. Generated evidence is never committed.

## 5. Candidate record

Before changing renderer behavior, write one falsifiable record in the task/PR context:

```text
Observation:
Owning component(s):
First suspect abstraction layer:
Hypothesis:
Target behavior/metric:
Semantic risk:
Affected ownership/ABI boundary:
Activation proof:
Fastest falsification test:
Rollback condition:
```

The point is not ceremony. It prevents an agent from drifting across unrelated layers when the first cheap test already identifies the failing assumption.

## 6. Correctness gate

A candidate must preserve every relevant observable semantic contract. Fail closed when the planner/runtime cannot prove safety.

For optimization admission, emit machine-readable reasons for both acceptance and rejection. Hazard-based transforms must name:

- semantic passes;
- generation-aware resources;
- access modes;
- RAW/WAR/WAW or explicit-barrier edges;
- attachment compatibility/liveness evidence where applicable.

Safety cannot be inferred from pass labels or adjacency alone.

Resource pruning must prove full-lifetime non-use across terrain, shadow, composite/final, copies/readback and temporal consumers. Argument-table/ABI migration must prove exact slot/layout compatibility. A correctly disabled optimization is preferable to an approximate enabled path.

On failure, diagnose the first divergent pass/resource/producer. Do not begin by manually comparing arbitrary screenshots.

## 7. Performance protocol

Use at least four paired blocks. The default interleaving is ABBA-like across blocks:

```text
block 1: baseline -> candidate
block 2: candidate -> baseline
block 3: baseline -> candidate
block 4: candidate -> baseline
```

Aggregate frame samples inside each trial before comparing profiles. Compare baseline and candidate within the same block, then summarize paired deltas across blocks. Do not pool every frame from every run as independent observations.

Structured JSON reports are authoritative. Log regex extraction may discover missing instrumentation but is never acceptance evidence. A metric absent from structured reports is `unavailable`; it is never inferred from prose. FPS is mandatory whenever the client performance run completed.

A performance candidate is accepted only when:

1. complete correctness gates pass for baseline and candidate;
2. the candidate has structured activation/admission proof;
3. at least four paired blocks exist;
4. at least one declared target metric improves in at least 75% of paired blocks and its paired median improves;
5. GPU time, CPU time, peak memory and stutter guardrails stay within declared limits.

A positive but unstable result is `inconclusive-noise`, not accepted optimization. Zero delta is not improvement.

## 8. Cheapest-proof-first verification ladder

Pay for evidence progressively:

```text
schema/static
  -> focused unit
    -> integration/build
      -> hosted native/offscreen capability
        -> physical GPU/native
          -> Minecraft E2E
            -> paired performance
```

Stop when a lower gate falsifies the candidate. Do not run expensive performance blocks to debug a compile error, ABI mismatch or semantic divergence.

## 9. Evidence graph

A final decision must be reconstructible from:

```text
source SHA
 + native/binary identity
 + environment identity
 + scenario identity
 + feature/admission activation
 + correctness result
 + paired performance result (when claimed)
 + exact artifact paths
 = decision
```

Missing required edges invalidate the claim. In particular, compilation is not activation; activation is not correctness; correctness is not performance improvement.

## 10. Autonomous agent loop

1. Generate context and record exact starting state.
2. Establish the cheapest passing baseline relevant to the claim.
3. Route to one or two component IDs and inspect source + nearest tests.
4. Write the falsifiable candidate record.
5. Implement the smallest complete change with explicit activation and fail-closed handling.
6. Run focused tests, then conformance.
7. If conformance fails, localize the first divergence and use diagnostic mode only there.
8. After correctness passes, run interleaved performance trials if performance is claimed.
9. Keep the change only when the structured decision supports it; revert correctness/guardrail failures and extend evidence for noise.
10. Self-review ABI symmetry, Metal 3/4 paths, resource recreate/close behavior, mixin application, stale flags and generated files.
11. Distill durable knowledge into tests/contracts/ADRs/checkers; keep transient evidence out of canonical docs.

## 11. Final report

The report must distinguish:

- implemented + validated;
- implemented + environment-blocked/unvalidated;
- rejected/reverted;
- inconclusive/noise;
- pre-existing failures/policy drift.

Include exact starting/ending commits, changed ownership boundaries, commands/exit status, correctness result, first divergence if any, activation proof, Metal validation status, CI status for the latest SHA, environment limits and artifact paths.

For performance, report before, after, raw delta, direction-normalized improvement and paired block count for every available relevant metric. Mark absent metrics `unavailable` with the exact missing structured source.
