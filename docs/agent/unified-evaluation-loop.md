# Unified render evaluation and autonomous optimization loop

This is the canonical workflow for `agent/unified-render-eval-performance`. It combines the render-contract framework with the Iris performance harness without collapsing correctness and measurement into one high-overhead run.

## Evaluation model

The repository has one evaluation platform and three suites:

```text
deterministic scenario + binary/environment identity
        |
        +-- conformance: logical pass trace, attachment readback, expectations
        |       -> first divergent pass/resource/producer
        |
        +-- performance: low-overhead counters and GPU/CPU timing
        |       -> ABBA-paired baseline/candidate comparison
        |
        +-- diagnostic: focused producer detail and readback
                -> evidence for one known divergent pass
```

`RenderTraceRecorder` and its generation-aware `ResourceIdentity` are the canonical cross-backend identities. Iris construction-plan traces are oracle evidence; timestamps, native pointers, encoder ordinals, object addresses and shader-pack names are not stable join keys.

Conformance and performance may use the same world, camera and profile, but they must not use the same instrumentation cost. Full producer details and broad GPU readback are disabled during performance trials. When conformance identifies a mismatch, rerun only the first divergent semantic pass in diagnostic mode.

## Required environment

Use an attended Apple Silicon Mac with macOS 26, Xcode/Swift containing the Metal 4 and MetalFX Frame Interpolator SDK surfaces, Java 25, a usable WindowServer session, a fixed display mode and a stable power state. Hosted runners may run headless Java and schema tests, but they cannot replace local Metal 4, readback, presentation or graphical evidence.

Set and record:

```bash
export WORLD="<deterministic world>"
export METALLUM_EVAL_SHADER_PACK="<pack name and version>"
export METALLUM_EVAL_SHADER_PACK_PATH="<pack archive path>"
export METALLUM_EVAL_DISPLAY="<resolution, scale, refresh, HDR state>"
export METALLUM_EVAL_POWER_STATE="<AC/battery and thermal preparation>"
```

A run is not comparable when code/binary hashes, world, shader pack, options, resolution, render distance, display mode or power state differ.

## Entry points

Validate the harness itself:

```bash
bash scripts/agent/verify_unified_eval.sh
```

Run correctness only:

```bash
MODE=conformance WORLD="$WORLD" \
  CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Run the complete correctness-gated ABBA experiment:

```bash
MODE=full WORLD="$WORLD" BLOCKS=4 \
  CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Diagnose the first divergence with expensive evidence:

```bash
MODE=diagnostic WORLD="$WORLD" \
  CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

The runner creates a unique directory below `build/agent-runs/`. It records source and binary identity, commands, exit status, correctness evidence, trial order, structured metrics, paired comparisons and the final decision. Generated evidence is never committed.

## Performance protocol

Use at least four paired blocks. The default order is ABBA:

```text
block 1: baseline -> candidate
block 2: candidate -> baseline
block 3: baseline -> candidate
block 4: candidate -> baseline
```

Aggregate frame samples inside each trial before comparing profiles. Compare baseline and candidate within the same block, then summarize paired deltas across blocks. Do not pool every frame from every run as independent observations.

Structured JSON reports are authoritative. Log regex extraction may be used only to discover missing instrumentation. A metric absent from structured reports is `unavailable`; it is never inferred from prose. FPS is mandatory whenever the client performance run completed.

An optimization is accepted only when:

1. the complete correctness gate passes;
2. at least four paired blocks exist;
3. one target metric improves in at least 75% of paired blocks and its paired median improves;
4. GPU time, CPU time, peak memory and stutter guardrails do not regress beyond the declared limits;
5. the optimization proves it activated through counters or plan/admission evidence.

A positive but unstable result is `inconclusive-noise`, not an accepted optimization.

## Autonomous agent loop

1. Run `doctor.sh`, `verify_unified_eval.sh`, and record the exact starting state.
2. Establish a passing baseline conformance run before changing renderer code.
3. Inspect structured metrics and select one measured bottleneck.
4. Write one falsifiable experiment record:

```text
Observation:
Hypothesis:
Target metric:
Semantic risk:
Affected ownership/ABI boundary:
Fastest falsification test:
Rollback condition:
```

5. Implement the smallest complete change. A planner, unused helper, unproven mixin, or counter without executable integration is incomplete.
6. Add activation evidence, focused tests and fail-closed handling.
7. Run focused tests, then conformance. If it fails, use the first-divergence report and diagnostic mode; do not inspect random screenshots first.
8. Only after correctness passes, run the interleaved performance experiment.
9. Keep the change only when `decision.json` is `accepted-candidate`. Revert rejected experiments; keep incomplete work disabled with a precise blocker.
10. Review Java/FFM/Swift ABI alignment, Metal 3 and Metal 4 paths, resource recreate/close behavior, stale flags and generated files before handoff.

## Optimization admission evidence

Hazard-based transformations must emit machine-readable reasons for both acceptance and rejection. A pass fusion or compute grouping decision must name the semantic passes, generation-aware resources, access modes and RAW/WAR/WAW or explicit-barrier edge involved. Safety cannot be inferred from a pass label.

Resource pruning must prove full-lifetime non-use across terrain, shadow, composite, final and temporal consumers. Argument-table or ABI migration must prove exact slot/layout compatibility. A disabled optimization with a correct blocker is preferable to an enabled approximation.

## Final report

The agent report must distinguish implemented/validated, implemented/unvalidated, rejected/reverted, inconclusive and environment-blocked work. Include the exact commands and artifact paths, first divergent pass when applicable, and a before/after table for every available metric. Do not claim runtime rendering or performance acceptance from compilation alone.
