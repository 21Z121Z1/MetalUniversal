# MetalUniversal documentation map

Documentation in this repository is indexed by **authority and purpose**, not by filename age. Agents must not assume that a detailed or recent-looking handoff is the current specification.

## Start here

1. `../AGENTS.md` — execution policy and bootstrap.
2. `agent/system-model.md` — system-level abstraction tower and control-plane model.
3. `agent/system-registry.json` — machine-readable knowledge router.
4. `agent/unified-evaluation-loop.md` — canonical correctness/performance loop.
5. `agent/unified-evaluation-acceptance.json` — machine acceptance thresholds.
6. `render-contract-validation.md` — backend-neutral semantic/correctness contract.

For a task-specific route, run from the repository root:

```bash
python3 scripts/agent/context.py --task "<task description>"
```

## Authority classes

### Canonical

Canonical documents define current contracts. If they disagree with shipping source/tests or structured runtime evidence from the same SHA, the executable evidence wins and the document must be corrected in the same change.

The exact canonical set is declared in `agent/system-registry.json`.

### Component reference

These documents explain a bounded subsystem. They are useful after the task has been routed to that subsystem, but they are not a reason to preload the entire directory.

### Architectural decisions

Long-lived, non-obvious choices belong under `agent/decisions/`. ADRs explain *why* a stable boundary exists; they do not track transient implementation status.

### Historical / advisory

The following are provenance, not current-state authority unless a canonical document explicitly points to a specific claim:

- `handoffs/**`;
- dated acceptance/debug reports;
- `agent/retired-branch-backlog.md`;
- `agent/branch-migration-matrix.json`;
- `iris_on_metal_implementation_plan.md`;
- `iris_on_metal_architecture.md`;
- `iris-audit/experimental-performance-architecture.md`;
- `iris-audit/advanced-optimization-runtime-handoff.md`.

Do not delete useful history merely because it is old. Keep it out of the default reasoning path.

## Durable knowledge rule

When a task produces a reusable result, distill it into the narrowest durable form:

- invariant -> test or canonical contract;
- interface decision -> ADR;
- routing/authority rule -> `system-registry.json` or control-plane checker;
- runtime result -> structured evidence artifact;
- rejected but uniquely useful experiment -> retirement ledger with exact SHA;
- transient investigation -> do not promote to canonical documentation.

This keeps the documentation graph small enough for agents to navigate while allowing the repository to accumulate verified knowledge indefinitely.
