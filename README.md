# MetalUniversal

[简体中文](README.zh-CN.md)

MetalUniversal is a Fabric rendering backend that preserves Minecraft Java rendering semantics and executes GPU work through native Metal on Apple Silicon. The codebase is also called Metallum in package names and build artifacts.

## Current support

| Area | Current state |
| --- | --- |
| Minecraft | 26.3 is the active source and build target. The current JAR does not claim 26.2 compatibility. |
| Platform | macOS on Apple Silicon is the primary product target. |
| Renderer | Vanilla uses the common renderer. Sodium and Iris are optional adapters. |
| Metal | Metal 3 is the maintained fallback. Metal 4 is an experimental capability-specific execution path. |
| MetalFX | Temporal scaling and frame generation are optional and experimental. |
| Mobile | iOS and Amethyst work remains on an isolated platform lineage and is not part of the current macOS artifact. |

The repository does not use Vulkan or MoltenVK as the Metal execution runtime. SPIR-V remains useful as shader intermediate representation before Metal Shading Language generation.

## Build

Requirements:

- Java 25
- Xcode with the macOS 26 SDK for the native module
- An Apple Silicon macOS system or a compatible macOS runner for the shipping native build

Build the Java and native macOS artifact:

```bash
./gradlew --no-daemon buildMacNative build \
  -x metalFrameGenerationPresentationValidation \
  -x metalFxOffscreenValidation
```

Run Java and contract tests without a native Apple environment:

```bash
./gradlew --no-daemon compileJava test \
  -x buildMacNative -x buildIOSNative -x buildIOSSpvc
```

Generate a local, ignored Minecraft source reference for the version in `gradle.properties`:

```bash
bash scripts/minecraft-reference.sh --print-path
```

## Architecture

The renderer has one semantic path:

```text
Minecraft 26.3 and RenderPearl semantics
  -> vanilla, Sodium, or Iris adapter
  -> stable resource and generation identity
  -> minimal execution plan
  -> Java Metal backend
  -> Java/FFM native ABI
  -> Swift Metal execution
  -> Apple Silicon GPU
```

Metal 4 lowers the same execution plan through additional capabilities. MetalFX consumes renderer outputs as an optional presentation feature. Neither creates a second renderer architecture.

Terrain work is judged by visible correctness and latency, not only build throughput. Publication must reject stale generations. Camera movement changes visibility and priority; it does not by itself invalidate correct geometry.

## Validation

GitHub-hosted checks can prove compilation, unit and contract behavior, ABI shape, exported symbols, and some hosted Metal paths for an exact commit. They do not prove physical GPU behavior, WindowServer presentation, Minecraft visual parity, stable performance, thermals, variable refresh timing, or MetalFX image quality.

Use `bash scripts/agent/verify_unified_eval.sh` for the maintained static evidence contract. Physical Apple Silicon results must record the source commit, JAR and native identities, environment, scenario, activation, correctness result, and measurement.

## Contributing

Read `AGENTS.md`, then inspect the source that owns the behavior and its nearest tests. Do not infer current architecture from old branch names, dated reports, or migration plans.

The canonical development line is `integration/metaluniversal`. `master` remains the existing stable line. Research history is preserved by `research/modernization-backlog`; mobile work remains isolated.

## License

See `LICENSE`.