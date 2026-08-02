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
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-120}"
if ! [[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "REPETITIONS must be a positive integer" >&2
  exit 2
fi
if ! [[ "$WARMUP_SECONDS" =~ ^[0-9]+$ ]] || ! [[ "$SAMPLE_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "WARMUP_SECONDS and SAMPLE_SECONDS must be non-negative integers" >&2
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
    "-Dmetallum.iris.experimental.argumentTables=$argument_tables" \
    "-Dmetallum.validation.warmupSeconds=$WARMUP_SECONDS" \
    "-Dmetallum.validation.sampleSeconds=$SAMPLE_SECONDS"
}

copy_validation_artifacts() {
  local marker="$1"
  local destination_root="$2"
  local artifact_root
  for artifact_root in \
    "$ROOT/build/metal-validation" \
    "$ROOT/build/render-contract" \
    "$ROOT/build/reports" \
    "$ROOT/build/test-results"; do
    [[ -d "$artifact_root" ]] || continue
    while IFS= read -r -d '' file; do
      rel="${file#$ROOT/}"
      destination="$destination_root/$rel"
      mkdir -p "$(dirname "$destination")"
      cp -p "$file" "$destination" 2>/dev/null || true
    done < <(find "$artifact_root" -type f -newer "$marker" -print0 2>/dev/null || true)
  done
}

for raw_profile in "${profile_list[@]}"; do
  profile="$(printf '%s' "$raw_profile" | tr -d '[:space:]')"
  [[ -n "$profile" ]] || continue
  common_args=()
  while IFS= read -r argument; do
    common_args+=("$argument")
  done < <(profile_args "$profile")
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

    copy_validation_artifacts "$marker" "$run_dir/artifacts"
  done
done

python3 - "$OUT" "$WORLD" "$REPETITIONS" <<'PY'
import json, pathlib, re, statistics, sys

root = pathlib.Path(sys.argv[1])
world = sys.argv[2]
repetitions = int(sys.argv[3])

metric_definitions = {
    "fps": {
        "direction": "higher",
        "unit": "FPS",
        "pattern": re.compile(r"(?i)\b(?:fps|frames per second)\D{0,10}([0-9]+(?:\.[0-9]+)?)"),
    },
    "gpu_ms": {
        "direction": "lower",
        "unit": "ms",
        "pattern": re.compile(r"(?i)(?:gpu(?: frame| render)?(?: time)?|gpuTime)\D{0,20}([0-9]+(?:\.[0-9]+)?)\s*ms"),
    },
    "cpu_ms": {
        "direction": "lower",
        "unit": "ms",
        "pattern": re.compile(r"(?i)(?:cpu(?: render| encode)?(?: time)?|cpuTime)\D{0,20}([0-9]+(?:\.[0-9]+)?)\s*ms"),
    },
    "encoder_count": {
        "direction": "lower",
        "unit": "encoders/frame",
        "pattern": re.compile(r"(?i)(?:native\s+)?encoder(?: count|s)?\D{0,20}([0-9]+(?:\.[0-9]+)?)"),
    },
    "native_render_encoder_count": {
        "direction": "lower",
        "unit": "render encoders/frame",
        "pattern": re.compile(r"(?!)"),
    },
    "native_compute_encoder_count": {
        "direction": "lower",
        "unit": "compute encoders/frame",
        "pattern": re.compile(r"(?!)"),
    },
    "native_blit_encoder_count": {
        "direction": "lower",
        "unit": "blit encoders/frame",
        "pattern": re.compile(r"(?!)"),
    },
    "store_load_bytes": {
        "direction": "lower",
        "unit": "bytes/frame",
        "pattern": re.compile(r"(?i)(?:store[_ -]?load(?: bytes)?|attachment bandwidth)\D{0,24}([0-9]+(?:\.[0-9]+)?)"),
    },
    "resident_resource_bytes": {
        "direction": "lower",
        "unit": "bytes",
        "pattern": re.compile(r"(?i)(?:resident(?: render)? resource bytes|residentResourceBytes)\D{0,24}([0-9]+(?:\.[0-9]+)?)"),
    },
    "peak_memory_bytes": {
        "direction": "lower",
        "unit": "bytes",
        "pattern": re.compile(r"(?i)(?:peak resident memory bytes|peakMemoryBytes)\D{0,24}([0-9]+(?:\.[0-9]+)?)"),
    },
    "stutter_count": {
        "direction": "lower",
        "unit": "events",
        "pattern": re.compile(r"(?i)(?:frame[_ -]?time stutter count|stutterCount)\D{0,20}([0-9]+(?:\.[0-9]+)?)"),
    },
    "ffm_call_count": {
        "direction": "lower",
        "unit": "calls",
        "pattern": re.compile(r"(?!)"),
    },
    "descriptor_binding_mutation_count": {
        "direction": "lower",
        "unit": "mutations",
        "pattern": re.compile(r"(?!)"),
    },
}

forbidden = [
    re.compile(r"MixinApplyError", re.I),
    re.compile(r"failed to apply mixin", re.I),
    re.compile(r"Execution of the command buffer was aborted", re.I),
    re.compile(r"Metal API Validation.*error", re.I),
    re.compile(r"Invalid backend", re.I),
]

def structured_metric_values(report):
    """Return source-report metrics and explicit reasons for unavailable fields."""
    native_counts = report.get("nativeEncoderCountsPerMeasuredFrame", {})
    cpu_frame = report.get("cpuRenderEncodeFrameMilliseconds", {})
    argument_binding = report.get("argumentBindingRuntime", {})
    unavailable = report.get("unavailableMetrics", {})
    values = {
        "fps": [report["sourceFpsFromP50"]] if report.get("sourceFpsFromP50") is not None else [],
        "gpu_ms": [report["gpuP50Milliseconds"]] if report.get("gpuP50Milliseconds") is not None else [],
        "cpu_ms": [cpu_frame["p50Milliseconds"]] if cpu_frame.get("p50Milliseconds") is not None else [],
        "native_render_encoder_count": [native_counts["renderPerFrame"]] if native_counts.get("renderPerFrame") is not None else [],
        "native_compute_encoder_count": [],
        "native_blit_encoder_count": [native_counts["blitPerFrame"]] if native_counts.get("blitPerFrame") is not None else [],
        "store_load_bytes": [],
        "resident_resource_bytes": [],
        "peak_memory_bytes": [],
        "stutter_count": ([report["frameTimeStutterCount"]]
                           if report.get("frameTimeStutterCount") is not None else []),
        "ffm_call_count": [],
        "descriptor_binding_mutation_count": ([argument_binding["bindingMutations"]]
                                                if argument_binding.get("enabled")
                                                and argument_binding.get("bindingMutations") is not None
                                                else []),
    }
    reasons = {}
    for key, report_key in {
        "native_compute_encoder_count": "nativeComputeEncoderCountPerFrame",
        "store_load_bytes": "attachmentStoreLoadBytes",
        "resident_resource_bytes": "residentRenderResourceBytes",
        "peak_memory_bytes": "peakResidentMemoryBytes",
        "stutter_count": "frameTimeStutterCount",
        "ffm_call_count": "javaToNativeFfmCallCount",
        "descriptor_binding_mutation_count": "descriptorBindingMutationCount",
    }.items():
        if report_key in unavailable:
            reasons[key] = unavailable[report_key]
    if not argument_binding.get("enabled"):
        reasons["descriptor_binding_mutation_count"] = (
            "unavailable — argument snapshot lane is disabled in the matching baseline; "
            "backend-wide descriptor mutation telemetry is not exposed"
        )
    return values, reasons

profiles = {}
for profile_dir in sorted(p for p in root.iterdir() if p.is_dir()):
    runs = []
    aggregate = {key: [] for key in metric_definitions}
    aggregate_reasons = {key: [] for key in metric_definitions}
    for run_dir in sorted(profile_dir.glob("run-*")):
        log_path = run_dir / "client.log"
        text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        metrics = {}
        metric_reasons = {}
        for key, definition in metric_definitions.items():
            values = [float(value) for value in definition["pattern"].findall(text)]
            metrics[key] = values
        report_paths = sorted(run_dir.rglob("native-fullscreen-baseline.json"))
        structured_report = None
        if report_paths:
            try:
                structured_report = json.loads(report_paths[-1].read_text(encoding="utf-8"))
                structured_values, structured_reasons = structured_metric_values(structured_report)
                for key, values in structured_values.items():
                    metrics[key] = [float(value) for value in values]
                    if key in structured_reasons:
                        metric_reasons[key] = structured_reasons[key]
                        aggregate_reasons[key].append(structured_reasons[key])
            except (OSError, ValueError, KeyError, TypeError) as error:
                metric_reasons["source_report"] = f"structured report unreadable: {error}"
        for key, values in metrics.items():
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
            "source_report": str(report_paths[-1]) if report_paths else None,
            "optimization_runtime": ({
                "renderFusion": structured_report.get("renderFusionRuntime"),
                "computeGrouping": structured_report.get("computeGroupingRuntime"),
            } if structured_report else None),
            "metric_unavailable_reasons": metric_reasons,
        })
    aggregate_stats = {}
    for key, values in aggregate.items():
        definition = metric_definitions[key]
        if values:
            ordered = sorted(values)
            p95_index = min(len(ordered) - 1, max(0, round(0.95 * (len(ordered) - 1))))
            aggregate_stats[key] = {
                "sample_count": len(values),
                "median": statistics.median(values),
                "p95": ordered[p95_index],
                "minimum": ordered[0],
                "maximum": ordered[-1],
                "direction": definition["direction"],
                "unit": definition["unit"],
            }
        else:
            aggregate_stats[key] = {
                "sample_count": 0,
                "direction": definition["direction"],
                "unit": definition["unit"],
                "unavailable_reason": (aggregate_reasons[key][0]
                                       if aggregate_reasons[key]
                                       else "metric missing from source report and client log"),
            }
    profiles[profile_dir.name] = {"runs": runs, "aggregate": aggregate_stats}


def compare_metric(before, after, direction):
    if before is None or after is None:
        return {"available": False, "reason": "metric missing from baseline or candidate"}
    raw_delta = after - before
    if before == 0:
        percent = None
    elif direction == "higher":
        percent = (after - before) / abs(before) * 100.0
    else:
        percent = (before - after) / abs(before) * 100.0
    improvement = raw_delta if direction == "higher" else -raw_delta
    return {
        "available": True,
        "before": before,
        "after": after,
        "raw_delta_after_minus_before": raw_delta,
        "improvement_percent": percent,
        "positive": improvement > 0,
        "unchanged": improvement == 0,
        "direction": direction,
    }

comparisons = {}
baseline = profiles.get("baseline")
if baseline is not None:
    for profile_name, profile in profiles.items():
        if profile_name == "baseline":
            continue
        metric_comparisons = {}
        for key, definition in metric_definitions.items():
            before_stats = baseline["aggregate"].get(key, {})
            after_stats = profile["aggregate"].get(key, {})
            comparison = compare_metric(
                before_stats.get("median"),
                after_stats.get("median"),
                definition["direction"],
            )
            comparison["unit"] = definition["unit"]
            comparison["baseline_sample_count"] = before_stats.get("sample_count", 0)
            comparison["candidate_sample_count"] = after_stats.get("sample_count", 0)
            if not comparison.get("available"):
                comparison["reason"] = (
                    after_stats.get("unavailable_reason")
                    or before_stats.get("unavailable_reason")
                    or comparison.get("reason")
                )
            metric_comparisons[key] = comparison
        comparisons[profile_name] = {
            "task_before_profile": "baseline",
            "task_after_profile": profile_name,
            "metrics": metric_comparisons,
            "has_any_positive_metric": any(
                value.get("available") and value.get("positive")
                for value in metric_comparisons.values()
            ),
            "acceptance_note": "A positive metric is necessary but not sufficient. Correctness, non-regression and noise review remain mandatory.",
        }

summary = {
    "world": world,
    "repetitions_requested": repetitions,
    "profiles": profiles,
    "comparisons": comparisons,
    "note": "Structured native-fullscreen-baseline.json metrics take precedence over log discovery. Acceptance still requires source-report semantics, direction consistency and like-for-like samples.",
}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
(root / "comparison.json").write_text(json.dumps(comparisons, indent=2) + "\n", encoding="utf-8")

labels = {
    "fps": "FPS",
    "gpu_ms": "GPU frame time",
    "cpu_ms": "CPU render/encode time",
    "encoder_count": "Native encoder count",
    "native_render_encoder_count": "Native render encoder count/frame",
    "native_compute_encoder_count": "Native compute encoder count/frame",
    "native_blit_encoder_count": "Native blit encoder count/frame",
    "store_load_bytes": "Attachment store/load bytes",
    "resident_resource_bytes": "Resident render resources",
    "peak_memory_bytes": "Peak memory",
    "stutter_count": "Frame-time stutters",
    "ffm_call_count": "Java→native/FFM calls",
    "descriptor_binding_mutation_count": "Descriptor/binding mutations",
}
lines = [
    "# Task before/after performance comparison",
    "",
    f"World: `{world}`",
    "",
    "Baseline is the task-before state. Each candidate profile is a task-after state.",
    "A positive percentage means an efficiency improvement, regardless of whether higher or lower is better for the raw metric.",
    "",
]
if not comparisons:
    lines.extend([
        "No candidate comparison is available. Run `baseline` together with at least one candidate profile.",
        "",
    ])
for profile_name, comparison in comparisons.items():
    lines.extend([
        f"## baseline → {profile_name}",
        "",
        "| Metric | Before | After | Raw delta | Efficiency improvement | Samples before/after |",
        "|---|---:|---:|---:|---:|---:|",
    ])
    for key in metric_definitions:
        value = comparison["metrics"][key]
        label = labels[key]
        if not value.get("available"):
            reason = value.get("reason", "no structured source report or log sample")
            lines.append(f"| {label} | unavailable ({reason}) | unavailable ({reason}) | — | — | {value['baseline_sample_count']}/{value['candidate_sample_count']} |")
            continue
        unit = value["unit"]
        percent = value.get("improvement_percent")
        percent_text = "undefined" if percent is None else f"{percent:+.3f}%"
        lines.append(
            f"| {label} | {value['before']:.6g} {unit} | {value['after']:.6g} {unit} | "
            f"{value['raw_delta_after_minus_before']:+.6g} {unit} | {percent_text} | "
            f"{value['baseline_sample_count']}/{value['candidate_sample_count']} |"
        )
    lines.extend([
        "",
        f"Any positive metric observed: `{str(comparison['has_any_positive_metric']).lower()}`.",
        "This is not an automatic acceptance result; verify correctness, non-regression and measurement noise.",
        "",
    ])
(root / "comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

cat > "$OUT/decision.md" <<'EOF'
# Decision

Status: UNREVIEWED

## Mandatory task-before/task-after result

Fill this section from `comparison.json`, `comparison.md`, source reports and repeated-run review.

| Metric | Task before | Task after | Absolute change | Efficiency improvement |
|---|---:|---:|---:|---:|
| FPS median | REQUIRED | REQUIRED | REQUIRED | REQUIRED |
| GPU frame-time median | REQUIRED or unavailable with reason | REQUIRED or unavailable with reason | REQUIRED | REQUIRED |
| CPU render/encode median | REQUIRED or unavailable with reason | REQUIRED or unavailable with reason | REQUIRED | REQUIRED |
| Native encoder count/frame | REQUIRED or unavailable with reason | REQUIRED or unavailable with reason | REQUIRED | REQUIRED |
| Attachment store/load bytes | REQUIRED or unavailable with reason | REQUIRED or unavailable with reason | REQUIRED | REQUIRED |
| Resident render-resource bytes | REQUIRED or unavailable with reason | REQUIRED or unavailable with reason | REQUIRED | REQUIRED |

A positive percentage means improvement. FPS is higher-is-better; time, encoder count, bandwidth and memory are lower-is-better.

## Acceptance review

The agent must inspect `summary.json`, `comparison.json`, source validation reports, screenshots/readbacks, Metal validation logs, and run-to-run variance.

No fixed gain percentage is required. At least one target metric must be strictly better, with a consistent positive direction across comparable repeated runs, and every correctness/non-regression gate must pass. If the direction is unstable or indistinguishable from noise, use `inconclusive-noise`.

Required decision states are defined in `acceptance.json`. Do not mark a lane accepted solely because its Gradle task exited zero or because one extracted sample improved.
EOF

printf 'Performance-cycle artifacts: %s\n' "$OUT"
exit "$overall_status"
