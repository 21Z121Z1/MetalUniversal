# 0001: Agent control plane is a router over executable truth

Status: accepted
Date: 2026-08-31

## Context

MetalUniversal accumulated strong validation infrastructure, multiple design/handoff documents and many task branches. Agents could execute sophisticated gates but still had to spend substantial context reconstructing which document, branch and workflow represented current authority. Stale branch names in tooling and historical plans could compete with the canonical development line.

## Decision

Adopt a thin agent control plane with these invariants:

1. `AGENTS.md` is the human/agent entrypoint.
2. `docs/agent/system-registry.json` is the machine-readable router for authorities, component boundaries and verification commands.
3. `scripts/agent/context.py` generates current Git/task routing context instead of storing volatile repository state in architecture prose.
4. Shipping source/tests/schemas and structured evidence outrank documentation.
5. Historical handoffs and retired plans are preserved but removed from the default reasoning path.
6. Stable semantic identities and immutable plans join semantics, execution and evidence; logs/pointers/timestamps never become canonical cross-run identities.
7. Control-plane drift is checked by `scripts/agent/verify_agent_control_plane.py` and the unified static verification path.

## Consequences

Agents can start from a small context capsule and expand only into the relevant subsystem. New documentation must declare a durable role rather than becoming another de facto source of truth. Dynamic blockers and branch inventories are generated or evidence-linked rather than copied into long-lived architecture documents.

This does not make the registry a new renderer truth. If the registry disagrees with executable behavior, the registry is wrong.

## Proof / enforcement

- `AGENTS.md`
- `docs/agent/system-model.md`
- `docs/agent/system-registry.json`
- `scripts/agent/context.py`
- `scripts/agent/verify_agent_control_plane.py`
- `scripts/agent/verify_unified_eval.sh`
- `.github/workflows/unified-eval-static.yml`
