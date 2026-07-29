# MetalFX / Frame Generation input-contract handoff (2026-07-30)

Branch: `codex/metalfx-input-contract`, based on `fork/master` at `98ee8a9`.

## Implemented

- Split scene color, camera/depth, finalized motion, reactive coverage, UI color,
  and frame-generation color into stamped Java/native contracts.
- Added real entity, feature, item-frame, crystal, block-entity, and piston motion
  capture paths. The two feature-renderer mixin owners that caused the reported
  startup crash are covered by bytecode-owner regression tests.
- Added exact-stamp history commit/reject handling and a scene+UI real-frame
  fallback for late admission or native enqueue failure.
- Isolated Metal 4 presenter argument tables per in-flight slot and retained
  private ring resources across partial encode failures.
- Automated client validation restores only `run/options.txt`'s
  `startedCleanly` marker atomically and forces `--graphicsBackend default` for
  that process. Launcher profiles are not changed.

## Gates

The shipped safety constant remains:

```java
OBJECT_MOTION_PRODUCER_CONNECTED = false;
```

QA can exercise the implementation without changing the shipped default:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew minecraftMetalFxClientValidation --no-daemon \
  -Dmetallum.metalfx.frameGeneration=true \
  -Dmetallum.metalfx.objectMotionProducer=true
```

## Verification

- Java fallback state and MRT input-contract tests passed.
- MetalFX offscreen validation passed 12 scenarios.
- Frame Generation lifecycle passed 13/13 scenarios.
- macOS and iOS native libraries compiled.
- A real client startup restored `startedCleanly=true` without the recovery
  warning, selected the Metal backend, and logged both QA gates as enabled.
- The unattended client could not complete the scripted readback timeline while
  its window was not being composited: sampling showed the render thread waiting
  in `CAMetalLayer.nextDrawable()`. No crash or Metal command-buffer failure was
  observed. Visible smoothness, tearing, and generated-frame quality remain
  human QA rather than a condition for this branch push.

Do not commit `logs/`, `run/`, validation artifacts, or Launcher files.
