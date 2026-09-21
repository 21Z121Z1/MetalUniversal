# ADR 0003: Generate branch topology and make CI proof subjects explicit

Status: accepted on the agent control-plane branch; effective when the branch is promoted.

## Context

Two control-plane failures have the same root cause: volatile identity was being inferred from labels instead of derived from executable state.

First, the repository can contain many task branches whose names make them look independent even when Git proves a strict containment chain. The inverse also occurs: a history-anchor commit can be hundreds of commits ahead while having the exact canonical file tree. The old branch migration matrix failed because it treated a historical snapshot as live branch authority.

Second, a pull-request workflow can be associated with a candidate head in GitHub while `actions/checkout` actually tests the synthetic PR merge commit. Calling that result "exact-head CI" overstates what the evidence identifies.

Both cases impose unnecessary archaeology on agents and create opportunities to attach a correct observation to the wrong subject.

## Decision

### 1. Live branch state is compiled from Git

Cross-branch tasks use `scripts/agent/branch_topology.py` to derive commit/tree identity, ancestry, canonical ahead/behind counts, nearest live descendant coverage and declared persistent roles.

No canonical file stores the current branch inventory or current branch SHAs. The migration matrix remains historical provenance.

The useful working unit is a lineage tip plus its covered ancestors, not a flat list of branch names.

### 2. Branch role uses tree identity as well as history

Commit ancestry and ahead counts are insufficient. The classifier also records tree SHA/equality and persistent roles from the system registry. This allows a reachability/history anchor to remain a history anchor even if its commit graph is far ahead while its tree equals canonical.

A `covered-ancestor` result is advisory only. The tool is non-destructive; PR/evidence/operator intent must be joined before retirement.

### 3. CI evidence declares its proof subject

Candidate-oriented workflows explicitly check out `${{ github.event.pull_request.head.sha || github.sha }}` and record `proof_subject=candidate-head`.

The general PR build keeps GitHub's synthetic merge checkout and records `proof_subject=merge-result`. It is promotion/integration evidence, not candidate-head evidence.

`scripts/agent/record_ci_subject.py` fails closed if the tested commit does not equal the commit implied by the declared subject. Evidence records both commit and tree identity.

## Consequences

Benefits:

- agents can compress many branch names into a small number of meaningful lineages;
- covered intermediate branches stop competing for attention with their descendant tips;
- divergent lines remain visibly independent instead of being ranked by date or commit count;
- history anchors cannot be mistaken for development merely because they have many parents;
- exact-head and merge-result CI conclusions are no longer conflated;
- branch and CI truth remain generated from Git/event state rather than copied into long-lived prose.

Costs:

- a full branch audit requires an explicit remote fetch;
- final PR reports need to name both candidate and merge-result identities when both matter;
- branch retirement still requires human/operator judgment because topology cannot infer intent or validation sufficiency.

## Invariants

- `branch-migration-matrix.json` is never live branch authority.
- `ahead_by` alone never determines branch role.
- persistent platform/history roles are declared; volatile task topology is generated.
- branch-topology tooling never deletes or rewrites repository refs.
- a workflow claiming candidate-head proof must verify the checked-out commit equals the candidate SHA.
- a synthetic merge result is never labeled as candidate-head evidence.
- commit SHA and tree SHA are distinct identities and may both matter.
