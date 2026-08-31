# MetalUniversal agent entrypoint

MetalUniversal is developed as one evidence-driven rendering system, not as a collection of unrelated Metal features. The canonical continued-development base is `integration/iris-metal-next`; `master` is the promoted stable tree. The isolated Apple-mobile line is `feature/ios-amethyst-runtime`. Historical experimental work is preserved by exact commit SHA and `research/modernization-backlog`, not by keeping disposable task branches forever.

The repository has a deliberately small agent control plane. Start from generated/current state, then expand only into the subsystem that owns the task.

## 60-second bootstrap

From the repository root:

```bash
python3 scripts/agent/context.py --task "<short task description>"
```

Then read, in order:

1. the files under **Read now** in that capsule;
2. the source roots of the top routed component(s);
3. the nearest tests and schemas;
4. raw evidence/history only when a concrete unresolved question requires it.

For renderer correctness/performance work, also run:

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

Do not preload every document in `docs/`, every handoff, or every historical branch. `docs/agent/system-registry.json` classifies authority and routes tasks; `docs/agent/system-model.md` explains the abstraction tower and control loop.

## Authority order

When sources disagree, use this order:

1. shipping source, tests, schemas, generated manifests and exact Git/binary identity;
2. structured runtime evidence produced by that exact identity;
3. canonical design/acceptance documents named by `docs/agent/system-registry.json`;
4. dated handoffs, retired plans, migration records and other historical prose.

Documentation is a map, not proof. A recent-looking historical document does not override executable truth.

## Branch lifecycle policy

Branch count is a repository invariant. Unless the operator explicitly authorizes another long-lived line, persistent branches are limited to:

- `master` — stable/promoted tree;
- `integration/iris-metal-next` — canonical continued-development base;
- `feature/ios-amethyst-runtime` — isolated Apple-mobile/Amethyst platform line;
- `research/modernization-backlog` — history-only anchor for retired experimental tips and unlanded research.

Keep the repository at **3–5 total branches** after task cleanup. A per-task branch is disposable regardless of prefix (`feature/`, `fix/`, `codex/`, `agent/`, `ci/`, `perf/`, `chore/`, `tooling/`, `archive/`, `research/`).

End-of-task rules:

1. Accepted work: land through the required validation/merge path, then delete the disposable branch after the human merge decision.
2. Rejected/superseded/diagnostic-only work: delete the branch.
3. Useful unlanded work: record exact SHA, purpose, validation boundary and follow-up in `docs/agent/retired-branch-backlog.md`, keep it reachable from `research/modernization-backlog`, then delete the disposable branch.
4. Close or mark superseded any PR whose head branch is retired.
5. Never create per-task archive branches. Git history, exact SHAs, PRs, tags where appropriate and the single research anchor are the archive.
6. Before the final report, inventory branches. More than five branches means repository policy is already violated or the task is incomplete; do not silently normalize this by deleting unrelated branches without authorization.

## Repository objective

MetalUniversal is a Metal backend for Minecraft Java on Apple platforms. The current canonical line combines:

- Minecraft/Iris/Sodium semantic compatibility;
- backend-neutral render-contract validation;
- Metal 3/4 execution and resource ownership;
- terrain scheduling/GPU-scene work;
- structured runtime telemetry and correctness-gated performance evaluation.

Correctness is mandatory. Any optimization that silently changes shader-pack-visible behavior is a regression.

## System boundaries

Use component IDs from `docs/agent/system-registry.json` when orienting work:

- `product.semantics` — Minecraft/Iris/Sodium observable semantics;
- `render.plan` — semantic graph, hazards, liveness and optimization admission;
- `render.execution` — Java Metal command/resource execution;
- `native.abi` — Java FFM ↔ Swift ABI/ownership;
- `terrain.scene` — terrain scheduling, visibility, ICB/GPU scene and telemetry;
- `validation.contract` — cross-backend identity, correctness oracle and first divergence;
- `evaluation.control` — agent harness, evidence, paired statistics and CI authority;
- `platform.mobile` — isolated iOS/Amethyst platform concerns sharing backend contracts.

Crossing more than two component boundaries in one change should be exceptional. If the task is not explicitly about the interface between them, split it.

## Canonical architecture identities

`RenderTraceRecorder`, semantic pass IDs and generation-aware `ResourceIdentity` are canonical evaluation identities. Immutable plan/admission records are the preferred bridge from semantics to optimization.

`IrisMetalPassTrace` and similar construction traces are oracle/diagnostic evidence, not competing cross-backend identities. Never key correctness or performance joins by timestamps, native pointers, encoder order, object addresses or shader-pack names.

## Standard commands

Harness bootstrap/static verification:

```bash
python3 scripts/agent/context.py --task "<task>"
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

Unified render evaluation:

```bash
MODE=conformance WORLD="<world>" CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh

MODE=full WORLD="<world>" BLOCKS=4 CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh

MODE=diagnostic WORLD="<world>" CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Focused compatibility gates:

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
./gradlew --no-daemon renderContractSyntheticValidation
./gradlew --no-daemon renderContractMinecraftDiagnose -Pworld="<world>"
./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation -Pworld="<world>"
```

Do not treat plain `./gradlew build` as a universal headless smoke test. The repository check graph contains attended WindowServer/hardware-GPU work. Hosted runners without the required Apple runtime surfaces can prove source/schema/build compatibility only and must report physical gates as environment-blocked.

## Non-negotiable rendering invariants

- Do not replace exact Iris/OpenGL-observable semantics with approximations.
- Do not add shader-pack-name special cases.
- Do not silently fall back. Reject, disable, or emit a precise fail-closed reason.
- Do not enable Iris and MetalFX together without an explicit shared ownership contract.
- Extend the existing Java/FFM/Swift bridge; do not load a shadow native module.
- Keep Java descriptors, Swift `@_cdecl` signatures, ownership, nullability and symbol-version behavior aligned.
- Do not merge render/compute work across RAW, WAR, WAW, explicit barriers, attachment transitions, clears or unsupported trace boundaries.
- Hazard/liveness reasoning must use generation-aware resources, not semantic names alone.
- Heavy producer trace and broad readback are conformance/diagnostic tools, not performance instrumentation.
- Structured JSON metrics are authoritative. Log regex is never an acceptance source.
- Do not claim performance without a passing correctness gate, activation proof and interleaved paired trials.
- Do not weaken tests, thresholds, Metal validation or error handling to make a candidate pass.
- Never commit shader packs, worlds, binaries, screenshots, captures, `.minecraft-reference/`, `build/agent-runs/` or `build/agent-evidence/`.

## Agent implementation loop

Follow the system loop defined in `docs/agent/system-model.md`:

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

Operationally:

1. Generate the context capsule and record exact start state.
2. Establish a passing baseline for the cheapest relevant correctness gate.
3. Route to one or two component IDs and inspect source + nearest tests.
4. Write one falsifiable hypothesis, target metric/behavior, semantic risk and rollback condition.
5. Implement the smallest complete change with explicit activation/admission evidence.
6. Run focused checks first; expand only after they pass.
7. On semantic failure, diagnose the first divergent pass/resource/producer rather than broad screenshot hunting.
8. After correctness passes, run the paired performance protocol where performance is claimed.
9. Self-review ABI symmetry, resource lifetime, Metal 3/4 path parity, mixin application, stale flags and generated files.
10. Distill reusable knowledge into tests/contracts/ADRs/checkers instead of accumulating chronological prose.

## Unified performance acceptance

A candidate is accepted only when:

- baseline and candidate correctness gates are complete and pass;
- mandatory structured FPS exists after a completed client run;
- at least four ABBA/interleaved paired blocks are available;
- at least one target metric improves in at least 75% of paired blocks and its paired median improves;
- GPU time, CPU time, peak memory and stutter guardrails remain within declared limits;
- activation is proved by structured counters or plan/admission evidence.

Zero delta is not improvement. Unstable direction is `inconclusive-noise`. A faster candidate with an unexplained image, attachment, depth, motion, reactive, shadow, water, held-item, sky or post-processing difference is rejected.

## Environment and verification truth

Hosted GitHub runners are a second validation environment, not a substitute for attended Apple Silicon when the claim depends on visible presentation, hardware-specific Metal 4 behavior, shader-pack parity or stable GPU performance.

Before claiming runtime completion, record:

- source SHA and native/binary identity;
- environment/toolchain and display/power state when relevant;
- scenario/world/shader-pack identity;
- activation/admission evidence;
- correctness result;
- performance result where claimed;
- exact artifact locations and commands/exit status.

Compilation alone is never rendering acceptance.

## Required final report

Every autonomous implementation/optimization report must distinguish:

- implemented + validated;
- implemented + environment-blocked/unvalidated;
- rejected/reverted;
- inconclusive/noise;
- pre-existing repository-policy drift.

Include starting/ending commits, files and ownership boundaries changed, commands and exit status, correctness evidence, first divergence if any, activation proof, Metal validation status, CI status for the latest SHA, environment limits, review readiness and residual risks.

For performance work report before, after, raw change, direction-normalized improvement and paired block count for every available relevant metric. Mark missing metrics `unavailable` with the absent structured source.

## Minecraft 26.2 reference source

When a task depends on vanilla Minecraft implementation details, materialize the reference tree instead of guessing from logs/signatures:

```bash
bash scripts/minecraft-reference.sh
```

The stable local source root is `.minecraft-reference/26.2/sources/`. It is generated from Mojang's client JAR, is git-ignored and must never be committed or uploaded as an artifact. See `docs/agent/minecraft-reference.md` for provenance and overrides.
