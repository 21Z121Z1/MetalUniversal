#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

TARGET_BRANCH="agent/unified-render-eval-performance"
SOURCE_BRANCH="codex/eval-framework-v0"

git config user.name "MetalUniversal Eval Integrator"
git config user.email "actions@users.noreply.github.com"
git fetch origin "$SOURCE_BRANCH"

if ! git merge-base --is-ancestor "origin/$SOURCE_BRANCH" HEAD; then
  set +e
  git merge --no-ff --no-commit "origin/$SOURCE_BRANCH"
  merge_status=$?
  set -e
  if (( merge_status != 0 )); then
    while IFS= read -r conflicted; do
      [[ -n "$conflicted" ]] || continue
      git checkout --ours -- "$conflicted"
      git add "$conflicted"
    done < <(git diff --name-only --diff-filter=U)
  fi
  git add -A
  git commit -m "merge(eval): preserve render-eval framework history"
fi

python3 <<'PY'
import json
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"expected patch anchor missing in {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


timing = "src/main/java/com/metallum/client/metal/render/MetalGpuTimingRecorder.java"
replace_once(
    timing,
    '    private static final boolean ENABLED = Boolean.getBoolean("metallum.validation.gpuTiming")\n            || Boolean.getBoolean("metallum.metalfx.debug");',
    '    private static final boolean ENABLED = Boolean.getBoolean("metallum.validation.gpuTiming")\n            || Boolean.getBoolean("metallum.metalfx.debug")\n            || Boolean.getBoolean("metallum.opt.terrainAdaptiveScheduling")\n            || Boolean.getBoolean("metallum.opt.terrainSchedulingTelemetry");'
)
replace_once(
    timing,
    '    private static long renderEncoderCacheHits;\n',
    '    private static long renderEncoderCacheHits;\n    private static long latestGpuNanos;\n'
)
replace_once(
    timing,
    '        SAMPLES.add(new Sample(submitIndex, start, end));\n',
    '        SAMPLES.add(new Sample(submitIndex, start, end));\n        latestGpuNanos = Math.max(1L, Math.round((end - start) * 1_000_000_000.0));\n'
)
replace_once(
    timing,
    '        renderEncoderCacheHits = 0L;\n',
    '        renderEncoderCacheHits = 0L;\n        latestGpuNanos = 0L;\n'
)
replace_once(
    timing,
    '    public static synchronized List<Sample> snapshot() {\n        return List.copyOf(SAMPLES);\n    }\n',
    '    public static synchronized List<Sample> snapshot() {\n        return List.copyOf(SAMPLES);\n    }\n\n    /** Latest completed GPU service duration, or zero when unavailable. */\n    public static synchronized long latestGpuNanos() {\n        return latestGpuNanos;\n    }\n'
)

bridge = "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
replace_once(
    bridge,
    '            setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));\n',
    '            setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));\n            systemThermalState = optionalDowncall(lookup, "metallum_system_thermal_state", FunctionDescriptor.of(INT));\n'
)
replace_once(
    bridge,
    '    private static final MethodHandle setDebugLabelsEnabled;\n',
    '    private static final MethodHandle setDebugLabelsEnabled;\n    @Nullable\n    private static final MethodHandle systemThermalState;\n'
)
replace_once(
    bridge,
    '    public static double metallum_NSWindow_backingScaleFactor(final MemorySegment window) {\n',
    '    /** Returns ProcessInfo thermalState (0 nominal through 3 critical), or -1. */\n    public static int metallum_system_thermal_state() {\n        if (systemThermalState == null) {\n            return -1;\n        }\n        try {\n            return (int) systemThermalState.invokeExact();\n        } catch (Throwable ignored) {\n            return -1;\n        }\n    }\n\n    public static double metallum_NSWindow_backingScaleFactor(final MemorySegment window) {\n'
)

swift = Path("src/main/native/MetallumNative.swift")
swift_text = swift.read_text(encoding="utf-8")
symbol = '@_cdecl("metallum_system_thermal_state")'
if symbol not in swift_text:
    anchor = '#if os(macOS) && canImport(MetalFX)\nimport MetalFX\n#endif\n'
    addition = '''#if os(macOS) && canImport(MetalFX)\nimport MetalFX\n#endif\n\n@_cdecl("metallum_system_thermal_state")\npublic func metallum_system_thermal_state() -> Int32 {\n    switch ProcessInfo.processInfo.thermalState {\n    case .nominal: return 0\n    case .fair: return 1\n    case .serious: return 2\n    case .critical: return 3\n    @unknown default: return -1\n    }\n}\n'''
    if anchor not in swift_text:
        raise SystemExit("Swift thermal export anchor missing")
    swift.write_text(swift_text.replace(anchor, addition, 1), encoding="utf-8")

mixins_path = Path("src/main/resources/metallum.mixins.json")
config = json.loads(mixins_path.read_text(encoding="utf-8"))
client = config["client"]
additions = [
    "sodium.MinecraftTerrainSchedulingMixin",
    "sodium.RenderRegionManagerTerrainSchedulingMixin",
    "sodium.RenderSectionManagerTerrainSchedulingMixin",
    "sodium.SodiumWorldRendererTerrainSchedulingMixin",
]
insertion = client.index("sodium.DrawBackendMixin") if "sodium.DrawBackendMixin" in client else len(client)
for name in reversed(additions):
    if name not in client:
        client.insert(insertion, name)
mixins_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
PY

python3 -m json.tool src/main/resources/metallum.mixins.json >/dev/null
./gradlew --no-daemon compileJava test \
  --tests com.metallum.client.terrain.TerrainSchedulingControllerTest \
  --tests com.metallum.client.terrain.TerrainNativeSignalTest \
  --tests com.metallum.mixin.MetallumMixinRegistrationTest

rm -f .github/workflows/unified-eval-bootstrap.yml
rm -f .github/workflows/unified-eval-pr-bootstrap.yml
rm -f scripts/agent/bootstrap_unified_eval.sh

git add -A
git commit -m "refactor(eval): reconcile correctness and performance foundations [unified-eval-generated]"
git push origin "HEAD:$TARGET_BRANCH"
