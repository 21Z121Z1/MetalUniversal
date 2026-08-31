# MetalUniversal agent entrypoint

MetalUniversal is one evidence-driven rendering system, not a collection of unrelated Metal features. The canonical continued-development base is `integration/iris-metal-next`; `master` is the promoted stable tree. `feature/ios-amethyst-runtime` is an isolated Apple-mobile line. Historical experiments are preserved by exact SHA and `research/modernization-backlog`, not by keeping every task branch alive.

The repository control plane is deliberately small. Start from generated current state, expand only into the ownership slice implicated by the task/diff, and prove the result with the cheapest independent evidence that can support the claim.

## 60-second bootstrap

From the repository root:

```bash
python3 scripts/agent/context.py --task "<short task description>"
```

The capsule is expected to tell you:

- exact branch/HEAD/diff base and policy warnings;
- direct component ownership of the current diff;
- downstream impact closure and boundary contracts at risk;
- the local `AGENTS.md` and canonical documents worth reading now;
- an ordered minimum proof ladder for the requested claim;
- any recoverable task checkpoint and its next command.

Then read only the returned component slice, nearest tests/schemas, and concrete evidence needed to resolve remaining questions. Do not preload all of `docs/`, all handoffs/prompts, or historical branches.

For a multi-step task, cache working state without polluting Git history:

```bash
python3 scripts/agent/checkpoint.py init \
  --task "<task>" \
  --hypothesis "<falsifiable hypothesis>" \
  --next-command "<next cheapest action>"
```

Update it as gates complete. `build/agent-state/` is generated/ignored state, never canonical truth.

## Authority order

When sources disagree:

1. shipping source, tests, schemas, generated manifests and exact Git/binary identity;
2. structured runtime evidence produced by that exact identity;
3. canonical design/acceptance documents named by `docs/agent/system-registry.json`;
4. dated handoffs, prompts, retired plans, migration records and historical prose.

Documentation is a map, not proof. A newer historical note does not override executable truth. `system-registry.json` routes authority/ownership/impact/proof; it must not become a second runtime fact database.

## Branch lifecycle policy

Persistent branches are normally limited to:

- `master` — promoted stable tree;
- `integration/iris-metal-next` — continued-development base;
- `feature/ios-amethyst-runtime` — isolated mobile platform line;
- `research/modernization-backlog` — history anchor for retired experimental tips/unlanded research.

Keep the repository at **3–5 total branches after task cleanup** unless the operator explicitly authorizes another long-lived line. A task branch is disposable regardless of prefix.

After the human merge/retire decision:

1. accepted work: land through required gates, then delete the task branch;
2. rejected/superseded/diagnostic work: delete it;
3. uniquely useful unlanded work: record exact SHA, purpose, validation boundary and follow-up in `docs/agent/retired-branch-backlog.md`, keep it reachable from the research anchor, then delete the task branch;
4. do not keep open PRs or `archive/*` branches solely as memory;
5. never delete unrelated branches automatically to satisfy the budget—report pre-existing drift instead.

## Abstraction tower and component IDs

The canonical conceptual tower is defined in `docs/agent/system-model.md`:

```text
operator intent
 -> Minecraft/Iris/Sodium observable semantics
 -> semantic pass/resource identity
 -> immutable render/terrain plan + admission
 -> Java backend execution
 -> Java/FFM ABI
 -> Swift/Metal execution
 -> structured evidence
 -> acceptance/promotion
```

Use these component IDs when orienting work:

- `product.semantics` — Minecraft/Iris/Sodium observable meaning;
- `render.plan` — semantic graph, hazards, liveness and optimization admission;
- `render.execution` — Java Metal command/resource execution;
- `native.abi` — Java FFM ↔ native descriptor/symbol/ownership contract;
- `native.execution` — Swift/Metal implementation below the ABI;
- `terrain.scene` — generation-owned terrain scheduling/visibility/GPU scene/ICB;
- `validation.contract` — backend-neutral correctness oracle/first divergence;
- `evaluation.control` — context/proof routing, evidence, statistics and CI authority;
- `platform.mobile` — isolated iOS/Amethyst concerns sharing backend/native contracts.

A diff directly owning more than two components should normally be split unless the boundary itself is the task. The context tool computes downstream impact separately; downstream impact does not mean every impacted component needs to be edited.

## Canonical semantic identities

`RenderTraceRecorder`, semantic pass IDs and generation-aware `ResourceIdentity` are canonical evaluation identities. Immutable plan/admission records are the preferred bridge from semantics to execution policy.

Construction traces such as `IrisMetalPassTrace` are oracle/diagnostic evidence, not competing cross-backend identities. Never use timestamps, native pointers, encoder ordinals, object addresses, log line numbers or shader-pack names as stable joins.

## Non-negotiable rendering invariants

- Preserve exact Iris/OpenGL-observable semantics; do not substitute approximations.
- Do not add shader-pack-name special cases.
- Never silently fall back. Reject, disable, or emit a precise fail-closed reason.
- Do not enable Iris and MetalFX together without an explicit ownership contract.
- Extend the existing Java/FFM/Swift bridge; never load a shadow native module with duplicate state/types.
- Keep Java descriptors/downcalls and Swift `@_cdecl` signatures aligned in layout, symbol version, ownership and nullability.
- Do not merge/group work across RAW, WAR, WAW, explicit barriers, attachment transitions, clears or unsupported semantic boundaries.
- Hazard/liveness decisions use generation-aware physical resources, not semantic names alone.
- Resource recreate/resize/reload/close/command-buffer retirement paths are correctness, not cleanup trivia.
- Heavy producer trace and broad readback are conformance/diagnostic instruments, not performance instrumentation.
- Structured JSON is acceptance authority. Log regex is not.
- Never weaken tests, thresholds, Metal validation, error handling or fixture expectations to make a candidate pass.
- Never commit shader packs, worlds, binaries, captures, `.minecraft-reference/`, `build/agent-runs/`, `build/agent-evidence/` or `build/agent-state/`.

## One implementation loop

Use the system loop:

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

Operationally:

1. Generate the context capsule; for long work create/update a checkpoint.
2. Inspect direct ownership, impact closure and named boundary contracts before editing.
3. Read the scoped `AGENTS.md`, source, nearest tests and canonical contract for that slice.
4. State one falsifiable hypothesis, target behavior/metric, semantic risk, fastest falsification and rollback condition.
5. Implement the smallest complete change with explicit activation/admission evidence and lifecycle handling.
6. Run the generated proof ladder in increasing cost. Stop when an earlier gate falsifies the change.
7. On semantic failure, diagnose the first divergent pass/resource/producer before broad screenshot hunting.
8. If the validation oracle/analyzer itself changed, independently self-test/fixture-test the judge before using it to approve the candidate.
9. Run physical presentation/device proof only when the claim cannot be established in hosted/headless environments.
10. Run paired performance only after correctness and activation pass and only when performance is claimed.
11. Re-read the final diff for ABI symmetry, resource lifetime, Metal 3/4 parity, mixin application, stale flags and generated files.
12. Distill reusable knowledge into tests/contracts/ADRs/registry/checkers; keep transient progress in generated evidence/checkpoint.

## Proof and evidence truth

`docs/agent/system-registry.json` defines the proof DAG. The intended escalation is:

```text
agent control
 -> repository/static contracts
 -> synthetic semantic oracle
 -> focused GPU/native contracts
 -> independent exact-head hosted CI
 -> Minecraft conformance/E2E
 -> physical presentation/device evidence when required
 -> interleaved paired performance when claimed
```

Do not pay for a later proof when an earlier proof already rejects the candidate, and do not use a later proof to erase an unmet semantic/ABI obligation.

The unified evaluation runner already emits `run-manifest.json`, correctness/admission/trial artifacts and `decision.json`. Reuse those as the evidence graph. A valid decision binds source/binary/scenario identity, activation, correctness, performance when relevant, and artifact locations.

Compilation is not activation. Activation is not correctness. Correctness is not a performance win. A single FPS average is not paired evidence. A screenshot without semantic linkage is diagnostic evidence only.

## Standard commands

Bootstrap/control:

```bash
python3 scripts/agent/context.py --task "<task>"
python3 scripts/agent/checkpoint.py show   # when a checkpoint exists
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

Unified correctness/performance:

```bash
MODE=conformance WORLD="<world>" CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh

MODE=diagnostic WORLD="<world>" CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh

MODE=full WORLD="<world>" BLOCKS=4 CANDIDATE_PROFILE="<profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Focused compatibility:

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
./gradlew --no-daemon renderContractSyntheticValidation
./gradlew --no-daemon renderContractMinecraftDiagnose -Pworld="<world>"
./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation -Pworld="<world>"
```

Do not treat plain `./gradlew build` as a universal headless smoke test; the check graph includes capability-sensitive native/GPU/presentation work.

## Performance acceptance

A performance candidate is accepted only when:

- baseline and candidate correctness gates pass;
- the optimization is proved active by structured counters/plan/admission evidence;
- mandatory structured FPS exists after a completed client run;
- at least four interleaved paired blocks exist;
- a target metric improves in at least 75% of pairs and its paired median improves;
- GPU time, CPU time, memory and stutter guardrails remain within declared limits.

Zero delta is not improvement. Unstable direction is `inconclusive-noise`. Faster output with an unexplained image, attachment, depth, motion, reactive, shadow, water, held-item, sky or post-processing difference is rejected.

## Environment truth

GitHub hosted runners are an independent second environment, not a substitute for attended Apple Silicon or real iOS when a claim depends on visible presentation, hardware-specific behavior, stable GPU performance or device/runtime integration.

Before claiming runtime completion, bind the conclusion to source SHA, binary/native identity, toolchain/environment, scenario/world/shader-pack, activation/admission, correctness, relevant performance data and exact evidence locations. Missing capability is `environment-blocked`, never silently passed.

## Documentation and memory

`docs/README.md` classifies documentation. Prompts are recipes; handoffs/plans are provenance; ADRs explain durable decisions; canonical docs describe current contracts. Do not infer authority from filename/date/detail.

When a task produces reusable knowledge, prefer the narrowest durable representation:

- test/contract for an invariant;
- ADR for a non-obvious long-lived decision;
- registry/checker for routing/impact/proof policy;
- structured artifact for runtime evidence;
- checkpoint for interruptible working state;
- retirement ledger for uniquely useful unlanded SHA.

The goal is to let verified knowledge accumulate without making future agents read proportionally more material.

## Required final report

Distinguish:

- implemented + validated;
- implemented + environment-blocked/unvalidated;
- rejected/reverted;
- inconclusive/noise;
- pre-existing repository-policy drift.

Include starting/ending SHA, direct ownership/boundaries changed, actual commands/gates and exit status, exact-head CI status, correctness/first divergence, activation proof, physical limits, review readiness and residual risk. For performance include before/after, raw/direction-normalized change and paired block count for every available relevant metric; mark unavailable metrics with the exact missing structured source.

## Minecraft 26.2 reference source

When vanilla implementation details matter, materialize the reference instead of guessing:

```bash
bash scripts/minecraft-reference.sh
```

The stable local tree is `.minecraft-reference/26.2/sources/`, generated from Mojang's client JAR and intentionally ignored. See `docs/agent/minecraft-reference.md` for provenance/overrides.
