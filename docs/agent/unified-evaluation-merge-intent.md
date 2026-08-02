# Unified rendering evaluation integration record

Canonical continued-development branch: `integration/iris-metal-next`

Historical merge branch: `agent/unified-render-eval-performance`

The historical branch integrated the complete history of `codex/eval-framework-v0` into `feature/iris-metal-performance`. It is now an ancestor of the canonical integration branch; its original commits were not squashed, copied or rewritten. New work must branch from `integration/iris-metal-next`, not from the historical merge inputs.

## Merge policy applied

- A real non-fast-forward merge commit preserved both parent histories.
- Non-conflicting terrain scheduling, telemetry, native thermal-state and tests were retained.
- Overlapping renderer and mixin-registry files were resolved in favor of the newer Iris performance implementation, then the required terrain mixins were registered explicitly.
- Cleanly merged GPU timing and Java/FFM/Swift thermal ABI changes were kept once; duplicate reapplication was rejected during bootstrap validation.
- The old branch did not replace the current render-contract framework or create a second evaluation identity.

## Verification performed during integration

The merge bootstrap passed Java compilation and the focused terrain/mixin tests after excluding `buildMacNative`, `buildIOSNative` and `buildIOSSpvc` on the hosted macOS 15 runner. That runner lacked the macOS/iOS 26 Metal 4 and Frame Interpolator SDK surfaces required by the branch at the time, so native Metal 4 and attended graphical validation remained explicit local-agent gates rather than being marked green.

The canonical integration branch is now additionally checked by the macOS 26 build workflow and the unified headless/native compile workflow. Those hosted checks still do not replace physical Apple Silicon visual, readback, presentation, frame-pacing, or performance acceptance.

## Resulting evaluation model

The repository exposes one agent-facing platform with separate suites:

- conformance: render-contract logical passes, attachment readback and first divergence;
- performance: low-overhead structured metrics and ABBA-paired trials;
- diagnostic: focused producer detail and readback for a known divergent pass.

Performance evidence is invalid unless the matching correctness gate is complete and passes. `RenderTraceRecorder` and generation-aware resource identities are canonical; Iris construction traces are oracle evidence, not a competing timestamp-based trace system.
