# Agent prompt recipes

Files in this directory are **advisory task recipes**, not current system authority. They may be useful when delegating a bounded workflow to another agent, but branch names, read orders, stage status and validation rules inside an older prompt can drift.

Before using any recipe:

```bash
python3 scripts/agent/context.py --task "<intended task>"
```

Then apply the repository-root `AGENTS.md`, `system-registry.json` and the generated proof plan over any conflicting prompt text. Prefer making future prompts thin: state the objective/constraints and let the repository control plane supply current ownership, authority and verification routes.

Do not copy prompt prose into canonical architecture documents merely because an experiment succeeded. Distill successful knowledge into tests, contracts, ADRs, registry/checker rules or implementation instead.
