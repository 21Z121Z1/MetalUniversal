#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORLD="${WORLD:-}"
CORRECTNESS_GATE="${P1_CORRECTNESS_GATE:-}"
RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_P1_MATRIX_OUT:-$RUN_ROOT/p1-metal4-main-matrix-$STAMP}"
HEAD_SHA="$(git rev-parse HEAD)"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "P1 physical matrix requires an Apple-silicon Mac" >&2
  exit 2
fi
if [[ -z "$WORLD" || ! -d "run/saves/$WORLD" ]]; then
  echo "WORLD must name an existing validation world under run/saves/" >&2
  exit 2
fi
if [[ -z "$CORRECTNESS_GATE" || ! -s "$CORRECTNESS_GATE" ]]; then
  echo "P1_CORRECTNESS_GATE must point to the passing physical correctness pair decision" >&2
  exit 2
fi
if [[ -z "${POTATO_SHADER_PACK:-}" || -z "${POTATO_SHADER_PACK_VERSION:-}" ]]; then
  echo "P1 matrix requires POTATO_SHADER_PACK and POTATO_SHADER_PACK_VERSION" >&2
  exit 2
fi
if [[ -z "${BSL_SHADER_PACK:-}" || -z "${BSL_SHADER_PACK_VERSION:-}" ]]; then
  echo "P1 matrix requires BSL_SHADER_PACK and BSL_SHADER_PACK_VERSION" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "P1 physical matrix requires a clean worktree" >&2
  git status --short >&2
  exit 2
fi

mkdir -p "$OUT/profiles"
cp "$CORRECTNESS_GATE" "$OUT/physical-correctness-decision.json"
cat > "$OUT/matrix-contract.json" <<'JSON'
{
  "required_state": "accepted-candidate",
  "required_profiles": ["V1", "I0", "I1"],
  "correctness_identity_keys": ["production_jar_sha256", "native_dylib_sha256"]
}
JSON

read -r CORRECTNESS_JAR_SHA CORRECTNESS_DYLIB_SHA < <(
  python3 - "$CORRECTNESS_GATE" "$HEAD_SHA" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
head = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))
if data.get("state") != "pass":
    raise SystemExit(f"physical correctness state is not pass: {data.get('state')}")
identity = data.get("identity")
if not isinstance(identity, dict) or identity.get("sourceSha") != head:
    raise SystemExit(f"physical correctness identity does not match HEAD {head}: {identity}")
jar = identity.get("productionJarSha256")
dylib = identity.get("nativeDylibSha256")
if not isinstance(jar, str) or len(jar) != 64 or not isinstance(dylib, str) or len(dylib) != 64:
    raise SystemExit(f"physical correctness binary identity is incomplete: {identity}")
print(jar, dylib)
PY
)

# Rebuild the current HEAD production bits once and prove they still hash to
# the exact JAR/native pair accepted by the physical correctness run. All
# performance profiles execute from this clean, verified worktree afterward.
./gradlew --no-daemon \
  buildMacNative jar verifyProductionJarIsolation \
  -x buildIOSNative -x buildIOSSpvc \
  2>&1 | tee "$OUT/build.log"

JAR="$(find build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-dev.jar' ! -name '*-validation.jar' \
  -print | head -n 1)"
DYLIB="src/main/resources/natives/macos/libmetallum.dylib"
if [[ -z "$JAR" || ! -s "$JAR" || ! -s "$DYLIB" ]]; then
  echo "Could not resolve exact P1 production JAR/native dylib" >&2
  exit 2
fi
LOCAL_JAR_SHA="$(shasum -a 256 "$JAR" | awk '{print $1}')"
LOCAL_DYLIB_SHA="$(shasum -a 256 "$DYLIB" | awk '{print $1}')"
if [[ "$LOCAL_JAR_SHA" != "$CORRECTNESS_JAR_SHA" || "$LOCAL_DYLIB_SHA" != "$CORRECTNESS_DYLIB_SHA" ]]; then
  echo "P1 performance bits do not match the correctness-approved production bits" >&2
  echo "  jar:   correctness=$CORRECTNESS_JAR_SHA local=$LOCAL_JAR_SHA" >&2
  echo "  dylib: correctness=$CORRECTNESS_DYLIB_SHA local=$LOCAL_DYLIB_SHA" >&2
  exit 3
fi

for profile in V1 I0 I1; do
  profile_out="$OUT/profiles/$profile"
  semantic=false
  if [[ "$profile" == "I0" || "$profile" == "I1" ]]; then
    semantic=true
  fi

  # build.gradle forwards metallum.opt.* generically but not the semantic Iris
  # switch. JAVA_TOOL_OPTIONS is inherited by Loom's client JVM, so inject the
  # immutable startup property there; I0/I1 trials still have to prove both the
  # semantic-layer activation log and the exact staged shader-pack log.
  p1_java_tool_options="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dmetallum.iris.semantic=$semantic -Dmetallum.iris.performanceCounters=true"
  JAVA_TOOL_OPTIONS="$p1_java_tool_options" \
  PROFILE_ID="$profile" \
  METALLUM_P1_PERFORMANCE_OUT="$profile_out" \
  WORLD="$WORLD" \
  P1_CORRECTNESS_GATE="$CORRECTNESS_GATE" \
    bash scripts/agent/run_metal4_main_p1_physical_performance.sh

  test -s "$profile_out/environment.json"
  test -s "$profile_out/decision.json"
  if [[ "$profile" == "I0" || "$profile" == "I1" ]]; then
    while IFS= read -r log; do
      grep -F "Iris-on-Metal semantic layer active:" "$log" >/dev/null || {
        echo "$profile trial did not prove semantic-layer activation: $log" >&2
        exit 3
      }
    done < <(find "$profile_out/trials" -type f -name client.log -print | sort)
  fi
done

python3 scripts/agent/check_metal4_main_profile_matrix.py "$OUT" \
  --expected-head "$HEAD_SHA" \
  --expected-jar-sha "$CORRECTNESS_JAR_SHA" \
  --expected-dylib-sha "$CORRECTNESS_DYLIB_SHA" \
  --output "$OUT/decision.json"

echo "P1 physical performance matrix: ACCEPTED"
echo "Evidence: $OUT"
