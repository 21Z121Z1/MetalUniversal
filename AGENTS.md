# MetalUniversal agent guide

This file applies to the whole repository. Read a more specific `AGENTS.md` only when one exists in the directory that you change.

## Objective

MetalUniversal preserves Minecraft Java rendering semantics while it lowers work to a native Metal backend for Apple Silicon. The current product line targets Minecraft 26.3. Vanilla is a real client of the common renderer. Sodium and Iris are optional adapters.

## Authority

Use this order when facts disagree:

1. Source code, tests, and build configuration at the exact commit.
2. Exact-head CI evidence for that commit.
3. Current contract documents and architecture decision records.
4. Git history, old reports, branch names, and handoff notes.

Do not treat a branch name, an old roadmap, or a generated status file as product authority.

## Start

Read `gradle.properties`, `build.gradle`, the files that own the behavior, and their nearest tests. Do not preload historical reports. Use `scripts/minecraft-reference.sh --print-path` only when the task needs Minecraft source evidence.

## Architecture invariants

- The GPU execution path is native Metal. SPIR-V can be a shader intermediate representation, but Vulkan and MoltenVK are not renderer runtimes.
- Common rendering semantics must work without Sodium or Iris.
- Sodium and Iris adapt producer-specific semantics to common contracts. They do not own the common renderer.
- Metal 3 is the maintained fallback. Metal 4 is a capability-specific lowering path, not a second renderer.
- MetalFX temporal scaling and frame generation are optional presentation features. They do not define core rendering or hide source performance.
- Java, the Foreign Function and Memory API, and Swift form one native ABI contract. Check symbol names, fixed-width types, size, alignment, count, stride, ownership, lifetime, completion, and failure behavior together.
- Terrain work and publication must be generation-safe. Old CPU work cannot replace newer geometry. Submitted GPU work retires through its real lifetime.
- Prefer one explicit owner, immutable plans, direct code, and conservative fallback. Remove forwarding layers and parallel implementations that have no independent contract.
- Keep the iOS and Amethyst lifecycle isolated from the macOS renderer. Share only contracts that are truly platform-neutral.

## Validation

Run the cheapest relevant check first.

For Java and contract changes:

```bash
./gradlew --no-daemon compileJava test -x buildMacNative -x buildIOSNative -x buildIOSSpvc
```

For repository evidence contracts:

```bash
bash scripts/agent/verify_unified_eval.sh
```

For the shipping macOS native module on a compatible runner:

```bash
./gradlew --no-daemon buildMacNative build -x metalFrameGenerationPresentationValidation -x metalFxOffscreenValidation
```

Hosted compilation does not prove physical Metal behavior, WindowServer presentation, Minecraft visual parity, stable performance, thermal behavior, or MetalFX quality. Record those as physical Apple Silicon validation.

## Git safety

`main` is the canonical 26.3 development branch. Resolve its live HEAD before work. Historical branches and their reports are evidence, not alternate development authority. Keep platform-specific iOS lifecycle contracts isolated within the common renderer architecture.

Do not force-push shared history. Do not merge an old task branch wholesale. Compare it with the confirmed target, port only a coherent superior delta with its tests, and then retire the task branch after the result is reachable.
