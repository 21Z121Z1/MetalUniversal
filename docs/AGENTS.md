# Documentation authority guide

Scope: `docs/`. Global policy remains in the repository-root `AGENTS.md`.

Do not infer authority from detail, date, filename or directory. `docs/agent/system-registry.json` is the machine-readable classification/router.

Rules:

- Canonical docs describe current contracts and must change with the implementation they govern.
- Component references are read only after routing to that component.
- ADRs preserve durable reasons, not transient status.
- Handoffs, prompts, migration matrices, retired plans and dated reports are provenance/recipes unless the registry explicitly promotes them.
- Do not rewrite historical documents to make them look current; fix classification or current contracts instead.
- Runtime state, current blockers and measurements belong in exact-SHA structured evidence/checkpoints, not long-lived architecture prose.

Before adding a new design document, ask whether the knowledge is better encoded as a test, schema, ADR, registry/checker rule, or an update to an existing canonical document.
