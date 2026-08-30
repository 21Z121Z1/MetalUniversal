# Retired branch backlog

This document is the recovery map for work intentionally removed from the branch namespace during the 2026-08-30 consolidation. A deleted branch is not lost: its exact tip is listed below and every unique retired tip is reachable from `research/modernization-backlog`.

The active development rule is to re-implement or selectively cherry-pick still-relevant work onto a fresh bounded branch from `integration/iris-metal-next`, validate it against the current tree, merge it, and delete that bounded branch. Do not resurrect the old branch namespace.

## Highest-priority unlanded work

- **Correctness first:** PR #39 (`fix/p2-mrt-store-liveness-review`) contains the attachment-local MRT deferred-store fix. Port its product/test delta before more aggressive load/store, memoryless, pass-fusion or aliasing work.
- **Shadow correctness:** PR #38 (`fix/bsl-shadow-attachment-format`) was still primarily a reproduction/diagnostic lane; implement the real authoritative shadow render-pass/pipeline attachment-format fix fresh on the current integration tree.
- **Terrain ownership/lifetime:** PR #24 (`fix/metal4-arena-lifetime`) contains useful `TerrainMeshGeneration` generation ownership and Sodium arena-lifetime protection. Selectively port those pieces; do not wholesale merge its old renderer/cache changes.
- **Iris/TBDR resource stack:** PRs #44, #45, #47, #49, #50, #52 and #54 cover constrained memoryless allocation, residency of pipeline allocations, single argument-table authority, exact attachment death points, generation-safe alias recipes/runtime and placement-heap execution. Re-land in dependency order after the MRT correctness fix.
- **GPU terrain submission:** PRs #42, #46, #48, #51, #55 and #57 cover sparse visible ICB authoring, persistent terrain scene, frame-slot scratch reuse, draw authority, self-contained admission and fused visibility+ICB authoring. Treat this as one stacked experimental line and revalidate on physical Metal 4 before default-on promotion.
- **Pipeline compilation:** PR #56 parallelizes stable MSL artifact lookup/prewarm; PR #58 removes structurally duplicate Metal 4 depth/stencil PSO variants. These are good bounded follow-ups after correctness convergence.
- **Semantic alternate architecture:** PR #25 is a design/migration source only. Mine tests/contracts and current-valid semantics; do not wholesale merge the divergent old tree.

## Retired branch tips

`master-ancestor` means the exact branch tip was already reachable from `master` at consolidation time. `history-preserved-only` means it was not in the promoted tree and is preserved only through the research history anchor for selective recovery.

| Retired branch | Exact tip | Status at consolidation |
| --- | --- | --- |
| `agent/metal-eval-v3` | `407f0d92db80d5d17b4c04548448dd55d159c44a` | master-ancestor |
| `archive/metal-iris-beta-2026-08-02` | `a725ce98ee90d64900af4c7f5d0983877969e3cb` | history-preserved-only |
| `archive/metal4-geometry-handoff-2026-07-28` | `9e743f6a026a749b8b581bffea0303ceb9ce151c` | master-ancestor |
| `archive/metalfx-v1-prototype-2026-08-02` | `6de5bd9842f270a7ffe648c93ee49a1036c0d0ef` | history-preserved-only |
| `chore/p2-stage-decision` | `2d1db558e9465d08560ca585cbfea80d66215d08` | master-ancestor |
| `ci/minecraft-client-e2e-20260815` | `a3fb6819225571e350040034c8629878457387ee` | history-preserved-only |
| `ci/minecraft-client-e2e-p0-20260819` | `4e6be7b27edbed43731dc5ddee58ab28c9f2cd9a` | master-ancestor |
| `codex/amethyst-ios-runtime-262` | `c0a463afc64c6089b98d6db1ba77ba0e9214efa8` | history-preserved-only |
| `codex/apple-gpu-native-foundation-20260824` | `4d3179537ccb35c09858cc9b1bfc22836f2b7505` | history-preserved-only |
| `codex/argument-snapshot-pipeline-owned-20260829` | `bb445ff69fb78a89bb765626d43a225e3c88cfbe` | history-preserved-only |
| `codex/argument-table-runtime-audit-20260829` | `54e6b7bc38b3197da48159f85051877cd382a7d5` | history-preserved-only |
| `codex/autonomous-metal-next` | `d2a44452c1e6b2bf395f23c6923af177e4a30af3` | history-preserved-only |
| `codex/autonomous-metal-next-20260813` | `292b7bb070504c2addb7e26b9d73c32f2ff48f01` | history-preserved-only |
| `codex/bsl-metalfx-framegen-cutout-20260813` | `5a65e59f42d7e6d2438a492425a19781e1bfdba6` | history-preserved-only |
| `codex/iris-argument-table-authority-20260829` | `6ef54c6466b6a102bd441e0a721df5f182f22bb8` | history-preserved-only |
| `codex/iris-attachment-last-use-20260829` | `2ce1eacfcfce152697b84b4a65e51d438ba19876` | history-preserved-only |
| `codex/iris-attachment-last-use-clean-20260829` | `7235052aff745e07ae2599c6bc35e4d2661c89c4` | history-preserved-only |
| `codex/iris-heap-alias-recipe-20260829` | `b8a7038fdd9c0fa24dc80da77e90fef84ff7c65e` | history-preserved-only |
| `codex/iris-heap-alias-runtime-clean-20260829` | `4790ddad449d636e4bc6d8e437fd0308fb9f1794` | history-preserved-only |
| `codex/iris-memoryless-allocation-clean-20260829` | `d86eb8368dcd5a247b10d76e945cad7742497c93` | history-preserved-only |
| `codex/iris-memoryless-allocation-seam-20260829` | `4e4f644a8c7ed6e00d16ad3499e86a1b508b6c07` | history-preserved-only |
| `codex/iris-placement-heap-alias-20260829` | `b8a7038fdd9c0fa24dc80da77e90fef84ff7c65e` | history-preserved-only |
| `codex/iris-placement-heap-alias-staging-20260829` | `0887244de2b114139b0d1e31b259d05cdf311ed9` | history-preserved-only |
| `codex/iris-placement-heap-execution-clean-20260829` | `854ea820a602f5fd77a67c661761a2b125689dc0` | history-preserved-only |
| `codex/iris-placement-heap-execution-staging-20260829` | `32990f5fb630d08aa53479140b5d0ae1fbe9202c` | history-preserved-only |
| `codex/iris-transient-attachment-classification-20260829` | `28f83636f80912759ee86ad255f85d3f8ce5224f` | history-preserved-only |
| `codex/iris-transient-attachment-classification-clean-20260829` | `4d3179537ccb35c09858cc9b1bfc22836f2b7505` | history-preserved-only |
| `codex/master-merge-resolved-20260830` | `d11ce93b87a7f148c091551858b720055879b3da` | history-preserved-only |
| `codex/master-merge-staging-20260830` | `98c9867c31e5aa513666bee154bcbf82a37d68cd` | history-preserved-only |
| `codex/master-promote-validated-20260830` | `4ff2375dbdcfe635a079765a898601d7b3f276ee` | master-ancestor |
| `codex/metal-modernization-continuation-20260830` | `40fa3f083915c3b6d658562e7e9d02b2f5958513` | history-preserved-only |
| `codex/metal4-attachment-invariant-pso-20260829` | `52cc32fd8860e1a8ccff10a36a208f3c92d1feee` | history-preserved-only |
| `codex/metal4-attachment-invariant-pso-staging-20260829` | `fb9fed116054a410955b337dcfbbb9a6e076ec40` | history-preserved-only |
| `codex/metal4-flexible-pso-sdk-probe-20260829` | `d8397d58597dc4d1a47a40dc1dfe423d5002b7ff` | history-preserved-only |
| `codex/metal4-flexible-pso-staging-20260829` | `b6309fe808dcb3b07e8280132f2e71473a8e1479` | history-preserved-only |
| `codex/minecraft262-render-audit-20260829` | `6813edacf3ade1b82e80a2c0a8c67f0b149a4d06` | history-preserved-only |
| `codex/p1-binding-shadow-safe` | `4a5eb81859f8191b5dd7d4d2dd07a7a42ad0c0b3` | history-preserved-only |
| `codex/p1-uniform-token-ci` | `3da08b247db22ecc75a19519d3506d029680c9a4` | history-preserved-only |
| `codex/placement-heap-integration-staging-20260830` | `ff9bbbfc72107e0817f172016725c96d37b9ae03` | history-preserved-only |
| `codex/pso-artifact-parallel-prewarm-clean-20260829` | `14157dd0ea5eb72cb1c162f0c2df8cbbe5eab790` | history-preserved-only |
| `codex/pso-cache-hit-parallel-prewarm-staging-20260829` | `76438c0e86f997fcd10b99b9a0b260e805423222` | history-preserved-only |
| `codex/real-client-acceptance-20260830` | `e15cd3c6480a58f11ff02534e1bb9bd3adf4d057` | master-ancestor |
| `codex/real-client-replay-staging-20260830` | `cbcf31f23fac5998c9d5217ef0d332cc5d6f2bb4` | history-preserved-only |
| `codex/terrain-fused-visible-icb-canonical-20260829` | `a0010490941eb43926e830308ce87793612fe5d5` | master-ancestor |
| `codex/terrain-fused-visible-icb-clean-20260829` | `14e1fc77a2f4e4b20e1fd6c9097ffc6137072724` | history-preserved-only |
| `codex/terrain-fused-visible-icb-staging-20260829` | `6c605c6a114469f86796bd187f8c49a6433e149a` | history-preserved-only |
| `codex/terrain-persistent-gpu-scene-20260829` | `dc860c591b2326c3a775ffd7af356df9e066d932` | history-preserved-only |
| `codex/terrain-persistent-gpu-scene-clean-20260829` | `2b7f45eaafde9a3fa736fde0a002cad521f90d63` | history-preserved-only |
| `codex/terrain-scene-frame-slot-ring-20260829` | `455002ca1bcdfcf6506c38b0e4ce84b6e5657fbd` | history-preserved-only |
| `codex/terrain-scene-frame-slot-ring-clean-20260829` | `356985ecd9cef7bff2d6b08979209753896a3b64` | history-preserved-only |
| `codex/terrain-visibility-pso-specialization-20260829` | `b6c6c24bed140779fa7c73cc2bb63bc1d8dd79a7` | history-preserved-only |
| `codex/terrain-visible-bitset-only-staging-20260829` | `6c8748334c65b14ecaf639f6e4593735f1f0977b` | history-preserved-only |
| `codex/terrain-visible-candidate-index-20260829` | `a00b014da3b37e86b3f5089302c6d8e0402db192` | history-preserved-only |
| `codex/terrain-visible-gpu-icb-20260829` | `24b1ecccc28d188f67678289d8a6e5ce51ce0daf` | history-preserved-only |
| `codex/terrain-visible-gpu-icb-clean-20260829` | `d8bccaa9b06b7e3d93f031574534f797922080a7` | history-preserved-only |
| `codex/terrain-visible-icb-authority-fix-20260829` | `b6336623a2a1e72363610986d22b9f1414a1be77` | history-preserved-only |
| `codex/terrain-visible-icb-authority-fix-staging-20260829` | `6d6c78658490f397dc4f5dc6b0d602c9ba32fb50` | history-preserved-only |
| `codex/terrain-visible-icb-barrier-20260829` | `a00b014da3b37e86b3f5089302c6d8e0402db192` | history-preserved-only |
| `codex/terrain-visible-icb-modernize-20260829` | `9886e670aead691e56da22f28cac6a2a15993a15` | history-preserved-only |
| `codex/terrain-visible-icb-optimize-20260829` | `c58dc6a8f6ce3288c23e35eeaebbcf50463019bc` | history-preserved-only |
| `codex/terrain-visible-icb-optimize-v2-20260829` | `7f03f188d204b723daf120418bfe46bcd0c76825` | history-preserved-only |
| `codex/terrain-visible-native-fixture-staging-20260829` | `8c0d0b6bbe1db42b912a45b339ea0fa729a2a5b4` | history-preserved-only |
| `codex/terrain-visible-no-readback-staging-20260829` | `1658b3ee7e9f59d6939787a056e9729c8f2c81a6` | history-preserved-only |
| `codex/terrain-visible-self-contained-gate-clean-20260829` | `72b561211c4299ff6dd876eb713073eb3f808e72` | history-preserved-only |
| `codex/terrain-visible-self-contained-gate-staging-20260829` | `eb46e36a3274216b270bde94c555fec85a079968` | history-preserved-only |
| `feature/iris-semantic-completion` | `82176724974b3cd0d95f0ec9760eb4e3dd1f7f93` | history-preserved-only |
| `feature/metal4-main-production` | `8f045147bce00ac2b6d064af8026339c9b557458` | master-ancestor |
| `feature/metalfx-framegen-contracts` | `89b9f584acd0c91fadfd468380e6b72ed9893026` | history-preserved-only |
| `feature/p2-consumer-store-liveness` | `f83c62e8eb693170759a222342bb4f427e9f069d` | master-ancestor |
| `feature/p2-deferred-color-store-suppression` | `f6aa75a51784f4d4bfe438ec7c882f20ce34d3a3` | master-ancestor |
| `feature/p2-tbdr-attachment-compiler` | `9ebac773f0e04ad4dbd0910612cf18c42b4f5fc5` | master-ancestor |
| `feature/p3-token-native-bulk-patch` | `bc3c140e9a55fb48826875d1b352caf00ef658a4` | master-ancestor |
| `feature/p3b-token-paired-performance` | `4b050e7b688910778aad780ddc8dfffe37cbce8e` | master-ancestor |
| `feature/token-native-private-bindings` | `de9d274a997721979f26afc783e4ec0cd1c95323` | history-preserved-only |
| `fix/bsl-shadow-attachment-format` | `b7665a96c6b0690cfa8c455bc7e4ff16492b70f6` | history-preserved-only |
| `fix/ci-canonical-push-authority-gate` | `d2c9622483b21255cedb0f72dd6273a384a4a2c1` | master-ancestor |
| `fix/ci-decouple-pr-merge-gate` | `5d0acabd6359fe14952d567aef2888a9649a2b89` | master-ancestor |
| `fix/metal-allocation-residency-20260829` | `40c71d9a50d7a48c7989e9a94f063a0d7c061228` | history-preserved-only |
| `fix/metal-allocation-residency-clean-20260829` | `4e71f282181eeda9af53cc528580ffd6cfd71c95` | history-preserved-only |
| `fix/metal4-arena-lifetime` | `82bf939701e17e1cc699b38c6ad83ae605c6605a` | history-preserved-only |
| `fix/p2-mrt-store-liveness-review` | `02eedff6364e43fd018def5f008d3d5bfa567082` | history-preserved-only |
| `perf/swift-performance-by-design-20260819` | `e9e1a8900e4e88b9a904b6a07db3e0e22129792d` | master-ancestor |
| `perf/swift-performance-by-design-final-20260819` | `e9e1a8900e4e88b9a904b6a07db3e0e22129792d` | master-ancestor |
| `perf/swift-performance-by-design-final-validation-20260819` | `e9e1a8900e4e88b9a904b6a07db3e0e22129792d` | master-ancestor |
| `perf/swift-performance-by-design-ready-20260819` | `e9e1a8900e4e88b9a904b6a07db3e0e22129792d` | master-ancestor |
| `research/iris-semantic-contracts-alt` | `88a242b3f10a0632c6f52927c1affde70f2a0e0f` | history-preserved-only |
| `tooling/minecraft-reference-26.2` | `4935ce723d13ccb0a5a1da6a954bc18d5a05d720` | history-preserved-only |
