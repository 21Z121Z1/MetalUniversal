# Metal 4 Iris Launcher Profile

The repository now exposes `runClientMetal4Iris`, an isolated Minecraft
launcher profile for the native Metal 4 experience. It enables the Iris
semantic path, argument tables, render and compute command packets, terrain
ICB, frame-graph optimization lanes, explicit residency and Metal 4's main
renderer. MetalFX, frame generation and Metal HUD remain disabled so the
profile is a direct renderer comparison.

The selected shader pack is still controlled by `run/config/iris.properties`.
The current checked-out configuration selects `bsl-shaders.zip`. Change that
ignored local file to `potato-shaders.zip` before launching the Potato profile.

## Launch

From the repository worktree:

```bash
./scripts/launcher/run_metal4_iris.sh
```

The default world is `Codex MobileGL Sodium A6`. Select another existing
world without editing the profile:

```bash
WORLD="Codex MobileGL Sodium A5" ./scripts/launcher/run_metal4_iris.sh
```

The script forwards additional Gradle arguments, so diagnostics can be added
without changing the profile:

```bash
./scripts/launcher/run_metal4_iris.sh --info
```

This is the reproducible Fabric/Loom development launcher profile for this
repository. It does not modify the user's official Minecraft Launcher files
or account data.
