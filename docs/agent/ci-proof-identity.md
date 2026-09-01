# CI proof-subject identity

A green workflow is evidence only for the Git object it actually tested. Pull-request UI association is not sufficient identity evidence because GitHub can execute a workflow on either the candidate head or a synthetic merge commit.

## Two valid proof subjects

### Candidate head

Answers:

> Does the exact proposed source commit satisfy this gate?

PR validation workflows that claim exact-head evidence must check out:

```yaml
ref: ${{ github.event.pull_request.head.sha || github.sha }}
```

and verify the resulting `git rev-parse HEAD` against that expected SHA.

### Merge result

Answers:

> Does the candidate integrate with the current target base as represented by GitHub's synthetic PR merge commit?

On a `pull_request` workflow with the default checkout, `GITHUB_SHA` is normally the synthetic merge subject. This is useful promotion/integration evidence, but it is not the candidate head and must not be described as exact-head proof.

The repository's ordinary `build` PR job intentionally retains this merge-result role. Candidate-oriented Unified eval, Metal capabilities, Minecraft reference and production-client E2E use the candidate-head subject.

## Machine identity record

Use:

```bash
python3 scripts/agent/record_ci_subject.py \
  --proof-subject candidate-head \
  --output build/agent-evidence/ci-subject.json
```

or `--proof-subject merge-result` for the synthetic merge gate.

The structured record includes:

- `proof_subject`;
- PR candidate head SHA when available;
- PR base SHA when available;
- event SHA;
- expected tested commit SHA;
- actual tested commit SHA;
- actual tested tree SHA;
- `exact_match`.

The command fails closed when the checked-out commit does not match the declared subject.

## Proof producers are independent

A proof-producing workflow owns only the evidence it directly executes. It must not turn itself into a distributed scheduler by polling sibling workflows, waiting for their conclusions, or rewriting their results into its own `PASS` state.

This is both an epistemic rule and a resource rule:

```text
cheap/static proof producer ---------\
hosted Metal exact-head producer -----+--> promotion/readiness composition
Minecraft production E2E producer ----+
merge-result integration producer ----/
```

Each producer emits typed, exact-subject evidence and terminates as soon as its own obligation is resolved. The promotion layer composes those independent conclusions. In GitHub, required checks and the agent's merge-readiness inspection are the normal composition surface; an expensive macOS GPU runner must not sleep while waiting for sibling workflows.

A capability block is also a result, not a pass. If a hosted environment cannot execute the JVM -> FFM -> Swift GPU suite, the hosted evidence must say `environment-blocked`, record what executed, and name the stronger authority that still has to establish the runtime claim. Another workflow may later satisfy that obligation, but it does not retroactively make the blocked hosted execution become `pass`.

For pull-request branches, an exact-head workflow should normally run once from the `pull_request` event. Running the same expensive job again from an `agent/**` push duplicates cost without creating a different proof subject. The canonical development branch may still run the same workflow on `push` so the post-merge commit is independently validated.

See ADR `docs/agent/decisions/0004-independent-proof-producers-and-promotion-composition.md`.

## Promotion model

Candidate proof and merge-result proof answer different questions:

```text
candidate head
  -> semantic/static/native/runtime proof of proposed source

candidate head + current target base
  -> synthetic merge-result integration proof
```

Neither substitutes for the other. A candidate can be internally correct but conflict with a moving base; a merge-result build can be green while a workflow description incorrectly attributes the evidence to the head SHA.

For final PR readiness, report both identities when both are required:

```text
candidate_head_sha
candidate-head gate conclusions
merge_result_sha
merge-result gate conclusion
```

Do not collapse them into one `source_sha` field.

Promotion is the composition point. It may require several independently green candidate-head checks plus a merge-result check and, for stronger claims, physical/device evidence. No individual proof producer should claim overall promotion readiness merely because it can query the other jobs.

## Evidence ownership

CI identity belongs at L8 acceptance/promotion and the evidence plane. It must not leak into renderer semantics. Workflows and evidence record which Git object they tested; source code remains unaware of PR mechanics.

The tested tree SHA is recorded because two different commits can intentionally have the same tree (for example a history/reachability commit). Commit identity describes provenance; tree identity describes tested content. Agents should preserve both when that distinction matters.
