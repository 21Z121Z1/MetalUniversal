# Agent harness guide

Scope: `scripts/agent`. Global policy remains in the repository-root `AGENTS.md`.

This directory is the control/evidence plane. It may route, normalize and judge evidence, but it must never become a second renderer truth.

Local invariants:

- Bind every decision/check to exact source/binary/scenario identity; older-SHA PASS is stale.
- Keep path-derived ownership facts distinct from task-derived routing inference and hypotheses.
- Structured JSON is acceptance authority; log regex is discovery/diagnostic fallback only.
- A changed oracle/analyzer needs independent self-tests/fixtures before it can approve the same candidate.
- Preserve capability-blocked, correctness-failed, regression, inconclusive-noise and accepted states.
- Keep generated task/evidence state under ignored `build/`; durable knowledge belongs in tests/contracts/ADRs/registry/checkers.
- Prefer indexes that link existing evidence over copying metrics into another truth store.
- Proof `depends_on` describes logical obligation; `covers` describes artifacts emitted inside one executor. Never use `covers` to erase an independent-environment requirement.
- Retain a cheap fail-fast preflight even when an expensive integrated executor technically covers it.
- `run_iris_perf_cycle.sh` is a legacy reproduction lane tied to the superseded Iris performance protocol. New work uses `run_unified_eval_cycle.sh`; do not choose an executable merely because it still exists.

When ownership, boundary or proof routing changes, update `docs/agent/system-registry.json` and add/adjust a representative `docs/agent/routing-fixtures.json` case. A routing change without a regression fixture should be exceptional.
