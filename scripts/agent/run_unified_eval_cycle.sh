#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORLD="${WORLD:-}"
CANDIDATE_PROFILE="${CANDIDATE_PROFILE:-all-safe-lanes}"
BLOCKS="${BLOCKS:-4}"
MODE="${MODE:-full}"
CORRECTNESS_GATE="${METALLUM_CORRECTNESS_GATE:-}"
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
if [[ "$MODE" == "performance" || "$MODE" == "full" ]] && (( BLOCKS < 4 )); then
  echo "Performance acceptance requires at least four paired ABBA blocks" >&2
  exit 2
fi
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

mkdir -p "$OUT" "$OUT/correctness" "$OUT/trials"

profile_args() {
  local profile="$1"
  local pass_fusion=false
  local compute_grouping=false
  local depth_liveness=false
  local argument_tables=false
  local terrain_adaptive=false
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
    "-Dmetallum.iris.experimental.passFusion=$pass_fusion" \
    "-Dmetallum.iris.passFusion=$pass_fusion" \
    "-Dmetallum.iris.computeGrouping=$compute_grouping" \
    "-Dmetallum.iris.experimental.computeGrouping=$compute_grouping" \
    "-Dmetallum.iris.depthLiveness=$depth_liveness" \
    "-Dmetallum.iris.experimental.resourcePruning=$depth_liveness" \
    "-Dmetallum.iris.argumentTables=$argument_tables" \
    "-Dmetallum.iris.experimental.argumentTables=$argument_tables" \
    "-Dmetallum.opt.terrainAdaptiveScheduling=$terrain_adaptive" \
    "-Dmetallum.opt.terrainSchedulingTelemetry=$terrain_adaptive"
}

write_manifest() {
  python3 - "$OUT" "$WORLD" "$CANDIDATE_PROFILE" "$BLOCKS" "$MODE" <<'PY'
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
  "schema_version": 1,
  "world": sys.argv[2],
  "candidate_profile": sys.argv[3],
  "paired_blocks": int(sys.argv[4]),
  "mode": sys.argv[5],
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
  local marker="$trial_dir/.start-marker"
  local args=()
  local arg status
  mkdir -p "$trial_dir/artifacts"
  : > "$marker"
  while IFS= read -r arg; do args+=("$arg"); done < <(profile_args "$profile")
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
  python3 scripts/agent/analyze_unified_eval.py --normalize-trial "$trial_dir"
  return "$status"
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
  run_profile_task baseline renderContractMinecraftValidation "$OUT/correctness/baseline" || gate_status=fail
  run_profile_task "$CANDIDATE_PROFILE" renderContractMinecraftValidation "$OUT/correctness/candidate" || gate_status=fail
  if [[ "$MODE" == "diagnostic" ]]; then
    run_profile_task "$CANDIDATE_PROFILE" renderContractMinecraftDiagnose "$OUT/correctness/diagnostic" || gate_status=fail
  fi
  if [[ "$gate_status" != "pass" ]]; then gate_reason="one or more correctness commands failed; inspect command/status/log artifacts"; fi
  python3 - "$OUT/correctness/gate.json" "$gate_status" "$gate_reason" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({"schema_version":1,"status":sys.argv[2],"reason":sys.argv[3]}, indent=2) + "\n")
PY
fi

# Recompute binary fingerprints after correctness/build tasks have produced the
# exact JAR and dylib used by the following trials.
write_manifest

if [[ "$MODE" == "full" || "$MODE" == "performance" ]]; then
  for ((block=1; block<=BLOCKS; block++)); do
    block_dir="$OUT/trials/block-$(printf '%03d' "$block")"
    mkdir -p "$block_dir"
    if (( block % 2 == 1 )); then order=(baseline candidate); else order=(candidate baseline); fi
    printf '%s\n' "${order[@]}" > "$block_dir/order.txt"
    for label in "${order[@]}"; do
      profile=baseline
      [[ "$label" == "candidate" ]] && profile="$CANDIDATE_PROFILE"
      run_profile_task "$profile" minecraftNativeRenderEfficiencyValidation "$block_dir/$label" || true
    done
  done
fi

if [[ "$MODE" == "full" || "$MODE" == "performance" ]]; then
  set +e
  python3 scripts/agent/analyze_unified_eval.py --root "$OUT"
  analysis_status=$?
  set -e
else
  analysis_status=0
  if [[ "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("status"))' "$OUT/correctness/gate.json")" != "pass" ]]; then
    analysis_status=2
  fi
  python3 - "$OUT/decision.json" "$analysis_status" "$MODE" <<'PY'
import json, pathlib, sys
status = int(sys.argv[2])
pathlib.Path(sys.argv[1]).write_text(json.dumps({
  "schema_version": 1,
  "state": "correctness-pass-no-performance-decision" if status == 0 else "rejected-correctness-gate",
  "mode": sys.argv[3],
  "reason": "No performance acceptance was requested." if status == 0 else "Correctness gate failed."
}, indent=2) + "\n")
PY
fi
printf '%d\n' "$analysis_status" > "$OUT/analysis-exit-status.txt"

echo "Unified evaluation artifacts: $OUT"
exit "$analysis_status"
