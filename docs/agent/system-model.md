# MetalUniversal agent system model

This document is the conceptual control plane for continued development. It exists to minimize agent reconstruction cost: an agent should be able to discover the current authority, route a task to the right subsystem, change the smallest ownership boundary, and prove the result without reading the repository linearly.

The repository is not a bag of renderer features. It is one system with two tightly coupled planes:

- **data plane** — Minecraft/Iris/Sodium semantics are lowered into explicit render intent and executed by Metal;
- **control plane** — agents observe repository/runtime state, choose a bounded change, run evidence-producing gates, and distill durable decisions.

The control plane must never invent a second rendering truth. Source, executable contracts and structured runtime evidence remain authoritative.

## 1. The abstraction tower

Reason about the renderer from the top down and debug it from the first broken layer down. Every layer has a deliberately narrow contract with the layer below it.

```text
L0  Operator intent / product contract
    "What must Minecraft visibly and behaviorally do?"
        |
L1  Game + Iris + Sodium observable semantics
    passes, resources, draw ordering, shader-pack-visible behavior
        |
L2  Canonical semantic identity
    semantic pass IDs + generation-aware ResourceIdentity
        |
L3  Immutable render / terrain plans
    hazards, liveness, attachment policy, batching, admission reasons
        |
L4  Backend execution policy
    Metal 3/4 path selection, encoder reuse, ICB, residency, fallbacks
        |
L5  Java/FFM ABI
    exact descriptors, ownership, nullability, symbol/version contracts
        |
L6  Swift / Metal execution
    MTL resources, encoders, command buffers, presentation
        |
L7  Evidence plane
    structured traces, counters, readback, timings, first divergence
        |
L8  Acceptance / promotion
    correctness gate -> paired performance -> review -> promotion
```

Do not skip a layer when diagnosing. A visual mismatch is first a semantic/evidence problem, not immediately a Swift problem. A native crash is first an ABI/lifetime problem, not an optimization problem. A performance regression is not actionable until correctness and activation are both proved.

## 2. One semantic address space

Agents need stable names that survive refactors, backend changes and profiling runs. Use these identities in descending preference:

1. semantic pass ID;
2. generation-aware `ResourceIdentity`;
3. immutable plan/admission record;
4. stable pipeline/layout key;
5. source path + symbol.

Never use timestamps, native pointers, encoder ordinals, object addresses, log line numbers or shader-pack names as cross-run identity.

This gives every claim an address. For example:

```text
semantic pass
  -> resource generation
    -> hazard/liveness edge
      -> admitted backend transform
        -> Java/FFM descriptor
          -> native encoder work
            -> structured evidence
```

That chain is the preferred debugging join path.

## 3. Authority model

When information conflicts, use this order:

1. shipping source, tests, schemas, exact Git/binary identity;
2. structured runtime evidence produced by that exact identity;
3. canonical design/acceptance documents named by `docs/agent/system-registry.json`;
4. historical handoffs, retired plans, migration records and prose notes.

A newer date does not outrank executable truth. A historical document may explain why code exists but cannot prove that the current tree still behaves that way.

`docs/agent/system-registry.json` is a **router**, not a fact database. It identifies authorities, component boundaries and verification entry points. Runtime state is generated from Git and evidence; it must not be copied into long-lived architecture prose.

## 4. Context-budget model

An agent should expand context only when the current layer cannot answer the question.

### Tier 0 — bootstrap capsule

Run:

```bash
python3 scripts/agent/context.py --task "<short task description>"
```

The capsule should answer, in a few hundred lines at most:

- current branch / HEAD / dirty state;
- relation to the canonical development branch when locally resolvable;
- canonical authorities;
- likely subsystem routes for the task;
- exact verification entry points;
- warnings about repository-policy drift.

### Tier 1 — system contracts

Read only:

- `AGENTS.md`;
- this document;
- `docs/agent/unified-evaluation-loop.md` when rendering correctness/performance is involved.

### Tier 2 — component slice

Read the source roots and canonical docs returned for the routed component. Do not preload unrelated Iris, terrain, MetalFX, iOS and historical documents.

### Tier 3 — evidence and history

Open raw traces, runtime artifacts, old handoffs and migration records only to resolve a concrete question that survived Tiers 0–2.

This is intentionally asymmetric: source navigation is cheap; broad historical reading is expensive and often misleading.

## 5. System components and ownership

The machine-readable component map lives in `docs/agent/system-registry.json`. The stable conceptual boundaries are:

- **product.semantics** — Minecraft/Iris/Sodium observable meaning;
- **render.plan** — semantic graph, hazards, liveness and optimization admission;
- **render.execution** — Java Metal execution and resource lifecycle;
- **native.abi** — Java FFM ↔ Swift ABI and ownership;
- **terrain.scene** — terrain scheduling, visibility and GPU scene/ICB submission;
- **validation.contract** — backend-neutral correctness oracle and first-divergence diagnosis;
- **evaluation.control** — agent harness, evidence, statistics and CI authority;
- **platform.mobile** — isolated iOS/Amethyst platform lineage sharing backend contracts.

A change that crosses two components must name the ownership boundary it crosses. A change that crosses three or more should be split unless the interface itself is the subject of the task.

## 6. The agent control loop

Use one closed loop for implementation work:

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

### Observe

Generate the context capsule, inspect exact Git state, and identify available execution environments. Do not infer environment capability from the host name.

### Orient

Map the task to one or two component IDs. Read their source roots, nearest tests and canonical contracts. Establish the first layer in the abstraction tower that could explain the issue.

### Decide

Write one falsifiable hypothesis and one acceptance boundary. Prefer the smallest change that can disprove the hypothesis quickly.

### Act

Change the narrowest complete ownership slice. Keep optimization admission fail-closed. Extend existing Java/FFM/Swift paths rather than introducing shadow control planes or duplicate native modules.

### Verify

Run the cheapest relevant gate first, then expand:

```text
static/schema -> focused unit -> integration/build -> GPU/native -> Minecraft E2E -> paired performance
```

Do not pay for later gates when an earlier gate already falsifies the candidate.

### Distill

Persist only knowledge that will remain useful after the branch disappears:

- stable contract/invariant -> canonical documentation or test;
- non-obvious long-lived design choice -> ADR under `docs/agent/decisions/`;
- reusable machine rule -> schema/registry/checker;
- rejected experiment -> exact commit/evidence in the retired-branch backlog if still useful;
- transient logs/screenshots -> generated evidence only, never canonical docs.

This keeps accumulated knowledge executable and searchable instead of turning the repository into a chronological notebook.

## 7. Evidence graph

Every performance or correctness conclusion should be reconstructible as:

```text
source SHA
 + binary/native identity
 + scenario identity
 + feature/admission activation
 + correctness result
 + performance result (when relevant)
 + artifact locations
 = decision
```

The decision is invalid if one required edge is missing. In particular:

- compilation is not runtime activation;
- activation is not correctness;
- correctness is not performance improvement;
- average FPS without paired blocks is not an optimization decision;
- a screenshot without semantic-pass linkage is diagnostic evidence, not a root-cause proof.

## 8. Plans are compiled intent, not alternate truth

The preferred long-term architecture is to make performance transformations consume immutable, inspectable plans derived from semantics instead of discovering policy ad hoc inside native hot paths.

A plan should contain only facts needed for execution/admission:

- stable pass/resource identities;
- access modes and hazard edges;
- attachment compatibility and load/store intent;
- resource liveness;
- pipeline/layout identity;
- batching/ICB eligibility;
- explicit acceptance or rejection reason codes.

The same plan should feed:

1. conservative execution;
2. optimized execution;
3. structured activation evidence;
4. diagnosis and tests.

This prevents the agent harness, Java renderer and Swift backend from independently re-deriving the same policy with subtly different rules.

## 9. Branches are work queues, not memory

The canonical development base is `integration/iris-metal-next`; `master` is the promoted stable tree. Task branches are bounded queues of work. Durable knowledge belongs in source/tests/contracts/ADRs, not in an ever-growing branch namespace.

Before closing a task:

- land accepted work through the required gates, or record any uniquely useful unlanded SHA in the retirement ledger;
- remove disposable branches after the human merge/retire decision;
- do not create archive branches as documentation.

The context tool should warn about policy drift, but it must not delete branches automatically.

## 10. Documentation lifecycle

Documentation has four classes:

- **canonical** — current system contracts and acceptance rules;
- **component reference** — narrow, current domain explanation;
- **ADR** — immutable reason for a long-lived architectural decision;
- **historical/advisory** — dated handoffs, superseded plans, migration notes.

The index in `docs/README.md` and registry classification determine authority. Do not infer authority from filename, date or directory alone.

When implementation invalidates a canonical statement, update the contract in the same change. When only historical context becomes stale, do not rewrite history; improve its classification or add a newer ADR/contract.

## 11. Design tests for agent-friendliness

A repository-level design change is agent-friendly only if it improves at least one of these without weakening another:

- **time-to-first-correct-file** — how quickly an agent reaches the right ownership slice;
- **context amplification** — useful facts obtained per token/file opened;
- **state recoverability** — ability to reconstruct current work from Git + structured evidence;
- **proof locality** — distance between changed behavior and the test/evidence that proves it;
- **semantic stability** — identities remain useful across backend/refactor changes;
- **failure specificity** — first failing layer and reason code are explicit;
- **knowledge compaction** — durable lessons become tests/contracts/ADRs rather than repeated prose;
- **branch/doc entropy** — obsolete work stops competing with current authority.

Optimize the repository for these properties before adding more autonomous machinery.
