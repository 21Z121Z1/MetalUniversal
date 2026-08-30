# Validation plan

## Evidence identity

Every durable run manifest must contain:

- repository commit and dirty state;
- native dylib, production/validation JAR and shader-pack SHA-256;
- macOS, Xcode/Swift, Java, hardware and Metal 3/4 mode;
- Minecraft/Fabric/Sodium/Iris versions;
- world/seed/camera path, render and simulation distance;
- resolution, scale, GUI scale, VSync/FPS cap and quality settings;
- exact JVM/system properties and MetalFX/frame-generation/dynamic-resolution
  settings;
- warmup/sample timestamps, process PID/exit status and artifact paths.

Missing identity makes a trial inadmissible. A missing metric is `unavailable`
and fails a gate that requires it; it is never coerced to zero.

## Static and unit lanes

Run after each relevant slice and again from a clean build:

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify.sh static
./gradlew --no-daemon test --stacktrace
./gradlew --no-daemon renderContractSyntheticValidation
```

Required focused suites include:

- semantic/object generation and deferred destruction;
- compiled binding layout and argument snapshot ABI;
- render/state/compute packet layout and atomic rejection;
- pipeline identity/archive/prewarm telemetry;
- frame graph RAW/WAR/WAW/load/store/liveness;
- shader translation and BSL/Potato/conformance counts;
- terrain scope, command ranges, generation invalidation and fallback reasons;
- validation normalizer/admission/analyzer self-tests.

`git diff --check` and Mixin registration/descriptor tests run before every
commit affecting Java/native/Mixin ABI.

## Native and physical-GPU lanes

```bash
./gradlew --no-daemon buildMacNative build --stacktrace
bash scripts/agent/verify.sh gpu
```

Then exercise, with Metal API Validation and GPU Validation where supported:

- exported symbol and Java/Swift ABI symmetry;
- render/state/compute packet physical execution and zero-execution rejection;
- MRT formats and independent blend/masks;
- integer/float/depth/stencil clear and readback;
- copy/blit/mipmap plus row-alignment cases;
- render -> compute and compute -> render visibility;
- delayed completion and deferred release;
- Metal 3 argument buffer and Metal 4 argument table;
- terrain multi-draw and ICB with complete residency;
- intermediate render-contract attachments and first-divergence identity.

Synthetic success does not prove a real-client path. Each optimized path has a
separate activation criterion in the client manifest.

## Real Minecraft correctness lanes

Profiles, all using the same fixed world and scripted camera/action sequence:

1. `A`: Sodium, no shader pack.
2. `B`: Iris + pinned BSL archive.
3. `C`: Iris + pinned Potato archive.

The client must start from CLI/Gradle automation, enter the world, execute the
sequence, write structured evidence, and exit normally. No Computer Use is
used. Where a launcher-only or visual judgment cannot be automated reliably,
the report separates internal/readback proof from human-observed scanout.

The action sequence covers:

- main menu and fixed-world load;
- stationary and moving terrain/entity/held-item/particle/sky/sun/shadow/water
  views;
- opaque, cutout, cutout-mipped and translucent terrain including cross-plane
  foliage and emissive vegetation;
- chunk traversal/rebuild/upload;
- GUI, resize and fullscreen transition;
- shader reload, off/on, world unload/reload and dimension switch;
- normal process exit.

Each capture binds final and intermediate attachments to semantic pass and
resource generation. On mismatch, compare the first divergent pass/producer,
not only the final framebuffer checksum.

## Optimization admission

| Path | Required activation |
| --- | --- |
| Render packet | calls > 0, operations > 0, replays = 0 |
| State packet | operations > 0, replays = 0 when lane enables it |
| Compute packet | real dispatch calls/operations > 0, replays = 0 in compute conformance lane |
| Argument table/buffer | encoded snapshots and updates > 0; table binds > 0; individual resource setters lower than baseline; fallback reason absent for qualifying passes |
| Terrain ICB | attempts > 0, accepted > 0, indirect draws > 0, qualifying fallback = 0; all required resource generations resident |
| Pipeline prewarm | actual sampled render + compute PSO creations = 0; late identity list empty |
| Frame graph optimization | compiled pass/fusion/alias counters > 0 only where the corresponding hazard proof is recorded |
| FFM reduction | exact MethodHandle downcalls/frame lower than starting baseline with equal draw work |

No qualifying draw/dispatch is a failed activation, not a zero-valued success.

## Performance protocol

First compare starting MetalUniversal `B` against candidate `C`, then compare
the accepted candidate to MobileGL/MoltenVK `V`; stock OpenGL `O` is a
correctness/performance reference. All paths keep fixed quality and work.

- Warmup: 30 seconds.
- Sample: 120 seconds.
- Pairing: at least four interleaved blocks in ABBA or equivalent order.
- Power/thermal: record power source and thermal state for every block.
- Basic comparison disables MetalFX, frame interpolation and dynamic
  resolution.

Required metrics:

```text
average/median FPS, 1%/0.1% low,
frame p50/p95/p99/p99.9,
frames >33.3/>50/>100 ms, missed deadlines,
CPU render/encode, GPU frame, FFM calls, native setters,
packet calls/ops/replays, argument updates/binds,
encoders, command buffers, draws/multi-draw/ICB,
runtime pipeline compiles, transient/peak memory, GC,
chunk rebuild and upload
```

Acceptance requires one target metric to improve in at least 75% of paired
blocks and its paired median to improve. Draw count, render distance, shader
settings, shadows, attachments and image quality must remain equivalent. p99,
long frames, correctness, memory, GC and activation guardrails may not regress.

## MobileGL/MoltenVK differential lane

Build commit `598c5497...` against the exact MoltenVK 1.4.2 dylib identified in
`source-map.md`. Record all CMake options, linked library paths and test results.
O, V and M then use identical game/mod/pack/world/action/JVM/display settings.

Correctness arbitration order is:

```text
fixed OpenGL/Iris/Sodium observable semantics
> Metal API/GPU validation and proven lifetime
> O/V/M differential evidence
> MobileGL output alone
```

A Vulkan/MoltenVK-specific difference is not automatically a native Metal bug,
and a MobileGL omission is not permission to omit pinned-client behavior.

## Final build, Git and delivery gates

```bash
./gradlew clean test --stacktrace
./gradlew buildMacNative build --stacktrace
bash scripts/agent/verify_unified_eval.sh
git status --short
git diff --check
git diff --stat origin/feature/mobilegl-inspired-hotpath...HEAD
git log --oneline origin/feature/mobilegl-inspired-hotpath..HEAD
```

After the final fetch/rebase and affected retest, push only:

```bash
git push origin HEAD:feature/mobilegl-inspired-hotpath
```

No force option is permitted. Fetch again and require local HEAD equals remote
target HEAD and the task worktree is clean. Runtime artifacts, worlds, shader
pack binaries, dylibs, captures, traces and `.codex-run` are never committed.
