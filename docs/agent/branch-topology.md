# Live branch topology for agents

Branch refs are volatile work state, not durable architecture. Do not maintain a canonical JSON snapshot of "current branches". Generate branch truth from Git whenever a task actually spans branches.

## Entry point

For a full remote audit:

```bash
python3 scripts/agent/branch_topology.py --refresh
```

For machine consumption:

```bash
python3 scripts/agent/branch_topology.py --refresh --format json
```

Without `--refresh`, the tool uses already-present `origin/*` refs and falls back to local heads. `--refresh` is intentionally explicit because fetching every branch is useful for cross-branch archaeology but wasteful for ordinary single-branch work.

## What the compiler measures

For each live ref it records:

- exact commit SHA and tree SHA;
- ancestry relation to `integration/iris-metal-next`;
- canonical-only and branch-only commit counts;
- whether the file tree is identical to canonical;
- nearest live descendant branches that fully contain the branch history;
- a role derived from topology plus the persistent roles declared by `system-registry.json`.

Roles are intentionally small:

- `development` — canonical continued-development line;
- `stable` — promoted stable line;
- `platform-line` — explicitly persistent divergent platform lineage;
- `history-anchor` — reachability/provenance anchor; tree content is not development authority;
- `lineage-tip` — unabsorbed descendant of canonical with no live task descendant;
- `covered-ancestor` — task branch completely contained by a nearer live descendant;
- `divergent-tip` — non-persistent branch that diverged from canonical;
- `absorbed-ancestor` / `tree-equivalent-history` — no unique current tree authority;
- `unresolved` — insufficient Git information; never silently classify as safe to delete.

## Why commit count is insufficient

A history anchor can be hundreds of commits "ahead" because it has extra parents preserving retired tips while its file tree is byte-for-byte identical to canonical. Conversely, a two-commit task branch can contain unique product behavior. Therefore:

```text
ahead/behind count
    + ancestry
    + tree identity
    + declared persistent role
    = branch topology
```

Never use branch name, age, commit count, or open-PR state alone as authority.

## Lineages, not a flat branch list

The useful unit for an agent is a lineage tip plus its covered ancestors. If:

```text
canonical -> A -> B -> C
```

and all refs are live, `C` is the lineage tip, `B` is covered by `C`, and `A` is covered by its nearest live descendant `B`. This lets an agent inspect the unique delta at each edge instead of repeatedly comparing every branch to canonical.

Two branches that share an ancestor and then diverge remain two independent tips. A topology compiler must not call either one "newer" merely because it has more commits.

## PR metadata is secondary evidence

Open/draft/closed PR state helps establish operator intent, but Git topology outranks it for code containment. An open PR can point at an ancestor already fully contained by a later branch; a branch with no PR can still contain unique unlanded implementation.

For cleanup decisions join:

```text
live topology
 + PR state / operator intent
 + unique tree delta
 + exact-SHA validation evidence
 = retirement or integration decision
```

The topology tool deliberately does not call GitHub APIs or cache PR state. That keeps its output reproducible from Git and avoids turning external metadata into a second branch truth store.

## Safety

`branch_topology.py` is read-only except for the optional fetch/prune of remote-tracking refs. It never deletes repository branches, rewrites refs, merges code, or marks work accepted.

`retirement_advisory=covered-by-live-descendant` means only that Git ancestry shows no unique commits beyond the named descendant. It is not delete authorization. Before retiring a branch, check its PR/evidence, ensure the descendant is the intended survivor, and obtain the operator decision required by root `AGENTS.md`.

## Durable branch knowledge

Do not copy live SHAs or branch counts into canonical architecture docs. Durable facts should instead become:

- a merged source/test/contract;
- an ADR explaining a long-lived lineage boundary;
- an exact SHA in the retirement ledger/research anchor when unique unlanded history must remain reachable;
- a topology rule/self-test when future agents need to make the same classification.

`docs/agent/branch-migration-matrix.json` is historical provenance only. It must never again be compared for exact equality with the live remote branch inventory.
