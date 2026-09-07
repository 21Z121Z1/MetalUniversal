# Native Metal execution guide

Scope: `src/main/native`. Global policy remains in the repository-root `AGENTS.md`.

This directory is the Swift/Metal execution layer below the Java/FFM ABI. Native implementation details must not become an independent policy engine: semantic identity, admission and render intent come from the upper layers.

Local invariants:

- Every exported `@_cdecl` change must be reviewed against `MetalNativeBridge`, Java descriptor layout, nullability, ownership and fallback/version behavior.
- Extend the existing native module. Never load a shadow dylib that duplicates bridge types or global state.
- Retain/release, in-flight ownership, command-buffer retirement, resize/reload and teardown are correctness paths.
- Metal 3/4 paths must remain intentionally equivalent or capability-gated with explicit evidence.
- Do not re-derive hazard/liveness policy from pointers, encoder state or ad-hoc native heuristics when an immutable plan/admission record exists upstream.

Use the proof plan from `python3 scripts/agent/context.py --task "<task>"`; native compilation proves ABI/toolchain compatibility, not visible presentation or shader-pack correctness.
