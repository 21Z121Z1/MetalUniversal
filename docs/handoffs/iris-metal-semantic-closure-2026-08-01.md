# Iris Metal Semantic Closure Handoff

Status: **FROZEN / Gate C NOT PASSED**

This handoff freezes the Iris semantic task at repository HEAD
`f5fe101267c97cfbab6d6a814032a69e072d657e`. The delegated task
`019fb2f9-6f5c-7430-a937-95490250ef49` was stopped and archived after its
current command ended. No new production implementation is authorized from
this handoff.

## Frozen identity

- Worktree: `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-iris`
- Branch: `iris-on-metal`
- Upstream: `fork/iris-on-metal`
- HEAD: `f5fe101267c97cfbab6d6a814032a69e072d657e`
- Dirty tracked-diff SHA-256 (binary patch):
  `d90420053f55ef030bd2adf8c1230f4d15acc08e6932581faace52f19ca4c3ee`
- Frozen at: `2026-08-01T01:43:10Z`
- Full path ownership: `iris-metal-dirty-ownership-2026-08-01.json`
- Evidence index: `iris-metal-evidence-index-2026-08-01.json`

The worktree is intentionally not clean. `logs/latest.log`, upstream PR
documentation, MetalFX changes, Iris implementation changes, validation code,
and untracked test resources remain untouched. Do not stage the worktree as a
single change set.

## Accepted conclusions

- The BSL `iris_overlay` external texture-unit-1 boundary is closed for the
  fixed BSL HIGH regression. The implementation is generic, gives draw-local
  `Sampler1` precedence, validates live same-device external resources, and
  fails closed for invalid resources.
- Potato Gate 2 reload evidence is accepted for its fixed scene and lifecycle
  contract. The selected BSL HIGH fixture is accepted only as a visible raster
  and reload regression, not as complete BSL or Iris coverage.
- The non-Iris shaders-off exact gate is accepted for the explicitly frozen
  capture contracts in `non-iris-gate-20260731-atlas-phase-iter2` and
  `non-iris-gate-20260801-serializer-bootstrap-iter1`: both reported zero
  differing bytes at frames 160 and 220. This does not prove all worlds,
  RenderTypes, or final source states.
- Core pass, compute, storage-image, SSBO, shadow target, MRT, post/final,
  resize, dimension, and toggle fixtures provide useful evidence for their
  declared contracts. They remain partial semantic coverage, not Gate C.
- The uniform Oracle boundary is now instrumented at native
  `ProgramUniforms.update()` events, but value parity is still partial.

## Rejected, superseded, or incomplete evidence

- Any result that predates the recorded source/JAR identity of the accepted
  artifact is historical only and must not be used as proof for current HEAD.
- Earlier uniform traces that re-called suppliers or update paths are
  `SUPERSEDED`; they may have observer effects and cannot justify production
  changes.
- The current Oracle result remains `INCOMPLETE`: wall-clock values, first
  history/projection state, and scene/input timing are not closed.
- The selected BSL Gate remains `PARTIAL`; its clean visual evidence and user
  inspection do not establish full BSL option, compute, color, or OpenGL parity.
- Any evidence built with `-x compileJava`, an unrelated dirty source tree,
  or an artifact whose final source identity is not recorded is `INCOMPLETE`
  for final release acceptance.

## Blocking P0 issues

1. Production pipeline admission is still fail-open.
   `IrisPipelineFactoryMixin` catches `Throwable` and, unless the diagnostic
   property `metallum.iris.strict=true` is set, returns
   `VanillaRenderingPipeline`. A pack failure must instead reject activation or
   retain the previous valid pipeline with a user-visible reason.
2. Uniform update semantics are not proven equivalent to Iris.
   `IrisMetalUniformValues` performs global frame materialization, updates
   unvisited fixed inputs through reflected private state, and advances history
   outside the per-program update schedule. Previous/history initialization and
   per-program frequency boundaries remain unresolved.
3. The validation Oracle must be isolated from production and must be proven
   side-effect free. OpenGL trace mixins are still in the main Mixin manifest;
   tracing must not call suppliers, `updateAll`, or any stateful update path.
4. There is no single clean, reproducible final artifact proving all gates.
   Several accepted focused runs use older JARs or skipped compilation, and
   the current tree includes unrelated MetalFX and user-owned changes.

## Next implementation direction

Work only in a new clean integration checkout or explicitly authorized copy,
starting from this HEAD and the ownership manifest. Apply changes in this
order:

1. Split the ownership groups and rebuild one reproducible artifact. Do not
   edit this frozen worktree as the integration target.
2. Replace default fail-open admission with a typed admission result. Active
   pack failure must block generation activation or preserve the previous
   valid generation; do not use a vanilla pipeline to represent successful pack
   loading. Narrow catches to expected shader/admission/pipeline exceptions.
3. Move OpenGL trace and capture mixins into a validation-only source set/JAR.
   Keep default `build`/`check` independent of attended WindowServer
   presentation validation.
4. Rebuild the uniform Oracle from immutable cached values at the real
   `ProgramUniforms.update()` and Metal staging-upload boundaries. Prove trace
   on/off equality for frame bytes and supplier/update call counts before
   changing production uniform code.
5. Model uniform ownership and scheduling per program, with explicit
   tick/frame/draw/history-commit phases and a defined first-frame state. Do
   not add more name-based matrix aliases.
6. Only after uniform parity is closed, complete catalog, final-output/color,
   and lifecycle-combination gates, then rerun Gate A/B/C from one clean JAR.

## Reproduction commands

These are read-only or diagnostic commands for the frozen tree; they do not
constitute final acceptance:

```sh
cd /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-iris
git status --porcelain=v1
git rev-parse HEAD
git diff --no-ext-diff --binary | shasum -a 256
git diff --check

# Focused historical tests (artifact identity must be checked first)
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test --tests com.metallum.client.metal.render.IrisMetalUniformValuesTest --no-daemon

# Do not call these a final Gate C run; they require a clean, identity-stamped build.
```

## Explicit prohibitions

- No commit, push, tag, PR, or Launcher/profile change from this handoff.
- No `git reset`, `git clean`, `git checkout`, broad staging, or deletion of
  evidence/user files.
- No pack-name, shader-text, placeholder, skipped-pass, tolerance, or silent
  fallback workaround.
- No claim of complete Iris 1.11.2 semantic support or Gate C closure.

Final disposition: this is a high-value experimental foundation with accepted
focused regressions, but it is not merge-ready as a complete Iris semantic
backend.
