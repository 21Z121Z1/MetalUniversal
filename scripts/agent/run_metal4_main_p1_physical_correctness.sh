#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_CORRECTNESS_OUT:-$RUN_ROOT/p1-metal4-main-correctness-$STAMP}"
E2E_ROOT="$ROOT/.github/ci/minecraft-e2e"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "P1 physical correctness requires an Apple-silicon Mac" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "P1 physical correctness requires a clean worktree" >&2
  git status --short >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
mkdir -p "$OUT"

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

# Build the production bits exactly once. Both lanes consume these same files.
./gradlew --no-daemon \
  buildMacNative jar verifyProductionJarIsolation \
  -x buildIOSNative -x buildIOSSpvc \
  2>&1 | tee "$OUT/build.log"

JAR="$(find build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-dev.jar' ! -name '*-validation.jar' \
  -print | head -n 1)"
DYLIB="src/main/resources/natives/macos/libmetallum.dylib"
if [[ -z "$JAR" || ! -s "$JAR" || ! -s "$DYLIB" ]]; then
  echo "Could not resolve exact P1 production JAR/native dylib" >&2
  exit 2
fi
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
DYLIB="$(cd "$(dirname "$DYLIB")" && pwd)/$(basename "$DYLIB")"
JAR_SHA="$(sha256_file "$JAR")"
DYLIB_SHA="$(sha256_file "$DYLIB")"

python3 - "$OUT/environment.json" "$HEAD_SHA" "$JAR_SHA" "$DYLIB_SHA" <<'PY'
import json, pathlib, platform, subprocess, sys
path = pathlib.Path(sys.argv[1])
def cmd(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except Exception as exc:
        return f"unavailable: {exc}"
path.write_text(json.dumps({
    "schema_version": 1,
    "stage": "P1-metal4-main-production",
    "kind": "physical-correctness-pair",
    "sourceSha": sys.argv[2],
    "productionJarSha256": sys.argv[3],
    "nativeDylibSha256": sys.argv[4],
    "platform": platform.platform(),
    "machine": platform.machine(),
    "macOS": cmd("sw_vers"),
    "xcode": cmd("xcodebuild", "-version"),
    "java": cmd("java", "-version"),
}, indent=2) + "\n", encoding="utf-8")
PY

run_lane() {
  local lane="$1"
  local lane_out="$OUT/$lane"
  mkdir -p "$lane_out"
  rm -rf "$E2E_ROOT/build/evidence" "$E2E_ROOT/build/run/clientGameTest"

  MTL_DEBUG_LAYER=1 \
  MTL_SHADER_VALIDATION=0 \
  MTL_HUD_ENABLED=0 \
  MTLFX_HUD_ENABLED=0 \
  METALLUM_HOSTED_METAL_OFFSCREEN=true \
    ./gradlew -p .github/ci/minecraft-e2e --no-daemon \
      "-PmetallumJar=$JAR" \
      "-Pp1Metal4Lane=$lane" \
      "-Pp1SourceSha=$HEAD_SHA" \
      "-Pp1ProductionJarSha256=$JAR_SHA" \
      "-Pp1NativeDylibSha256=$DYLIB_SHA" \
      runProductionClientGameTest \
      2>&1 | tee "$lane_out/client.log"

  test -s "$E2E_ROOT/build/evidence/metal4-main-renderer-evidence.json"
  test -s "$E2E_ROOT/build/evidence/runtime-evidence.json"
  test -s "$E2E_ROOT/build/evidence/presentation-evidence.json"
  test -s "$E2E_ROOT/build/evidence/reload-evidence.json"
  test -s "$E2E_ROOT/build/evidence/readback-control/suite.json"
  cp -R "$E2E_ROOT/build/evidence" "$lane_out/evidence"
}

# Correctness A/B is intentionally ordered baseline then candidate. Performance
# acceptance uses a separate interleaved paired protocol after this gate passes.
run_lane baseline
run_lane candidate

python3 scripts/agent/check_metal4_main_e2e_pair.py \
  "$OUT/baseline/evidence/metal4-main-renderer-evidence.json" \
  "$OUT/candidate/evidence/metal4-main-renderer-evidence.json" \
  --output "$OUT/pair-decision.json"

python3 - "$OUT" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
for lane in ("baseline", "candidate"):
    evidence = root / lane / "evidence"
    runtime = json.loads((evidence / "runtime-evidence.json").read_text(encoding="utf-8"))
    presentation = json.loads((evidence / "presentation-evidence.json").read_text(encoding="utf-8"))
    reload = json.loads((evidence / "reload-evidence.json").read_text(encoding="utf-8"))
    readback = json.loads((evidence / "readback-control/suite.json").read_text(encoding="utf-8"))
    checks = {
        "backend_metal": str(runtime.get("backend", "")).lower() == "metal",
        "production_runtime": runtime.get("productionRuntime") is True,
        "world_loaded": runtime.get("worldLoaded") is True,
        "chunks_rendered": runtime.get("chunksRendered") is True,
        "render_contract_complete": runtime.get("renderContractRequestedCaptures", 0) > 0
        and runtime.get("renderContractRequestedCaptures") == runtime.get("renderContractCompletedCaptures")
        and runtime.get("renderContractFailedCaptures") == 0
        and runtime.get("renderContractDroppedCaptures") == 0,
        "presentation_healthy": presentation.get("completeAndSuccessful") is True
        and presentation.get("presentEncodeCalls", 0) > 0
        and presentation.get("presentCommandBuffersFailed") == 0,
        "reload_healthy": reload.get("reloadCompleted") is True
        and str(reload.get("backendAfterReload", "")).lower() == "metal"
        and reload.get("worldLoadedAfterReload") is True
        and reload.get("postReloadPresentationHealthy") is True,
        "readback_exact": readback.get("allExact") is True,
    }
    if not all(checks.values()):
        raise SystemExit(f"{lane} production E2E guardrail failed: {checks}")
    (root / lane / "production-e2e-checks.json").write_text(
        json.dumps({"schema_version": 1, "state": "pass", "checks": checks}, indent=2) + "\n",
        encoding="utf-8",
    )
PY

echo "P1 physical correctness pair: PASS"
echo "Evidence: $OUT"
