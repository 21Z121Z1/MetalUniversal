#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORLD="${WORLD:-}"
BLOCKS="${BLOCKS:-4}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-120}"
CORRECTNESS_GATE="${P1_CORRECTNESS_GATE:-}"
UI_SCALE="${UI_SCALE:-3}"
RENDER_DISTANCE="${RENDER_DISTANCE:-16}"
PROFILE_ID="V1"
WORLD_SCENARIO_ID="metal-validation-fixed-camera-v1"
EXPECTED_FRAMEBUFFER_WIDTH=1708
EXPECTED_FRAMEBUFFER_HEIGHT=960
CAMERA_POLICY="world-player-pose snapped to x/z block centers, y half-block, yaw nearest 90 degrees, pitch 0; held fixed by MetalValidationClient"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_PERFORMANCE_OUT:-$RUN_ROOT/p1-metal4-main-performance-$STAMP}"
OPTIONS_FILE="$ROOT/run/options.txt"
IRIS_CONFIG="$ROOT/run/config/iris.properties"

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
if ! [[ "$UI_SCALE" =~ ^[0-9]+$ ]] || (( UI_SCALE < 1 )); then
  echo "UI_SCALE must be a positive integer" >&2
  exit 2
fi
if ! [[ "$RENDER_DISTANCE" =~ ^[0-9]+$ ]] || (( RENDER_DISTANCE < 2 )); then
  echo "RENDER_DISTANCE must be an integer >= 2" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "P1 physical performance requires a clean worktree" >&2
  git status --short >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
mkdir -p "$OUT/correctness" "$OUT/trials"

read -r CORRECTNESS_JAR_SHA CORRECTNESS_DYLIB_SHA < <(
  python3 - "$CORRECTNESS_GATE" "$HEAD_SHA" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
head = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))
if data.get("state") != "pass":
    raise SystemExit(f"P1 physical correctness gate is not passing: {data.get('state')}")
identity = data.get("identity")
if not isinstance(identity, dict) or identity.get("sourceSha") != head:
    raise SystemExit(f"P1 correctness gate does not belong to current HEAD {head}: {identity}")
jar = identity.get("productionJarSha256")
dylib = identity.get("nativeDylibSha256")
if not isinstance(jar, str) or len(jar) != 64 or not isinstance(dylib, str) or len(dylib) != 64:
    raise SystemExit(f"P1 correctness gate has incomplete binary identity: {identity}")
print(jar, dylib)
PY
)
cp "$CORRECTNESS_GATE" "$OUT/correctness/physical-pair-decision.json"
python3 - "$OUT/correctness/gate.json" "$HEAD_SHA" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "schema_version": 1,
    "status": "pass",
    "source_sha": sys.argv[2],
    "reason": "exact-production physical Metal 4/residency baseline+candidate correctness pair passed before performance"
}, indent=2) + "\n", encoding="utf-8")
PY

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/metallum-p1-perf.XXXXXX")"
SNAPSHOT="$TMP_ROOT/world"
EVAL_WORLD="metallum-p1-perf-$$"
EVAL_WORLD_PATH="$ROOT/run/saves/$EVAL_WORLD"
OPTIONS_BACKUP="$TMP_ROOT/options.txt"
IRIS_BACKUP="$TMP_ROOT/iris.properties"
OPTIONS_EXISTED=false
IRIS_EXISTED=false

if [[ -f "$OPTIONS_FILE" ]]; then
  cp "$OPTIONS_FILE" "$OPTIONS_BACKUP"
  OPTIONS_EXISTED=true
fi
if [[ -f "$IRIS_CONFIG" ]]; then
  cp "$IRIS_CONFIG" "$IRIS_BACKUP"
  IRIS_EXISTED=true
fi

restore_runtime_config() {
  if [[ "$OPTIONS_EXISTED" == true ]]; then
    mkdir -p "$(dirname "$OPTIONS_FILE")"
    cp "$OPTIONS_BACKUP" "$OPTIONS_FILE"
  else
    rm -f "$OPTIONS_FILE"
  fi
  if [[ "$IRIS_EXISTED" == true ]]; then
    mkdir -p "$(dirname "$IRIS_CONFIG")"
    cp "$IRIS_BACKUP" "$IRIS_CONFIG"
  else
    rm -f "$IRIS_CONFIG"
  fi
}

cleanup() {
  rm -rf "$EVAL_WORLD_PATH"
  restore_runtime_config
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT INT TERM

pin_colon_option() {
  local path="$1" key="$2" value="$3"
  mkdir -p "$(dirname "$path")"
  touch "$path"
  python3 - "$path" "$key" "$value" <<'PY'
import pathlib, sys
path, key, value = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
prefix = key + ":"
out = []
replaced = False
for line in lines:
    if line.startswith(prefix):
        if not replaced:
            out.append(prefix + value)
            replaced = True
    else:
        out.append(line)
if not replaced:
    out.append(prefix + value)
path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY
}

pin_equals_property() {
  local path="$1" key="$2" value="$3"
  mkdir -p "$(dirname "$path")"
  touch "$path"
  python3 - "$path" "$key" "$value" <<'PY'
import pathlib, sys
path, key, value = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
prefix = key + "="
out = []
replaced = False
for line in lines:
    if line.startswith(prefix):
        if not replaced:
            out.append(prefix + value)
            replaced = True
    else:
        out.append(line)
if not replaced:
    out.append(prefix + value)
path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY
}

# V1 is explicitly the no-shader Sodium/Metal profile. Pin mutable client
# settings before snapshotting any measurements, then restore them on exit.
pin_colon_option "$OPTIONS_FILE" "guiScale" "$UI_SCALE"
pin_colon_option "$OPTIONS_FILE" "renderDistance" "$RENDER_DISTANCE"
pin_equals_property "$IRIS_CONFIG" "enableShaders" "false"

cp -a "run/saves/$WORLD" "$SNAPSHOT"

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

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
    "-Dmetallum.iris.semantic=false" \
    "-Dmetallum.metalfx.mode=OFF" \
    "-Dmetallum.metalfx.frameGeneration=false" \
    "-Dmetallum.metalfx.objectMotionProducer=false" \
    "-Dmetallum.metal.hud=false" \
    "-Dmetallum.iris.performanceCounters=true" \
    "-Dmetallum.validation.gpuTiming=true" \
    "-Dmetallum.validation.gpuPassTiming=true" \
    "-Dmetallum.validation.metalDebugLayer=0" \
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

validate_trial_identity() {
  local report="$1" lane="$2"
  python3 - "$report" "$lane" "$HEAD_SHA" "$EXPECTED_FRAMEBUFFER_WIDTH" "$EXPECTED_FRAMEBUFFER_HEIGHT" <<'PY'
import json, math, pathlib, sys
path = pathlib.Path(sys.argv[1])
lane, head = sys.argv[2], sys.argv[3]
width, height = int(sys.argv[4]), int(sys.argv[5])
data = json.loads(path.read_text(encoding="utf-8"))
problems = []
if data.get("drawableWidth") != width or data.get("drawableHeight") != height:
    problems.append(f"drawable {data.get('drawableWidth')}x{data.get('drawableHeight')} != {width}x{height}")
if not isinstance(data.get("measuredFrameIntervals"), int) or data.get("measuredFrameIntervals") <= 0:
    problems.append("no measured frame intervals")
readback = data.get("nativeMainReadback")
if not isinstance(readback, dict) or readback.get("completed") is not True or readback.get("passed") is not True:
    problems.append(f"native main readback did not pass: {readback}")
source = data.get("sourceCommit")
if source is not None and source != head:
    problems.append(f"report sourceCommit {source} != {head}")
engaged = data.get("metal4MainRendererEngaged") is True
if engaged != (lane == "candidate"):
    problems.append(f"main renderer engagement={engaged} does not match lane={lane}")
if data.get("residencySetEnabled") is not True:
    problems.append("explicit residency is not active")
if problems:
    raise SystemExit("P1 V1 trial identity/guardrail failed: " + "; ".join(problems))
PY
}

run_trial() {
  local lane="$1" trial_dir="$2"
  local args=() arg status report
  reset_eval_world
  mkdir -p "$trial_dir/artifacts/validation"
  while IFS= read -r arg; do args+=("$arg"); done < <(lane_args "$lane")
  args+=(
    "-Dmetallum.validation.warmupSeconds=$WARMUP_SECONDS"
    "-Dmetallum.validation.sampleSeconds=$SAMPLE_SECONDS"
    "-Dmetallum.validation.output=$trial_dir/artifacts/validation"
    "-Dmetallum.validation.sourceCommit=$HEAD_SHA"
  )
  printf '%s\n' "${args[@]}" > "$trial_dir/properties.txt"
  {
    printf '[command] ./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation -Pworld=%q' "$EVAL_WORLD"
    printf ' %q' "${args[@]}"
    printf '\n'
  } > "$trial_dir/command.txt"

  set +e
  MTL_DEBUG_LAYER=0 MTL_SHADER_VALIDATION=0 \
    ./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation \
      "-Pworld=$EVAL_WORLD" "${args[@]}" 2>&1 | tee "$trial_dir/client.log"
  status=${PIPESTATUS[0]}
  set -e
  printf '%d\n' "$status" > "$trial_dir/exit-status.txt"
  if (( status != 0 )); then
    return "$status"
  fi

  report="$(find "$trial_dir" -type f -name native-fullscreen-baseline.json -print | head -n 1)"
  if [[ -z "$report" ]]; then
    echo "P1 performance trial produced no native-fullscreen-baseline.json: $trial_dir" >&2
    printf '2\n' > "$trial_dir/exit-status.txt"
    return 2
  fi
  validate_trial_identity "$report" "$lane"
  python3 scripts/agent/check_metal4_main_trial.py "$report" \
    --expected "$lane" --output "$trial_dir/metal4-main-admission.json"
  python3 scripts/agent/normalize_unified_trial.py "$trial_dir"
}

WORLD_SHA="$(world_sha256)"
CAMERA_SCRIPT_SHA="$(sha256_file src/main/java/com/metallum/client/validation/MetalValidationClient.java)"
OPTIONS_SHA="$(sha256_file "$OPTIONS_FILE")"
IRIS_CONFIG_SHA="$(sha256_file "$IRIS_CONFIG")"

python3 - "$OUT/environment.json" "$HEAD_SHA" "$CORRECTNESS_JAR_SHA" "$CORRECTNESS_DYLIB_SHA" \
  "$WORLD" "$WORLD_SHA" "$PROFILE_ID" "$WORLD_SCENARIO_ID" "$UI_SCALE" "$RENDER_DISTANCE" \
  "$CAMERA_POLICY" "$CAMERA_SCRIPT_SHA" "$OPTIONS_SHA" "$IRIS_CONFIG_SHA" \
  "$BLOCKS" "$WARMUP_SECONDS" "$SAMPLE_SECONDS" <<'PY'
import json, os, pathlib, platform, subprocess, sys
path = pathlib.Path(sys.argv[1])
def cmd(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except Exception as exc:
        return f"unavailable: {exc}"
def properties(path):
    out = {}
    for raw in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value.strip()
    return out
props = properties("gradle.properties")
identity = {
    "profile_id": sys.argv[7],
    "candidate_sha": sys.argv[2],
    "production_jar_sha256": sys.argv[3],
    "native_dylib_sha256": sys.argv[4],
    "world_sha256": sys.argv[6],
    "world_scenario_id": sys.argv[8],
    "resolution": [1708, 960],
    "ui_scale": int(sys.argv[9]),
    "render_distance": int(sys.argv[10]),
    "camera_pose": sys.argv[11],
    "camera_script_sha256": sys.argv[12],
    "minecraft_version": props.get("minecraft_version", "unknown"),
    "sodium_version": props.get("sodium_version", "unknown"),
    "macos_version": cmd("sw_vers", "-productVersion"),
    "java_version": cmd("java", "-version"),
}
required = {
    "candidate_sha", "production_jar_sha256", "native_dylib_sha256", "world_sha256",
    "world_scenario_id", "resolution", "ui_scale", "render_distance", "camera_pose",
    "camera_script_sha256", "minecraft_version", "sodium_version", "macos_version", "java_version"
}
missing = sorted(key for key in required if identity.get(key) in (None, "", "unknown"))
if missing:
    raise SystemExit(f"V1 benchmark identity is incomplete: {missing}")
path.write_text(json.dumps({
    "schema_version": 2,
    "stage": "P1-metal4-main-production",
    "kind": "physical-performance-abba",
    "benchmark_contract": "docs/agent/benchmark-profiles.json",
    "identity": identity,
    "world": sys.argv[5],
    "pinned_runtime_config": {
        "options_sha256": sys.argv[13],
        "iris_properties_sha256": sys.argv[14],
        "iris_semantic": False,
        "shader_pack": None,
        "metalfx_mode": "OFF",
        "frame_generation": False,
        "expected_framebuffer": [1708, 960],
    },
    "pairedBlocks": int(sys.argv[15]),
    "warmupSeconds": int(sys.argv[16]),
    "sampleSeconds": int(sys.argv[17]),
    "pairing": "ABBA-equivalent alternating order",
    "machine": platform.machine(),
    "xcode": cmd("xcodebuild", "-version"),
    "display": os.environ.get("METALLUM_EVAL_DISPLAY", "unrecorded"),
    "powerState": os.environ.get("METALLUM_EVAL_POWER_STATE", "unrecorded"),
    "laneContract": {
        "common": "V1 no-shader + Metal4 compiler/present + explicit residency; MetalFX/FG and unrelated experimental lanes off",
        "baseline": "metal4MainRenderer=false",
        "candidate": "metal4MainRenderer=true"
    }
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
    run_trial "$lane" "$block_dir/$lane"
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
