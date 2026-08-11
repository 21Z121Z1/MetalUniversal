# Autonomous unified render evaluation and optimization task

Use `integration/iris-metal-next` as the canonical base. Create or use one bounded feature branch from that base for the assigned change. Do not continue development from superseded `agent/*`, `codex/*`, archive, test-bootstrap, or pre-Iris feature branches unless the operator explicitly requests historical extraction. Read `AGENTS.md`, `docs/agent/unified-evaluation-loop.md`, `docs/agent/unified-evaluation-acceptance.json`, and `docs/render-contract-validation.md` before modifying code.

## Objective

Improve one measured Iris-on-Metal rendering bottleneck while preserving every observable shader-pack contract. Use the render-contract framework to establish and localize correctness, and the structured ABBA performance harness to establish efficiency. A faster result with an unexplained semantic or graphical difference is a failed result.

## Operating constraints

- Do not merge, publish, force-push, rebase shared history, change supported dependency versions, or delete user data.
- Preserve unrelated dirty work. Commit only coherent changes on the current feature branch.
- Do not weaken validation, thresholds, Metal API Validation, failure handling, or fixture expectations.
- Do not add shader-pack-name special cases or silent approximations.
- Do not infer hazard safety from pass names. Use generation-aware resources and explicit access edges.
- Do not use screenshot similarity as proof for depth, motion, reactive, shadow, HDR or temporal attachments.
- Do not claim performance from compilation, a single run, pooled frame samples, or log regexes.

## Required execution

1. Run:

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

Record HEAD, dirty state, machine/display/power configuration, Java/Swift/Xcode versions, world and shader-pack identity. Resolve environment failures before renderer edits.

2. Establish baseline correctness and performance artifacts:

```bash
MODE=full WORLD="<world>" BLOCKS=4 \
  CANDIDATE_PROFILE="<single candidate profile>" \
  bash scripts/agent/run_unified_eval_cycle.sh
```

When no candidate exists yet, run conformance first and use the baseline profile for both sides only to validate the harness. Do not interpret identical profiles as a performance experiment.

3. Select one hypothesis. Write an execution note containing:

```text
Observation:
Hypothesis:
Target metric:
Semantic risk:
Affected ownership/ABI boundary:
Fastest falsification test:
Rollback condition:
```

Do not combine unrelated caching, fusion, allocation and submission ideas in one experiment.

4. Implement the smallest complete change. It must include executable integration, fail-closed admission, focused tests, activation counters or structured plan evidence, and lifecycle handling. Cross-language ABI changes must update Java handles/descriptors/wrappers, Swift exports, Metal 3/4 paths and tests together.

5. Run focused tests, then the correctness suite. If correctness fails, use the first divergent semantic pass/resource from render-contract evidence. Run `MODE=diagnostic` only for that pass or producer range. Do not broaden capture until the focused evidence is insufficient.

6. After correctness passes, run at least four ABBA paired blocks. Structured JSON metrics are authoritative. Inspect source reports and `comparison.json`; `decision.json` is the admission gate, not a substitute for source review.

7. Decide:

- `accepted-candidate`: retain the change and document activation and measurements.
- `rejected-correctness-gate` or `rejected-regression`: revert the experiment.
- `inconclusive-noise`: increase duration/blocks or add a more direct structured metric; do not call it a win.
- `blocked-environment` or `blocked-semantic-ambiguity`: leave the unsafe lane disabled and document the exact missing evidence.

8. Self-review the complete diff. Check ABI symmetry, resource generations, recreate/close paths, both Metal modes, mixin application evidence, stale flags, generated files and `git diff --check`.

## Required final report

Report starting/ending commit, files and boundaries changed, commands and exit status, correctness gate and first divergence if any, activation evidence, rejected experiments, remaining environment limits, and whether the branch is ready for human review.

Include task-before/task-after values, absolute change, direction-normalized improvement percentage and paired block count for FPS, GPU frame time, CPU render/encode time, native encoder count, attachment store/load bytes, resident resources, peak memory and stutters. Mark unavailable metrics with the exact missing structured source. Never omit before/after FPS after a completed performance client run.
