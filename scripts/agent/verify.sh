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

run_step() {
  local name="$1"
  shift
  local log="$OUT/${name}.log"
  printf '\n[%s] %q' "$name" "$1" | tee -a "$COMMAND_LOG"
  shift || true
  for arg in "$@"; do printf ' %q' "$arg" | tee -a "$COMMAND_LOG"; done
  printf '\n' | tee -a "$COMMAND_LOG"
  set +e
  "$@" >/dev/null 2>&1
  set -e
}

# run_logged is separate because macOS Bash 3.2 makes reconstructing "$@"
# after logging unnecessarily error-prone.
run_logged() {
  local name="$1"
  shift
  local log="$OUT/${name}.log"
  {
    printf '[command]'
    printf ' %q' "$@"
    printf '\n'
  } | tee -a "$COMMAND_LOG"
  set +e
  "$@" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  set -e
  printf '[exit] %s %d\n' "$name" "$status" | tee -a "$COMMAND_LOG"
  if (( status != 0 )); then
    return "$status"
  fi
}

bash scripts/agent/doctor.sh

git rev-parse HEAD > "$OUT/start-head.txt"
git status --porcelain=v1 > "$OUT/start-git-status.txt"

case "$MODE" in
  static)
    run_logged unit-tests ./gradlew --no-daemon clean test
    run_logged native-and-build ./gradlew --no-daemon buildMacNative build verifyProductionJarIsolation
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
    run_logged native-and-build ./gradlew --no-daemon buildMacNative build verifyProductionJarIsolation
    run_logged mrt-compute-targets ./gradlew --no-daemon \
      metalMrtBackendIntegrationTest \
      metalComputeBackendIntegrationTest \
      metalIrisTargetsIntegrationTest
    run_logged shader-translation ./gradlew --no-daemon metalIrisShaderTranslationTest
    if [[ -n "${WORLD:-}" ]]; then
      METALLUM_AGENT_RUN_ROOT="$RUN_ROOT" \
      PROFILES="${PROFILES:-baseline,all-safe-lanes}" \
      REPETITIONS="${REPETITIONS:-3}" \
      WORLD="$WORLD" \
        bash scripts/agent/run_iris_perf_cycle.sh
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

git rev-parse HEAD > "$OUT/end-head.txt"
git status --porcelain=v1 > "$OUT/end-git-status.txt"

python3 - "$OUT" "$MODE" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
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
summary = {
    "mode": mode,
    "artifact_root": str(root),
    "start_head": (root / "start-head.txt").read_text().strip(),
    "end_head": (root / "end-head.txt").read_text().strip(),
    "forbidden_log_hits": hits,
    "status": "pass" if not hits else "fail-forbidden-log-pattern",
}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
if hits:
    print(json.dumps(summary, indent=2))
    raise SystemExit(1)
PY

printf 'Verification artifacts: %s\n' "$OUT"
