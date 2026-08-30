# MetalUniversal agent map

The canonical continued-development base is `integration/iris-metal-next`. Create one bounded feature branch from it for each task. The repository intentionally keeps only a small set of long-lived branches; disposable task branches must be merged or deleted before the task is considered complete. Historical work is preserved by exact commit SHA and the single `research/modernization-backlog` history anchor, not by accumulating branch refs.

This file is the repository entry map. The canonical autonomous workflow is the unified render evaluation loop; legacy performance scripts remain useful for focused compatibility but are not the final acceptance authority.

## Branch lifecycle policy

Branch count is an explicit repository invariant. Unless the operator explicitly authorizes another long-lived line, the only persistent branches are:

- `master`: stable/promoted tree;
- `integration/iris-metal-next`: canonical continued-development base;
- `feature/ios-amethyst-runtime`: isolated Apple-mobile/Amethyst platform line;
- `research/modernization-backlog`: history-only anchor for retired experimental branch tips and unlanded research.

Keep the repository at **3–5 total branches**. A branch created for one task is disposable even if its name starts with `feature/`, `fix/`, `codex/`, `agent/`, `ci/`, `perf/`, `chore/`, `tooling/`, `archive/`, or `research/`. The prefix does not grant permanence.

Mandatory end-of-task rule for every disposable branch:

1. If the change is accepted, land it into the appropriate long-lived branch through the repository's required validation/merge path, then delete the disposable branch.
2. If the experiment is rejected, superseded, diagnostic-only, or no longer needed, delete the branch instead of leaving it as an archive.
3. If useful work is not ready to land, record the exact commit SHA, purpose, validation boundary and follow-up in `docs/agent/retired-branch-backlog.md`; make sure the commit remains reachable from `research/modernization-backlog`; then delete the disposable branch.
4. Close or mark superseded any PR whose head branch is retired. Do not keep an open PR solely to preserve history.
5. `*-staging-*`, `*-clean-*`, `*-audit-*`, `*-probe-*`, `*-replay-*`, bootstrap CI and one-shot workflow branches are never long-lived. Remove them as part of the same task that created them.
6. Do not create per-task `archive/*` branches. Git history, exact SHAs, PRs, tags when appropriate, and the single research history anchor are the archive.
7. Before the final report, run a branch inventory. If the task leaves more than five branches, it is incomplete unless the operator explicitly approved the additional persistent branch.

Merging into a shared long-lived branch still requires whatever human/CI authorization the task and repository policy require. That does not relax the cleanup rule: a disposable branch may wait only for that explicit decision, and after the decision it must be merged-and-deleted or simply deleted.

## Repository objective

MetalUniversal is a Metal backend for Minecraft Java on Apple platforms. This branch combines Iris-on-Metal optimization with backend-neutral render-contract validation and terrain/runtime telemetry. Correctness is mandatory: an optimization that silently changes any shader-pack-visible result is a regression.

## Read first

For correctness, diagnosis, or performance work, read in this order:

1. `docs/agent/unified-evaluation-loop.md`
2. `docs/agent/unified-evaluation-acceptance.json`
3. `docs/render-contract-validation.md`
4. `docs/iris-audit/advanced-optimization-runtime-handoff.md`
5. `docs/iris-audit/experimental-performance-architecture.md`
6. `docs/agent/prompts/autonomous-unified-render-eval.md`

Documentation is a map, not proof. Verify claims against source, generated manifests and runtime evidence.

## Architecture map

- Iris/Metal renderer and resource lifecycle: `src/main/java/com/metallum/client/metal/render/`
- Render-contract identity, capture, expectations and diagnosis: `src/main/java/com/metallum/client/validation/`
- Terrain scheduling and runtime telemetry: `src/main/java/com/metallum/client/terrain/`
- Java FFM bridge: `src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java`
- Metal wrappers: `src/main/java/com/metallum/client/metal/render/mtl/`
- Swift Metal/MetalFX implementation: `src/main/native/`
- Iris/Sodium/render mixins: `src/main/java/com/metallum/mixin/`
- Validation fixtures: `validation/render-contract/`
- Agent harness: `scripts/agent/`
- Generated run evidence: `build/agent-runs/` (never commit)

`RenderTraceRecorder`, semantic pass IDs and generation-aware `ResourceIdentity` are canonical evaluation identities. `IrisMetalPassTrace` is Iris construction/oracle evidence and must not become a competing cross-backend identity based on timestamps, pointers or encoder order.

## Standard commands

Run from the repository root.

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
MODE=conformance WORLD="<world>" CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
MODE=full WORLD="<world>" BLOCKS=4 CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Focused legacy commands remain available:

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
./gradlew --no-daemon renderContractSyntheticValidation
./gradlew --no-daemon renderContractMinecraftDiagnose -Pworld="<world>"
./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation -Pworld="<world>"
```

Do not use plain `./gradlew build` as a headless smoke test. The repository check graph includes attended WindowServer and hardware-GPU work. Hosted runners without macOS 26 Metal 4 SDK surfaces can validate Java/schema logic only and must report native gates as environment-blocked.

## Non-negotiable invariants

- Do not replace exact Iris/OpenGL-observable semantics with approximations.
- Do not add shader-pack-name special cases.
- Do not silently fall back. Reject, disable, or emit a precise fail-closed reason.
- Do not enable Iris and MetalFX together without an explicit shared ownership contract.
- Extend the existing Java/FFM/Swift bridge; do not load a shadow native module.
- Keep Java descriptors, Swift `@_cdecl` signatures, ownership and nullability aligned.
- Do not merge render/compute work across RAW, WAR, WAW, explicit barriers, attachment transitions, clears or unsupported trace boundaries.
- Hazard and liveness reasoning must use generation-aware resources, not semantic names alone.
- Heavy producer trace and broad readback are conformance/diagnostic tools, not performance instrumentation.
- Structured JSON metrics are authoritative. Log regex is never an acceptance source.
- Do not claim performance without a passing correctness gate and interleaved paired trials.
- Do not weaken tests, thresholds, Metal validation or error handling to make a candidate pass.
- Never commit shader packs, worlds, binaries, screenshots, captures or `build/agent-runs/`.

## Unified acceptance

A candidate is accepted only when:

- baseline and candidate render-contract gates are complete and pass;
- mandatory structured FPS exists after a completed client run;
- at least four ABBA/interleaved paired blocks are available;
- at least one target metric improves in at least 75% of paired blocks and its paired median improves;
- GPU time, CPU time, peak memory and stutter guardrails remain within limits;
- activation is proved by structured counters or admission/plan evidence.

Zero delta is not improvement. Unstable direction is `inconclusive-noise`. A faster candidate with an unexplained image, attachment, depth, motion, reactive, shadow, water, held-item, sky or post-processing difference is rejected.

## Autonomous work policy

Safe and pre-authorized:

- inspect source, logs, reports, captures and git history;
- edit in-scope source, tests, scripts and documentation;
- run non-destructive builds, validation and local Minecraft profiles;
- create local commits on the current feature branch;
- revert the agent's own rejected experiments.

Stop for a human decision before:

- force-pushing, rebasing shared history, merging or releasing;
- changing supported Minecraft/Iris/Sodium versions or public semantic guarantees;
- deleting worlds, shader packs, captures or unrelated work;
- accepting a visual difference as intentional without an existing specification;
- enabling an optimization by default when admission or runtime evidence is incomplete.

## Optimization loop

1. Record environment and exact source/binary identity.
2. Establish a passing baseline conformance run.
3. Select one measured bottleneck and write a falsifiable hypothesis.
4. Implement the smallest complete change with activation evidence and fail-closed admission.
5. Run focused tests and conformance.
6. On failure, diagnose the first divergent semantic pass/producer instead of broad screenshot hunting.
7. After correctness passes, run at least four ABBA paired blocks.
8. Retain only `accepted-candidate`; revert correctness or guardrail failures; extend evidence for noise.
9. Self-review ABI symmetry, Metal 3/4 paths, resource lifecycle, mixin application, stale flags and generated files.
10. Hand off exact commands, artifacts, measurements, rejected experiments and remaining limits.

## Required final report

Every autonomous optimization report must include starting/ending commits, files and ownership boundaries changed, commands and exit status, correctness evidence, first divergence if any, activation proof, Metal validation status, environment limits and review readiness.

Report before, after, raw change, direction-normalized improvement and paired block count for FPS, GPU frame time, CPU render/encode time, native encoder count, attachment store/load bytes, resident render resources, peak memory and stutters. Mark missing metrics `unavailable` with the exact absent structured source. Compilation alone is never rendering or performance acceptance.

## Minecraft 26.2 reference source

When a task depends on vanilla Minecraft implementation details, do not infer them from logs or signatures if the local reference tree is absent. Materialize it once with:

```bash
bash scripts/minecraft-reference.sh
```

The stable source root is `.minecraft-reference/26.2/sources/` for the current project version. It is generated locally from Mojang's client JAR, is intentionally git-ignored, and must never be committed or uploaded as an artifact. See `docs/agent/minecraft-reference.md` for provenance and override details.
