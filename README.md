# MetalUniversal

MetalUniversal is an experimental Metal rendering backend for Minecraft Java on Apple platforms. It replaces the conventional graphics path with a Java/FFM/Swift Metal stack and is being developed toward exact Minecraft/Iris/Sodium semantic compatibility, correctness-gated Metal 3/4 optimization, terrain GPU submission and iOS support.

The stable branch is `master`; continued renderer development uses `integration/iris-metal-next`. Experimental optimizations remain fail-closed/default-off until their correctness and runtime activation are proved.

## Agent entrypoint

This repository is designed to be driven by coding agents without requiring them to reread the entire project history. Start with:

```bash
python3 scripts/agent/context.py --task "<what you are trying to change>"
```

Then follow `AGENTS.md`. The system model is `docs/agent/system-model.md`; the machine-readable knowledge router is `docs/agent/system-registry.json`.

## Architecture

```text
Minecraft / Iris / Sodium semantics
        |
semantic pass + generation-aware resource identity
        |
immutable render / terrain plans
        |
Metal execution policy (Metal 3 / Metal 4 / ICB / residency)
        |
Java FFM ABI
        |
Swift / Metal
        |
structured correctness + performance evidence
```

Primary code ownership:

| Area | Path |
|---|---|
| Metal renderer/resources | `src/main/java/com/metallum/client/metal/render/` |
| Render-contract validation | `src/main/java/com/metallum/client/validation/` |
| Terrain/runtime telemetry | `src/main/java/com/metallum/client/terrain/` |
| Java FFM bridge | `src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java` |
| Swift Metal implementation | `src/main/native/` |
| Minecraft/Iris/Sodium mixins | `src/main/java/com/metallum/mixin/` |
| Agent/evaluation harness | `scripts/agent/` |

For architecture authority and historical-document classification, see `docs/README.md`.

## Supported targets

- macOS on Apple Silicon (M1 or newer), using the repository native bridge.
- iOS arm64 through the isolated mobile/Amethyst platform line and packaged native libraries.

Exact runtime compatibility remains dependent on the current Minecraft/Fabric/Sodium/Iris pins in the build. Treat README prose as orientation; source/build metadata is authoritative.

## Build and verification

Prerequisites for full native work include Java 25, Xcode/Swift and an Apple Silicon macOS environment. Hosted CI can prove a large static/native-compile subset but cannot replace attended physical Metal/presentation acceptance.

Useful entry points:

```bash
# Agent/control-plane + headless static gates
bash scripts/agent/verify_unified_eval.sh

# Focused static compatibility
bash scripts/agent/verify.sh static

# Native/GPU-focused gate on a capable Apple host
bash scripts/agent/verify.sh gpu

# Build macOS native module
./gradlew buildMacNative

# Build iOS native libraries
./gradlew buildIOSNative
./gradlew buildIOSSpvc
```

Do not use plain `./gradlew build` as proof that the renderer works in Minecraft; runtime rendering, shader-pack correctness, presentation and performance have separate evidence gates.

Generated native artifacts live under `src/main/resources/natives/` when built. Generated worlds, shader packs, screenshots, captures and agent evidence are not repository source and must not be committed.

## Runtime evaluation

Correctness and performance share one unified evaluation platform but use different instrumentation costs. The canonical workflow is documented in `docs/agent/unified-evaluation-loop.md`.

Example:

```bash
MODE=conformance WORLD="<world>" CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh

MODE=full WORLD="<world>" BLOCKS=4 CANDIDATE_PROFILE=compute-grouping \
  bash scripts/agent/run_unified_eval_cycle.sh
```

Performance claims require passing correctness, activation proof and paired/interleaved trials. Structured reports are the acceptance authority.

## Minecraft 26.2 source reference

For tasks that require vanilla implementation details:

```bash
bash scripts/minecraft-reference.sh
```

This materializes `.minecraft-reference/26.2/sources/` locally from Mojang's client JAR. The generated tree is intentionally ignored and must not be committed.

## License

MIT License — see `LICENSE`.
