# Autonomous Iris-on-Metal performance loop

This document defines the repository-native workflow for a local coding agent. `AGENTS.md` is the entry map; this file is the execution protocol.

## Objective

Improve rendering efficiency on the Iris semantic Metal path while preserving every observable shader-pack contract. The agent must optimize measured bottlenecks, not maximize code changes or enable every experimental lane.

## Initial state

Expected branch:

```text
feature/iris-metal-performance
```

The branch contains several unvalidated optimization lanes. Treat them as hypotheses until local build, GPU and Minecraft evidence exists.

## Phase 0: establish control of the environment

Run:

```bash
bash scripts/agent/doctor.sh
```

Resolve environment failures before modifying renderer code. A missing Java 25 toolchain, stale native dylib, absent shader-pack fixture, locked console, wrong world, or wrong branch is an environment problem, not a renderer bug.

Record:

- exact HEAD;
- dirty working-tree state;
- macOS, Xcode, Swift and Java versions;
- machine and display configuration;
- selected world, shader pack and Minecraft settings.

Do not delete or overwrite a dirty user worktree. Preserve unrelated changes.

## Phase 1: baseline correctness

Run:

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
```

If the branch does not compile or an existing test fails, fix that before performance tuning. Do not weaken a test or turn off validation to make the baseline green.

Classify failures as one of:

- Java compile or Mixin signature;
- Java/FFM/Swift ABI mismatch;
- Swift compile/link;
- shader translation or MSL compile;
- resource lifetime or ownership;
- Metal validation/hazard;
- visual/semantic mismatch;
- harness/environment failure.

Write the classification and evidence into the current run's `decision.md` or a temporary execution note before coding.

## Phase 2: baseline measurement

Run one profile first:

```bash
WORLD="<world name>" \
PROFILES="baseline" \
REPETITIONS=3 \
  bash scripts/agent/run_iris_perf_cycle.sh
```

Use a deterministic world, camera, resolution, shader pack, options, render distance, power state and display mode. Do not compare runs taken under different configurations.

The wrapper stores commands, logs, plan dumps and generated validation artifacts under `build/agent-runs/`.

Inspect source reports, not only regex-extracted values in `summary.json`. If a required metric is absent or ambiguous, add structured instrumentation before optimizing.

## Phase 3: select one hypothesis

Choose one lane or one measured bottleneck. Examples:

- native render encoder count is high because compatible Iris passes end unnecessarily;
- compute encoder churn dominates CPU encode time;
- unused depth histories allocate and copy full-resolution textures;
- repeated binding mutations are encoded despite stable ABI and handles;
- attachment stores are dead after the final consumer;
- Java-to-native call count dominates a known binding path.

For the selected hypothesis, write:

```text
Observation:
Hypothesis:
Expected metric:
Semantic risk:
Files likely involved:
Fastest falsification test:
Rollback condition:
```

Do not implement multiple independent ideas in one experiment. A coherent cross-language ABI change is one experiment; unrelated caching and shader fusion are not.

## Phase 4: implement the smallest complete change

A complete performance change includes:

- executable integration, not only a planner or unused helper;
- fail-closed handling for unsupported or ambiguous cases;
- focused unit/integration coverage;
- structured counters or reports sufficient to prove activation;
- documentation of the flag and ownership boundary;
- no duplicated native module or shadow global state.

For FFM changes, update in one change:

1. Java `MethodHandle` field;
2. symbol lookup and optional/required policy;
3. `FunctionDescriptor`;
4. Java wrapper;
5. MTL wrapper if applicable;
6. Swift `@_cdecl` function;
7. Metal 3 path;
8. Metal 4 path;
9. native tests and stale-dylib failure behavior.

For encoder fusion/grouping, prove resource independence and preserve logical traces. Never infer safety from pass names.

## Phase 5: focused validation

Run the smallest tests that can falsify the change, then:

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
```

Use `focused` mode while iterating:

```bash
TASKS="IrisMetalArgumentSnapshotTest metalComputeBackendIntegrationTest" \
  bash scripts/agent/verify.sh focused
```

After a Mixin change, explicitly inspect startup logs for target resolution and handler-signature errors. `require = 0` is not permission to ship a non-applied optimization; prove the injection executes with counters or a focused test.

## Phase 6: comparable performance experiment

Run only baseline and the selected candidate first:

```bash
WORLD="<world name>" \
PROFILES="baseline,compute-grouping" \
REPETITIONS=3 \
  bash scripts/agent/run_iris_perf_cycle.sh
```

Replace the candidate profile as appropriate. Compare median and p95, inspect run-to-run variation, and verify the lane activated.

A candidate is inconclusive when the delta is within noise. Increase sample duration/repetitions or add a more direct metric; do not call noise a win.

## Phase 7: correctness and visual acceptance

The candidate must not introduce unexplained differences in:

- held items and entities;
- water reflection/refraction;
- shadows and shadow depth;
- sky/terrain boundary and LOD stability;
- transparency and cutout handling;
- depthtex0/1/2;
- composite/final color output;
- motion/reactive resources;
- shader-pack fallback/rejection behavior.

Use existing render-contract/readback tooling. Add a deterministic capture when the current harness cannot observe the affected output. A screenshot-only subjective check is not sufficient for a resource/attachment semantic claim.

## Phase 8: decision

Use one state from `iris-performance-acceptance.json`:

- `accepted`;
- `accepted-disabled-by-default`;
- `rejected-reverted`;
- `blocked-environment`;
- `blocked-semantic-ambiguity`;
- `inconclusive-noise`.

Keep an optimization enabled by default only when its admission proof is complete, its fallback is fail-closed, and repeated validation passes. Otherwise retain it behind an explicit flag or revert it.

## Phase 9: self-review and handoff

Before finishing:

- inspect `git diff --check`;
- inspect the full diff, not only changed snippets;
- search for stale property names and dead code;
- verify resource close/recreate paths;
- verify every Java FFM descriptor against Swift;
- check both Metal 3 and Metal 4 paths;
- ensure generated binaries/artifacts are untracked;
- rerun the relevant acceptance gates after the final edit;
- update `decision.md` and the implementation documentation.

The final report must distinguish:

- implemented and validated;
- implemented but not runtime-validated;
- measured but inconclusive;
- intentionally deferred;
- environment-blocked.

Do not open, merge or publish a PR unless the user explicitly authorizes it.
