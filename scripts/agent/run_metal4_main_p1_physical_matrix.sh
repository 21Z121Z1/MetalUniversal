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

mkdir -p "$OUT/profiles"
cp "$CORRECTNESS_GATE" "$OUT/physical-correctness-decision.json"

for profile in V1 I0 I1; do
  profile_out="$OUT/profiles/$profile"
  PROFILE_ID="$profile" \
  METALLUM_P1_PERFORMANCE_OUT="$profile_out" \
  WORLD="$WORLD" \
  P1_CORRECTNESS_GATE="$CORRECTNESS_GATE" \
    bash scripts/agent/run_metal4_main_p1_physical_performance.sh
  test -s "$profile_out/environment.json"
  test -s "$profile_out/decision.json"
done

python3 - "$OUT" "$HEAD_SHA" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
head = sys.argv[2]
profiles = ("V1", "I0", "I1")
identities = {}
decisions = {}
errors = []

for profile in profiles:
    profile_root = root / "profiles" / profile
    environment = json.loads((profile_root / "environment.json").read_text(encoding="utf-8"))
    decision = json.loads((profile_root / "decision.json").read_text(encoding="utf-8"))
    identity = environment.get("identity")
    if not isinstance(identity, dict):
        errors.append(f"{profile}: missing identity object")
        continue
    if identity.get("profile_id") != profile:
        errors.append(f"{profile}: identity profile_id={identity.get('profile_id')!r}")
    if identity.get("candidate_sha") != head:
        errors.append(f"{profile}: candidate_sha does not equal current HEAD")
    if decision.get("state") != "accepted-candidate":
        errors.append(f"{profile}: decision={decision.get('state')!r}")
    identities[profile] = identity
    decisions[profile] = decision

common_keys = (
    "candidate_sha",
    "production_jar_sha256",
    "native_dylib_sha256",
    "world_sha256",
    "world_scenario_id",
    "resolution",
    "ui_scale",
    "render_distance",
    "camera_pose",
    "camera_script_sha256",
    "minecraft_version",
    "sodium_version",
    "macos_version",
    "java_version",
)
if len(identities) == len(profiles):
    reference = identities["V1"]
    for profile in ("I0", "I1"):
        for key in common_keys:
            if identities[profile].get(key) != reference.get(key):
                errors.append(
                    f"{profile}: common identity mismatch for {key}: "
                    f"{identities[profile].get(key)!r} != {reference.get(key)!r}"
                )
    for profile in ("I0", "I1"):
        for key in ("shader_pack_name", "shader_pack_version", "shader_pack_sha256", "shader_options_sha256", "iris_version"):
            value = identities[profile].get(key)
            if not isinstance(value, str) or not value or value == "unknown":
                errors.append(f"{profile}: missing shader identity field {key}")

state = "accepted-candidate" if not errors else "rejected-candidate"
result = {
    "schema_version": 1,
    "stage": "P1-metal4-main-production",
    "state": state,
    "source_sha": head,
    "required_profiles": list(profiles),
    "profile_states": {profile: decisions.get(profile, {}).get("state", "missing") for profile in profiles},
    "shared_identity_fields": list(common_keys),
    "errors": errors,
    "reason": (
        "V1, I0 and I1 independently accepted the same exact P1 candidate under matched physical Metal 4/residency trials"
        if not errors
        else "one or more P1 physical performance profiles or cross-profile identity invariants failed"
    ),
}
(root / "decision.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2, sort_keys=True))
if errors:
    raise SystemExit(3)
PY

echo "P1 physical performance matrix: ACCEPTED"
echo "Evidence: $OUT"
