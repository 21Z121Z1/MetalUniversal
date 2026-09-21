# Architectural decision records

Use this directory only for decisions that are both non-obvious and expected to outlive the task branch.

Create an ADR when changing a stable abstraction boundary, ownership rule, identity scheme, acceptance authority, compatibility contract, or long-lived execution policy. Do not create ADRs for routine refactors, one-off experiments, current blockers, benchmark results, or branch status.

Filename format:

```text
NNNN-short-decision-name.md
```

Each ADR must contain:

```text
# NNNN: Title
Status: accepted | superseded by NNNN
Date: YYYY-MM-DD

## Context
What durable problem forced a choice?

## Decision
What exact invariant/boundary is adopted?

## Consequences
What becomes easier, harder, or prohibited?

## Proof / enforcement
Which source, tests, schemas or checks make the decision executable?
```

Prefer adding an enforcement mechanism in the same change. An ADR without any executable or reviewable consequence should usually remain ordinary prose instead.
