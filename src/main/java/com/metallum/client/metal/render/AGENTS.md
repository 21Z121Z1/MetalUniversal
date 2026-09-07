# Metal render execution guide

Scope: `com.metallum.client.metal.render` and its `mtl` wrappers. Global policy remains in the repository-root `AGENTS.md`; this file only states local ownership boundaries.

Treat this directory as the Java execution layer between immutable semantic/render intent and the Java/FFM native boundary. Preserve semantic pass identity and generation-aware resource identity while changing execution policy.

Local invariants:

- Admission is fail-closed. Encoder reuse, grouping, liveness, ICB, residency and other optimizations need explicit activation/rejection evidence.
- Never infer RAW/WAR/WAW safety from names or encoder order; consume the existing plan/resource identities.
- Resource recreate/close paths are part of correctness. Check resize, reload, generation change and command-buffer retirement.
- A Java descriptor or call-shape change that reaches `MetalNativeBridge` is an ABI-boundary change; inspect `native.abi` and `native.execution` before editing one side alone.
- Metal 3/4 behavior must remain intentionally equivalent or explicitly capability-gated.

Before broad tests, run the proof plan emitted by:

```bash
python3 scripts/agent/context.py --task "<task>"
```

For render behavior, the first independent oracle is the render-contract layer; compilation is not runtime correctness.
