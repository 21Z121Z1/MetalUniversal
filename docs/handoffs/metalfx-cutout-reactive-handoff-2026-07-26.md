# MetalFX CUTOUT reactive repair handoff — 2026-07-26

## Scope and stop point

This handoff covers the in-progress repair for Temporal flicker on alpha-tested
Sodium terrain, specifically leaves and grass. The intended fix is not a
material-name special case: every Sodium non-translucent terrain pass using the
same `ALPHA_CUTOUT=0.5` discard contract writes exact post-discard coverage to a
separate R8 MRT attachment. A compute pass expands that coverage by the current
jitter/upscale footprint and merges it into the MetalFX reactive mask.

Work intentionally stopped at the user's request. Do not claim the Minecraft
renderer path is validated yet.

Repository:

```text
/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master
```

The repository currently has no usable committed baseline: `git status --short`
reports the entire tree as untracked. Preserve it. Do not reset, clean, or treat
an empty Git diff as evidence.

The fail-closed gate is still correct:

```java
private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;
```

It is at
`src/main/java/com/metallum/client/metal/render/MetalFxManager.java`.

## Why the old implementation flickered

Leaves and grass are rendered by Sodium's CUTOUT terrain path into the ordinary
opaque scene target. They do not pass through Minecraft's later translucent,
particle, weather, cloud, or item-entity targets. The existing reactive-mask
inputs therefore missed their rapidly changing alpha-tested coverage.

A 3x3 depth-edge heuristic is insufficient: subpixel alpha coverage can change
under Temporal jitter without producing a stable depth discontinuity in the
same input pixel. Treating zero motion as a substitute would also be wrong.

The source path inspected in this checkout was:

```text
DefaultChunkRenderer.render
→ ShaderChunkRenderer.begin / compileProgram
→ Sodium blocks/block_layer_opaque shaders
→ non-translucent fragment-discard pass
→ ALPHA_CUTOUT=0.5
```

The new fragment shader copies Sodium's atlas sampling/RGSS logic and performs
the same cutoff before writing both outputs. Thus a discarded color pixel
cannot incorrectly write coverage.

## Implemented source changes

### Sodium CUTOUT MRT producer

New:

- `src/main/java/com/metallum/client/metal/render/MetalCutoutReactivePipeline.java`
  builds and caches a Sodium-compatible CUTOUT pipeline with:
  - color attachment 0: `RGBA8`
  - color attachment 1: `R8_UNORM`, red channel only
  - Sodium vertex shader
  - `USE_VERTEX_COMPRESSION`, `USE_FOG`, and `ALPHA_CUTOUT=0.5`
- `src/main/java/com/metallum/mixin/sodium/ShaderChunkRendererMetalFxMixin.java`
  selects the custom pipeline only for the relevant terrain pass.
- `src/main/java/com/metallum/mixin/sodium/DefaultChunkRendererMetalFxMixin.java`
  replaces that pass's one-color descriptor with the same color/depth targets
  plus the indexed R8 coverage attachment.
- `src/main/resources/assets/metallum/shaders/blocks/block_layer_cutout_reactive.fsh`
  writes coverage only after the same alpha discard used for scene color.

Modified:

- `src/main/resources/metallum.mixins.json` registers both Sodium mixins.

### Native coverage dilation and ABI

Modified:

- `src/main/native/MetallumNative.swift`
  - adds `metallum_cutout_reactive_dilate`;
  - caches its compute pipeline;
  - exports:
    - `metallum_metalfx_supports_cutout_reactive`
    - `metallum_metalfx_apply_cutout_reactive`
  - reads a separate R8 exact-coverage texture and max-merges into the final R8
    reactive mask with radius 0 through 3.
- `src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java`
  binds both optional native exports through FFM.
- `src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java`
  flushes pending clears, ends the render encoder, and invokes the native
  dilation pass with the correct fence.
- `src/main/java/com/metallum/client/metal/render/MetalFxMath.java`
  adds `cutoutReactiveRadius(scale, pixelJitter)`:

  ```text
  ceil(max(abs(jitter)) + max(0, 1 / renderScale - 1)), clamped to [0, 3]
  ```

- `src/main/java/com/metallum/client/metal/render/MetalFxManager.java`
  allocates/clears/releases the R8 coverage texture, attaches its view to the
  Sodium pass, combines it before Temporal encoding, and preserves the resulting
  reactive mask for MetalFX.

### Tests already added

Modified:

- `src/test/java/com/metallum/client/metal/render/MetalFxMathTest.java`
  tests 1.0x, 0.67x, 0.5x, jitter, and invalid-input fail-closed radii.
- `src/test/native/MetalFXOffscreenValidation.swift`
  uses synthetic exact post-discard coverage for the `alpha_test` scenario,
  applies radius-1 dilation, exports `cutout_coverage`, and asserts:
  - every covered pixel remains reactive;
  - dilation adds reactive pixels outside exact coverage;
  - Temporal receives `preserveReactiveMask=true`.

## Commands that passed before the stop

With Java 25:

```bash
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
  ./gradlew buildMacNative compileJava --no-daemon
```

Result: `BUILD SUCCESSFUL` in 14 seconds.

```bash
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
  ./gradlew test compileMetalFxOffscreenValidation --no-daemon
```

Result: `BUILD SUCCESSFUL` in 18 seconds.

```bash
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
  ./gradlew metalFxOffscreenValidation --no-daemon
```

Result: `BUILD SUCCESSFUL` in 6 seconds. Eight offscreen scenarios passed on
Apple M1 Pro with Metal API Validation enabled. The summary is:

```text
build/metal-validation/offscreen-current/summary.json
```

It reports `scenario_count=8`, `status=passed`, and no window, layer, drawable,
system screenshot, or Computer Use.

Native artifact produced by that build:

```text
build/resources/main/natives/macos/libmetallum.dylib
SHA-256 755d5b97cd9a3dfa2464e4760c1efeaa6620ce6eb0b82041c8cb16400543e5a3
size 353888 bytes
mtime 2026-07-26 18:19:49 +0800
```

## Last edit is deliberately unverified

The final edit before this handoff extended
`MetalFxManager.captureValidationFrameIfRequested()` to read back:

```text
cutout-coverage.bin
reactive.bin
```

and added CUTOUT metrics, frames 74/82, and acceptance logic for
`cutout_leaves` and `cutout_grass`.

This last `MetalFxManager.java` edit has **not** been compiled or tested. It is
the first thing the next agent must check. In particular, verify the enlarged
`MotionMetrics` record and `String.format` argument order.

The existing JAR is stale and must not be used as evidence:

```text
build/libs/metallum-1.0.1.jar
SHA-256 4d32dcf16446752bb989a4ce212e8172d518d132c5bb64ab6711f8120be5bd69
mtime 2026-07-26 17:30:53 +0800
```

It predates the CUTOUT work and the new dylib.

## Required next steps

1. Compile immediately, without changing code first:

   ```bash
   JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
     ./gradlew compileJava --no-daemon
   ```

   Fix all errors in the last validation metrics edit.

2. Finish `MetalValidationClient.java` deterministic CUTOUT scenes:

   - add capture frame 74: `cutout_leaves`;
   - add capture frame 82: `cutout_grass`;
   - place controlled `OAK_LEAVES` and `SHORT_GRASS` blocks in the camera view;
   - save and restore every replaced `BlockState`;
   - allow several frames for chunk rebuild before capture;
   - pitch the grass camera downward deterministically;
   - increase expected GPU captures from 8 to 10 and controlled frames to about
     90;
   - restore the test scene before client exit.

   Do not use screenshots or attended input. The expected coverage requirement
   is generated by known test blocks and verified from the actual pre-present
   R8 attachment.

3. Review mixin remapping before runtime. The current redirect has
   `remap=false` on the `CommandEncoder.createRenderPass` invoke. The enclosing
   Sodium method name should remain unremapped, but the Mojang
   `CommandEncoder` target may need independent remapping, for example an
   `@At(..., remap=true)` configuration. Confirm against this repo's working
   Sodium mixins and the runtime injection log rather than guessing.

4. Run:

   ```bash
   JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
     ./gradlew test buildMacNative metalFxOffscreenValidation --no-daemon
   ```

5. Run the repository's actual automated Minecraft client validation task
   after listing tasks. It must use a client, fixed test world, GPU readback,
   and automatic exit. Inspect logs for:

   - both new mixins applied;
   - custom CUTOUT shader compiled;
   - `cutoutReactive=true`;
   - `MetalFX CUTOUT reactive coverage prepared ...`;
   - frame 74 and 82 coverage counts;
   - exact coverage entirely included in reactive;
   - nonzero dilation pixels when radius is nonzero;
   - no Metal API Validation errors.

6. If the leaves/grass captures fail:

   - first confirm the target CUTOUT pass and attachment signature;
   - then confirm the R8 target is cleared before the pass;
   - then confirm terrain chunk rebuild completed;
   - only then adjust scene geometry or thresholds.

   Do not fall back to block-name detection, fake zero motion, or a larger
   depth-edge heuristic.

7. Only after current-source tests pass:

   - run the full build;
   - verify the JAR-embedded macOS dylib SHA-256 equals the newly built dylib;
   - update the Minecraft Launcher experience profile and copy the new JAR;
   - preserve `frameGeneration=false` and the object-motion gate;
   - remove forced JVM properties for user-selectable MetalFX settings if the
     profile still locks the options;
   - document that a client restart is required.

8. Update:

   - `docs/metalfx-motion-pipeline-implementation.md`
   - `docs/metalfx-validation.md`
   - `docs/metalfx-final-acceptance-2026-07-26.md`

   Separate offscreen evidence from actual Minecraft renderer evidence. Do not
   mark the flicker repair complete until both leaf and grass GPU captures pass
   through the real Sodium CUTOUT draw path.

## Acceptance invariants

- Color and coverage use the same texture sample and alpha discard.
- Uncovered pixels remain invalid in the exact coverage attachment.
- The final reactive mask contains all exact coverage.
- Expansion is derived from current jitter and input/output scale, bounded to
  radius 3.
- The coverage attachment is separate from the final reactive mask, avoiding a
  render/write and compute/read ownership ambiguity.
- MetalFX sees `preserveReactiveMask=true` only when a producer actually ran.
- Failure retains the existing depth-edge fallback and does not enable Frame
  Generation.
- `OBJECT_MOTION_PRODUCER_CONNECTED` remains `false`.

## User-facing issue still open

The screenshot showed disabled MetalFX controls because the generated Launcher
profile forces MetalFX system properties. This was diagnosed earlier but was
not changed during this CUTOUT repair. After rebuilding the experience profile,
leave only safety-critical forced properties (especially Frame Generation off)
and allow ordinary rendering options to be chosen in the UI.

