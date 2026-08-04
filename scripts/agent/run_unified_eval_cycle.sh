#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORLD="${WORLD:-}"
CANDIDATE_PROFILE="${CANDIDATE_PROFILE:-all-safe-lanes}"
METAL4_MODE="${METAL4_MODE:-true}"
BLOCKS="${BLOCKS:-4}"
MODE="${MODE:-full}"
CORRECTNESS_GATE="${METALLUM_CORRECTNESS_GATE:-}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-120}"
ADMISSION_WARMUP_SECONDS="${ADMISSION_WARMUP_SECONDS:-2}"
ADMISSION_SAMPLE_SECONDS="${ADMISSION_SAMPLE_SECONDS:-5}"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_UNIFIED_EVAL_OUT:-$RUN_ROOT/unified-eval-$STAMP}"

case "$MODE" in
  full|conformance|performance|diagnostic) ;;
  *) echo "MODE must be full, conformance, performance, or diagnostic" >&2; exit 2 ;;
esac
if [[ -z "$WORLD" ]]; then
  echo "WORLD must name an existing world under run/saves/" >&2
  exit 2
fi
if [[ ! -d "run/saves/$WORLD" ]]; then
  echo "World does not exist: run/saves/$WORLD" >&2
  exit 2
fi
if ! [[ "$BLOCKS" =~ ^[1-9][0-9]*$ ]]; then
  echo "BLOCKS must be a positive integer" >&2
  exit 2
fi
for duration_name in WARMUP_SECONDS SAMPLE_SECONDS ADMISSION_WARMUP_SECONDS ADMISSION_SAMPLE_SECONDS; do
  duration_value="${!duration_name}"
  if ! [[ "$duration_value" =~ ^[0-9]+$ ]]; then
    echo "$duration_name must be a non-negative integer" >&2
    exit 2
  fi
done
if (( SAMPLE_SECONDS == 0 || ADMISSION_SAMPLE_SECONDS == 0 )); then
  echo "SAMPLE_SECONDS and ADMISSION_SAMPLE_SECONDS must be greater than zero" >&2
  exit 2
fi
if [[ "$MODE" == "performance" || "$MODE" == "full" ]] && (( BLOCKS < 4 )); then
  echo "Performance acceptance requires at least four paired ABBA blocks" >&2
  exit 2
fi
case "$METAL4_MODE" in
  true|false) ;;
  *) echo "METAL4_MODE must be true or false" >&2; exit 2 ;;
esac
if [[ "$MODE" == "performance" ]]; then
  if [[ -z "$CORRECTNESS_GATE" || ! -f "$CORRECTNESS_GATE" ]]; then
    echo "MODE=performance requires METALLUM_CORRECTNESS_GATE pointing to a prior passing gate.json" >&2
    exit 2
  fi
  python3 - "$CORRECTNESS_GATE" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
data = json.loads(p.read_text(encoding="utf-8"))
if data.get("status") != "pass":
    raise SystemExit(f"correctness gate is not passing: {p}")
PY
fi

mkdir -p "$OUT" "$OUT/correctness" "$OUT/admission" "$OUT/trials"

profile_args() {
  local profile="$1"
  local warmup_seconds="${2:-0}"
  local sample_seconds="${3:-0}"
  local pass_fusion=false
  local compute_grouping=false
  local depth_liveness=false
  local argument_tables=false
  local terrain_adaptive=false
  local render_command_packet=false
  local compute_command_packet=false
  local terrain_icb=false
  case "$profile" in
    baseline) ;;
    depth-liveness) depth_liveness=true ;;
    compute-grouping) compute_grouping=true ;;
    pass-fusion) pass_fusion=true ;;
    argument-tables) argument_tables=true ;;
    terrain-adaptive) terrain_adaptive=true ;;
    all-safe-lanes)
      pass_fusion=true
      compute_grouping=true
      depth_liveness=true
      argument_tables=true
      ;;
    all-safe-plus-terrain)
      pass_fusion=true
      compute_grouping=true
      depth_liveness=true
      argument_tables=true
      terrain_adaptive=true
      ;;
    mobilegl-hotpath)
      render_command_packet=true
      compute_command_packet=true
      ;;
    mobilegl-complete)
      pass_fusion=true
      compute_grouping=true
      depth_liveness=true
      argument_tables=true
      render_command_packet=true
      compute_command_packet=true
      terrain_icb=true
      ;;
    *) echo "Unknown profile: $profile" >&2; return 2 ;;
  esac
  printf '%s\n' \
    "-Dmetallum.iris.semantic=true" \
    "-Dmetallum.metalfx.mode=OFF" \
    "-Dmetallum.metalfx.frameGeneration=false" \
    "-Dmetallum.metalfx.objectMotionProducer=false" \
    "-Dmetallum.metal.hud=false" \
    "-Dmetallum.iris.performanceCounters=true" \
    "-Dmetallum.validation.gpuTiming=true" \
    "-Dmetallum.validation.gpuPassTiming=true" \
    "-Dmetallum.hotpath.telemetry=true" \
    "-Dmetallum.validation.warmupSeconds=$warmup_seconds" \
    "-Dmetallum.validation.sampleSeconds=$sample_seconds" \
    "-Dmetallum.iris.experimental.passFusion=$pass_fusion" \
    "-Dmetallum.iris.passFusion=$pass_fusion" \
    "-Dmetallum.iris.computeGrouping=$compute_grouping" \
    "-Dmetallum.iris.experimental.computeGrouping=$compute_grouping" \
    "-Dmetallum.iris.depthLiveness=$depth_liveness" \
    "-Dmetallum.iris.experimental.resourcePruning=$depth_liveness" \
    "-Dmetallum.iris.argumentTables=$argument_tables" \
    "-Dmetallum.iris.experimental.argumentTables=$argument_tables" \
    "-Dmetallum.opt.argumentBuffers=$argument_tables" \
    "-Dmetallum.opt.terrainAdaptiveScheduling=$terrain_adaptive" \
    "-Dmetallum.opt.terrainSchedulingTelemetry=$terrain_adaptive" \
    "-Dmetallum.opt.encoderStateShadow=true" \
    "-Dmetallum.opt.renderStatePacket=true" \
    "-Dmetallum.opt.renderCommandPacket=$render_command_packet" \
    "-Dmetallum.opt.computeCommandPacket=$compute_command_packet" \
    "-Dmetallum.opt.terrainIcb=$terrain_icb" \
    "-Dmetallum.opt.metal4=$METAL4_MODE" \
    "-Dmetallum.opt.metal4MainQueuePilot=$METAL4_MODE"
}

write_manifest() {
  python3 - "$OUT" "$WORLD" "$CANDIDATE_PROFILE" "$BLOCKS" "$MODE" "$METAL4_MODE" \
    "$WARMUP_SECONDS" "$SAMPLE_SECONDS" "$ADMISSION_WARMUP_SECONDS" "$ADMISSION_SAMPLE_SECONDS" <<'PY'
import hashlib, json, os, pathlib, platform, subprocess, sys
out = pathlib.Path(sys.argv[1])

def command(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except Exception as exc:
        return f"unavailable: {exc}"

def digest(path):
    path = pathlib.Path(path)
    if not path.is_file(): return None
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()

candidates = {
  "production_jar": next((str(p) for p in pathlib.Path('build/libs').glob('*.jar') if 'validation' not in p.name), None),
  "validation_jar": next((str(p) for p in pathlib.Path('build/libs').glob('*validation*.jar')), None),
  "native_dylib": "src/main/resources/natives/macos/libmetallum.dylib",
}
manifest = {
  "schema_version": 2,
  "world": sys.argv[2],
  "candidate_profile": sys.argv[3],
  "paired_blocks": int(sys.argv[4]),
  "mode": sys.argv[5],
  "metal4_mode": sys.argv[6] == "true",
  "performance_protocol": {
    "warmup_seconds": int(sys.argv[7]),
    "sample_seconds": int(sys.argv[8]),
    "admission_warmup_seconds": int(sys.argv[9]),
    "admission_sample_seconds": int(sys.argv[10]),
    "source_report": "native-fullscreen-baseline.json",
    "normalization": "one unique report payload per trial; byte-identical copies are allowed",
  },
  "git": {"head": command('git','rev-parse','HEAD'), "status": command('git','status','--porcelain=v1')},
  "environment": {
    "platform": platform.platform(),
    "machine": platform.machine(),
    "java": command('java','-version'),
    "swift": command('swiftc','--version'),
    "xcode": command('xcodebuild','-version'),
    "display": os.environ.get('METALLUM_EVAL_DISPLAY', 'must be recorded by local operator'),
    "power_state": os.environ.get('METALLUM_EVAL_POWER_STATE', 'must be recorded by local operator'),
    "shader_pack": os.environ.get('METALLUM_EVAL_SHADER_PACK', 'must be recorded by local operator'),
    "shader_pack_sha256": digest(os.environ.get('METALLUM_EVAL_SHADER_PACK_PATH', '')),
  },
  "binaries": {name: {"path": path, "sha256": digest(path) if path else None} for name, path in candidates.items()},
  "identity_rule": "Runs are comparable only when scenario, binary hashes, settings, display and power state match.",
}
(out / 'run-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
PY
}

run_logged() {
  local name="$1"
  shift
  local log="$OUT/correctness/$name.log"
  {
    printf '[command]'
    printf ' %q' "$@"
    printf '\n'
  } > "$OUT/correctness/$name.command"
  set +e
  "$@" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  set -e
  printf '%d\n' "$status" > "$OUT/correctness/$name.status"
  return "$status"
}

copy_artifacts_since() {
  local marker="$1"
  local destination="$2"
  local root file rel
  mkdir -p "$destination"
  for root in build/metal-validation build/render-contract build/reports build/test-results; do
    [[ -d "$root" ]] || continue
    while IFS= read -r -d '' file; do
      rel="${file#$ROOT/}"
      mkdir -p "$destination/$(dirname "$rel")"
      cp -p "$file" "$destination/$rel" 2>/dev/null || true
    done < <(find "$root" -type f -newer "$marker" -print0 2>/dev/null || true)
  done
}

run_profile_task() {
  local profile="$1"
  local task="$2"
  local trial_dir="$3"
  local warmup_seconds="${4:-0}"
  local sample_seconds="${5:-0}"
  local normalize="${6:-false}"
  local marker="$trial_dir/.start-marker"
  local args=()
  local arg status normalize_status=0
  mkdir -p "$trial_dir/artifacts"
  : > "$marker"
  while IFS= read -r arg; do args+=("$arg"); done < <(profile_args "$profile" "$warmup_seconds" "$sample_seconds")
  printf '%s\n' "${args[@]}" > "$trial_dir/properties.txt"
  local command=(./gradlew --no-daemon "$task" "-Pworld=$WORLD" "${args[@]}" \
    "-Dmetallum.validation.output=$trial_dir/artifacts/validation" \
    "-Dmetallum.iris.experimental.planDump=$trial_dir/optimization-plan.json")
  {
    printf '[command]'
    printf ' %q' "${command[@]}"
    printf '\n'
  } > "$trial_dir/command.txt"
  set +e
  "${command[@]}" 2>&1 | tee "$trial_dir/client.log"
  status=${PIPESTATUS[0]}
  set -e
  printf '%d\n' "$status" > "$trial_dir/exit-status.txt"
  copy_artifacts_since "$marker" "$trial_dir/artifacts"
  if [[ "$normalize" == "true" ]]; then
    set +e
    python3 scripts/agent/normalize_unified_trial.py "$trial_dir"
    normalize_status=$?
    set -e
  fi
  if (( status != 0 )); then return "$status"; fi
  return "$normalize_status"
}

write_admission_decision() {
  local admission_file="$1"
  local exit_status="$2"
  python3 - "$admission_file" "$OUT/decision.json" "$exit_status" <<'PY'
import json, pathlib, sys
admission_path = pathlib.Path(sys.argv[1])
decision_path = pathlib.Path(sys.argv[2])
status = int(sys.argv[3])
try:
    admission = json.loads(admission_path.read_text(encoding="utf-8"))
except Exception as exc:
    admission = {"state": "inconclusive-admission-evidence", "reason": str(exc)}
state = admission.get("state", "inconclusive-admission-evidence")
decision_path.write_text(json.dumps({
    "schema_version": 2,
    "state": state,
    "reason": admission.get("reason", "candidate admission could not be established"),
    "candidate_profile": admission.get("profile"),
    "admission": admission,
    "performance_trials_started": False,
    "analysis_exit_status": status,
}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

bash scripts/agent/doctor.sh
write_manifest
cp docs/agent/unified-evaluation-acceptance.json "$OUT/acceptance.json"
git status --porcelain=v1 > "$OUT/git-status.txt"

gate_status=pass
gate_reason="all requested correctness gates passed"
if [[ "$MODE" == "performance" ]]; then
  cp "$CORRECTNESS_GATE" "$OUT/correctness/gate.json"
else
  run_logged static bash scripts/agent/verify.sh static || gate_status=fail
  run_logged gpu bash scripts/agent/verify.sh gpu || gate_status=fail
  run_logged synthetic ./gradlew --no-daemon renderContractSyntheticValidation || gate_status=fail
  run_profile_task baseline renderContractMinecraftValidation "$OUT/correctness/baseline" 0 0 false || gate_status=fail
  run_profile_task "$CANDIDATE_PROFILE" renderContractMinecraftValidation "$OUT/correctness/candidate" 0 0 false || gate_status=fail
  if [[ "$MODE" == "diagnostic" ]]; then
    run_profile_task "$CANDIDATE_PROFILE" renderContractMinecraftDiagnose "$OUT/correctness/diagnostic" 0 0 false || gate_status=fail
  fi
  if [[ "$gate_status" != "pass" ]]; then gate_reason="one or more correctness commands failed; inspect command/status/log artifacts"; fi
  python3 - "$OUT/correctness/gate.json" "$gate_status" "$gate_reason" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({"schema_version":1,"status":sys.argv[2],"reason":sys.argv[3]}, indent=2) + "\n")
PY
fi

write_manifest

analysis_status=0
admission_passed=true
if [[ "$MODE" == "full" || "$MODE" == "performance" ]]; then
  if [[ "$gate_status" == "pass" ]]; then
    admission_dir="$OUT/admission/candidate"
    set +e
    run_profile_task "$CANDIDATE_PROFILE" minecraftNativeRenderEfficiencyValidation \
      "$admission_dir" "$ADMISSION_WARMUP_SECONDS" "$ADMISSION_SAMPLE_SECONDS" true
    probe_status=$?
    set -e
    if (( probe_status == 0 )); then
      set +e
      python3 scripts/agent/check_unified_eval_admission.py \
        "$admission_dir/metrics.json" \
        --profile "$CANDIDATE_PROFILE" \
        --output "$OUT/admission/admission.json"
      admission_status=$?
      set -e
    else
      admission_status=2
      python3 - "$OUT/admission/admission.json" "$CANDIDATE_PROFILE" "$probe_status" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
  "schema_version": 1,
  "state": "inconclusive-admission-evidence",
  "profile": sys.argv[2],
  "reason": f"admission probe or strict normalization failed with exit status {sys.argv[3]}",
}, indent=2) + "\n", encoding="utf-8")
PY
    fi
    if (( admission_status != 0 )); then
      admission_passed=false
      analysis_status="$admission_status"
      write_admission_decision "$OUT/admission/admission.json" "$analysis_status"
    fi
  else
    admission_passed=false
  fi
fi

if [[ "$admission_passed" == "true" && ( "$MODE" == "full" || "$MODE" == "performance" ) ]]; then
  for ((block=1; block<=BLOCKS; block++)); do
    block_dir="$OUT/trials/block-$(printf '%03d' "$block")"
    mkdir -p "$block_dir"
    if (( block % 2 == 1 )); then order=(baseline candidate); else order=(candidate baseline); fi
    printf '%s\n' "${order[@]}" > "$block_dir/order.txt"
    for label in "${order[@]}"; do
      profile=baseline
      [[ "$label" == "candidate" ]] && profile="$CANDIDATE_PROFILE"
      run_profile_task "$profile" minecraftNativeRenderEfficiencyValidation \
        "$block_dir/$label" "$WARMUP_SECONDS" "$SAMPLE_SECONDS" true || true
    done
  done
fi

if [[ "$MODE" == "full" || "$MODE" == "performance" ]]; then
  if [[ "$admission_passed" == "true" ]]; then
    set +e
    python3 scripts/agent/analyze_unified_eval.py --root "$OUT"
    analysis_status=$?
    set -e
  elif [[ "$gate_status" != "pass" ]]; then
    set +e
    python3 scripts/agent/analyze_unified_eval.py --root "$OUT"
    analysis_status=$?
    set -e
  fi
else
  analysis_status=0
  if [[ "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("status"))' "$OUT/correctness/gate.json")" != "pass" ]]; then
    analysis_status=2
  fi
  python3 - "$OUT/decision.json" "$analysis_status" "$MODE" <<'PY'
import json, pathlib, sys
status = int(sys.argv[2])
pathlib.Path(sys.argv[1]).write_text(json.dumps({
  "schema_version": 2,
  "state": "correctness-pass-no-performance-decision" if status == 0 else "rejected-correctness-gate",
  "mode": sys.argv[3],
  "reason": "No performance acceptance was requested." if status == 0 else "Correctness gate failed."
}, indent=2) + "\n")
PY
fi
printf '%d\n' "$analysis_status" > "$OUT/analysis-exit-status.txt"

echo "Unified evaluation artifacts: $OUT"
exit "$analysis_status"
