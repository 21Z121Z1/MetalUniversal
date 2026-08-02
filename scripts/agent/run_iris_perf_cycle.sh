#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ -z "${WORLD:-}" ]]; then
  echo "WORLD must name an existing world under run/saves/" >&2
  exit 2
fi
if [[ ! -d "run/saves/$WORLD" ]]; then
  echo "World does not exist: run/saves/$WORLD" >&2
  exit 2
fi

PROFILES="${PROFILES:-baseline,depth-liveness,compute-grouping,pass-fusion,argument-tables,all-safe-lanes}"
REPETITIONS="${REPETITIONS:-3}"
if ! [[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "REPETITIONS must be a positive integer" >&2
  exit 2
fi

RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_AGENT_CYCLE_OUT:-$RUN_ROOT/iris-perf-$STAMP}"
mkdir -p "$OUT"

bash scripts/agent/doctor.sh
cp docs/agent/iris-performance-acceptance.json "$OUT/acceptance.json"
git rev-parse HEAD > "$OUT/start-head.txt"
git status --porcelain=v1 > "$OUT/git-status.txt"

IFS=',' read -r -a profile_list <<< "$PROFILES"
overall_status=0

profile_args() {
  local profile="$1"
  # Every profile states all lane flags explicitly so inherited Java properties
  # cannot contaminate a baseline/candidate comparison.
  local pass_fusion=false
  local compute_grouping=false
  local depth_liveness=false
  local argument_tables=false
  case "$profile" in
    baseline) ;;
    depth-liveness) depth_liveness=true ;;
    compute-grouping) compute_grouping=true ;;
    pass-fusion) pass_fusion=true ;;
    argument-tables) argument_tables=true ;;
    all-safe-lanes)
      pass_fusion=true
      compute_grouping=true
      depth_liveness=true
      argument_tables=true
      ;;
    *)
      echo "Unknown profile: $profile" >&2
      return 2
      ;;
  esac
  printf '%s\n' \
    "-Dmetallum.iris.semantic=true" \
    "-Dmetallum.metalfx.mode=OFF" \
    "-Dmetallum.metalfx.frameGeneration=false" \
    "-Dmetallum.metalfx.objectMotionProducer=false" \
    "-Dmetallum.metal.hud=false" \
    "-Dmetallum.iris.performanceCounters=true" \
    "-Dmetallum.iris.experimental.passFusion=$pass_fusion" \
    "-Dmetallum.iris.passFusion=$pass_fusion" \
    "-Dmetallum.iris.computeGrouping=$compute_grouping" \
    "-Dmetallum.iris.experimental.computeGrouping=$compute_grouping" \
    "-Dmetallum.iris.depthLiveness=$depth_liveness" \
    "-Dmetallum.iris.experimental.resourcePruning=$depth_liveness" \
    "-Dmetallum.iris.argumentTables=$argument_tables" \
    "-Dmetallum.iris.experimental.argumentTables=$argument_tables"
}

for raw_profile in "${profile_list[@]}"; do
  profile="$(printf '%s' "$raw_profile" | tr -d '[:space:]')"
  [[ -n "$profile" ]] || continue
  mapfile -t common_args < <(profile_args "$profile")
  profile_dir="$OUT/$profile"
  mkdir -p "$profile_dir"
  printf '%s\n' "${common_args[@]}" > "$profile_dir/properties.txt"

  for ((run=1; run<=REPETITIONS; run++)); do
    run_dir="$profile_dir/run-$run"
    mkdir -p "$run_dir/artifacts"
    marker="$run_dir/.start-marker"
    : > "$marker"
    plan_path="$run_dir/optimization-plan.json"
    log_path="$run_dir/client.log"

    command=(
      ./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation
      "-Pworld=$WORLD"
      "${common_args[@]}"
      "-Dmetallum.iris.experimental.planDump=$plan_path"
    )
    {
      printf '[command]'
      printf ' %q' "${command[@]}"
      printf '\n'
    } > "$run_dir/command.txt"

    echo "[$profile run $run/$REPETITIONS] starting"
    set +e
    "${command[@]}" 2>&1 | tee "$log_path"
    status=${PIPESTATUS[0]}
    set -e
    printf '%d\n' "$status" > "$run_dir/exit-status.txt"
    if (( status != 0 )); then
      overall_status=1
    fi

    # Preserve every generated validation/report artifact touched during this
    # run without assuming a single fixed build output path.
    while IFS= read -r -d '' file; do
      rel="${file#$ROOT/}"
      case "$rel" in
        build/agent-runs/*) continue ;;
      esac
      destination="$run_dir/artifacts/$rel"
      mkdir -p "$(dirname "$destination")"
      cp -p "$file" "$destination" 2>/dev/null || true
    done < <(find "$ROOT/build" -type f -newer "$marker" -print0 2>/dev/null || true)
  done
done

python3 - "$OUT" "$WORLD" "$REPETITIONS" <<'PY'
import json, pathlib, re, statistics, sys
root = pathlib.Path(sys.argv[1])
world = sys.argv[2]
repetitions = int(sys.argv[3])
metric_patterns = {
    "gpu_ms": re.compile(r"(?i)(?:gpu(?: frame| render)?(?: time)?|gpuTime)\D{0,20}([0-9]+(?:\.[0-9]+)?)\s*ms"),
    "cpu_ms": re.compile(r"(?i)(?:cpu(?: render| encode)?(?: time)?|cpuTime)\D{0,20}([0-9]+(?:\.[0-9]+)?)\s*ms"),
    "encoder_count": re.compile(r"(?i)(?:native\s+)?encoder(?: count|s)?\D{0,20}([0-9]+)"),
    "fps": re.compile(r"(?i)\b(?:fps|frames per second)\D{0,10}([0-9]+(?:\.[0-9]+)?)"),
}
forbidden = [
    re.compile(r"MixinApplyError", re.I),
    re.compile(r"failed to apply mixin", re.I),
    re.compile(r"Execution of the command buffer was aborted", re.I),
    re.compile(r"Metal API Validation.*error", re.I),
    re.compile(r"Invalid backend", re.I),
]
profiles = {}
for profile_dir in sorted(p for p in root.iterdir() if p.is_dir()):
    runs = []
    aggregate = {key: [] for key in metric_patterns}
    for run_dir in sorted(profile_dir.glob("run-*")):
        log_path = run_dir / "client.log"
        text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        metrics = {}
        for key, pattern in metric_patterns.items():
            values = [float(value) for value in pattern.findall(text)]
            metrics[key] = values
            aggregate[key].extend(values)
        hits = []
        for pattern in forbidden:
            for match in pattern.finditer(text):
                hits.append({"pattern": pattern.pattern, "line": text.count("\n", 0, match.start()) + 1})
        exit_status = int((run_dir / "exit-status.txt").read_text().strip()) if (run_dir / "exit-status.txt").exists() else -1
        runs.append({
            "name": run_dir.name,
            "exit_status": exit_status,
            "metrics": metrics,
            "forbidden_log_hits": hits,
            "plan_present": (run_dir / "optimization-plan.json").exists(),
        })
    aggregate_stats = {}
    for key, values in aggregate.items():
        if values:
            ordered = sorted(values)
            p95_index = min(len(ordered) - 1, max(0, round(0.95 * (len(ordered) - 1))))
            aggregate_stats[key] = {
                "sample_count": len(values),
                "median": statistics.median(values),
                "p95": ordered[p95_index],
                "minimum": ordered[0],
                "maximum": ordered[-1],
            }
        else:
            aggregate_stats[key] = {"sample_count": 0}
    profiles[profile_dir.name] = {"runs": runs, "aggregate": aggregate_stats}
summary = {
    "world": world,
    "repetitions_requested": repetitions,
    "profiles": profiles,
    "note": "Regex-extracted metrics are discovery aids. Acceptance requires validating the source report semantics and comparing like-for-like samples.",
}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
PY

cat > "$OUT/decision.md" <<'EOF'
# Decision

Status: UNREVIEWED

The agent must fill this file after inspecting `summary.json`, source validation reports, screenshots/readbacks, Metal validation logs, and run-to-run variance.

Required decision states are defined in `acceptance.json`. Do not mark a lane accepted solely because its Gradle task exited zero.
EOF

printf 'Performance-cycle artifacts: %s\n' "$OUT"
exit "$overall_status"
