# MetalUniversal agent entrypoint

MetalUniversal is one evidence-driven rendering system. The canonical continued-development base is `integration/iris-metal-next`; `master` is promoted stable; `feature/ios-amethyst-runtime` is the isolated mobile line. Treat task branches as disposable work queues, not memory.

This file is intentionally a map, not the repository encyclopedia. Current architecture lives in `docs/agent/system-model.md`; machine ownership/impact/proof policy lives in `docs/agent/system-registry.json`.

## 60-second bootstrap

Run from the repository root:

```bash
python3 scripts/agent/context.py --task "<short task description>"
```

Interpret the capsule precisely:

- **changed-component ownership** is path-derived and may be treated as a computed fact;
- **planned component route** is task-derived inference until a diff establishes ownership;
- **impact closure** is a conservative downstream inference, not an instruction to edit every impacted component;
- **proof obligations** are the complete logical evidence closure;
- **minimum execution schedule** collapses gates already integrated by larger executors while retaining cheap fail-fast preflight;
- checkpoint PASS results only apply to the exact `source_sha` at which they ran.

Read only the returned local `AGENTS.md`, canonical docs, nearest tests/schemas and concrete evidence needed for the task. Do not preload all of `docs/`, old prompts/handoffs or historical branches.

For multi-step work:

```bash
python3 scripts/agent/checkpoint.py init \
  --task "<task>" \
  --hypothesis "<falsifiable hypothesis>" \
  --next-command "<next cheapest action>"
```

`build/agent-state/` is generated/ignored state, never canonical truth.

## Authority and epistemic order

When sources disagree:

1. shipping source, tests, schemas, generated manifests and exact Git/binary identity;
2. structured runtime evidence produced by that exact identity;
3. canonical design/acceptance documents named by the registry;
4. prompts, handoffs, retired plans, migration records and other historical prose.

Do not present task routing, suspected cause or expected performance effect as fact. Do not reuse PASS evidence from another SHA.

## Abstraction tower

```text
operator intent
 -> Minecraft/Iris/Sodium observable semantics
 -> semantic pass + generation-aware resource identity
 -> immutable render/terrain plan + admission
 -> Java backend execution
 -> Java/FFM ABI
 -> Swift/Metal execution
 -> structured evidence
 -> acceptance/promotion
```

Component IDs:

`product.semantics` · `render.plan` · `render.execution` · `native.abi` ·
`native.execution` · `terrain.scene` · `validation.contract` ·
`evaluation.control` · `platform.mobile`

When ownership is ambiguous inside a renderer execution root, prefer the more conservative execution component/proof route rather than a cheaper guess. A diff directly owning more than two components should normally be split unless the interface itself is the task.

## Non-negotiable renderer invariants

- Preserve exact Iris/OpenGL-observable semantics; do not substitute approximations or shader-pack-name special cases.
- Fail closed. Unsupported/unsafe optimization paths must reject/disable with stable reason evidence.
- Hazard/liveness reasoning uses generation-aware physical resources and explicit RAW/WAR/WAW/barrier/attachment transitions.
- Lower/native layers execute admitted intent; they must not independently re-derive semantic policy.
- Extend the existing Java/FFM/Swift bridge; do not create a shadow native module/control plane.
- Keep Java descriptors/downcalls and Swift exports aligned in symbol, layout, ownership, nullability and lifecycle.
- Resource recreate/resize/reload/close/retirement paths are correctness.
- Structured JSON is acceptance authority; log regex is not.
- A changed oracle/analyzer must independently prove itself before judging the same candidate.
- Never weaken tests, thresholds, Metal validation or error handling to produce a pass.
- Never commit shader packs, worlds, binaries, captures, `.minecraft-reference/`, `build/agent-runs/`, `build/agent-evidence/` or `build/agent-state/`.

## One implementation loop

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

1. Generate context; create/update a checkpoint for long work.
2. Separate ownership facts from task inference; inspect impact closure and named boundary contracts.
3. Read the smallest returned component slice and nearest tests.
4. State one falsifiable hypothesis, target behavior/metric, semantic risk, fastest falsifier and rollback condition.
5. Implement the narrowest complete change with activation/admission and lifecycle evidence.
6. Run the generated **minimum execution schedule** in increasing cost; stop when a cheap gate falsifies the candidate.
7. Diagnose semantic failures from the first divergent pass/resource/producer before broad capture.
8. Use physical/device proof only when the requested claim cannot be established elsewhere.
9. Run paired performance only after correctness/activation and only for a performance claim.
10. Self-review the final diff, then distill reusable knowledge into tests/contracts/ADR/registry + routing fixture/checker.

## Evidence truth

A valid decision binds:

```text
source SHA
 + binary/native identity
 + environment/scenario identity
 + activation/admission
 + correctness
 + performance when claimed
 + exact artifact locations
```

Compilation is not activation. Activation is not correctness. Correctness is not performance improvement. A screenshot without semantic linkage is diagnostic evidence. A PASS from another SHA is stale.

The unified runner already emits the canonical run manifest, correctness/admission/trial artifacts and `decision.json`; reuse those instead of copying metrics into another truth store.

## Environment truth

Hosted CI is an independent environment, not a substitute for attended Apple Silicon or a real iOS device when the claim depends on visible presentation, physical Metal behavior, stable GPU performance or device integration. Missing capability is `environment-blocked`, never silently passed.

For vanilla Minecraft implementation details:

```bash
bash scripts/minecraft-reference.sh
```

The generated `.minecraft-reference/26.2/sources/` tree is ignored and must not be committed.

## Branch and memory discipline

Normally retain only `master`, `integration/iris-metal-next`, `feature/ios-amethyst-runtime`, and `research/modernization-backlog`, plus bounded active task work. After the human merge/retire decision, delete disposable task branches. Preserve uniquely useful unlanded work by exact SHA + retirement ledger/research anchor, not per-task archive branches.

Durable knowledge should compile into the narrowest form:

- invariant -> test/canonical contract;
- ownership or proof rule -> registry + routing fixture/checker;
- long-lived interface reason -> ADR;
- runtime result -> exact-SHA structured evidence;
- transient work -> ignored checkpoint.

## Final report

Distinguish validated, environment-blocked/unvalidated, rejected/reverted, inconclusive/noise and pre-existing policy drift. Report starting/ending SHA, changed ownership/boundaries, actual gates and exit status, exact-head CI, correctness/activation evidence, remaining physical limits and residual risk. For performance, include before/after, raw and direction-normalized delta, and paired block count.
