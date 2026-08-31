# MetalUniversal agent system model

This document is the conceptual control plane for continued development. It exists to minimize reconstruction cost: an agent should discover current authority, understand the smallest relevant slice, predict the consequences of a diff, choose the cheapest sufficient proof, and resume interrupted work without reading the repository linearly.

MetalUniversal is not a bag of renderer features. It is one evidence-driven system with two coupled planes:

- **data plane** — Minecraft/Iris/Sodium semantics are lowered into explicit intent and executed by Metal;
- **control plane** — agents observe exact state, route work, calculate impact/proof obligations, execute bounded changes, and distill durable knowledge.

The control plane is never a second renderer truth. Source, executable contracts, exact identity and structured runtime evidence remain authoritative.

## 1. The abstraction tower

Reason from the top down and debug from the first broken layer down. Every layer has a narrow contract with the one below it.

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
L4  Java backend execution policy
    Metal 3/4 selection, encoder reuse, ICB, residency, fallbacks
        |
L5  Java / FFM ABI
    descriptors, ownership, nullability, symbol/version contracts
        |
L6  Swift / Metal execution
    MTL resources, encoders, command buffers, presentation
        |
L7  Evidence plane
    structured traces, counters, readback, timings, first divergence
        |
L8  Acceptance / promotion
    correctness -> activation -> exact-head CI -> physical proof when needed
    -> paired performance when claimed -> review -> promotion
```

Do not skip layers when diagnosing. A visual mismatch is first a semantic/evidence problem, not immediately a Swift problem. A native crash is first an ABI/lifetime problem. A performance regression is not actionable until correctness and activation are proved.

## 2. One semantic address space

Use identities that survive refactors and backend changes, in this order:

1. semantic pass ID;
2. generation-aware `ResourceIdentity`;
3. immutable plan/admission record;
4. stable pipeline/layout key;
5. source path + symbol.

Never use timestamps, native pointers, encoder ordinals, object addresses, log line numbers or shader-pack names as cross-run identity.

Preferred debugging join path:

```text
semantic pass
  -> resource generation
    -> hazard/liveness edge
      -> admitted backend transform
        -> Java execution record
          -> FFM descriptor/symbol
            -> Swift/Metal work
              -> structured evidence
```

Every important claim should be joinable along this path.

## 3. Authority model

When information conflicts:

1. shipping source, tests, schemas, generated manifests and exact Git/binary identity;
2. structured runtime evidence from that exact identity;
3. canonical design/acceptance documents named by `docs/agent/system-registry.json`;
4. handoffs, prompts, retired plans, migration records and historical prose.

A recent date does not outrank executable truth. `docs/agent/system-registry.json` is a router/contract graph, not a runtime fact database. Volatile state is generated from Git/evidence and must not be copied into architecture prose.

## 4. Context-budget model

Expand context only when the current layer cannot answer the question.

### Tier 0 — generated capsule

Run:

```bash
python3 scripts/agent/context.py --task "<short task description>"
```

The capsule should answer:

- current branch/HEAD/dirty state and diff base;
- direct component ownership of the diff;
- downstream impact closure and boundary contracts at risk;
- canonical documents/local `AGENTS.md` to read now;
- the cheapest sufficient proof ladder for the claim;
- recoverable checkpoint status, if present;
- repository-policy warnings.

Use `--since <ref>` only when the task intentionally has a different comparison base. Use `--claim performance|presentation|platform` when the requested conclusion is stronger than what task wording makes obvious.

### Tier 1 — system contracts

Read only root `AGENTS.md`, this document, and the unified evaluation contract when rendering correctness/performance is involved.

### Tier 2 — component slice

Read the routed component's local `AGENTS.md`, source roots, nearest tests and canonical docs. Do not preload unrelated Iris, terrain, MetalFX, iOS or historical documents.

### Tier 3 — evidence/history

Open raw traces, runtime artifacts, old handoffs, prompts and migration records only to resolve a concrete unresolved question.

Source navigation is cheap; broad chronological reading is expensive and commonly misleading.

## 5. System components and ownership

The machine-readable graph is `docs/agent/system-registry.json`. Stable conceptual components are:

- **product.semantics** — Minecraft/Iris/Sodium observable meaning;
- **render.plan** — immutable semantic graph, hazards, liveness and admission;
- **render.execution** — Java Metal command/resource execution;
- **native.abi** — Java FFM ↔ native descriptor/symbol/ownership contract;
- **native.execution** — Swift/Metal implementation below that ABI;
- **terrain.scene** — generation-owned terrain scheduling, visibility and GPU scene/ICB submission;
- **validation.contract** — backend-neutral oracle and first-divergence diagnosis;
- **evaluation.control** — agent routing, evidence, statistics and CI authority;
- **platform.mobile** — isolated iOS/Amethyst platform concerns sharing native/backend contracts.

A change that directly owns more than two components should be split unless the interface between those components is itself the task. Shared directories do not imply shared ownership: the registry uses more-specific path patterns and task semantics to route the diff.

## 6. Impact graph and proof closure

A source edit has two different sets of consequences:

- **direct ownership** — components whose contract/code is actually edited;
- **impact closure** — downstream components whose assumptions or observable behavior may change.

Do not make agents rediscover this graph from prose. Components declare `impact_targets`; named boundaries declare the contract and independent proofs required when a change can cross them.

Examples:

```text
product.semantics -> render.plan -> render.execution -> validation.contract -> evaluation.control
terrain.scene ---------------------> render.execution -> validation.contract
native.abi -> native.execution -----------------------> validation.contract
platform.mobile -> native.abi/native.execution
```

These arrows mean "a change here may invalidate assumptions/evidence there", not "the implementation must call through every node".

The proof planner computes a **proof closure**, not a maximal test suite. Each proof profile has a rank, environment, command/authority route, dependencies and a precise statement of what it proves. Run in increasing rank and stop when an earlier proof falsifies the candidate.

Canonical ladder:

```text
agent control
  -> repository/static contracts
    -> synthetic semantic oracle
      -> focused GPU/native contracts
        -> independent exact-head hosted CI
          -> Minecraft conformance/E2E
            -> physical presentation/device proof when the claim requires it
              -> paired performance only when performance is claimed
```

A later proof cannot substitute for an earlier semantic obligation. Conversely, do not run a physical/performance gate merely because it exists when the change is control-plane-only.

Important asymmetry: a changed validation oracle must independently prove itself with fixtures/self-tests before it is allowed to approve the same renderer candidate. A changed benchmark/analyzer likewise needs independent self-tests; otherwise the candidate and judge share one unverified failure mode.

## 7. The agent control loop

Use one closed loop:

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

### OBSERVE

Generate the capsule, inspect exact Git state and discover environment capability. Do not infer GPU/presentation capability from a runner label.

### ORIENT

Map the task and diff to direct components, inspect impact closure/boundaries, and locate the first abstraction layer able to explain the problem.

### DECIDE

Write one falsifiable hypothesis, one acceptance boundary and one rollback condition. Select the cheapest proof that can falsify it.

### ACT

Change the narrowest complete ownership slice. Admission remains fail-closed. Extend existing Java/FFM/Swift paths; do not add duplicate policy engines or native modules.

### VERIFY

Execute the generated proof ladder in order. On a semantic failure, diagnose the first divergent pass/resource/producer. Only broaden instrumentation when that focused evidence is insufficient.

### DISTILL

Persist durable knowledge in the narrowest executable form:

- invariant -> test or canonical contract;
- long-lived interface decision -> ADR;
- routing/proof rule -> registry/checker;
- runtime result -> structured evidence;
- uniquely useful rejected experiment -> exact SHA/evidence in retirement ledger;
- transient investigation -> generated state only.

## 8. Recoverable task state

Git preserves code, but not enough of an interrupted agent's working state. The repository therefore supports a small **ephemeral checkpoint** under ignored `build/agent-state/current.json`.

Create one after orientation when a task spans multiple edits/gates:

```bash
python3 scripts/agent/checkpoint.py init \
  --task "<task>" \
  --hypothesis "<falsifiable hypothesis>" \
  --next-command "<next cheapest action>"
```

Update it as evidence arrives:

```bash
python3 scripts/agent/checkpoint.py update \
  --status verifying \
  --check "static|pass|build/.../evidence.json" \
  --next-command "<next command>"
```

The checkpoint records start/current SHA, direct/impacted components, boundary/proof IDs, completed checks, blockers and next command. `context.py` surfaces it automatically and warns when its branch/HEAD no longer matches.

This state is deliberately **not committed**. It is a handoff/cache, not canonical truth. If a cloud environment will disappear, preserve the checkpoint with the same mechanism used for generated evidence or summarize its stable conclusions into the PR/commit/tests/contracts. Do not turn task-state churn into repository history.

## 9. Evidence graph

Every correctness/performance decision must reconstruct as:

```text
source SHA
 + binary/native identity
 + scenario identity
 + activation/admission
 + correctness result
 + performance result when claimed
 + artifact locations
 = decision
```

The unified runner already emits `run-manifest.json` plus correctness/admission/trial artifacts and `decision.json`. Reuse those artifacts. Do not create a second metrics database simply to make them easier to find; index/link them instead.

Invalid shortcuts:

- compilation != runtime activation;
- activation != correctness;
- correctness != performance improvement;
- one average FPS number != paired performance acceptance;
- screenshot similarity without semantic-pass linkage != root-cause proof;
- hosted native compilation != attended presentation/device proof.

## 10. Plans are compiled intent, not alternate truth

Performance transformations should consume immutable, inspectable plans derived from semantics instead of discovering policy ad hoc inside native hot paths.

A plan contains only execution/admission facts:

- stable pass/resource identities;
- access modes and hazard edges;
- attachment compatibility/load-store intent;
- liveness;
- pipeline/layout identity;
- batching/ICB eligibility;
- stable acceptance/rejection reason codes.

The same plan should feed conservative execution, optimized execution, activation evidence, diagnosis and tests. This prevents the harness, Java renderer and Swift backend from re-deriving policy differently.

## 11. Local agent guides

High-risk ownership roots contain deliberately short scoped `AGENTS.md` files. They declare local invariants, not duplicate global policy. A scoped guide may tighten rules but must not redefine canonical branches, authority order, acceptance semantics or proof thresholds.

Current guides cover:

- Java Metal render execution;
- render-contract validation;
- terrain scene/lifecycle;
- Swift/native execution;
- agent harness/evidence plane.

If a subsystem grows large enough that an agent repeatedly needs the same local knowledge, add a scoped guide only when those rules are stable and local. Do not create one as a task diary.

## 12. Branches are work queues, not memory

`integration/iris-metal-next` is the continued-development base; `master` is promoted stable. Task branches are bounded work queues. Durable memory lives in source/tests/contracts/ADRs, not an ever-growing branch namespace.

The context/control checks may report branch-policy drift but must never delete unrelated branches automatically. After the human merge/retire decision, dispose of the task branch according to root `AGENTS.md`.

## 13. Documentation lifecycle

Documentation classes:

- **canonical** — current contracts/acceptance;
- **component reference** — narrow current explanation;
- **ADR** — durable reason for a long-lived choice;
- **historical/advisory** — handoffs, prompts, superseded plans, migration notes.

Prompts are recipes, not authority. Historical docs are provenance, not state. When implementation invalidates canonical prose, update it in the same change. Do not rewrite old handoffs merely to make them look current.

## 14. Design tests for agent-friendliness

A repository-level design change is agent-friendly only if it improves at least one of these without weakening another:

- **time-to-first-correct-file** — how quickly the correct ownership slice is reached;
- **context amplification** — useful facts per token/file opened;
- **impact visibility** — downstream/boundary consequences are explicit before editing;
- **proof economy** — cheapest sufficient independent evidence is known in advance;
- **state recoverability** — interrupted work resumes from Git + checkpoint + evidence;
- **proof locality** — changed behavior and proving evidence are close;
- **semantic stability** — identities survive backend/refactor changes;
- **failure specificity** — first failing layer/reason is explicit;
- **knowledge compaction** — lessons become tests/contracts/ADRs/checkers rather than prose duplication;
- **branch/doc entropy** — obsolete work stops competing with current authority.

Optimize these properties before adding more autonomous machinery. The target is not maximum automation; it is minimum uncertainty per unit of agent attention and compute.
