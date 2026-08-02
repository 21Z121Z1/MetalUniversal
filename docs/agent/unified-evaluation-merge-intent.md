# Unified rendering evaluation merge intent

This branch integrates the complete history of `codex/eval-framework-v0` into `feature/iris-metal-performance` without replacing the newer Iris-on-Metal implementation with stale conflicting backend files.

The merge policy is explicit:

- preserve both parent histories with a real merge commit;
- retain non-conflicting render-evaluation and terrain scheduling files;
- resolve overlapping backend files in favor of the current performance branch;
- reapply only the old telemetry and scheduling hooks that remain required by the retained code;
- build one agent-facing workflow in which render-contract correctness gates authorize performance comparisons, rather than maintaining independent harnesses.

The final evaluation model separates conformance, performance and focused diagnosis. Performance evidence is invalid unless the corresponding render-contract run is complete and free of unexplained semantic or visual differences.
