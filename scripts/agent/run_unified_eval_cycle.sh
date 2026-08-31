#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INIT_SOURCE="$ROOT/scripts/agent/fixed_drawable.init.gradle"
IMPL="$ROOT/scripts/agent/run_unified_eval_cycle_impl.sh"

# `argument-tables` is not an independently switchable unified-eval lane.
# Metal 4 main rendering already owns the native MTL4ArgumentTable execution
# path; the old Iris snapshot is diagnostics-only. Benchmarking the snapshot
# would measure duplicated Java bookkeeping, not the native table authority.
# Use the P1 physical baseline/candidate route, which changes only
# metallum.opt.metal4MainRenderer while keeping Metal4 compiler/present/
# residency and exact product bits identical.
if [[ "${CANDIDATE_PROFILE:-all-safe-lanes}" == "argument-tables" \
      || "${BASELINE_PROFILE:-baseline}" == "argument-tables" ]]; then
  echo "argument-tables is not a standalone unified performance lane; use scripts/agent/run_metal4_main_p1_physical_performance.sh" >&2
  exit 2
fi

# Keep Gradle's normal caches/toolchains. A uniquely named init script is
# installed only for this invocation and removed on every wrapper exit. The
# init script itself is task-graph scoped, so correctness and other Gradle
# tasks in the unified cycle are untouched; only the paired native-render
# efficiency task gets the fixed 1708x960 windowed Retina workload.
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${HOME:?HOME is required to resolve Gradle user home}/.gradle}"
export GRADLE_USER_HOME
INIT_DIR="$GRADLE_USER_HOME/init.d"
INIT_PATH="$INIT_DIR/metallum-unified-fixed-drawable-$$.gradle"
mkdir -p "$INIT_DIR"
cp "$INIT_SOURCE" "$INIT_PATH"
cleanup() {
  rm -f "$INIT_PATH"
}
trap cleanup EXIT INT TERM

bash "$IMPL" "$@"
