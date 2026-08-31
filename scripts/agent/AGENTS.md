# Agent harness guide

Scope: `scripts/agent`. Global policy remains in the repository-root `AGENTS.md`.

This directory is the control/evidence plane. It may route, normalize and judge evidence, but it must never become a second renderer truth.

Local invariants:

- Bind every decision to exact source/binary/scenario identity.
- Structured JSON is acceptance authority; log regex is discovery/diagnostic fallback only.
- A changed oracle/analyzer needs self-tests or independent fixtures before it can approve the same candidate.
- Preserve the distinction between capability-blocked, correctness-failed, regression, inconclusive noise and accepted candidate.
- Keep generated task/evidence state under ignored `build/`; canonical knowledge belongs in tests/contracts/ADRs/checkers.
- Prefer one manifest/index that links existing evidence over copying metrics into another report.

When adding a new gate, register it in `docs/agent/system-registry.json` only if it changes the proof ladder. Keep the cheapest sufficient gate first.
