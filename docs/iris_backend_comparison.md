# Iris backend comparison

The acceptance oracle is the same BSL pack, world, camera, time and framebuffer
extent on Vulkan and Metal. The capture hook is opt-in and reads the
backend-neutral `RenderTarget` through Blaze3D; it does not use a desktop
screenshot.

## Capture

Run the client twice from the Iris worktree, using the same save and camera:

```text
./gradlew runClient \
  -Dmetallum.backend.compare.enabled=true \
  -Dmetallum.backend.compare.name=metal \
  -Dmetallum.backend.compare.frames=90 \
  -Dmetallum.backend.compare.output=build/backend-compare \
  -Dmetallum.metalfx.mode=OFF \
  -Dmetallum.iris.semantic=true \
  -Dmetallum.metal.hud=true
```

For the Vulkan run, set `preferredGraphicsBackend:"vulkan"` in the selected
instance's `options.txt`, leave `startedCleanly:true`, and use the same command
with `metallum.backend.compare.name=vulkan`. Restore the profile to its normal
value after the run.

Each backend directory contains `frame-*.bin`, `frame-*.png`, and metadata
JSON. The raw bytes are RGBA8 in backend-native copy order. The PNG is a view of
those bytes for inspection; it is not used as the numeric oracle.

```text
./gradlew backendFrameCompare
```

The comparison is exact by default. A nonzero tolerance must be supplied
explicitly with `-PbackendCompareMaxChannelDelta` and
`-PbackendCompareMaxDifferingPixels`; tolerance does not turn missing frames or
different dimensions into a pass.

## Current boundary

Iris 1.11.2's `IrisMixinPlugin` disables its normal shader-rendering mixins when
`preferredGraphicsBackend` contains `vulkan`; only `VKOnly` mixins remain. Thus
the current Metal branch cannot use a Vulkan run from this exact instance as a
Vulkan+BSL semantic oracle. A valid cross-backend comparison needs either an
active Vulkan Iris path or a separately verified native Iris/OpenGL reference.

Potato has passed the local, visible native-Metal gate, including stable frames,
motion, and shader reload. BSL's shadow and richer lighting semantics remain a
separate acceptance gate. A successful raw-frame capture or headless CI run
alone must not be called full BSL adaptation.
