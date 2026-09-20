# Evidence harness rules

Scope: `scripts/agent`.

This directory contains reusable validation and evidence tools. It is not a repository router or a second renderer model.

- Bind every verdict to the exact source, binary, environment, and scenario identity.
- Treat structured evidence as authority. Use log matching only for diagnostics or explicit failure detection.
- Keep capability-blocked, correctness-failed, regression, inconclusive, and accepted results distinct.
- Give every analyzer or oracle a self-test before it can approve the same candidate.
- Keep generated runs under ignored `build/` paths. Do not commit live status, checkpoints, branch inventories, or benchmark results.
- Candidate-head workflows prove the candidate commit. General pull-request builds can prove only the synthetic merge result.
- Physical Apple Silicon validation remains independent from hosted compilation and synthetic GPU checks.
- Delete task-specific migration or patch scripts after their coherent change is present in source and tests.