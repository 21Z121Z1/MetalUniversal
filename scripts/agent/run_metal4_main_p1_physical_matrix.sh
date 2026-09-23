#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
source scripts/agent/physical_preflight.sh
physical_preflight
: "${WORLD:?WORLD must name an existing validation world under run/saves}"
: "${POTATO_SHADER_PACK:?Supply a Potato shader ZIP}"
: "${POTATO_SHADER_PACK_VERSION:?Supply its version}"
: "${BSL_SHADER_PACK:?Supply a BSL shader ZIP}"
: "${BSL_SHADER_PACK_VERSION:?Supply its version}"
HEAD_SHA="$(git rev-parse HEAD)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_MATRIX_OUT:-$ROOT/build/agent-runs/p1-metal4-main-matrix-$STAMP}"
mkdir -p "$OUT/correctness" "$OUT/profiles"
# Each optional stack earns its own exact-production correctness gate.
for renderer in vanilla sodium iris; do
  P1_RENDERER_MODE="$renderer" \
  METALLUM_P1_CORRECTNESS_OUT="$OUT/correctness/$renderer" \
    bash scripts/agent/run_metal4_main_p1_physical_correctness.sh
done
JAR="$(production_jar_path)"
JAR_SHA="$(shasum -a 256 "$JAR" | awk '{print $1}')"
DYLIB_SHA="$(shasum -a 256 src/main/resources/natives/macos/libmetallum.dylib | awk '{print $1}')"
# All profiles must independently reach accepted-candidate, using the same
# production_jar_sha256 and native_dylib_sha256 as their correctness receipts.
for profile in V1 S1 I0 I1; do
  case "$profile" in V1) renderer=vanilla;; S1) renderer=sodium;; *) renderer=iris;; esac
  PROFILE_ID="$profile" \
  METALLUM_P1_PERFORMANCE_OUT="$OUT/profiles/$profile" \
  P1_CORRECTNESS_GATE="$OUT/correctness/$renderer/pair-decision.json" \
    bash scripts/agent/run_metal4_main_p1_physical_performance.sh
done
python3 scripts/agent/check_metal4_main_profile_matrix.py "$OUT" \
  --expected-head "$HEAD_SHA" --expected-jar-sha "$JAR_SHA" \
  --expected-dylib-sha "$DYLIB_SHA" --output "$OUT/decision.json"
echo "P1 physical matrix: ACCEPTED; evidence: $OUT"
