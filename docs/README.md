# MetalUniversal documentation map

Documentation is indexed by **authority and purpose**, not by filename age. A detailed or recent-looking handoff is not automatically the current specification. The nearest `AGENTS.md` gives local documentation rules; `agent/system-registry.json` is the machine-readable authority/router.

## Start here

Do not read this directory linearly. From the repository root run:

```bash
python3 scripts/agent/context.py --task "<task description>"
```

It returns the canonical/local docs that matter to the task, direct ownership, downstream impact and the minimum proof ladder.

The small canonical system set is:

1. `../AGENTS.md` — execution policy and bootstrap;
2. `agent/system-model.md` — abstraction tower, impact/proof/recovery model;
3. `agent/system-registry.json` — machine-readable component/boundary/proof graph;
4. `agent/unified-evaluation-loop.md` — canonical correctness/performance loop;
5. `agent/unified-evaluation-acceptance.json` — machine acceptance thresholds;
6. `render-contract-validation.md` — backend-neutral semantic/correctness contract.

The exact canonical set is declared in the registry. The list above is explanatory, not a second source of authority.

## Authority classes

### Canonical

Canonical documents define current contracts. If they disagree with shipping source/tests or exact-SHA structured runtime evidence, executable evidence wins and the canonical document must be corrected in the same change.

### Component reference

A component reference explains one bounded subsystem. Read it only after the task is routed to that component. High-risk source roots also contain short local `AGENTS.md` files that state directory-level ownership and invariants without duplicating the global model.

### Architectural decisions

Long-lived, non-obvious choices belong under `agent/decisions/`. ADRs explain *why* a stable boundary exists; they do not track transient implementation status, current blockers or benchmark numbers.

### Historical / advisory

The following are provenance or recipes, not current-state authority unless a canonical contract explicitly cites a particular claim:

- `handoffs/**`;
- `agent/prompts/**` — reusable recipes only; see its README;
- dated acceptance/debug reports;
- `agent/retired-branch-backlog.md`;
- `agent/branch-migration-matrix.json`;
- `agent/cloud-first-metal-program.json`;
- `iris_on_metal_implementation_plan.md`;
- `iris_on_metal_architecture.md`;
- `iris-audit/experimental-performance-architecture.md`;
- `iris-audit/advanced-optimization-runtime-handoff.md`.

Do not delete useful history merely because it is old. Keep it out of the default reasoning path and do not rewrite it merely to look current.

## Runtime and task state are not documentation

Current branch/HEAD, blockers, environment capability, measurements and the next action are volatile. Generate them from Git/evidence and, for long tasks, use the ignored checkpoint:

```bash
python3 scripts/agent/checkpoint.py init --task "<task>" --next-command "<next action>"
```

The unified evaluation runner already emits exact-SHA `run-manifest.json` and `decision.json`; link those artifacts instead of copying their values into a new prose report.

## Durable knowledge rule

When a task produces a reusable result, distill it into the narrowest durable form:

- invariant -> test or canonical contract;
- interface decision -> ADR;
- ownership/impact/proof rule -> `system-registry.json` or control-plane checker;
- runtime result -> structured evidence artifact;
- useful interrupted state -> generated checkpoint/evidence;
- rejected but uniquely useful experiment -> retirement ledger with exact SHA;
- transient investigation -> do not promote to canonical documentation.

The goal is a documentation graph that can grow in verified knowledge without growing proportionally in agent reading cost.
