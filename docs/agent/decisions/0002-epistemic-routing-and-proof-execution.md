# ADR 0002: Separate epistemic routing, proof obligations and execution scheduling

Status: accepted on the agent control-plane branch; effective when the branch is promoted.

## Context

MetalUniversal agents need two things that are easy to conflate:

1. a correct model of what a change can invalidate;
2. an economical plan for obtaining enough evidence.

The first control-plane iteration improved task routing and proof closure, but two failure modes remained.

First, task-keyword routing could be presented as "direct ownership" before any changed path established that fact. This made a useful inference look stronger than it was.

Second, the proof DAG described logical dependencies but did not distinguish them from executor composition. Some high-level commands already run lower gates internally. Listing every proof node as a separate command therefore encouraged duplicate work.

A third related problem was discovered in path ownership: broad `IrisMetal*.java` matching classified runtime/execution classes as product semantics, creating an under-proof route.

## Decision

The control plane uses three explicit separations.

### 1. Facts vs routing inference

The context capsule labels its basis:

- path-derived changed-component ownership is a computed fact;
- task-text routing is an inference used for orientation before a diff exists;
- impact closure is a conservative inference from declared component edges;
- exact-SHA structured evidence is fact for that identity only.

The backward-compatible `direct_components` field remains an active route, but consumers must inspect `ownership_basis`.

### 2. Proof obligations vs execution schedule

Proof profiles describe a logical evidence graph with `depends_on`.

Executors may additionally declare `covers` when a single command emits lower proof artifacts as part of its normal fail-closed flow. The context compiler computes:

- `proof_obligations`: complete logical closure;
- `execution_plan`: minimum command schedule after covered nodes are collapsed.

A cheap `always_preflight` profile is retained even when a later executor would technically cover it, because early falsification can save more expensive work.

Independent-environment obligations are not removed merely because another executor is stronger in a different environment.

### 3. Conservative ownership fallback

Narrow ownership patterns are reserved for classes whose role is explicit, such as plan/hazard/liveness/lifetime compiler/admission objects or the exact FFM bridge.

Other files under Java renderer execution roots default to `render.execution`, which carries stronger GPU/conformance/E2E obligations. A filename prefix such as `IrisMetal` is not sufficient evidence of semantic ownership.

Representative cases are stored in `docs/agent/routing-fixtures.json` and executed by the control-plane self-test.

## Consequences

Benefits:

- agents can distinguish observed state from inferred orientation;
- a routing mistake is less likely to silently weaken proof;
- integrated evaluation commands no longer imply that every lower node should be re-run separately;
- the repository can add new components/proof executors while regression-testing the agent world model;
- control-plane edits that merely discuss renderer performance remain control claims unless a stronger claim is explicit.

Costs:

- the registry schema is more expressive;
- executor `covers` declarations must remain truthful when scripts change;
- routing fixtures need maintenance when ownership boundaries intentionally change.

## Invariants

- `covers` may only reference known proof profiles at the same or lower rank.
- At least one cheap fail-fast preflight remains explicit.
- No proof result is reusable across source SHAs without re-establishing equivalence.
- Unknown renderer execution files route conservatively rather than into a cheaper semantic bucket.
- Changed judge/oracle code requires independent self-proof before judging the same candidate.
