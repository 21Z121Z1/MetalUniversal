#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORLD="${WORLD:-}"
BLOCKS="${BLOCKS:-4}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-120}"
CORRECTNESS_GATE="${P1_CORRECTNESS_GATE:-}"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_PERFORMANCE_OUT:-$RUN_ROOT/p1-metal4-main-performance-$STAMP}"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "P1 physical performance requires an Apple-silicon Mac" >&2
  exit 2
fi
if [[ -z "$WORLD" || ! -d "run/saves/$WORLD" ]]; then
  echo "WORLD must name an existing validation world under run/saves/" >&2
  exit 2
fi
if [[ -z "$CORRECTNESS_GATE" || ! -s "$CORRECTNESS_GATE" ]]; then
  echo "P1_CORRECTNESS_GATE must point to a passing pair-decision.json from the physical correctness runner" >&2
  exit 2
fi
if ! [[ "$BLOCKS" =~ ^[0-9]+$ ]] || (( BLOCKS < 4 )); then
  echo "P1 performance requires BLOCKS >= 4" >&2
  exit 2
fi
if ! [[ "$WARMUP_SECONDS" =~ ^[0-9]+$ ]] || (( WARMUP_SECONDS < 30 )); then
  echo "P1 performance requires WARMUP_SECONDS >= 30" >&2
  exit 2
fi
if ! [[ "$SAMPLE_SECONDS" =~ ^[0-9]+$ ]] || (( SAMPLE_SECONDS < 120 )); then
  echo "P1 performance requires SAMPLE_SECONDS >= 120" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "P1 physical performance requires a clean worktree" >&2
  git status --short >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
mkdir -p "$OUT/correctness" "$OUT/trials"

python3 - "$CORRECTNESS_GATE" "$HEAD_SHA" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
head = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))
if data.get("state") != "pass":
    raise SystemExit(f"P1 physical correctness gate is not passing: {data.get('state')}")
identity = data.get("identity")
if not isinstance(identity, dict) or identity.get("sourceSha") != head:
    raise SystemExit(
        f"P1 correctness gate does not belong to current HEAD {head}: {identity}"
    )
PY
cp "$CORRECTNESS_GATE" "$OUT/correctness/physical-pair-decision.json"

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/metallum-p1-perf.XXXXXX")"
SNAPSHOT="$TMP_ROOT/world"
EVAL_WORLD="metallum-p1-perf-$$"
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
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
print(h.hexdigest())
PY
}

reset_eval_world() {
  rm -rf "$EVAL_WORLD_PATH"
  mkdir -p "$(dirname "$EVAL_WORLD_PATH")"
  cp -a "$SNAPSHOT" "$EVAL_WORLD_PATH"
}

common_args() {
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
  common_args
  case "$lane" in
    baseline) printf '%s\n' "-Dmetallum.opt.metal4MainRenderer=false" ;;
    candidate) printf '%s\n' "-Dmetallum.opt.metal4MainRenderer=true" ;;
    *) echo "unknown P1 performance lane: $lane" >&2; return 2 ;;
  esac
}

run_task() {
  local lane="$1" task="$2" trial_dir="$3" warmup="$4" sample="$5" normalize="$6"
  local args=() arg status report
  reset_eval_world
  mkdir -p "$trial_dir/artifacts/validation"
  while IFS= read -r arg; do args+=("$arg"); done < <(lane_args "$lane")
  args+=(
    "-Dmetallum.validation.warmupSeconds=$warmup"
    "-Dmetallum.validation.sampleSeconds=$sample"
    "-Dmetallum.validation.output=$trial_dir/artifacts/validation"
    "-Dmetallum.validation.sourceCommit=$HEAD_SHA"
  )
  printf '%s\n' "${args[@]}" > "$trial_dir/properties.txt"
  {
    printf '[command] ./gradlew --no-daemon %q -Pworld=%q' "$task" "$EVAL_WORLD"
    printf ' %q' "${args[@]}"
    printf '\n'
  } > "$trial_dir/command.txt"

  set +e
  MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=0 \
    ./gradlew --no-daemon "$task" "-Pworld=$EVAL_WORLD" "${args[@]}" \
      2>&1 | tee "$trial_dir/client.log"
  status=${PIPESTATUS[0]}
  set -e
  printf '%d\n' "$status" > "$trial_dir/exit-status.txt"
  if (( status != 0 )); then
    return "$status"
  fi

  if [[ "$normalize" == "true" ]]; then
    report="$(find "$trial_dir" -type f -name native-fullscreen-baseline.json -print | head -n 1)"
    if [[ -z "$report" ]]; then
      echo "P1 performance trial produced no native-fullscreen-baseline.json: $trial_dir" >&2
      printf '2\n' > "$trial_dir/exit-status.txt"
      return 2
    fi
    python3 scripts/agent/check_metal4_main_trial.py "$report" \
      --expected "$lane" --output "$trial_dir/metal4-main-admission.json"
    python3 scripts/agent/normalize_unified_trial.py "$trial_dir"
  fi
}

WORLD_SHA="$(world_sha256)"
python3 - "$OUT/environment.json" "$HEAD_SHA" "$WORLD" "$WORLD_SHA" \
  "$BLOCKS" "$WARMUP_SECONDS" "$SAMPLE_SECONDS" <<'PY'
import json, os, pathlib, platform, subprocess, sys
path = pathlib.Path(sys.argv[1])
def cmd(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except Exception as exc:
        return f"unavailable: {exc}"
path.write_text(json.dumps({
    "schema_version": 1,
    "stage": "P1-metal4-main-production",
    "kind": "physical-performance-abba",
    "sourceSha": sys.argv[2],
    "world": sys.argv[3],
    "worldSha256": sys.argv[4],
    "pairedBlocks": int(sys.argv[5]),
    "warmupSeconds": int(sys.argv[6]),
    "sampleSeconds": int(sys.argv[7]),
    "pairing": "ABBA-equivalent alternating order",
    "platform": platform.platform(),
    "machine": platform.machine(),
    "macOS": cmd("sw_vers"),
    "xcode": cmd("xcodebuild", "-version"),
    "java": cmd("java", "-version"),
    "display": os.environ.get("METALLUM_EVAL_DISPLAY", "operator-must-record"),
    "powerState": os.environ.get("METALLUM_EVAL_POWER_STATE", "operator-must-record"),
    "laneContract": {
        "common": "Metal4 compiler + present + explicit residency; MetalFX/FG and unrelated experimental lanes off",
        "baseline": "metal4MainRenderer=false",
        "candidate": "metal4MainRenderer=true"
    }
}, indent=2) + "\n", encoding="utf-8")
PY

# Re-prove semantic correctness on the immutable performance world before timing.
run_task baseline renderContractMinecraftValidation \
  "$OUT/correctness/render-contract-baseline" 0 0 false
run_task candidate renderContractMinecraftValidation \
  "$OUT/correctness/render-contract-candidate" 0 0 false
python3 - "$OUT/correctness/gate.json" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "schema_version": 1,
    "status": "pass",
    "reason": "physical production pair gate plus same-world baseline/candidate render-contract validation passed"
}, indent=2) + "\n", encoding="utf-8")
PY

# Odd blocks A->B, even blocks B->A yields A B B A A B B A ...
for ((block=1; block<=BLOCKS; block++)); do
  block_dir="$OUT/trials/block-$(printf '%02d' "$block")"
  if (( block % 2 == 1 )); then
    order=(baseline candidate)
  else
    order=(candidate baseline)
  fi
  for lane in "${order[@]}"; do
    run_task "$lane" minecraftNativeRenderEfficiencyValidation \
      "$block_dir/$lane" "$WARMUP_SECONDS" "$SAMPLE_SECONDS" true
  done
done

python3 scripts/agent/analyze_unified_eval.py "$OUT"
python3 - "$OUT/decision.json" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
if data.get("state") != "accepted-candidate":
    raise SystemExit(
        f"P1 performance candidate was not accepted: {data.get('state')} — {data.get('reason')}"
    )
PY

echo "P1 physical paired performance: ACCEPTED"
echo "Evidence: $OUT"
