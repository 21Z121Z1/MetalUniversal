# Non-Iris vanilla/Sodium Metal regression gate

Purpose: prove that the Iris semantic layer does not alter the ordinary native
Metal renderer when no shader pack is active. This gate is separate from
Potato/BSL and must run after every cross-framework change.

## Lane N0: offline ownership invariants

Required assertions:

- no selected pack means `IrisPipelineFactoryMixin` does not construct
  `MetalWorldRenderingPipeline`;
- inactive/deactivated `IrisMetalPipelineOverrides` cannot answer terrain or
  core pipeline lookups;
- retiring an old Iris generation cannot retire a newer generation;
- shader-off teardown invalidates pack PSOs and releases all generation-owned
  resources;
- native Sodium/core `RenderPipeline` identity, formats, depth/blend state and
  bind layouts are unchanged when no override is active;
- ordinary `test`, MRT, target and generic-vertex suites remain green.

This lane is headless/physical-GPU evidence only. It cannot prove a visible
Minecraft frame.

## Lane N1: same-build deterministic shaders-off A/B

Use two isolated clones of the same fixed world, settings, time, weather,
camera, framebuffer and logical capture frames:

1. control: `-Dmetallum.iris.semantic=false`, shaders disabled;
2. treatment: `-Dmetallum.iris.semantic=true`, shaders disabled.

Both runs keep:

- native Metal/CAMetalLayer;
- Sodium enabled;
- MetalFX OFF, frame generation false and object-motion producer false;
- signed JDK 25, `MTL_DEBUG_LAYER=1`,
  `MTL_SHADER_VALIDATION=0`;
- the same deterministic readiness gate and stable-frame phase.

Required machine receipt:

- no `MetalWorldRenderingPipeline` or Iris generation starts in either lane;
- `IrisMetalPipelineOverrides.active()` is absent at capture;
- native Sodium solid/cutout/translucent pipeline identities match;
- loaded/visible chunks, entity-state rows, world clock, camera and framebuffer
  match before comparing images;
- final-target dimensions and orientation metadata match;
- stable final-target buffers are byte-identical. If nondeterministic vanilla
  animation prevents exact equality, the receipt must identify the field and
  freeze it rather than loosening the image threshold;
- no crash, Metal fault, fallback backend or stale Iris resource is present.

## Lane N2: visible acceptance

Inspect one clean treatment frame plus motion after N1 passes. This is a human
gate for missing geometry, transparency ordering, sky/cloud/weather, entities,
particles, hand, text/UI and water. Internal counters or an offscreen hash do
not replace it.

## Durable gate output

Write one versioned directory under
`build/iris-runtime/non-iris-gate-<date-or-revision>/` containing:

- `settings.md`, `result.md` and the exact implementation revision/diff hash;
- control/treatment console, debug and latest logs;
- scene/readiness/entity receipts;
- final-target raw buffers, metadata and upright inspection PNGs;
- a machine-readable comparison report;
- explicit `PASS`, `PARTIAL` or `BLOCKED`.

The capture producer and offline verifier are now separate tasks. Prepare two
independent game-directory clones from the same clean source. Each clone must
contain the same `saves/New World` bytes and an existing
`config/iris.properties` with `enableShaders=false`.

Use a new versioned evidence root; the capture tasks refuse to overwrite an
existing `control` or `treatment` lane:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew minecraftNonIrisControlCapture --no-daemon \
  -PnonIrisRoot=/absolute/evidence/non-iris-gate-<revision> \
  -PnonIrisControlGameDir=/absolute/isolated/control-game

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew minecraftNonIrisTreatmentCapture --no-daemon \
  -PnonIrisRoot=/absolute/evidence/non-iris-gate-<revision> \
  -PnonIrisTreatmentGameDir=/absolute/isolated/treatment-game
```

Both tasks force native Iris correctness isolation: MetalFX `OFF`, frame
generation false, object-motion producer false, Metal HUD false,
`MTL_DEBUG_LAYER=1` and `MTL_SHADER_VALIDATION=0`. The default deterministic
scene is the accepted clear dusk fixture: clock `108500` (`12500` modulo day),
fixed camera, frozen simulation, 240 stable render polls plus 8000 ms, and
logical capture frames 160 and 220. Each task hashes every file in its world
snapshot before launching; the receipts contain that SHA-256, the scenario
identity and the absolute isolated game directory.

The capture reads `GameRenderer.mainRenderTarget()` directly. It does not call
`MetalFxManager`, so the non-Iris gate is not coupled to the optional temporal
or presentation implementation.

Then run the offline verifier:

```text
./gradlew nonIrisRegressionCompare \
  -PnonIrisRoot=/absolute/evidence/non-iris-gate-<revision>
```

It requires at least two matching frames per lane, exact entity rows and
byte-identical raw final targets. Each capture/session receipt must identify a
real Metal device, shaders disabled, no loaded pack, a
`VanillaRenderingPipeline`, no active Iris Metal generation, MetalFX OFF,
frame generation false and object-motion override false. It also requires
matching non-empty scenario/world identities and identical pre-launch world
snapshot SHA-256 values while rejecting a shared game directory. The compare
task never starts Minecraft and never removes evidence.
