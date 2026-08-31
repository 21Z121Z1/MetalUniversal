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

## Evidence ownership

CI identity belongs at L8 acceptance/promotion and the evidence plane. It must not leak into renderer semantics. Workflows and evidence record which Git object they tested; source code remains unaware of PR mechanics.

The tested tree SHA is recorded because two different commits can intentionally have the same tree (for example a history/reachability commit). Commit identity describes provenance; tree identity describes tested content. Agents should preserve both when that distinction matters.
