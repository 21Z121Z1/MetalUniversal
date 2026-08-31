#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INIT_SOURCE="$ROOT/scripts/agent/fixed_drawable.init.gradle"
IMPL="$ROOT/scripts/agent/run_unified_eval_cycle_impl.sh"

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
