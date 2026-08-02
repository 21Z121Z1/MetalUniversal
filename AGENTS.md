# MetalUniversal agent map

This file is a map, not the complete manual. Read only the documents relevant to the current task.

## Repository objective

MetalUniversal is a Metal backend for Minecraft Java on Apple platforms. The current performance branch adds Iris-on-Metal optimization lanes while preserving Iris-visible shader-pack semantics. Correctness is mandatory; an optimization that silently changes observable rendering is a regression.

## Read first

For Iris performance work, read in this order:

1. `docs/iris-audit/advanced-optimization-runtime-handoff.md`
2. `docs/iris-audit/advanced-optimization-local-agent-handoff.md`
3. `docs/iris-audit/experimental-performance-architecture.md`
4. `docs/agent/iris-performance-loop.md`
5. `docs/agent/iris-performance-acceptance.json`

Inspect the implementation after reading the map. Do not treat documentation claims as proof; verify against code and tests.

## Architecture map

- Java renderer and resource lifecycle: `src/main/java/com/metallum/client/metal/render/`
- Java FFM bridge: `src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java`
- Metal wrappers: `src/main/java/com/metallum/client/metal/render/mtl/`
- Swift Metal/MetalFX implementation: `src/main/native/`
- Iris/Sodium integration mixins: `src/main/java/com/metallum/mixin/iris/`, `.../sodium/`, `.../render/`
- Java tests: `src/test/java/com/metallum/client/metal/render/`
- Native tests: `src/test/native/`
- Validation fixtures: `run/shaderpacks/`, local Minecraft world under `run/saves/`
- Agent harness: `scripts/agent/`
- Run artifacts: `build/agent-runs/` (never commit)

## Standard commands

Run commands from the repository root.

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
WORLD="<world name>" bash scripts/agent/run_iris_perf_cycle.sh
```

Direct Gradle entry points:

```bash
./gradlew --no-daemon clean test
./gradlew --no-daemon buildMacNative build verifyProductionJarIsolation
./gradlew --no-daemon metalMrtBackendIntegrationTest metalComputeBackendIntegrationTest metalIrisTargetsIntegrationTest
./gradlew --no-daemon metalIrisShaderTranslationTest
./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation -Pworld="<world name>"
./gradlew --no-daemon runClientIris -Pworld="<world name>"
```

## Non-negotiable invariants

- Do not replace exact Iris/OpenGL-observable semantics with approximations.
- Do not add shader-pack-name special cases.
- Do not silently fall back. Reject, disable, or log a precise fail-closed reason.
- Do not enable Iris and MetalFX in the same convenience profile unless a shared ownership contract is explicitly implemented and validated.
- Do not load a second native dylib to extend the existing Metal bridge. Extend `MetalNativeBridge` and the existing Swift module together.
- Keep Java FFM descriptors, Swift `@_cdecl` signatures, ownership, and nullability exactly aligned.
- Do not reuse render or compute encoders across a resource hazard, clear boundary, attachment transition, or unsupported trace boundary.
- Do not claim a performance gain without a recorded baseline and repeated comparable runs.
- Do not weaken tests, validation thresholds, Metal API Validation, or error handling to make a change pass.
- Never commit shader packs, worlds, generated binaries, screenshots, captures, or `build/agent-runs/`.

## Autonomous work policy

Safe and pre-authorized:

- inspect repository files, logs, generated reports, git history, and local tool output;
- edit in-scope source, tests, scripts, and documentation;
- run non-destructive builds, tests, validation tasks, and local Minecraft profiles;
- create local commits on the current feature branch;
- revert the agent's own unsuccessful experiments.

Stop and request a human decision before:

- force-pushing, rebasing shared history, merging, publishing a release, or modifying another branch;
- changing public semantic guarantees, supported Iris version, shader-pack compatibility policy, or Minecraft/Sodium/Iris versions;
- deleting user worlds, shader packs, captures, or unrelated work;
- accepting a visual difference as intentional without an existing specification;
- broadening work into MetalFX presentation/frame generation unless required by a demonstrated shared-backend regression.

## Optimization loop

1. Run `doctor.sh` and record environment state.
2. Establish a clean correctness and performance baseline.
3. Select one optimization lane or one measured bottleneck.
4. Form a falsifiable hypothesis and identify the metric and semantic risk.
5. Make the smallest coherent implementation, including instrumentation and tests.
6. Run focused tests, then static verification, then GPU/render validation.
7. Run at least three comparable baseline/candidate samples when claiming performance.
8. Keep the change only when acceptance gates pass; otherwise revert or leave it disabled with a precise blocker.
9. Update the active report with commands, artifacts, measurements, decisions, and remaining uncertainty.
10. Self-review the final diff for ABI drift, stale flags, leaked resources, missing failure paths, and undocumented behavior.

## Required final report

Every autonomous performance task must report:

- starting and ending commit;
- files and architectural boundaries changed;
- exact commands run and their exit status;
- baseline and candidate metrics with sample counts;
- correctness evidence and artifact paths;
- Metal API/shader validation status;
- known unvalidated conditions;
- reverted experiments and why they failed;
- whether the branch is safe to hand to human review.

A build without runtime evidence is not a rendering-performance acceptance. A faster frame with semantic or visual regression is a failed result.