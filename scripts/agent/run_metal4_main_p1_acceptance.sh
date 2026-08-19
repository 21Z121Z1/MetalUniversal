#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORLD="${WORLD:-}"
BLOCKS="${BLOCKS:-4}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-120}"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_OUT:-$RUN_ROOT/p1-metal4-main-$STAMP}"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "P1 physical acceptance requires an Apple-silicon Mac" >&2
  exit 2
fi
if [[ -z "$WORLD" || ! -d "run/saves/$WORLD" ]]; then
  echo "WORLD must name an existing validation world under run/saves/" >&2
  exit 2
fi
if ! [[ "$BLOCKS" =~ ^[0-9]+$ ]] || (( BLOCKS < 4 )); then
  echo "P1 performance acceptance requires BLOCKS >= 4" >&2
  exit 2
fi
if ! [[ "$WARMUP_SECONDS" =~ ^[0-9]+$ ]] || (( WARMUP_SECONDS < 30 )); then
  echo "P1 performance acceptance requires WARMUP_SECONDS >= 30" >&2
  exit 2
fi
if ! [[ "$SAMPLE_SECONDS" =~ ^[0-9]+$ ]] || (( SAMPLE_SECONDS < 120 )); then
  echo "P1 performance acceptance requires SAMPLE_SECONDS >= 120" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "P1 acceptance requires a clean worktree so source/binary identity is exact" >&2
  git status --short >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
mkdir -p "$OUT/correctness" "$OUT/trials"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/metallum-p1.XXXXXX")"
SNAPSHOT="$TMP_ROOT/world"
EVAL_WORLD="metallum-p1-eval-$$"
EVAL_WORLD_PATH="$ROOT/run/saves/$EVAL_WORLD"

cleanup() {
  rm -rf "$EVAL_WORLD_PATH"
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT INT TERM

cp -a "run/saves/$WORLD" "$SNAPSHOT"

world_sha256() {
  python3 - "$SNAPSHOT" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
h = hashlib.sha256()
for path in sorted(p for p in root.rglob('*') if p.is_file() and p.name != 'session.lock'):
    rel = path.relative_to(root).as_posix().encode()
    h.update(len(rel).to_bytes(8, 'big'))
    h.update(rel)
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
print(h.hexdigest())
PY
}

reset_eval_world() {
  rm -rf "$EVAL_WORLD_PATH"
  mkdir -p "$(dirname "$EVAL_WORLD_PATH")"
  cp -a "$SNAPSHOT" "$EVAL_WORLD_PATH"
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

copy_if_exists() {
  local source="$1" destination="$2"
  if [[ -e "$source" ]]; then
    mkdir -p "$(dirname "$destination")"
    cp -R "$source" "$destination"
  fi
}

common_renderer_args() {
  printf '%s\n' \
    "-Dmetallum.iris.semantic=true" \
    "-Dmetallum.metalfx.mode=OFF" \
    "-Dmetallum.metalfx.frameGeneration=false" \
    "-Dmetallum.metalfx.objectMotionProducer=false" \
    "-Dmetallum.metal.hud=false" \
    "-Dmetallum.iris.performanceCounters=true" \
    "-Dmetallum.validation.gpuTiming=true" \
    "-Dmetallum.validation.gpuPassTiming=true" \
    "-Dmetallum.iris.experimental.passFusion=false" \
    "-Dmetallum.iris.passFusion=false" \
    "-Dmetallum.iris.computeGrouping=false" \
    "-Dmetallum.iris.experimental.computeGrouping=false" \
    "-Dmetallum.iris.depthLiveness=false" \
    "-Dmetallum.iris.experimental.resourcePruning=false" \
    "-Dmetallum.iris.argumentTables=false" \
    "-Dmetallum.iris.experimental.argumentTables=false" \
    "-Dmetallum.opt.terrainAdaptiveScheduling=false" \
    "-Dmetallum.opt.terrainSchedulingTelemetry=false" \
    "-Dmetallum.opt.metal4=true" \
    "-Dmetallum.opt.metal4Compiler=true" \
    "-Dmetallum.opt.metal4Present=true" \
    "-Dmetallum.opt.residencySet=true" \
    "-Dmetallum.opt.metal4MainQueuePilot=false" \
    "-Dmetallum.hotpath.telemetry=false"
}

lane_args() {
  local lane="$1"
  common_renderer_args
  case "$lane" in
    baseline) printf '%s\n' "-Dmetallum.opt.metal4MainRenderer=false" ;;
    candidate) printf '%s\n' "-Dmetallum.opt.metal4MainRenderer=true" ;;
    *) echo "unknown P1 lane: $lane" >&2; return 2 ;;
  esac
}

run_task() {
  local lane="$1" task="$2" output="$3" warmup="$4" sample="$5"
  local args=() arg status report admission_status=0 normalize_status=0
  reset_eval_world
  mkdir -p "$output/artifacts/validation"
  while IFS= read -r arg; do args+=("$arg"); done < <(lane_args "$lane")
  args+=(
    "-Dmetallum.validation.warmupSeconds=$warmup"
    "-Dmetallum.validation.sampleSeconds=$sample"
    "-Dmetallum.validation.output=$output/artifacts/validation"
    "-Dmetallum.validation.sourceCommit=$HEAD_SHA"
  )
  printf '%s\n' "${args[@]}" > "$output/properties.txt"
  {
    printf '[command] ./gradlew --no-daemon %q -Pworld=%q' "$task" "$EVAL_WORLD"
    printf ' %q' "${args[@]}"
    printf '\n'
  } > "$output/command.txt"
  set +e
  ./gradlew --no-daemon "$task" "-Pworld=$EVAL_WORLD" "${args[@]}" 2>&1 | tee "$output/client.log"
  status=${PIPESTATUS[0]}
  set -e
  printf '%d\n' "$status" > "$output/exit-status.txt"
  if (( status != 0 )); then
    return "$status"
  fi
  if [[ "$task" != "minecraftNativeRenderEfficiencyValidation" ]]; then
    return 0
  fi

  report="$(find "$output" -type f -name native-fullscreen-baseline.json -print | head -n 1)"
  if [[ -z "$report" ]]; then
    echo "performance trial produced no native-fullscreen-baseline.json: $output" >&2
    printf '2\n' > "$output/exit-status.txt"
    return 2
  fi
  set +e
  python3 scripts/agent/check_metal4_main_trial.py "$report" \
    --expected "$lane" --output "$output/metal4-main-admission.json"
  admission_status=$?
  set -e
  if (( admission_status != 0 )); then
    printf '%d\n' "$admission_status" > "$output/exit-status.txt"
    return "$admission_status"
  fi
  set +e
  python3 scripts/agent/normalize_unified_trial.py "$output"
  normalize_status=$?
  set -e
  return "$normalize_status"
}

# Structural gate first. It proves the per-slot table model and allocator/completion invariants.
python3 scripts/agent/verify_metal4_main_hotpath.py \
  --output "$OUT/correctness/metal4-main-hotpath.json"

# Build one exact production artifact for all physical correctness/performance lanes.
./gradlew --no-daemon buildMacNative jar validationJar verifyProductionJarIsolation \
  2>&1 | tee "$OUT/correctness/build.log"
JAR="$(find build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-dev.jar' ! -name '*-validation.jar' -print | head -n 1)"
if [[ -z "$JAR" || ! -s "$JAR" ]]; then
  echo "could not resolve production MetalUniversal JAR" >&2
  exit 2
fi
DYLIB="src/main/resources/natives/macos/libmetallum.dylib"
test -s "$DYLIB"
WORLD_SHA="$(world_sha256)"

python3 - "$OUT/environment.json" "$HEAD_SHA" "$WORLD" "$WORLD_SHA" \
  "$(sha256_file "$JAR")" "$(sha256_file "$DYLIB")" "$BLOCKS" "$WARMUP_SECONDS" "$SAMPLE_SECONDS" <<'PY'
import json, pathlib, platform, subprocess, sys
path = pathlib.Path(sys.argv[1])
def command(*args):
    try: return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except Exception as exc: return f"unavailable: {exc}"
data = {
  "schema_version": 1,
  "stage": "P1-metal4-main-production",
  "candidate_sha": sys.argv[2],
  "world": sys.argv[3],
  "world_sha256": sys.argv[4],
  "production_jar_sha256": sys.argv[5],
  "native_dylib_sha256": sys.argv[6],
  "paired_blocks": int(sys.argv[7]),
  "warmup_seconds": int(sys.argv[8]),
  "sample_seconds": int(sys.argv[9]),
  "pairing": "ABBA-equivalent alternating order",
  "platform": platform.platform(),
  "machine": platform.machine(),
  "macos": command('sw_vers'),
  "xcode": command('xcodebuild','-version'),
  "java": command('java','-version'),
  "display": __import__('os').environ.get('METALLUM_EVAL_DISPLAY', 'operator-must-record'),
  "power_state": __import__('os').environ.get('METALLUM_EVAL_POWER_STATE', 'operator-must-record'),
  "lane_contract": {
    "common": "Metal4 compiler + present + explicit residency; MetalFX/FG and unrelated experimental lanes off",
    "baseline": "metal4MainRenderer=false",
    "candidate": "metal4MainRenderer=true",
  },
}
path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
PY

# Physical E2E closes the capability/correctness side: actual Metal 4 main renderer,
# explicit residency, framebuffer readback, present, reload and post-drain telemetry.
E2E_ROOT="$ROOT/.github/ci/minecraft-e2e"
rm -rf "$E2E_ROOT/build/evidence" "$E2E_ROOT/build/run/clientGameTest"
./gradlew -p .github/ci/minecraft-e2e --no-daemon \
  "-PmetallumJar=$ROOT/$JAR" -Pp1Metal4MainRenderer=true runProductionClientGameTest \
  2>&1 | tee "$OUT/correctness/physical-e2e.log"
copy_if_exists "$E2E_ROOT/build/evidence" "$OUT/correctness/physical-e2e/evidence"
copy_if_exists "$E2E_ROOT/build/run/clientGameTest/logs" "$OUT/correctness/physical-e2e/logs"
P1_EVIDENCE="$OUT/correctness/physical-e2e/evidence/metal4-main-renderer-evidence.json"
test -s "$P1_EVIDENCE"
python3 - "$P1_EVIDENCE" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f: data = json.load(f)
if data.get('status') != 'pass' or data.get('mainRendererEngagementFraction') != 1.0:
    raise SystemExit(f"physical P1 E2E did not prove full engagement: {data}
")
metrics = data.get('metrics', {})
for key in ('metal4.argumentTableAllocationsDuringEncoding', 'metal4.computeTableOverflow'):
    if metrics.get(key) != 0:
        raise SystemExit(f"{key} failed: {metrics}")
if metrics.get('metal4.renderTableHighWater') != 1:
    raise SystemExit(f"render-table high water failed: {metrics}")
if data.get('presentationHealthy') is not True:
    raise SystemExit(f"physical P1 presentation was unhealthy: {data}")
PY

# Exact semantic correctness on the same immutable world snapshot, with the
# Metal 4 dependency set equal in both lanes and only the main renderer toggled.
run_task baseline renderContractMinecraftValidation "$OUT/correctness/render-contract-baseline" 0 0
run_task candidate renderContractMinecraftValidation "$OUT/correctness/render-contract-candidate" 0 0
python3 - "$OUT/correctness/gate.json" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
  "schema_version": 1,
  "status": "pass",
  "reason": "physical P1 E2E and baseline/candidate render-contract validation passed",
}, indent=2) + '\n', encoding='utf-8')
PY

# Same-machine paired performance. Odd blocks run A->B, even blocks B->A.
for ((block=1; block<=BLOCKS; block++)); do
  block_dir="$OUT/trials/block-$(printf '%02d' "$block")"
  if (( block % 2 == 1 )); then order=(baseline candidate); else order=(candidate baseline); fi
  for lane in "${order[@]}"; do
    run_task "$lane" minecraftNativeRenderEfficiencyValidation \
      "$block_dir/$lane" "$WARMUP_SECONDS" "$SAMPLE_SECONDS"
  done
done

python3 scripts/agent/analyze_unified_eval.py "$OUT"
python3 - "$OUT/decision.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f: decision = json.load(f)
if decision.get('state') != 'accepted-candidate':
    raise SystemExit(f"P1 performance acceptance did not close: {decision}")
print(json.dumps(decision, indent=2))
PY

printf 'P1 Metal 4 main-renderer acceptance: PASS\nEvidence: %s\n' "$OUT"
