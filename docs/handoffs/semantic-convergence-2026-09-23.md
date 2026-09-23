# 2026-09-23 semantic branch convergence

This dated handoff records the exact remote tips inspected for the move to the
Minecraft 26.3 `main` line. It is provenance, not a live branch inventory. A
future operator must query the remote again before changing any ref. The
comparison target at the start of this work was
`40b9b9bc97f005c8613683a31324d88f2501657f`; the final implementation
and CI identity are recorded on PR #80 and its exact-head workflow receipts.
Remote ref retirement does not remove or reset local worktrees; unrelated
checked-out branches and their working trees are outside this operation.

## Evidence rule

Git ancestry and patch equivalence identify candidates for inspection but do
not establish semantic equivalence. The decisions below also consider the
current owner, contract, tests, and version boundary. Old 26.2 code is not
bulk-merged into the 26.3 tree. Each divergent retired tip must remain
reachable from `research/modernization-backlog` before its remote ref is
deleted. The research anchor records history; its tree is not a product tree.

Hosted compilation and synthetic GPU tests establish their named contracts,
not physical display, shader-pack visual parity, iOS device operation, or
paired performance acceptance. Unavailable structured metrics remain
unavailable; no performance candidate is accepted in this convergence.

## Exact-tip disposition

| Remote ref at audit | Exact tip | Semantic disposition |
| --- | --- | --- |
| `agent/frame-profiler-round2` | `4daa489d9f080171d62d69fd7dbe4d9d31f84262` | 26.2 profiling and default-off ICB/hill-climb research; archive the tip, do not promote unpaired performance settings. |
| `agent/mc26.3-p0b-terrain-observability-20260920` | `4cfb19260db1c4e854a97f9d187957c0f9edf38d` | Old workflow only printed terrain source excerpts; the permanent reference workflow now fails on 26.3 target signature and Mixin drift. |
| `agent/minecraft-26.3-renderpearl-migration-20260916` | `a52b95018ad8d0b113a8819682e54172dd19cc45` | Old terrain-work v1 schema and fixtures are superseded by v2 world epoch, mesh-generation, first-valid-draw, drop/overflow, and chronology checks. |
| `chatgpt/replay-iris-correctness-20260830` | `566c69f56189a8b4e80dc773de4fe37fcd0effc8` | One distinct 26.2 advanced-optimization correctness commit plus replay-parser/one-shot CI history. Do not bulk-merge it into 26.3 or enable its default-off experiments without current conformance. Preserve the exact tip for focused runtime-invariant follow-up. |
| `codex/bsl-pass-alignment-predevice-20260831` | `a946ffa5fab341af8c81ebe47b80c9a8137a89a2` | Old BSL pass/target research. The fixed alpha-test phase and default shadow draw-buffer attachment bug are corrected in current owners with focused tests. Physical shader-pack parity remains unverified. |
| `codex/frame-delivery-window-20260921` | `37e7f145570abd76f4c690e0b9080305bc9b51f1` | Already an ancestor of the convergence target; retain through `main` ancestry. |
| `codex/frame-evidence-contract-v1-20260920` | `969bf537d4ffd2b99a75f71f17e40fa2b58fd42c` | Already an ancestor; its frame-evidence contract and PR base remain in `main` ancestry. |
| `codex/frame-window-clock-coverage-20260922` | `448f0ddd258d05523299d23d6022544eba0a5df6` | Earlier Iris gate and source-frame/pipeline attribution are covered by the current central gate and segmented frame-evidence owner; physical acceptance remains separate. |
| `codex/iris-feature-gate-20260830` | `6167201bb47e2863f44a76300a3b1b515b63bc00` | Earlier BSL correctness and optimization gate. Current generation-aware cleanup supersedes old texture/compute cleanup; fusion validates attachment keys before publishing a plan and rejects incompatible groups. Shadow storage images require a raw physical view, which is corrected in the current owner. Preserve the exact tip. |
| `codex/mc26.3-p0b-evidence-closure-20260920` | `188cde30f13c0b68147bd5ad755e24d1d1b04ded` | Already an ancestor; current evidence closure retains its history. |
| `codex/mc26.3-spec-continuation-20260920` | `31bfc790c0a89ca9f0f1f38d2da7e605bb0e8d7e` | Current generation lifecycle is fail-closed; unique process-memory sampler and optional terrain/resource diagnostics stay in research. Peak resident memory is unavailable in the 26.3 normalizer, so its performance guardrail cannot pass until sampling is adapted. |
| `codex/mc26.3-vanilla-performance-convergence-20260921` | `9e2fac7f315a1acd3e405614c0b96cb57203ea3f` | Exact-head source-object receipt is already covered by current full-depth checkout, source bundle, and structured CI identity. |
| `codex/vision-cloud-convergence-20260921` | `5c034a16662cb237fd896cf632f35fb887661c51` | Current ownership recorder and background-thread PSO smoke cover the invariant. The older environment-limited downgrade is intentionally not an acceptance pass. |
| `codex/vision-cloud-convergence-20260922` | `bb8ce602e82abaa7c0ba4155cd80f3c97574a238` | Immutable seven-scenario balanced physical workload protocol remains inspectable at this tip. Current workload/trial tools have window and identity checks, but protocol equivalence is not established; no performance result is inherited. |
| `codex/vision-cloud-convergence-followthrough-20260921` | `ebb4dc75fa1e320a349273687059308080e9c6c4` | Earlier zero-receipt and chronological-tail contract. Current evidence distinguishes absent presentation from invalid timestamps and has segmented archives; exact tail equivalence remains a validation boundary, so no physical result is inherited. |
| `convergence/main-unification-20260923` | `40b9b9bc97f005c8613683a31324d88f2501657f` | Current PR #80 head at audit; final convergence commit is promoted through the PR to `main`. |
| `feature/ios-amethyst-runtime` | `d1e30d03dc2b02c8a015eba364ba0101572b3312` | 26.2 GLFW/Amethyst launcher lineage is incompatible with the 26.3 SDL client. The common tree retains iOS native/SPVC compilation; its odd-width glyph upload row-packing intent is reimplemented and tested. Device/launcher proof remains separate. |
| `integration/iris-metal-next` | `4c7309ea7b544c4cc21dc1875c57c57d710dd6b9` | Earlier 26.2 Iris/Metal line. Native-table authority is corrected in current admission. Async GPU tail, fixed drawable, terrain ICB producer accounting, and optional parallel MSL prewarm are not accepted 26.3 performance evidence; preserve the exact tip for focused follow-up. The unwired MetalFX color-transfer helper is not visual proof. |
| `master` | `cf4e66250c8fd3bcf6fdf58e00e0cd841052cd58` | Already an ancestor of the convergence target. It is the old 26.2 default, not the 26.3 product line. |
| `research/modernization-backlog` | `5870a7f01ce6662e0b51e5b15c65a9cdaef03337` | Retain as the sole history anchor; append divergent retired tips with a same-tree merge commit. |
| `task/ios-glfw-input-mode-proof-20260903` | `97f5b86e890d22e1cf756e72d6101f655c4cb234` | Old GLFW input workflow atop iOS lineage; it does not gate 26.3 SDL. Preserve exact tip before retirement. |

## Proof identity and remaining boundaries

The final `main` merge SHA, PR head SHA, workflow receipts, and history-anchor
tip are volatile. Resolve them from GitHub and Git at the time of use. Before
ref retirement, verify PR #80 is merged, all required CI checks are green,
every divergent tip above is reachable from the research anchor, and every
remote ref still matches the recorded exact tip. The 26.3 terrain source
checker runs after reference materialization in the permanent CI workflow;
its local self-test alone is not source validation. The current unified
performance normalizer has no structured peak resident-memory sample and
therefore cannot establish the memory guardrail or accept a performance
candidate. iOS native compilation does not establish launcher or device
runtime acceptance.
