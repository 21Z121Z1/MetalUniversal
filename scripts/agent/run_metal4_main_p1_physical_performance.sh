#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PROFILE_ID="${PROFILE_ID:-V1}"
WORLD="${WORLD:-}"
BLOCKS="${BLOCKS:-4}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-120}"
CORRECTNESS_GATE="${P1_CORRECTNESS_GATE:-}"
UI_SCALE="${UI_SCALE:-3}"
RENDER_DISTANCE="${RENDER_DISTANCE:-32}"
WORLD_SCENARIO_ID="metal-validation-fixed-camera-v1"
TARGET_FPS="${METALLUM_EVAL_TARGET_FPS:-120}"
TARGET_REFRESH_HZ="${METALLUM_EVAL_REFRESH_HZ:-120}"
EXPECTED_FRAMEBUFFER_WIDTH="${METALLUM_EVAL_FRAMEBUFFER_WIDTH:-}"
EXPECTED_FRAMEBUFFER_HEIGHT="${METALLUM_EVAL_FRAMEBUFFER_HEIGHT:-}"
CAMERA_POLICY="world-player-pose snapped to x/z block centers, y half-block, yaw nearest 90 degrees, pitch 0; held fixed by MetalValidationClient"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_PERFORMANCE_OUT:-$RUN_ROOT/p1-metal4-main-${PROFILE_ID}-performance-$STAMP}"
OPTIONS_FILE="$ROOT/run/options.txt"
IRIS_CONFIG="$ROOT/run/config/iris.properties"
SHADERPACK_DIR="$ROOT/run/shaderpacks"
SHADER_PACK_PATH=""
SHADER_PACK_VERSION=""
SHADER_PACK_LABEL=""
SHADER_OPTIONS_PATH=""
IRIS_SEMANTIC=false
STAGED_PACK_NAME=""
STAGED_PACK_PATH=""
STAGED_OPTIONS_PATH=""
SHADER_PACK_SHA=""
SHADER_OPTIONS_SHA=""

case "$PROFILE_ID" in
  V1)
    IRIS_SEMANTIC=false
    ;;
  I0)
    IRIS_SEMANTIC=true
    SHADER_PACK_PATH="${POTATO_SHADER_PACK:-}"
    SHADER_PACK_VERSION="${POTATO_SHADER_PACK_VERSION:-}"
    SHADER_OPTIONS_PATH="${POTATO_SHADER_OPTIONS:-}"
    SHADER_PACK_LABEL="Potato"
    ;;
  I1)
    IRIS_SEMANTIC=true
    SHADER_PACK_PATH="${BSL_SHADER_PACK:-}"
    SHADER_PACK_VERSION="${BSL_SHADER_PACK_VERSION:-}"
    SHADER_OPTIONS_PATH="${BSL_SHADER_OPTIONS:-}"
    SHADER_PACK_LABEL="BSL"
    ;;
  *)
    echo "PROFILE_ID must be V1, I0, or I1 (got $PROFILE_ID)" >&2
    exit 2
    ;;
esac

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
if [[ "$PROFILE_ID" != "V1" ]]; then
  if [[ -z "$SHADER_PACK_PATH" || ! -f "$SHADER_PACK_PATH" ]]; then
    echo "$PROFILE_ID requires an explicit content-addressed shader pack file" >&2
    exit 2
  fi
  if [[ -z "$SHADER_PACK_VERSION" ]]; then
    echo "$PROFILE_ID requires an explicit shader-pack version identity" >&2
    exit 2
  fi
  if [[ -n "$SHADER_OPTIONS_PATH" && ! -f "$SHADER_OPTIONS_PATH" ]]; then
    echo "shader options file does not exist: $SHADER_OPTIONS_PATH" >&2
    exit 2
  fi
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
if ! [[ "$TARGET_FPS" =~ ^[0-9]+$ ]] || (( TARGET_FPS < 1 )); then
  echo "METALLUM_EVAL_TARGET_FPS must be a positive integer" >&2
  exit 2
fi
if ! [[ "$TARGET_REFRESH_HZ" =~ ^[0-9]+$ ]] || (( TARGET_REFRESH_HZ < 1 )); then
  echo "METALLUM_EVAL_REFRESH_HZ must be a positive integer" >&2
  exit 2
fi
DISPLAY_PROFILE_JSON="$(system_profiler SPDisplaysDataType -json)"
read -r HOST_DISPLAY_WIDTH HOST_DISPLAY_HEIGHT HOST_DISPLAY_REFRESH_HZ < <(
  python3 - "$DISPLAY_PROFILE_JSON" <<'PY'
import json, sys
root = json.loads(sys.argv[1])
displays = [display for gpu in root.get("SPDisplaysDataType", [])
            for display in gpu.get("spdisplays_ndrvs", [])]
main = next((display for display in displays
             if display.get("spdisplays_main") == "spdisplays_yes"
             and display.get("spdisplays_online") == "spdisplays_yes"), None)
if main is None:
    raise SystemExit("system_profiler found no online main display")
pixels = main.get("_spdisplays_pixels", "").split(" x ")
resolution = main.get("_spdisplays_resolution", "")
if len(pixels) != 2 or "@" not in resolution or not resolution.rstrip().endswith("Hz"):
    raise SystemExit(f"system_profiler main display lacks native pixels or active refresh: {main}")
try:
    width, height = (int(value.strip()) for value in pixels)
    refresh = float(resolution.split("@", 1)[1].strip()[:-2].strip())
except ValueError as exc:
    raise SystemExit(f"could not parse system_profiler main display mode: {main}") from exc
print(width, height, refresh)
PY
)
python3 - "$HOST_DISPLAY_REFRESH_HZ" "$TARGET_REFRESH_HZ" <<'PY'
import math, sys
actual, expected = float(sys.argv[1]), float(sys.argv[2])
if not math.isfinite(actual) or abs(actual - expected) > 0.5:
    raise SystemExit(f"active main display is {actual:g} Hz; benchmark target is {expected} Hz")
PY
if [[ -n "$EXPECTED_FRAMEBUFFER_WIDTH" || -n "$EXPECTED_FRAMEBUFFER_HEIGHT" ]]; then
  if ! [[ "$EXPECTED_FRAMEBUFFER_WIDTH" =~ ^[0-9]+$ ]] || (( EXPECTED_FRAMEBUFFER_WIDTH < 1 )) \
      || ! [[ "$EXPECTED_FRAMEBUFFER_HEIGHT" =~ ^[0-9]+$ ]] || (( EXPECTED_FRAMEBUFFER_HEIGHT < 1 )); then
    echo "METALLUM_EVAL_FRAMEBUFFER_WIDTH and HEIGHT must be set together as positive integers" >&2
    exit 2
  fi
fi
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "P1 physical performance requires a clean worktree" >&2
  git status --short >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
mkdir -p "$OUT/correctness" "$OUT/trials"

assert_console_unlocked() {
  local lock_state
  lock_state="$(ioreg -n Root -d1 | awk -F'= ' '/IOConsoleLocked/ { gsub(/"/, "", $2); print $2; exit }')"
  if [[ "$lock_state" != "No" ]]; then
    echo "physical fullscreen P1 trial requires an unlocked WindowServer console (IOConsoleLocked=$lock_state)" >&2
    return 2
  fi
}
assert_console_unlocked

read -r CORRECTNESS_JAR_SHA CORRECTNESS_DYLIB_SHA < <(
  python3 - "$CORRECTNESS_GATE" "$HEAD_SHA" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
head = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))
if data.get("state") != "pass":
    raise SystemExit(f"P1 physical correctness gate is not passing: {data.get('state')}")
framebuffer = data.get("framebufferEquivalence")
checks = data.get("checks")
if not isinstance(framebuffer, dict) or framebuffer.get("state") != "pass" or not isinstance(checks, dict) \
        or checks.get("framebuffer_equivalence") is not True:
    raise SystemExit("P1 physical correctness gate has no passing exact framebuffer-equivalence evidence")
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

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

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
  if [[ -n "$STAGED_PACK_PATH" ]]; then
    rm -f "$STAGED_PACK_PATH"
  fi
  if [[ -n "$STAGED_OPTIONS_PATH" ]]; then
    rm -f "$STAGED_OPTIONS_PATH"
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

# Pin mutable client inputs. Shader packs are never downloaded here: I0/I1
# only accept caller-supplied files and record their exact hashes.
pin_colon_option "$OPTIONS_FILE" "guiScale" "$UI_SCALE"
pin_colon_option "$OPTIONS_FILE" "renderDistance" "$RENDER_DISTANCE"
pin_colon_option "$OPTIONS_FILE" "maxFps" "$TARGET_FPS"
pin_colon_option "$OPTIONS_FILE" "enableVsync" "true"
pin_colon_option "$OPTIONS_FILE" "fullscreen" "true"
# 26.3's AFK limiter cuts a stationary foreground game to 30 FPS after 60s.
# `minimized` preserves ordinary idle behavior while leaving visible fullscreen
# trials uncapped by the inactivity limiter.
pin_colon_option "$OPTIONS_FILE" "inactivityFpsLimit" "minimized"
if [[ "$PROFILE_ID" == "V1" ]]; then
  pin_equals_property "$IRIS_CONFIG" "enableShaders" "false"
  pin_equals_property "$IRIS_CONFIG" "shaderPack" ""
else
  mkdir -p "$SHADERPACK_DIR"
  SHADER_PACK_SHA="$(sha256_file "$SHADER_PACK_PATH")"
  safe_base="$(basename "$SHADER_PACK_PATH")"
  STAGED_PACK_NAME="metallum-p1-${PROFILE_ID}-${SHADER_PACK_SHA:0:12}-${safe_base}"
  STAGED_PACK_PATH="$SHADERPACK_DIR/$STAGED_PACK_NAME"
  STAGED_OPTIONS_PATH="$SHADERPACK_DIR/$STAGED_PACK_NAME.txt"
  cp "$SHADER_PACK_PATH" "$STAGED_PACK_PATH"
  if [[ -n "$SHADER_OPTIONS_PATH" ]]; then
    cp "$SHADER_OPTIONS_PATH" "$STAGED_OPTIONS_PATH"
  else
    : > "$STAGED_OPTIONS_PATH"
  fi
  SHADER_OPTIONS_SHA="$(sha256_file "$STAGED_OPTIONS_PATH")"
  [[ "$(sha256_file "$STAGED_PACK_PATH")" == "$SHADER_PACK_SHA" ]] || {
    echo "staged shader pack hash mismatch" >&2
    exit 2
  }
  pin_equals_property "$IRIS_CONFIG" "shaderPack" "$STAGED_PACK_NAME"
  pin_equals_property "$IRIS_CONFIG" "enableShaders" "true"
fi

assert_pinned_render_options() {
  python3 - "$OPTIONS_FILE" "$UI_SCALE" "$RENDER_DISTANCE" "$TARGET_FPS" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
expected = {
    "guiScale": sys.argv[2],
    "renderDistance": sys.argv[3],
    "maxFps": sys.argv[4],
    "enableVsync": "true",
    "fullscreen": "true",
    "inactivityFpsLimit": "minimized",
}
actual = {}
for raw in path.read_text(encoding="utf-8").splitlines():
    if ":" in raw:
        key, value = raw.split(":", 1)
        actual[key] = value
bad = {key: (actual.get(key), value) for key, value in expected.items() if actual.get(key) != value}
if bad:
    raise SystemExit(f"fullscreen render options drifted from the P1 contract: {bad}")
PY
}
assert_pinned_render_options

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
    "-Dmetallum.iris.semantic=$IRIS_SEMANTIC" \
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
  local report="$1" lane="$2" log="$3"
  python3 - "$report" "$lane" "$HEAD_SHA" "$OUT/environment.json" \
    "$HOST_DISPLAY_WIDTH" "$HOST_DISPLAY_HEIGHT" <<'PY'
import json, math, pathlib, sys
path = pathlib.Path(sys.argv[1])
lane, head = sys.argv[2], sys.argv[3]
environment_path = pathlib.Path(sys.argv[4])
display_width, display_height = int(sys.argv[5]), int(sys.argv[6])
data = json.loads(path.read_text(encoding="utf-8"))
environment = json.loads(environment_path.read_text(encoding="utf-8"))
identity = environment.get("identity")
if not isinstance(identity, dict):
    raise SystemExit("P1 environment evidence has no identity object")
expected_resolution = identity.get("resolution")
if (isinstance(expected_resolution, list) and len(expected_resolution) == 2
        and all(isinstance(value, int) and not isinstance(value, bool) and value > 0 for value in expected_resolution)):
    width, height = expected_resolution
else:
    width = height = None
actual_width, actual_height = data.get("drawableWidth"), data.get("drawableHeight")
problems = []
if (not isinstance(actual_width, int) or isinstance(actual_width, bool) or actual_width <= 0
        or not isinstance(actual_height, int) or isinstance(actual_height, bool) or actual_height <= 0):
    problems.append(f"fullscreen drawable is invalid: {actual_width}x{actual_height}")
elif actual_width < math.ceil(display_width * 0.95) or actual_height < math.ceil(display_height * 0.90):
    problems.append(
        f"drawable {actual_width}x{actual_height} does not fill main display "
        f"{display_width}x{display_height} within fullscreen bounds"
    )
elif width is not None and height is not None and (actual_width, actual_height) != (width, height):
    problems.append(f"drawable {actual_width}x{actual_height} != captured fullscreen baseline {width}x{height}")
elif width is None or height is None:
    if lane != "baseline":
        problems.append("first fullscreen drawable must be captured from the baseline lane")
    else:
        identity["resolution"] = [actual_width, actual_height]
        identity["resolution_source"] = "first accepted fullscreen baseline native-fullscreen-baseline.json"
        pinned = environment.setdefault("pinned_runtime_config", {})
        pinned["expected_framebuffer"] = [actual_width, actual_height]
        environment_path.write_text(json.dumps(environment, indent=2) + "\n", encoding="utf-8")
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
    raise SystemExit("P1 trial identity/guardrail failed: " + "; ".join(problems))
PY
  if [[ "$PROFILE_ID" != "V1" ]]; then
    grep -F "Using shaderpack: $STAGED_PACK_NAME" "$log" >/dev/null || {
      echo "$PROFILE_ID trial did not prove exact shader-pack activation: $STAGED_PACK_NAME" >&2
      return 2
    }
  fi
}

run_trial() {
  local lane="$1" trial_dir="$2"
  local args=() arg status report
  assert_console_unlocked
  assert_pinned_render_options
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
  assert_console_unlocked
  assert_pinned_render_options

  report="$(find "$trial_dir" -type f -name native-fullscreen-baseline.json -print | head -n 1)"
  if [[ -z "$report" ]]; then
    echo "P1 performance trial produced no native-fullscreen-baseline.json: $trial_dir" >&2
    printf '2\n' > "$trial_dir/exit-status.txt"
    return 2
  fi
  validate_trial_identity "$report" "$lane" "$trial_dir/client.log"
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
  "$SHADER_PACK_LABEL" "$SHADER_PACK_VERSION" "$SHADER_PACK_SHA" "$SHADER_OPTIONS_SHA" \
  "$BLOCKS" "$WARMUP_SECONDS" "$SAMPLE_SECONDS" "$TARGET_FPS" "$TARGET_REFRESH_HZ" \
  "$EXPECTED_FRAMEBUFFER_WIDTH" "$EXPECTED_FRAMEBUFFER_HEIGHT" \
  "$HOST_DISPLAY_WIDTH" "$HOST_DISPLAY_HEIGHT" "$HOST_DISPLAY_REFRESH_HZ" <<'PY'
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
profile = sys.argv[7]
identity = {
    "profile_id": profile,
    "candidate_sha": sys.argv[2],
    "production_jar_sha256": sys.argv[3],
    "native_dylib_sha256": sys.argv[4],
    "world_sha256": sys.argv[6],
    "world_scenario_id": sys.argv[8],
    "resolution": ([int(sys.argv[24]), int(sys.argv[25])]
                    if sys.argv[24] and sys.argv[25] else "pending-first-fullscreen-baseline"),
    "resolution_source": ("explicit METALLUM_EVAL_FRAMEBUFFER_WIDTH/HEIGHT"
                          if sys.argv[24] and sys.argv[25] else "captured from first fullscreen baseline trial"),
    "window_mode": "fullscreen",
    "target_fps": int(sys.argv[22]),
    "target_refresh_hz": int(sys.argv[23]),
    "display_pixel_mode": [int(sys.argv[26]), int(sys.argv[27])],
    "display_refresh_hz": float(sys.argv[28]),
    "vsync_enabled": True,
    "inactivity_fps_limit": "minimized",
    "ui_scale": int(sys.argv[9]),
    "render_distance": int(sys.argv[10]),
    "camera_pose": sys.argv[11],
    "camera_script_sha256": sys.argv[12],
    "minecraft_version": props.get("minecraft_version", "unknown"),
    "sodium_version": props.get("sodium_version", "unknown"),
    "macos_version": cmd("sw_vers", "-productVersion"),
    "java_version": cmd("java", "-version"),
}
if profile in ("I0", "I1"):
    identity.update({
        "shader_pack_name": sys.argv[15],
        "shader_pack_version": sys.argv[16],
        "shader_pack_sha256": sys.argv[17],
        "shader_options_sha256": sys.argv[18],
        "iris_version": props.get("iris_version", "unknown"),
    })
required_by_profile = {
    "V1": {
        "candidate_sha", "production_jar_sha256", "native_dylib_sha256", "world_sha256",
        "world_scenario_id", "resolution", "ui_scale", "render_distance", "camera_pose",
        "camera_script_sha256", "window_mode", "target_fps", "target_refresh_hz", "display_pixel_mode",
        "display_refresh_hz", "vsync_enabled", "inactivity_fps_limit",
        "minecraft_version", "sodium_version", "macos_version", "java_version"
    },
    "I0": {
        "candidate_sha", "production_jar_sha256", "native_dylib_sha256", "world_sha256",
        "world_scenario_id", "resolution", "ui_scale", "render_distance", "camera_pose",
        "camera_script_sha256", "shader_pack_name", "shader_pack_version", "shader_pack_sha256",
        "shader_options_sha256", "window_mode", "target_fps", "target_refresh_hz", "display_pixel_mode",
        "display_refresh_hz", "vsync_enabled", "inactivity_fps_limit",
        "minecraft_version", "sodium_version", "iris_version", "macos_version", "java_version"
    },
    "I1": {
        "candidate_sha", "production_jar_sha256", "native_dylib_sha256", "world_sha256",
        "world_scenario_id", "resolution", "ui_scale", "render_distance", "camera_pose",
        "camera_script_sha256", "shader_pack_name", "shader_pack_version", "shader_pack_sha256",
        "shader_options_sha256", "window_mode", "target_fps", "target_refresh_hz", "display_pixel_mode",
        "display_refresh_hz", "vsync_enabled", "inactivity_fps_limit",
        "minecraft_version", "sodium_version", "iris_version", "macos_version", "java_version"
    },
}
missing = sorted(key for key in required_by_profile[profile] if identity.get(key) in (None, "", "unknown"))
if missing:
    raise SystemExit(f"{profile} benchmark identity is incomplete: {missing}")
path.write_text(json.dumps({
    "schema_version": 4,
    "stage": "P1-metal4-main-production",
    "kind": "physical-performance-abba",
    "benchmark_contract": "docs/agent/benchmark-profiles.json",
    "identity": identity,
    "world": sys.argv[5],
    "pinned_runtime_config": {
        "options_sha256": sys.argv[13],
        "iris_properties_sha256": sys.argv[14],
        "iris_semantic": profile in ("I0", "I1"),
        "shader_pack": None if profile == "V1" else sys.argv[15],
        "metalfx_mode": "OFF",
        "frame_generation": False,
        "expected_framebuffer": ([int(sys.argv[24]), int(sys.argv[25])]
                                  if sys.argv[24] and sys.argv[25] else "captured-from-first-fullscreen-baseline"),
        "display_pixel_mode": [int(sys.argv[26]), int(sys.argv[27])],
        "display_refresh_hz": float(sys.argv[28]),
        "minimum_fullscreen_drawable_fraction": {"width": 0.95, "height": 0.90},
        "window_mode": "fullscreen",
        "target_fps": int(sys.argv[22]),
        "target_refresh_hz": int(sys.argv[23]),
        "vsync_enabled": True,
        "inactivity_fps_limit": "minimized",
    },
    "pairedBlocks": int(sys.argv[19]),
    "warmupSeconds": int(sys.argv[20]),
    "sampleSeconds": int(sys.argv[21]),
    "pairing": "ABBA-equivalent alternating order",
    "machine": platform.machine(),
    "xcode": cmd("xcodebuild", "-version"),
    "display": {
        "label": os.environ.get("METALLUM_EVAL_DISPLAY", "unrecorded"),
        "native_pixel_mode": [int(sys.argv[26]), int(sys.argv[27])],
        "active_refresh_hz": float(sys.argv[28]),
        "source": "system_profiler SPDisplaysDataType active online main display",
    },
    "powerState": os.environ.get("METALLUM_EVAL_POWER_STATE", "unrecorded"),
    "laneContract": {
        "common": f"{profile} benchmark stack + Metal4 compiler/present + explicit residency; MetalFX/FG and unrelated experimental lanes off",
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

echo "P1 $PROFILE_ID physical paired performance: ACCEPTED"
echo "Evidence: $OUT"
