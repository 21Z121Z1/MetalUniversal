# MetalUniversal MobileGL comprehensive completion goal

## Result

Starting from the exact GitHub tip of
`feature/mobilegl-inspired-hotpath` recorded for this run, deliver and push a
production-ready MetalUniversal whose observable OpenGL behavior is represented
once, validated once, and executed efficiently through the existing
Java -> FFM -> Swift -> Metal architecture.

The completed renderer must:

1. absorb the applicable, independently reimplemented lessons from MobileGL's
   OpenGL API semantics, validation, context/state machine, object model,
   generation and deletion rules, backend-object isolation, execution ordering,
   synchronization, frame ownership, tests, and fail-closed behavior;
2. establish a clear boundary from Minecraft, Blaze3D, Sodium, and Iris
   compatibility calls, through normalized observable semantics and
   generation-aware resource identities, to compiled pipeline/pass/binding
   plans and ordered commands, with Swift and Metal executing rather than
   reinterpreting Java semantics;
3. systemically reduce Java allocation, reflection, string lookup, FFM
   crossings, native setters, encoder churn, runtime pipeline compilation, and
   transient-resource overhead without changing draw work, quality, ordering,
   precision, shader-pack settings, or visible results;
4. close render, compute, direct/multi/indirect draw, clear, copy, blit, mipmap,
   readback, barrier, fence, query, presentation, frames-in-flight, deferred
   destruction, argument table/buffer, pipeline archive/prewarm, compiled frame
   graph, and safely admissible Sodium terrain ICB behavior with atomic packet
   execution and explicit fail-closed reasons;
5. preserve fixed-version Sodium, Iris, BSL, and Potato observable semantics,
   with no shader-pack-name special cases, guessed uniforms, silent fallback,
   stale cross-encoder state, or GC-dependent GPU lifetime;
6. run correctly and stably in real Minecraft for Sodium-only, Iris + BSL, and
   Iris + Potato profiles, including world load, terrain, entities, held items,
   particles, sky/sun/shadow, water, translucent foliage, GUI, resize/fullscreen,
   shader reload/off/on, chunk loading, world unload/reload, dimension switch,
   and normal exit, with Metal API/GPU validation and first-semantic-divergence
   evidence for any mismatch;
7. establish like-for-like stock OpenGL, MobileGL DirectVulkan + MoltenVK, and
   native Metal evidence on this Mac, then pass at least four interleaved paired
   30-second-warmup/120-second-sample blocks against the starting MetalUniversal
   baseline, including correctness guardrails and activation proof; and
8. finish with reviewable logical commits, a non-force fast-forward push only
   to `feature/mobilegl-inspired-hotpath`, identical local and remote HEADs, and
   a clean task worktree.

## Completion rule

Compilation, documentation, interfaces, counters, disabled pilots, short smoke
runs, main-menu startup, translation success, or one final screenshot are not
completion. The result is complete only when the implementation, physical-GPU
and real-client correctness, formal performance protocol, Git synchronization,
and final evidence all pass together.

## Avoid over-engineering

Implement only the smallest complete mechanism required by fixed OpenGL,
Iris, or Sodium observable semantics; Metal correctness and lifetime rules; an
actually measured bottleneck; or a mandatory acceptance gate. Do not introduce
unused abstraction layers, competing state machines, speculative backends,
unconsumed planners, mirror-only binding tables, broad rewrites, or features
without runtime activation and validation evidence. Prefer extending the
existing canonical state, frame graph, bridge, native module, telemetry, and
validation infrastructure over creating parallel systems.
