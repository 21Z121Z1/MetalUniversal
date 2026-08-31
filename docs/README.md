# MetalUniversal documentation map

Documentation is indexed by **authority and purpose**, not by filename age. A detailed or recent-looking handoff is not automatically the current specification. The nearest `AGENTS.md` gives local rules; `agent/system-registry.json` is the machine-readable ownership/impact/proof graph.

## Start here

Do not read this directory linearly. From the repository root run:

```bash
python3 scripts/agent/context.py --task "<task description>"
```

The capsule distinguishes path-derived ownership facts from task-derived routing inference, computes downstream impact/boundary risk, and returns both the complete proof obligations and a deduplicated minimum execution schedule.

For a cross-branch audit, separately compile the live Git topology:

```bash
python3 scripts/agent/branch_topology.py --refresh
```

Branch topology is generated navigation state, not documentation. Do not copy its current branch count/SHAs into canonical prose.

The small canonical system set is:

1. `../AGENTS.md` — compact execution/bootstrap map;
2. `agent/system-model.md` — abstraction tower, epistemic model, impact/proof/recovery model;
3. `agent/system-registry.json` — machine-readable component/boundary/proof graph;
4. `agent/unified-evaluation-loop.md` — canonical correctness/performance loop;
5. `agent/unified-evaluation-acceptance.json` — machine acceptance thresholds;
6. `render-contract-validation.md` — backend-neutral semantic/correctness contract.

The exact canonical set is declared in the registry. This list is explanatory, not a second source of authority.

## Authority classes

### Canonical

Canonical documents define current contracts. If they disagree with shipping source/tests or exact-SHA structured runtime evidence, executable evidence wins and the canonical document must be corrected in the same change.

### Component and operational reference

A component reference explains one bounded subsystem. Read it only after the task is routed to that component. High-risk source roots also contain short local `AGENTS.md` files that state directory-level ownership/invariants without duplicating the global model.

Operational references explain how to derive volatile truth without storing it. Current examples:

- `agent/branch-topology.md` — compile live branch lineages/roles from Git;
- `agent/ci-proof-identity.md` — distinguish candidate-head evidence from synthetic merge-result evidence.

These files define derivation rules; they must not embed a periodically maintained snapshot of live refs or workflow SHAs.

### Architectural decisions

Long-lived, non-obvious choices belong under `agent/decisions/`. ADRs explain *why* a stable boundary exists; they do not track transient implementation status, blockers or benchmark numbers.

### Historical / advisory

These are provenance or recipes, not current-state authority unless a canonical contract explicitly cites a particular claim:

- `handoffs/**`;
- `agent/prompts/**` — reusable recipes only;
- dated acceptance/debug reports;
- `agent/retired-branch-backlog.md`;
- `agent/branch-migration-matrix.json`;
- `agent/cloud-first-metal-program.json`;
- `iris_on_metal_implementation_plan.md`;
- `iris_on_metal_architecture.md`;
- `iris-audit/experimental-performance-architecture.md`;
- `iris-audit/advanced-optimization-runtime-handoff.md`.

`agent/branch-migration-matrix.json` is especially important to classify correctly: it is provenance about an earlier migration event, never a required mirror of the current GitHub branch inventory.

Do not delete useful history merely because it is old. Keep it out of the default reasoning path and do not rewrite old handoffs merely to look current.

## Legacy executable compatibility

Some old executable surfaces remain in-tree only so earlier experiments can be reproduced. They are **not** default agent entry points.

- `agent/iris-performance-loop.md` is now a tombstone for the superseded pre-unified performance protocol.
- `agent/iris-performance-acceptance.json` is the companion acceptance file for that legacy protocol; its `reference_branch` is intentionally historical.
- `../scripts/agent/run_iris_perf_cycle.sh` is the corresponding legacy runner.

For new work use `agent/unified-evaluation-loop.md`, `agent/unified-evaluation-acceptance.json` and `../scripts/agent/run_unified_eval_cycle.sh`. A legacy script being executable does not make it current authority.

## Runtime, branch and task state are not documentation

Current branch/HEAD, live branch inventory, PR merge SHA, blockers, environment capability, measurements and next action are volatile. Generate them from Git/event/evidence rather than copying them into architecture prose.

For long tasks use the ignored checkpoint:

```bash
python3 scripts/agent/checkpoint.py init --task "<task>" --next-command "<next action>"
```

For branch archaeology use `branch_topology.py`; for CI identity use the structured output of `record_ci_subject.py`. The unified evaluator already emits exact-SHA `run-manifest.json` and `decision.json`; link those artifacts instead of copying their values into a prose status report.

## Durable knowledge rule

When a task produces a reusable result, compile it into the narrowest durable form:

- invariant -> executable test or canonical contract;
- interface decision -> ADR;
- ownership/impact/proof rule -> `system-registry.json` **plus a representative routing fixture/checker**;
- branch-classification rule -> branch-topology compiler/self-test, not a live inventory file;
- CI identity rule -> proof-subject recorder/workflow assertion;
- runtime result -> structured exact-SHA evidence artifact;
- useful interrupted state -> generated checkpoint/evidence;
- rejected but uniquely useful experiment -> retirement ledger with exact SHA;
- transient investigation -> do not promote to canonical documentation.

The goal is for verified knowledge to grow faster than the reading cost imposed on the next agent.
