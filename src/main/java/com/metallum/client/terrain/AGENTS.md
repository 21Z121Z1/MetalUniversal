# Terrain scene guide

Scope: `com.metallum.client.terrain`. Global policy remains in the repository-root `AGENTS.md`.

Treat terrain as a generation-owned GPU scene and scheduling subsystem, not as a disconnected optimization bag.

Local invariants:

- Geometry, visibility, ICB records, residency and scratch resources must have explicit generation/frame-slot ownership.
- Admission must prove that the optimized draw path is the real draw authority; counters without execution are not activation evidence.
- Camera epochs must not accidentally become resource lifetimes.
- CPU/GPU scheduling changes need deterministic correctness evidence before timing claims.
- Performance claims require the unified paired protocol; hosted synthetic speedups alone are directional evidence.

Route the task with `python3 scripts/agent/context.py --task "<task>"` and follow its proof ladder. Check lifecycle/reload paths as carefully as the steady-state hot path.
