# Native render efficiency handoff (2026-07-27)

## User direction

Pause the current FrameGen-resolution tuning. The next agent should treat native
Minecraft rendering efficiency as the primary investigation: native Retina
fullscreen already reaches roughly 100-120 FPS without MetalFX or FrameGen, so
first determine how much more source headroom can be recovered from the main
renderer and the unfinished Metal 4 migration.

Do not deploy a Launcher QA jar until a later task explicitly resumes deployment
and its runtime gates pass.

## Checkout and ownership

- Worktree: repository root
- Branch: `claude/framegen-comparison`
- HEAD: `0449ea0e5ef8aa034d63f2e7a900b5183fa4276e`
- The branch is one local commit ahead of its fork. Current work is uncommitted.
- Do not push without explicit authorization.
- Preserve all dirty `logs/2026-07-27-*.log.gz` files.
- Preserve `docs/handoffs/metalfx-production-gate-handoff-2026-07-27.md`.
- Do not stage, delete, rewrite, or revert those user-owned files.

## Authoritative native baseline

Artifact:

`build/metal-validation/minecraft-native-fullscreen-current/native-fullscreen-baseline.json`

Real M1 Pro, MetalFX OFF, FrameGen OFF, Retina borderless fullscreen:

| Metric | Result |
| --- | ---: |
| Drawable | 3024 x 1734 |
| Samples | 240 frames |
| Frame interval p50 / p95 | 8.318 / 9.817 ms |
| Main command-buffer GPU p50 / p95 | 6.019 / 7.041 ms |
| Main command-buffer GPU max | 8.345 ms |
| Source FPS from p50 | 120.22 |
| Stable 60 source gate | true |

This disproves the earlier assumption that native rendering cannot reach 60.
It does not prove the same cadence in every world, with high entity density, or
with shaders. Keep the controlled-scene result separate from broad claims.

## Native-direct FrameGen experiment

The experimental topology is:

```text
native 3024x1734 real scene
  -> bounded 1280x734 FrameGen scene
  -> 858x492 depth/motion inputs
  -> Metal 4 FrameInterpolator/present
  -> generated scene scaled in fused native present
  -> native GUI on real and generated frames
```

Latest complete real run:

`build/metal-validation/minecraft-native-framegen-current/`

| Metric | Result |
| --- | ---: |
| Complete generated/real source pairs | 128 |
| Presented records | 252 |
| Source interval p95 | 18.356 ms |
| Main source command-buffer GPU p95 | 12.397 ms |
| Generated GPU p95 | 5.248 ms |
| Present interval p95 | 8.333 ms |
| Stable source / present | 60 / 120 |

The native-direct path is real and visible, but the old
`native-direct-performance.json` field `gpuMarginTo60FpsMilliseconds: 4.27` is
stale and misleading: it subtracts only the source command-buffer p95 from
16.67 ms and omits generated and real-present GPU work.

An independent calculation over the 128 complete timeline pairs gives:

| Combined GPU service per source pair | Result |
| --- | ---: |
| Mean | 13.430 ms |
| p50 | 12.864 ms |
| p95 | 17.701 ms |
| Max | 17.919 ms |
| Conservative p95 margin to 16.667 ms | -1.034 ms |

So the current FrameGen topology maintains cadence through pipelining, but it
does not yet prove 3-4 ms of shader headroom. Do not quote 4.27 ms as available
shader budget.

## Last functional change and exact verification state

`MetalFxManager.java` now avoids a redundant native-resolution shader copy in
native-direct mode. The presenter snapshots the main render target directly
into its ring buffer; Temporal still owns `nativeSceneTarget`. Motion history is
committed on successful native-direct submissions so reset does not remain
permanently asserted.

Verification completed after that Java change:

```text
./gradlew compileJava test buildMacNative --no-daemon   PASS
./gradlew minecraftNativeFrameGenerationValidation     PASS
```

The paired run changed source GPU p95 from 12.710 ms to 12.397 ms. A transient
run with history accidentally held at reset measured 7.99 ms source GPU p95 but
produced zero generated frames; it is diagnostic evidence only, not a valid
performance result.

After the valid run, `build.gradle` was edited to compute total GPU service
(source + generated + real present) and gate at least 3 ms of conservative
margin. That final Gradle edit has **not** been parsed, compiled, or run. Start
by inspecting it; do not assume it is syntactically or semantically correct.

## What Metal 4 currently means

The log proves:

```text
Metal 4: requested=true available=true compiler=true present=true barrier=false
Metal 4 pipeline path engaged (MTL4Compiler)
frame generation present path: Metal 4
```

This is not a full Metal 4 renderer. Current implementation uses:

- `MTL4Compiler` for pipeline-state creation while those PSOs still interoperate
  with the existing Metal 3 render encoder.
- A dedicated Metal 4 pilot queue for FrameGen present.
- The main Java-driven render command buffer remains Metal 3.
- The full main-queue/barrier migration described as M7 in
  `docs/metal4-barrier-map.md` is still open.

Do not attribute the 7.04 ms native GPU result to a completed Metal 4 renderer.

## Native optimization priorities

### P0: obtain pass-level evidence in MetalFX OFF mode

The current recorder measures only the whole main command buffer. Add bounded,
low-overhead timing around major native passes, or capture a Metal System Trace:

- Sodium terrain solid / cutout / translucent
- entities and particles
- world depth preservation (must be absent when MetalFX is OFF)
- post-processing and GUI
- texture uploads and command-buffer idle gaps
- encoder transitions, fence waits, and drawable wait

Run at least the controlled validation room plus one representative real world.
Record CPU encode time separately from GPU execution. The native baseline has
about 2.78 ms between GPU p95 (7.04) and frame-interval p95 (9.82), so CPU,
submission, or pacing work is material even before GPU shader optimization.

### P0: verify OFF truly removes all MetalFX work

Trace the OFF branch from `MetalFxManager.beginFrame/endUpscale` through the
main renderer and confirm no motion, reactive, world-depth snapshot, scene copy,
or private MetalFX texture allocation survives. Add counters/assertions to the
native baseline artifact rather than relying on configuration text alone.

### P1: finish main-render Metal 4 migration only from the barrier map

Read `docs/metal4-barrier-map.md` before editing. It records two existing Metal 3
race edges and explains why the six MetalFX exports need MTL4 twins. The likely
payoff is lower CPU encoding/submission overhead and explicit residency/barrier
control; do not promise lower fragment cost without measurement.

Suggested staged order:

1. Measure Metal 3 main-queue CPU encode and GPU time.
2. Implement the smallest MTL4 main command-buffer path with kill switches.
3. Preserve Metal 3 fallback and pixel/readback parity.
4. Enable API/GPU validation for the new barrier path.
5. A/B the same 240-frame native OFF scene and a real world.

### P1: inspect bandwidth and overdraw before shader micro-optimization

At 3024 x 1734, full-screen RGBA8 read+write traffic is roughly 40 MiB per pass.
Audit unnecessary resolve/copy/clear/load-store actions, full-screen post passes,
and translucent/cutout overdraw. The removed native-direct copy saved only about
0.31 ms under real paired contention, so each proposed pass removal must be
measured rather than estimated.

### P2: separate renderer optimization from FrameGen contention

The native OFF source GPU p95 is 7.04 ms; enabling the full-resolution motion
pipeline plus FrameGen raises source GPU p95 to 12.40 ms. Do not label the
difference as ordinary native-render cost. Profile these independently:

- native renderer only
- native renderer + motion production, no generated present
- native renderer + motion + generated present

The full-resolution camera/disocclusion/merge path and later depth/motion
resampling are strong optimization candidates. A future direct-FG path should
produce bounded-resolution motion inputs directly where correctness permits,
instead of producing all auxiliary textures at 3024 x 1734 and downsampling.

## Immediate next commands

Use the Minecraft Java 25 runtime:

```bash
export JAVA_HOME='/path/to/a/JDK-25/Contents/Home'
```

First inspect the unverified tail of the current Gradle change:

```bash
git diff --check
git diff -- build.gradle
./gradlew compileJava buildMacNative tasks --no-daemon
```

Then preserve the current native baseline before adding instrumentation. Do not
overwrite it with a differently configured run. Prefer a new output directory
or copy it into a dated benchmark directory with a manifest of JVM properties,
drawable size, world, view distance, commit, and dylib hash.

Recommended new verification task name:

```text
minecraftNativeRenderEfficiencyValidation
```

It should run MetalFX OFF, FrameGen OFF, Retina fullscreen, collect at least 240
steady frames, emit pass-level CPU/GPU timings, and exit automatically.

## Remaining broader goal work

The original exact-half Temporal-to-native plus native FrameGen objective is not
complete. Native-resolution FrameGen itself previously cost 22-26 ms and cannot
meet 120 present on this M1 Pro. The bounded native-direct experiment is a viable
alternative, not a completed replacement. Fullscreen/resize/GUI/exit lifecycle,
HUD fields, timeline acceptance, shader-headroom proof, and Launcher QA deployment
remain open after the native renderer investigation.

## 2026-07-28 continuation: measured main-render work

The continuation added validation-only logical-pass CPU timings and native
render/blit encoder GPU timestamps. On this M1 Pro the counter set supports
stage-boundary sampling, so the GPU rows describe whole native encoders rather
than individual draws. Pass labels are retained when pass timing is enabled,
without enabling ordinary debug labels globally.

Latest labeled baseline before the CPU lookup optimization, MetalFX OFF at
3024x1736:

| Metric | Result |
| --- | ---: |
| Frame interval p50 / p95 | 8.331 / 9.804 ms |
| Main command-buffer GPU p50 / p95 | 6.602 / 7.164 ms |
| Main world render encoder GPU p95 | 6.204 ms |
| Entity-translucent render encoder GPU p95 | 0.399 ms |
| GUI-before-blur render encoder GPU p95 | 0.211 ms |

The world work is already coalesced into one native encoder whose first logical
label is `Sky disc`; the data does not support blindly merging more world
passes. The next GPU optimization should inspect overdraw and attachments inside
that encoder, plus the full-screen present/copy path that is not yet part of the
native encoder timing table.

`MetalRenderPass` now retains the current native encoder and returns it directly
while `MetalCommandEncoder` confirms the same wrapper is still active. A clear,
blit, attachment change, or any other encoder transition invalidates the cache
by object identity, preserving the existing rebuild/rebind path. In the
approximately 300-frame validation window this eliminated:

| Avoided CPU work | Count |
| --- | ---: |
| Native render-encoder factory/FFM calls | 21,587 |
| Temporary attachment/clear arrays | 64,761 |
| Factory calls still required | 4,206 |
| Duplicate lookup elimination rate | 83.7% |

Compared with the labeled baseline, representative logical-pass CPU p95 changed
as follows: Sky disc 0.1202 -> 0.1087 ms (-9.6%), Terrain 0.1276 -> 0.1213 ms
(-4.9%), entity translucent 0.0458 -> 0.0375 ms (-18.0%), and GUI before blur
0.0349 -> 0.0322 ms (-7.8%). Whole-command-buffer GPU p95 was 7.172 ms in the
confirmation run, effectively unchanged (+0.1%); frame-interval p95 was 9.681 ms
(-1.3%). Treat the exact CPU counts as the hard result and the short timing
deltas as controlled-scene evidence, not a universal speedup claim.

Two kill-switch A/B experiments remain disabled by default:

| Candidate | GPU p95 | Frame p95 | Decision |
| --- | ---: | ---: | --- |
| split fence | 7.515 ms (+4.9%) | 9.690 ms (-1.2%) | Reject for now: worse GPU tail |
| explicit Metal 3 residency | 6.255 ms (-12.8%) | 10.943 ms (+13.0%) | Inconclusive: frame tail regressed |

The split-fence run used a drawable eight pixels taller than the first baseline;
the residency run matched the later 3024x1740 confirmation but had abnormally
slow world preparation. Neither result is strong enough to change defaults.

## 2026-07-28 continuation: Metal 4 main-queue boundary

The `metallum.opt.metal4MainQueuePilot` path is no longer an empty-command-buffer
smoke test. It now validates all of these on the real M1 Pro runtime:

- three reusable `MTL4CommandBuffer` / `MTL4CommandAllocator` slots;
- a real `MTL4ComputeCommandEncoder` buffer copy on every submission;
- a queue-attached, committed and requested `MTLResidencySet` containing the
  source and destination allocations;
- six `MTL4CommitFeedback` completions followed by byte-for-byte shared-buffer
  readback validation.

The log gate is:

```text
Metal 4 main-queue pilot validated: 3 reusable buffers, 6 compute copies, explicit residency
```

This is a main-queue API/resource-lifecycle pilot only. The Java-driven Minecraft
render encoders still run on the Metal 3 command buffer, and the pilot does not
yet validate main-render argument tables, MetalFX MTL4 twin ABIs, the §1 encoder
barrier map, drawable presentation, or visual parity. Do not describe it as a
migrated Metal 4 renderer.

## 2026-07-28 completion: Metal 4 main Minecraft renderer

The statement immediately above describes the earlier pilot and is now
superseded for MetalFX OFF. The Java-driven Minecraft main renderer has a real,
opt-in Metal 4 backend behind `metallum.opt.metal4MainRenderer=true`. Metal 3 is
still the default and remains the fallback.

Implemented and exercised on the real M1 Pro:

- three reusable `MTL4CommandBuffer` / `MTL4CommandAllocator` slots with commit
  feedback, completion state, GPU timestamps and semaphore signaling;
- dual-dispatch native bridge entry points, so the existing Java command ABI
  selects `MTLCommandBuffer` or the opaque Metal 4 lease without changing its
  callers;
- `MTL4RenderCommandEncoder` for direct, indexed, multi-draw, indirect and
  triangle-fan draws, plus full/partial clear and deferred depth store;
- `MTL4ComputeCommandEncoder` for all former blit operations;
- separate per-slot vertex and fragment `MTL4ArgumentTable` instances, using
  GPU addresses for buffers and resource IDs for textures/samplers;
- PSO-time fail-closed binding limits: 31 buffers, 16 sampled images/samplers,
  128 texel textures, and vertex buffer slots no higher than 30;
- a persistent global residency set for every created buffer/texture, the
  layer residency set for drawables, locked add/remove/commit, and deferred
  removal through the existing destruction queue;
- consumer `.device` barriers at render/compute encoder creation according to
  `docs/metal4-barrier-map.md`; the Metal 4 path ignores the old Java fence
  calls while the Metal 3 path keeps them unchanged;
- queue-level drawable ordering: `waitForDrawable`, commit,
  `signalDrawable`, then `present`.

### MetalFX ABI compatibility boundary

The six MetalFX native exports still accept `MTLCommandBuffer`, not
`MTL4CommandBuffer`. Passing a Metal 4 lease to them would issue Metal 3
selectors on an incompatible object and crash. `MetalDevice` therefore engages
the Metal 4 main renderer only when MetalFX mode is OFF. If Spatial or Temporal
is active, it logs the fallback and retains the Metal 3 main queue. This is ABI
compatibility and crash prevention, not a claim that MetalFX compute/scaler
encoders have migrated to Metal 4.

The fallback was run with both Metal 4 switches requested. Temporal initialized,
the log reported `mainRenderer=false`, and all 16 expected GPU attachment
readbacks passed with zero failures. A full MetalFX-to-Metal-4 migration remains
a separate follow-up requiring MTL4 twins for those exports and their internal
argument tables, residency and barriers.

### Main-render runtime/readback validation

`minecraftNativeRenderEfficiencyValidation` now schedules a bounded 256x256
GPU-to-CPU readback of the real main color texture on timeline frame 16, before
the measured frame window begins at frame 61. The task fails unless the image is
nonblack, nonconstant, alpha-valid, and the requested Metal 4 backend reports
engaged residency plus command-buffer reuse.

Latest Metal 4 readback:

| Field | Result |
| --- | ---: |
| Pixels | 65,536 |
| Nonzero RGB pixels | 65,536 |
| Pixels differing from the first RGB value | 65,142 |
| FNV-1a 64 | `f08f3cdc98ccf6d6` |
| Validation | passed |

The same readback also passed with Metal 3. Independent launches are not
byte-stable: two Metal 3 captures differed in 24.2% of pixels (SSIM 0.9537,
PSNR 36.50 dB), while the first Metal 3/Metal 4 comparison differed in 40.1%
(SSIM 0.9059, PSNR 32.43 dB). Visual inspection shows matching geometry,
occlusion and material structure with low-amplitude color/lighting differences.
This proves a real, structured readback and perceptual parity, not byte-exact
golden parity; do not strengthen that claim without an in-process deterministic
golden harness.

The final Metal 4 readback run also completed all 240 measured frame intervals
and 240 GPU samples under Metal API Validation with no validation errors or GPU
faults.

### Quantified efficiency result

All rows below are the controlled Retina fullscreen room at 3024x1734 or
3024x1740, MetalFX OFF, with 240 measured frame intervals and GPU command
buffers. Validation-layer runs are excluded from performance comparison.

Earlier repeated set at 3024x1740:

| Backend median | GPU p50 | GPU p95 | Frame p50 | Frame p95 |
| --- | ---: | ---: | ---: | ---: |
| Metal 3, 2 runs | 6.755 ms | 7.173 ms | 8.327 ms | 9.791 ms |
| Metal 4, 3 runs | 6.132 ms | 7.191 ms | 8.319 ms | 9.714 ms |
| Delta | -9.2% | +0.25% | -0.10% | -0.78% |

Latest matched 3024x1734 readback set:

| Backend | GPU p50 | GPU p95 | Frame p50 | Frame p95 |
| --- | ---: | ---: | ---: | ---: |
| Metal 3, median of 2 | 6.782 ms | 7.146 ms | 8.330 ms | 9.763 ms |
| Metal 4, 1 run | 5.790 ms | 7.076 ms | 8.315 ms | 9.701 ms |
| Delta | -14.6% | -0.98% | -0.19% | -0.64% |

The defensible conclusion across both sets is a repeatable common-case GPU p50
improvement of roughly 9-15%. GPU p95 remains within about plus/minus 1% and is
not a proven tail improvement. Keep `metallum.opt.metal4MainRenderer` default
OFF until broader-world tail data justifies changing the default.

One non-validation Metal 4 readback run created 3 command buffers, completed
636 leases/submissions, and avoided 633 command-buffer factory calls.

Preserved artifacts:

- `build/metal-validation/native-render-metal3-readback-2026-07-28/`
- `build/metal-validation/native-render-metal3-readback-repeat-2026-07-28/`
- `build/metal-validation/native-render-metal4-readback-2026-07-28/`
- `build/metal-validation/native-render-metal4-readback-validation-2026-07-28/`

Final verification completed from current source:

```text
./gradlew minecraftNativeRenderEfficiencyValidation \
  -Dmetallum.opt.metal4MainRenderer=true --no-daemon                 PASS
./gradlew minecraftNativeRenderEfficiencyValidation \
  -Dmetallum.opt.metal4MainRenderer=true \
  -Dmetallum.validation.metalDebugLayer=1 --no-daemon               PASS
./gradlew minecraftMetalFxClientValidation \
  -Dmetallum.opt.metal4=true \
  -Dmetallum.opt.metal4MainRenderer=true --no-daemon                PASS (16/16)
./gradlew compileJava test buildMacNative --no-daemon               PASS
git diff --check                                                    PASS
```

The unit-test JVM prints an existing warning that the temporary test dylib and
the resource dylib both define the same Swift classes. Tests still pass; this is
test-loader noise, not evidence from the real client runtime. No commit, push,
Launcher deployment, log deletion or unrelated dirty-worktree cleanup was done.
