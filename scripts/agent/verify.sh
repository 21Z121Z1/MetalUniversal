#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

MODE="${1:-static}"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_AGENT_VERIFY_OUT:-$RUN_ROOT/verify-$MODE-$STAMP}"
mkdir -p "$OUT"
COMMAND_LOG="$OUT/commands.log"
: > "$COMMAND_LOG"
# Gradle's `clean` task removes the repository's build directory, including
# RUN_ROOT. Keep the live log stream outside build/ until each command exits,
# then copy the evidence back into the requested artifact directory. This
# prevents a successful `clean test` from destroying the harness's own report.
STAGING_ROOT="${METALLUM_AGENT_VERIFY_STAGING:-${TMPDIR:-/tmp}/metallum-agent-verify-$$}"
mkdir -p "$STAGING_ROOT"
STAGED_COMMAND_LOG="$STAGING_ROOT/commands.log"
: > "$STAGED_COMMAND_LOG"
# A static Gradle run invokes `clean`, which also removes prior performance
# evidence under the default agent-run root. Snapshot that evidence before
# Gradle starts and restore it after the gate so runs remain auditable.
PRESERVED_RUN_ROOT="$STAGING_ROOT/existing-agent-runs"
PRESERVE_EXISTING_RUNS=false
case "$RUN_ROOT" in
  "$ROOT/build/agent-runs"|"$ROOT/build/agent-runs"/*)
    if [[ -d "$RUN_ROOT" ]]; then
      mkdir -p "$PRESERVED_RUN_ROOT"
      cp -a "$RUN_ROOT/." "$PRESERVED_RUN_ROOT/"
      PRESERVE_EXISTING_RUNS=true
    fi
    ;;
esac
overall_status=0

START_HEAD="$(git rev-parse HEAD)"
START_GIT_STATUS="$(git status --porcelain=v1)"

sync_artifacts() {
  mkdir -p "$OUT"
  cp "$STAGED_COMMAND_LOG" "$COMMAND_LOG"
  if [[ -n "${STAGING_ENVIRONMENT:-}" && -f "$STAGING_ENVIRONMENT" ]]; then
    cp "$STAGING_ENVIRONMENT" "$OUT/environment.json"
  fi
}

run_logged() {
  local name="$1"
  shift
  local log="$STAGING_ROOT/${name}.log"
  {
    printf '[command]'
    printf ' %q' "$@"
    printf '\n'
  } | tee -a "$STAGED_COMMAND_LOG"
  set +e
  "$@" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  set -e
  printf '[exit] %s %d\n' "$name" "$status" | tee -a "$STAGED_COMMAND_LOG"
  mkdir -p "$OUT"
  cp "$log" "$OUT/${name}.log"
  cp "$STAGED_COMMAND_LOG" "$COMMAND_LOG"
  if (( status != 0 )); then
    overall_status=1
  fi
  return 0
}

bash scripts/agent/doctor.sh
DOCTOR_ENVIRONMENT="$(find "$RUN_ROOT" -type f -name environment.json -print 2>/dev/null | sort | tail -1)"
STAGING_ENVIRONMENT="$STAGING_ROOT/environment.json"
if [[ -n "$DOCTOR_ENVIRONMENT" && -f "$DOCTOR_ENVIRONMENT" ]]; then
  cp "$DOCTOR_ENVIRONMENT" "$STAGING_ENVIRONMENT"
else
  STAGING_ENVIRONMENT=""
fi

case "$MODE" in
  static)
    run_logged unit-tests ./gradlew --no-daemon clean test
    # Do not call `build`: this repository's `check` task includes attended
    # WindowServer and hardware-GPU validation. Static verification intentionally
    # compiles/assembles without crossing into those runtime gates.
    run_logged native-and-assemble ./gradlew --no-daemon \
      buildMacNative assemble validationJar verifyProductionJarIsolation
    ;;
  gpu)
    run_logged native-build ./gradlew --no-daemon buildMacNative
    run_logged mrt-compute-targets ./gradlew --no-daemon \
      metalMrtBackendIntegrationTest \
      metalComputeBackendIntegrationTest \
      metalIrisTargetsIntegrationTest
    run_logged shader-translation ./gradlew --no-daemon metalIrisShaderTranslationTest
    ;;
  focused)
    if [[ -z "${TASKS:-}" ]]; then
      echo "MODE=focused requires TASKS='taskA taskB'" >&2
      exit 2
    fi
    # Intentional word splitting: TASKS is a Gradle task list supplied by the agent.
    # shellcheck disable=SC2086
    run_logged focused ./gradlew --no-daemon $TASKS
    ;;
  full)
    run_logged unit-tests ./gradlew --no-daemon clean test
    run_logged native-and-assemble ./gradlew --no-daemon \
      buildMacNative assemble validationJar verifyProductionJarIsolation
    run_logged mrt-compute-targets ./gradlew --no-daemon \
      metalMrtBackendIntegrationTest \
      metalComputeBackendIntegrationTest \
      metalIrisTargetsIntegrationTest
    run_logged shader-translation ./gradlew --no-daemon metalIrisShaderTranslationTest
    if [[ -n "${WORLD:-}" ]]; then
      set +e
      METALLUM_AGENT_RUN_ROOT="$RUN_ROOT" \
      PROFILES="${PROFILES:-baseline,all-safe-lanes}" \
      REPETITIONS="${REPETITIONS:-3}" \
      WORLD="$WORLD" \
        bash scripts/agent/run_iris_perf_cycle.sh
      cycle_status=$?
      set -e
      printf '[exit] performance-cycle %d\n' "$cycle_status" | tee -a "$COMMAND_LOG"
      if (( cycle_status != 0 )); then
        overall_status=1
      fi
    else
      echo "WORLD is not set; full mode completed build/GPU validation but skipped Minecraft performance runs" \
        | tee "$OUT/client-skip.txt"
    fi
    ;;
  *)
    echo "Usage: bash scripts/agent/verify.sh {static|gpu|focused|full}" >&2
    exit 2
    ;;
esac

if [[ "$PRESERVE_EXISTING_RUNS" == true && -d "$PRESERVED_RUN_ROOT" ]]; then
  mkdir -p "$RUN_ROOT"
  cp -a "$PRESERVED_RUN_ROOT/." "$RUN_ROOT/"
fi

mkdir -p "$OUT"
printf '%s\n' "$START_HEAD" > "$OUT/start-head.txt"
printf '%s\n' "$START_GIT_STATUS" > "$OUT/start-git-status.txt"
git rev-parse HEAD > "$OUT/end-head.txt"
git status --porcelain=v1 > "$OUT/end-git-status.txt"
sync_artifacts

python3 - "$OUT" "$MODE" "$overall_status" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
command_failed = int(sys.argv[3]) != 0
forbidden = [
    re.compile(r"MixinApplyError", re.I),
    re.compile(r"failed to apply mixin", re.I),
    re.compile(r"Execution of the command buffer was aborted", re.I),
    re.compile(r"Invalid backend", re.I),
]
hits = []
for path in root.glob("*.log"):
    text = path.read_text(encoding="utf-8", errors="replace")
    for pattern in forbidden:
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            hits.append({"file": path.name, "line": line, "pattern": pattern.pattern})
status = "pass"
if command_failed:
    status = "fail-command"
if hits:
    status = "fail-forbidden-log-pattern"
summary = {
    "mode": mode,
    "artifact_root": str(root),
    "start_head": (root / "start-head.txt").read_text().strip(),
    "end_head": (root / "end-head.txt").read_text().strip(),
    "command_failed": command_failed,
    "forbidden_log_hits": hits,
    "status": status,
}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
PY

printf 'Verification artifacts: %s\n' "$OUT"
exit "$overall_status"
