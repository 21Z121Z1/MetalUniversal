# MetalUniversal agent system model

This document is the conceptual control plane for continued development. Its purpose is not to describe every implementation detail. Its purpose is to let a new agent reconstruct the smallest correct world model, predict what a change can invalidate, choose the cheapest sufficient proof, and resume interrupted work without reading the repository linearly.

MetalUniversal is one evidence-driven system. It should be understood through three mutually reinforcing structures:

1. a **vertical abstraction tower** that explains what each renderer layer means and owns;
2. a **horizontal agent control loop** that explains how work moves from observation to verified change;
3. a set of **persistent machine-readable graphs** that connect ownership, impact, proof and durable rationale.

The control plane is never a second renderer truth. Source, executable contracts, exact source/binary identity and structured runtime evidence remain authoritative.

## 1. The abstraction tower

Reason from the top down and debug from the first broken layer down. Every layer has a deliberately narrow contract with the one below it.

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
    correctness -> activation -> exact-head independent proof
    -> physical/device proof when required
    -> paired performance when claimed
    -> review -> promotion
```

Do not skip layers when diagnosing. A visual mismatch is first a semantic/evidence problem, not immediately a Swift problem. A native crash is first an ABI/lifetime problem. A performance regression is not actionable until correctness and activation are established.

The tower is also the preferred interface direction. Upper layers define meaning; lower layers execute admitted intent. Lower layers may report capability and evidence upward, but they should not independently re-invent semantic policy.

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

Every important correctness/performance claim should be joinable along this path. If an observation cannot be joined back to stable semantic identity, it is diagnostic context rather than final proof.

## 3. The agent's world model

A capable agent should not reconstruct the repository from prose every time. It should compile a task-local world model from four inputs:

```text
exact Git state
 + machine-readable component/boundary graph
 + task intent
 + exact-SHA evidence/checkpoint
 = task-local world model
```

That model should answer five questions before implementation:

1. What is known as fact right now?
2. Which component owns the behavior being changed?
3. Which downstream contracts can this change invalidate?
4. What claim is actually being made?
5. What is the cheapest independent evidence that can prove or falsify that claim?

`scripts/agent/context.py` is the compiler for this view. `docs/agent/system-registry.json` is its control graph. Neither may become a duplicate renderer implementation or a manually maintained runtime status database.

## 4. Epistemic labels

Agents make fewer errors when facts and inferences are visibly different.

Use these classes:

- **fact** — exact Git identity, changed path, executable source/test/schema, exact-SHA structured evidence;
- **computed fact** — deterministic result from facts and declared machine rules, such as path-derived component ownership;
- **inference** — task-text routing, impact closure, suspected root cause, expected performance effect;
- **claim** — the conclusion the task wants to establish: control-plane correctness, renderer correctness, presentation, platform runtime or performance;
- **historical context** — prompt, handoff, retired branch/plan, old report;
- **unknown** — information not established by current source/evidence.

The context capsule therefore distinguishes **changed-component ownership (path-derived fact)** from **planned component route (task-derived inference)**. An agent must not present a task-keyword match as if a source edit already proved ownership.

When evidence is bound to another SHA, it is stale evidence. It may guide investigation but cannot satisfy the current proof obligation.

## 5. Authority model

When information conflicts:

1. shipping source, tests, schemas, generated manifests and exact Git/binary identity;
2. structured runtime evidence from that exact identity;
3. canonical design/acceptance documents named by `docs/agent/system-registry.json`;
4. handoffs, prompts, retired plans, migration records and historical prose.

A recent date does not outrank executable truth. The registry is a router/contract graph, not a runtime fact database. Volatile state is generated from Git/evidence and must not be copied into architecture prose.

Prompts are recipes. Branch names are work locations. Neither is durable truth.

## 6. Context-budget model

Expand context only when the current layer cannot answer the question.

### Tier 0 — generated capsule

Run:

```bash
python3 scripts/agent/context.py --task "<short task description>"
```

The capsule should answer:

- current branch/HEAD/dirty state and diff base;
- whether ownership is path-derived fact, task-derived inference or unresolved;
- changed/planned components and downstream impact closure;
- boundary contracts at risk;
- canonical documents/local `AGENTS.md` to read now;
- conceptual proof obligations;
- the minimum execution schedule after integrated gates are collapsed;
- recoverable checkpoint status, if present;
- repository-policy warnings.

Use `--since <ref>` only when the task intentionally has a different comparison base. Use `--claim performance|presentation|platform` when the requested conclusion is stronger than the task/diff implies.

### Tier 1 — system contracts

Read only root `AGENTS.md`, this document, and the unified evaluation contract when renderer correctness/performance is involved.

### Tier 2 — component slice

Read the routed component's local `AGENTS.md`, source roots, nearest tests and canonical docs. Do not preload unrelated Iris, terrain, MetalFX, iOS or historical documents.

### Tier 3 — evidence/history

Open raw traces, runtime artifacts, old handoffs, prompts and migration records only to resolve a concrete unresolved question.

Source navigation is cheap; broad chronological reading is expensive and commonly misleading. The repository should expose a map, not force the agent to carry an encyclopedia in context.

## 7. System components and ownership

The machine-readable graph is `docs/agent/system-registry.json`. Stable conceptual components are:

- **product.semantics** — Minecraft/Iris/Sodium observable meaning and integration surface;
- **render.plan** — immutable semantic graph, hazards, liveness and admission;
- **render.execution** — Java Metal command/resource execution;
- **native.abi** — Java FFM ↔ native descriptor/symbol/ownership contract;
- **native.execution** — Swift/Metal implementation below that ABI;
- **terrain.scene** — generation-owned terrain scheduling, visibility and GPU scene/ICB submission;
- **validation.contract** — backend-neutral oracle and first-divergence diagnosis;
- **evaluation.control** — agent routing, evidence, statistics and CI authority;
- **platform.mobile** — isolated iOS/Amethyst platform concerns sharing native/backend contracts.

Ownership must be conservative. When a file under a high-risk execution root is not clearly a narrower plan/ABI/control object, route it to the execution component with the stronger proof obligations rather than guessing a cheaper semantic category.

This is why renderer file names such as `IrisMetal*Runtime.java` are not automatically classified as product semantics. Names are hints; ownership is a contract.

A change that directly owns more than two components should be split unless the interface between those components is itself the task.

## 8. Impact graph and proof closure

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

The proof planner first computes a **logical proof closure**. This answers: what evidence obligations must be satisfied for the proposed claim? It is intentionally independent of how many commands are required.

A changed validation oracle must independently prove itself with fixtures/self-tests before it can approve the same renderer candidate. A changed benchmark/analyzer likewise needs independent self-tests; otherwise candidate and judge share one unverified failure mode.

## 9. Proof obligations vs execution schedule

Proof correctness and execution economy are different problems.

A proof graph can be logically complete while still wasting compute if the agent executes every node separately. Several repository entry points intentionally integrate lower gates. For example, the unified conformance/full runner performs prerequisite static/GPU/synthetic checks before the expensive client decision.

Therefore each proof profile declares:

- `depends_on` — logical evidence dependencies;
- `covers` — lower proof artifacts produced inside this executor;
- `always_preflight` — a deliberately retained cheap fail-fast check;
- `cost_class` — relative resource class, not a performance benchmark.

The context compiler emits two views:

1. **proof obligations** — complete logical closure;
2. **minimum execution schedule** — commands that satisfy that closure without re-running integrated gates, while retaining cheap preflight.

Canonical pattern:

```text
cheap preflight
  -> minimum independent acceptance executor(s)
  -> exact-head independent CI where required
  -> physical/device proof only for claims that need it
```

Example for a performance change in Java renderer execution:

```text
logical obligations:
agent.control
repo.static
render.synthetic
render.gpu
minecraft.conformance
hosted.exact-head
minecraft.e2e
performance.paired

minimum execution schedule:
repo.static                  # retained fail-fast preflight
performance.paired           # integrates local correctness prerequisites
hosted.exact-head            # independent environment
minecraft.e2e                # production-client path
```

The goal is not the fewest commands at any cost. The goal is the least expected compute while preserving early falsification and independent evidence.

A later proof cannot erase an independent-environment obligation merely because it is more expensive.

## 10. World-model regression tests

The control plane itself is software and needs conformance tests.

`docs/agent/routing-fixtures.json` contains representative path/task/execution cases. It protects properties such as:

- `IrisMetal*Runtime.java` defaults to `render.execution`, not a cheaper semantic bucket;
- explicit plan/lifetime compiler classes route to `render.plan`;
- Java FFM bridge and Swift implementation remain distinct;
- control-plane edits remain control claims even when their prose discusses performance;
- integrated performance/conformance commands collapse redundant sub-gates without dropping independent CI/E2E obligations.

When a new subsystem, boundary or proof profile is introduced, add a representative fixture if a future routing regression could cause under-proof or large compute waste.

A graph change that cannot survive representative routing fixtures is not agent-friendly merely because its prose sounds coherent.

## 11. The agent control loop

Use one closed loop:

```text
OBSERVE -> ORIENT -> DECIDE -> ACT -> VERIFY -> DISTILL
```

### OBSERVE

Generate the capsule, inspect exact Git state and discover environment capability. Do not infer GPU/presentation capability from a runner label.

### ORIENT

Separate path-derived ownership facts from task-derived routing inference. Inspect impact closure/boundaries and locate the first abstraction layer able to explain the problem.

### DECIDE

Write one falsifiable hypothesis, one acceptance boundary and one rollback condition. Select the cheapest proof that can falsify it.

### ACT

Change the narrowest complete ownership slice. Admission remains fail-closed. Extend existing Java/FFM/Swift paths; do not add duplicate policy engines or native modules.

### VERIFY

Run the generated minimum execution schedule in order. On a semantic failure, diagnose the first divergent pass/resource/producer. Only broaden instrumentation when focused evidence is insufficient.

### DISTILL

Persist durable knowledge in the narrowest executable form:

- invariant -> test or canonical contract;
- long-lived interface decision -> ADR;
- routing/proof rule -> registry + routing fixture/checker;
- runtime result -> structured exact-SHA evidence;
- uniquely useful rejected experiment -> exact SHA/evidence in retirement ledger;
- transient investigation -> generated state only.

## 12. Recoverable task state

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

Each recorded check is bound to the source SHA at which it ran. After HEAD changes, old check results become stale and cannot silently satisfy new obligations.

The checkpoint is deliberately **not committed**. It is a handoff/cache, not canonical truth. If a cloud environment will disappear, preserve it with generated evidence or distill stable conclusions into the PR/commit/tests/contracts.

## 13. Evidence graph

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
- hosted native compilation != attended presentation/device proof;
- PASS on another SHA != PASS on current HEAD.

## 14. Plans are compiled intent, not alternate truth

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

## 15. Local agent guides

High-risk ownership roots contain deliberately short scoped `AGENTS.md` files. They declare local invariants, not duplicate global policy. A scoped guide may tighten rules but must not redefine canonical branches, authority order, acceptance semantics or proof thresholds.

Current guides cover:

- Java Metal render execution;
- render-contract validation;
- terrain scene/lifecycle;
- Swift/native execution;
- agent harness/evidence plane;
- documentation authority/lifecycle.

Use nesting to give agents local rules where they are needed. Do not turn scoped guides into subsystem encyclopedias or task diaries.

## 16. Knowledge accumulation is compilation

The system should become easier to drive after every completed task, not merely larger.

A reusable lesson is considered accumulated only when it has been compiled into one of these durable forms:

```text
new invariant            -> executable test / canonical contract
new ownership rule       -> registry + routing fixture
new boundary rule        -> named boundary + proof obligation
new proof rule           -> proof profile + verifier/self-test
long-lived design reason -> ADR
runtime observation      -> structured exact-SHA evidence
transient progress       -> ignored checkpoint
```

Chronological prose is the least preferred durable format because it is expensive to search, hard to validate and easy to confuse with current state.

This creates a flywheel:

```text
task
 -> verified result
   -> distilled rule/test/ADR
     -> stronger world model
       -> lower cost and error rate for the next task
```

That flywheel is the primary criterion for "agent-native" architecture.

## 17. Branches are work queues, not memory

`integration/iris-metal-next` is the continued-development base; `master` is promoted stable. Task branches are bounded work queues. Durable memory lives in source/tests/contracts/ADRs, not an ever-growing branch namespace.

The context/control checks may report branch-policy drift but must never delete unrelated branches automatically. After the human merge/retire decision, dispose of the task branch according to root `AGENTS.md`.

## 18. Documentation lifecycle

Documentation classes:

- **canonical** — current contracts/acceptance;
- **component reference** — narrow current explanation;
- **ADR** — durable reason for a long-lived choice;
- **historical/advisory** — handoffs, prompts, superseded plans, migration notes.

Canonical prose should explain stable intent and contracts, not copy volatile status. Machine-readable data should describe things agents must calculate mechanically. Tests/checkers should guard rules that must not silently regress.

When implementation invalidates canonical prose, update it in the same change. Do not rewrite old handoffs merely to make them look current.

## 19. Design tests for agent-friendliness

A repository-level design change is agent-friendly only if it improves at least one of these without weakening another:

- **time-to-first-correct-file** — how quickly the correct ownership slice is reached;
- **context amplification** — useful facts per token/file opened;
- **epistemic clarity** — facts, computed facts, inference, claims and stale evidence are visibly distinct;
- **impact visibility** — downstream/boundary consequences are explicit before editing;
- **proof economy** — logical completeness is preserved while integrated commands avoid duplicate compute;
- **state recoverability** — interrupted work resumes from Git + checkpoint + evidence;
- **proof locality** — changed behavior and proving evidence are close;
- **semantic stability** — identities survive backend/refactor changes;
- **failure specificity** — first failing layer/reason is explicit;
- **world-model testability** — ownership/proof routing has regression fixtures;
- **knowledge compaction** — lessons become tests/contracts/ADRs/checkers rather than prose duplication;
- **branch/doc entropy** — obsolete work stops competing with current authority.

Optimize these properties before adding more autonomous machinery.

The target is not maximum automation. It is **minimum uncertainty and minimum duplicated work per unit of agent attention and compute, subject to complete independent proof**.
