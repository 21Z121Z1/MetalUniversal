# ADR 0004: Independent proof producers and promotion composition

Status: accepted

## Context

MetalUniversal uses several independent evidence environments: cheap static checks, hosted macOS Metal execution, Minecraft 26.2 production-client E2E, synthetic merge-result integration, attended Apple Silicon, and device-specific runtime checks.

The earlier hosted-Metal workflow attempted to become an aggregator after completing its own GPU work. It polled GitHub Actions for sibling exact-head workflows and failed if those workflows had not completed in the same event context. On an `agent/**` push this left an expensive macOS runner idle for a bounded polling window even though its own Metal work had already finished. More importantly, the same workflow could classify JVM GPU execution as `environment-blocked` with zero executed integration tests and then emit downstream JSON fields claiming those suites were `pass` and `active-and-tests-executed`.

Those two failures have the same architectural cause: proof production and promotion composition were coupled.

## Decision

Proof producers are monotonic, exact-subject, and independent.

A proof-producing workflow:

1. binds itself to one explicit Git proof subject;
2. executes only the obligations it owns;
3. emits typed evidence for what actually happened, including `environment-blocked`, `unknown`, failure, and executed-test counts where relevant;
4. never converts a missing capability into `pass`;
5. never waits for or rewrites sibling workflow conclusions;
6. terminates when its own proof obligation is resolved.

Promotion/readiness is the sole composition point. It combines independent candidate-head checks, merge-result integration evidence, and any required physical/device acceptance. GitHub required checks plus the controlling agent's exact-SHA readiness inspection are the default composition mechanism; a dedicated cheap promotion checker may be added later if machine composition becomes necessary.

For task branches, expensive exact-head proof workflows run from `pull_request`, not both `push` and `pull_request`. The canonical development branch retains `push` validation so the post-merge SHA is separately tested.

## Consequences

- Hosted Metal CI cannot declare overall cloud/program completion. It declares only its own exact-head hosted proof result.
- An environment-blocked JVM/FFM/Swift integration path remains visibly blocked in `correctness.json`, `activation.json`, and `decision.json`; a production Minecraft E2E result may satisfy a separate higher-level obligation but does not rewrite the hosted fact.
- Cross-workflow races no longer consume macOS runner time.
- Evidence from different environments remains independently attributable and can be invalidated by exact SHA without hidden coupling.
- Promotion logic becomes cheaper to inspect and easier for an agent to reason about because proof production is a DAG of facts rather than workflows recursively judging workflows.

## Rejected alternatives

### Keep sibling polling but shorten the timeout

Rejected. It reduces wasted compute but preserves the wrong ownership boundary and remains event-order dependent.

### Treat environment-blocked hosted suites as pass when Minecraft E2E is green

Rejected. This destroys provenance. The two observations answer different questions and must remain distinct facts.

### Run expensive hosted Metal on both task-branch push and pull_request

Rejected for normal task branches. Both executions test the same candidate tree while doubling cost. Pull-request exact-head proof plus post-merge canonical push proof provides the useful identities without duplication.
