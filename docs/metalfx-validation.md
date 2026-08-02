# MetalFX Validation

Use the JDK 25 toolchain required by Minecraft 26.2 (any JDK 25 works; on this
machine Homebrew provides one):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew clean test buildMacNative build --no-daemon
```

`./gradlew check` additionally runs the native acceptance suite: lifecycle
state-machine tests, the MRT smoke test, the Java→FFM→Swift MRT backend
integration test, the offscreen temporal-semantics validation, and the windowed
`CAMetalDisplayLink` presentation validation (requires a WindowServer session;
exclude with `-x metalFrameGenerationPresentationValidation` on headless CI).

Run the spectator test world at 0.67 scale:

```sh
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
./gradlew runClient --no-daemon \
  --args='--quickPlaySingleplayer "New World"' \
  -Dmetallum.metalfx.mode=TEMPORAL \
  -Dmetallum.metalfx.scale=0.67 \
  -Dmetallum.metalfx.debug=true
```

For validation, the environment must be present before the Java process creates
the Metal device. The project's compute passes are labelled so validation can
be scoped to them:

```sh
MTL_DEBUG_LAYER=1 \
MTL_SHADER_VALIDATION=1 \
MTL_SHADER_VALIDATION_DEFAULT_STATE=none \
MTL_SHADER_VALIDATION_ENABLE_PIPELINES='Motion Reconstruction,Transparency Mask' \
MTL_SHADER_VALIDATION_REPORT_TO_STDERR=1 \
JAVA_HOME=/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home \
./gradlew runClient --no-daemon \
  --args='--quickPlaySingleplayer "New World"' \
  -Dmetallum.metalfx.mode=TEMPORAL \
  -Dmetallum.metalfx.scale=0.67 \
  -Dmetallum.metalfx.debug=true
```

This scoped run enabled both API and GPU validation and reached the world for
over 30 seconds without a validation report. Enabling shader validation for
every pipeline is not usable on this macOS 26.5.1/M1 Pro combination: MetalFX's
internal temporal kernel is instrumented with a reported `1024 x 1` threadgroup
against an `896` device limit and aborts in Apple's validation layer. The
project-owned pipelines pass when selected explicitly; the global MetalFX
validation assertion is recorded as an SDK/driver limitation rather than
hidden by disabling API validation.

## Sodium options

When Sodium 0.9 is present, the video settings page receives a `MetalFX` page
from the Sodium `sodium:config_api_user` API. It exposes:

- `MetalFX mode`: Off, Spatial, Temporal, or Auto;
- `Internal render resolution`: 50%, 67%, or 100%;
- `Transparent reactive mask`: the five separate Mojang transparency targets;
- `Metal frame generation`: the opt-in macOS 26 frame-interpolator path.

These options are persisted in `metallum-metalfx.properties` in the active
Minecraft game directory. They are marked as requiring a game restart because
the scene target dimensions and MetalFX descriptor are created with the Metal
device and `GameRenderer`; applying a setting cannot safely mutate those
resources in the middle of a frame. Explicit JVM properties remain the highest
priority override for automated validation. The transparent reactive option
does not remove the always-on depth-edge rejection, and alpha-cutout terrain
(leaves, grass and every other non-translucent discard pass material)
additionally writes exact post-discard coverage through the Sodium MRT
producer, which is dilated by the current jitter/upscale footprint and
max-merged into the reactive mask.

Repeat with `-Dmetallum.metalfx.scale=0.5`. A successful run should log the
configured phase count, all available transparency targets, and a line of the
form:

```text
MetalFX encode succeeded: mode=TEMPORAL, input=..., output=..., reactiveMask=true
```

The latest 0.67 run on the Apple M1 Pro produced:

```text
MetalFX configured: requested=TEMPORAL, effective=TEMPORAL, scale=0.67, phases=18
MetalFX reactive mask prepared from transparency targets: translucent=true, itemEntity=true, particles=true, weather=true, clouds=true
MetalFX encode succeeded: mode=TEMPORAL, input=2026x1126, output=3024x1680, reactiveMask=true
MetalFX temporal state: jitterPixels=(0.0, -0.16666666), motionVectorScale=(1013.0, 563.0), inputContent=2026x1126, depthReversed=true, motion=previousScreen-currentScreen
```

## Orientation contract

The native bridge keeps two fullscreen pipelines with different coordinate
contracts:

- the drawable present pipeline uses the original Metallum Y-flip because
  CAMetalLayer presents the framebuffer-oriented render target with the
  opposite vertical orientation;
- the texture-copy pipeline does not flip Y because MetalFX output, the
  native-resolution UI target, and other intermediate textures share the same
  coordinate space.

Using the present pipeline for both operations double-flips Spatial/Temporal
output before the final drawable present. The split is compiled into
src/main/native/MetallumNative.swift and is covered by the native build in
the validation command above.

The current log wording uses the equivalent screen-space convention
`motion=previousScreen-currentScreen`: X is previous minus current in Metal's
top-left screen coordinates, and Y is current minus previous because Metal
clip-space Y points up. The reactive pass also rejects the cleared-depth side
of 3x3 boundaries; alpha-cutout leaves and grass no longer depend on that
heuristic alone, because the Sodium CUTOUT MRT producer contributes their
exact post-discard coverage to the reactive mask.

## Automated client validation determinism

`minecraftMetalFxClientValidation` performs sixteen frame-exact GPU readbacks. To
keep them deterministic on a loaded machine:

- the run directory's `run/config/sodium-options.json` sets
  `chunk_build_defer_mode` to `ZERO_FRAMES`, and the validation client requests
  `SodiumWorldRenderer.scheduleRebuildForBlockArea(..., important=true)` after
  every scene block mutation, so occlusion-wall and CUTOUT scene changes are
  meshed synchronously on the frame that changes them;
- 40 warm-up frames (50 ms each) run before the scripted timeline so initial
  section compilation and the controlled entity's render section settle;
- the revealed-entity capture is taken on the wall-removal frame itself
  (frame 46), because the reveal's disocclusion transient only exists on the
  first frame the wall is gone;
- frames 74 and 82 capture controlled `OAK_LEAVES` (persistent) and
  `SHORT_GRASS`-on-`GRASS_BLOCK` scenes through the real Sodium CUTOUT draw
  path; every replaced `BlockState` is saved and restored, the grass camera
  pitches down 15 degrees deterministically, and the player pose is restored
  at exit so repeated runs do not drift the saved test world.

The acceptance for the CUTOUT frames requires more than 32 exact-coverage
pixels, every covered pixel present in the final reactive mask, and nonzero
dilation outside exact coverage whenever the jitter/scale radius is nonzero.

A passing build must also prove the run happened. `MetalValidationClient`
writes `build/metal-validation/minecraft-client-current/run-state.json` from
`finishRunState`, and calls `minecraft.stop()` only on its success path, so an
absent file means the scripted timeline never completed. That is reachable with
no error at all: when the window opens unfocused the pause screen can open on
the same frame the player joins, the compositor throttles the paused unfocused
client to roughly zero frames, and `beforeFrame` never reaches a frame with a
non-null level. Three such runs on 2026-07-27 captured zero GPU readbacks and
still reported `BUILD SUCCESSFUL`. Clearing `pauseOnLostFocus` before the level
guard narrowed the race but could not distinguish "validated" from "never ran",
so `runClient` now carries a `doLast` (active only when
`minecraftMetalFxClientValidation` is the invoked task) that requires the file
to exist, parse, report `status` `passed`, and have `completedGpuCaptures`
equal `expectedGpuCaptures`. The expected count is read from the file rather
than hard-coded so the gate follows the client. A gate-open run additionally
writes `frameGenerationRequested`, `frameGenerationFramesQueued`, and
`frameGenerationEnabledAtCompletion`. When the Gradle invocation explicitly
sets `-Dmetallum.metalfx.frameGeneration=true`, `doLast` also requires a
positive enqueue count and an enabled end state. This prevents a permanently
disabled presenter from being hidden behind successful Temporal attachment
readbacks; it does not claim that a background drawable reached scanout.

The 2026-07-27 gate-open run found exactly that former false positive: startup
size churn caused one scene encode failure, Frame Generation was permanently
disabled, and the old gate still passed 16/16. After changing the failure to a
recoverable suspension, the same command recovered with reset history, queued
255 source frames, remained enabled at completion, and passed all 16
readbacks. Presentation diagnostics from this background Minecraft window had
zero `presentedTime` and were correctly classified as not presented. The
separate foreground `metalFrameGenerationPresentationValidation` run passed
10 real and 9 generated presents; attended scanout/VRR judgment is still a
separate production gate.

The run entered `New World` and remained alive for more than one minute. A
system screenshot attempt was unavailable because this macOS session denies
display capture, and the Java/LWJGL window is not exposed as an independent
Computer Use application. The Launcher is exposed and was verified separately.

The local test world is kept in spectator mode with `Data.GameType=3` in
`level.dat`; this was re-read after the latest runs before launching the
validation matrix. The
initial pre-fix crash was caused by passing heap-backed `MemorySegment` matrix
arrays to a JDK 25 native downcall; the bridge now copies them into a confined
native arena before calling Swift.

The post-frame-generation scoped validation run on 2026-07-26 enabled Metal
API and GPU validation, entered `New World`, and reached the first successful
Temporal encode at `1144x642 -> 1708x960` without a project-owned validation
report. A separate 10-second `Metal System Trace` was recorded while frame
generation was enabled at `/tmp/metallum-metal-20260726.trace`; it contained
paired interpolated/rendered present events and no exported Metal command-buffer
error rows. The desktop was locked, so this run did not provide a screenshot or
pixel-level visual assertion.

The pacing revision was smoke-tested with Temporal 0.5 plus frame generation
after a clean `build`. It entered the spectator `New World`, reported the first
accepted `reset=YES` frame at `854x480 -> 1708x960`, and ran for about a minute
without a crash or native MetalFX failure. Its follow-up trace is
`/tmp/metallum-metal-temporal05-paced.trace`; the trace contains 652 paired
interpolated/rendered PresentThread submissions and zero exported
`metal-command-buffer-error` rows. The 401 profile/Realms requests in the log
are expected for the offline Fabric account and are unrelated to rendering.

After the frame-generation logging fix, a second runtime smoke test entered the
same spectator world and showed one accepted `reset=YES` queue message with no
per-frame native logging. The scoped validation and frame-generation paths still
need an unlocked visual pass for foliage, glass, particles, and camera-motion
artifact inspection.
