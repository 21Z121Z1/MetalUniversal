#!/usr/bin/env bash
# Sourced by manual physical runners; never used as hosted-macOS evidence.
physical_preflight() {
  if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    echo 'Physical validation requires an Apple Silicon Mac' >&2; return 2
  fi
  if [[ "${METALLUM_PHYSICAL_MACHINE:-}" != 1 || -n "${METALLUM_HOSTED_METAL_OFFSCREEN+x}" || "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]; then
    echo 'Declare METALLUM_PHYSICAL_MACHINE=1 on actual hardware; hosted/offscreen execution is forbidden' >&2; return 2
  fi
  if [[ "$(sw_vers -productVersion | cut -d. -f1)" -lt 26 ]]; then
    echo 'P1 Metal 4 validation requires macOS 26+' >&2; return 2
  fi
  pgrep -x WindowServer >/dev/null || { echo 'No WindowServer session' >&2; return 2; }
  local user console
  user="$(stat -f %Su /dev/console)"
  [[ "$user" == "$(id -un)" && "$user" != root ]] || { echo 'Run in the logged-in desktop user session' >&2; return 2; }
  launchctl print "gui/$(id -u)" >/dev/null || { echo 'No GUI launch session' >&2; return 2; }
  console="$(/usr/sbin/ioreg -n Root -d1)" || return 2
  if grep -Eq '"(IOConsoleLocked|CGSSessionScreenIsLocked)"[[:space:]]*=[[:space:]]*Yes' <<< "$console"; then
    echo 'Unlock the console before physical validation' >&2; return 2
  fi
  [[ -z "$(git status --porcelain=v1)" ]] || { echo 'A clean source checkout is required' >&2; return 2; }
  # Arbitrary inherited JVM flags could replace the immutable validation lane.
  [[ -z "${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}" ]] || {
    echo 'Unset inherited Java options; pass supported properties explicitly' >&2; return 2;
  }
}

production_jar_path() {
  local version path
  version="$(sed -n 's/^mod_version=//p' gradle.properties | head -n1)"
  path="$ROOT/build/libs/metallum-$version.jar"
  [[ -s "$path" ]] || { echo "Expected production JAR absent: $path" >&2; return 2; }
  printf '%s\n' "$path"
}

# Evidence outputs are append-once run identities. Never let an old passing
# decision survive a failed rerun that reuses an output directory.
prepare_physical_output() {
  local out="$1"
  [[ ! -e "$out" ]] || { echo "Physical output already exists: $out" >&2; return 2; }
  mkdir -p "$(dirname "$out")"
  mkdir "$out"
}
